/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.entropy.database.mcp.tools;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Context-aware cache for MCP tool invocations.
 *
 * <p>Provides two caching features:
 * <ul>
 *   <li>Recently accessed tables per connection (FIFO queue, max 50 entries, 5-minute TTL)</li>
 *   <li>Connection health status (last check result, 2-minute TTL)</li>
 * </ul>
 *
 * <p>Call {@link #pruneExpired()} periodically (recommended every ~10 minutes) to clean stale entries.
 *
 * <p><b>一份状态而不是两份</b>：表名队列和「最后访问时间」原本分成 {@code recentTables} 与
 * {@code tableLastAccess} 两个 map，键靠 {@code conn + "::" + table} 拼字符串关联。三条读写路径
 * （{@code recordTableAccess} / {@code getRecentTables} / {@code pruneExpired}）都要跨两个 map，
 * 于是存在「队列里已有表名、时间戳还没写」的一致性窗口，表现为刚记录的表在 {@code getRecentTables}
 * 里被当成过期漏掉。加全局锁被否决：这里是纯内存缓存，为一个「顶多少显示一条最近表」的窗口把所有工具
 * 调用串行化不值得。改成队列元素直接携带时间戳（{@link TableAccess}），两份状态并成一份，原子性问题
 * 随之消失，也不再需要拼字符串键。
 *
 * <p>没有换成 Caffeine（{@code BackupMetadataRepository} 那种写法）：本类在 tools 模块，引入 Caffeine
 * 会给该模块加一条新依赖；而这里的边界本来就够——每连接 FIFO 上限 50 条 + TTL，需要的是「每连接一条
 * 有序队列」而不是「全局 LRU」，Caffeine 反而表达不了前者。
 */
@Component
public class QueryContextCache {

    private static final int RECENT_TABLES_MAX = 50;
    private static final long TABLE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long HEALTH_CACHE_TTL_MS = 2 * 60 * 1000L;

    /**
     * Recently queried tables per connection, ordered FIFO (most recent first).
     *
     * <p>队列仍是 {@link ConcurrentLinkedDeque}：写入全部发生在 {@code compute} 的映射函数里（同一个键
     * 一次只有一个写者），但 {@link #getRecentTables} 在锁外遍历，需要弱一致迭代器而不是
     * {@code ArrayDeque} 的 fail-fast 迭代器。
     */
    private final ConcurrentHashMap<String, Deque<TableAccess>> recentTables = new ConcurrentHashMap<>();

    /** Connection health status cache. */
    private final ConcurrentHashMap<String, HealthStatus> healthCache = new ConcurrentHashMap<>();

    /**
     * Records that a table was accessed via the given connection.
     * Adds to the front of the deque; evicts the oldest if capacity exceeded.
     *
     * <p>时间戳就在队列元素里，所以整个「移到队首 + 更新时间 + 超限淘汰」是对单个值的一次替换，
     * 不存在中间态。
     */
    public void recordTableAccess(String connection, String tableName) {
        if (connection == null || tableName == null) {
            return;
        }
        recentTables.compute(connection, (conn, deque) -> {
            Deque<TableAccess> queue = deque != null ? deque : new ConcurrentLinkedDeque<>();
            // Remove existing entry to move to front
            queue.removeIf(entry -> entry.tableName().equals(tableName));
            queue.addFirst(new TableAccess(tableName, Instant.now().toEpochMilli()));
            while (queue.size() > RECENT_TABLES_MAX) {
                queue.removeLast();
            }
            return queue;
        });
    }

    /**
     * Returns the list of recently accessed tables for a connection, most recent first.
     * Only includes tables whose last access is within the TTL window.
     */
    public List<String> getRecentTables(String connection) {
        if (connection == null) {
            return List.of();
        }
        Deque<TableAccess> deque = recentTables.get(connection);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        long now = Instant.now().toEpochMilli();
        return deque.stream()
                .filter(entry -> (now - entry.lastAccessMs()) < TABLE_CACHE_TTL_MS)
                .map(TableAccess::tableName)
                .toList();
    }

    /**
     * Sets the health status for a connection.
     */
    public void setHealthStatus(String connection, boolean healthy) {
        if (connection == null) {
            return;
        }
        healthCache.put(connection, new HealthStatus(healthy, Instant.now().toEpochMilli()));
    }

    /**
     * Returns the cached health status if it exists and has not expired.
     */
    public Optional<HealthStatus> getHealthStatus(String connection) {
        if (connection == null) {
            return Optional.empty();
        }
        HealthStatus status = healthCache.get(connection);
        if (status == null) {
            return Optional.empty();
        }
        long now = Instant.now().toEpochMilli();
        if ((now - status.checkedAt()) > HEALTH_CACHE_TTL_MS) {
            return Optional.empty();
        }
        return Optional.of(status);
    }

    /**
     * Removes expired entries from both the table cache and health cache.
     *
     * <p>表队列只需要一遍：过期判断直接看元素自带的时间戳，不再需要「先清时间戳 map、再回头清队列里
     * 的孤儿引用」这两步——那两步之间的顺序本身就是之前一致性窗口的来源。
     *
     * <p>仍存在的一个窗口：清空某连接的队列后把该 entry 整体移除时，可能与一次并发的
     * {@link #recordTableAccess} 撞上，丢掉刚记录的一条。可接受——这是纯建议性缓存，丢一条的后果是
     * 下次 {@code getRecentTables} 少一行，不影响任何查询正确性。
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedRateString = "${entropy.mcp.housekeeping.prune-interval:600000}")
    public void pruneExpired() {
        long now = Instant.now().toEpochMilli();

        // Prune health cache
        healthCache.entrySet().removeIf(entry ->
                (now - entry.getValue().checkedAt()) > HEALTH_CACHE_TTL_MS);

        // Drop expired table entries, then drop connections that have nothing left
        recentTables.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(access -> (now - access.lastAccessMs()) > TABLE_CACHE_TTL_MS);
            return entry.getValue().isEmpty();
        });
    }

    /**
     * 一次表访问记录。
     *
     * @param tableName    表名
     * @param lastAccessMs 该表最近一次被访问的 epoch 毫秒；与表名放在同一个值里，是为了让「记录访问」
     *                     成为单次原子替换
     */
    private record TableAccess(String tableName, long lastAccessMs) {}

    /**
     * Record of connection health check result.
     *
     * @param healthy whether the connection is currently healthy
     * @param checkedAt epoch millisecond timestamp of the check
     */
    public record HealthStatus(boolean healthy, long checkedAt) {}
}
