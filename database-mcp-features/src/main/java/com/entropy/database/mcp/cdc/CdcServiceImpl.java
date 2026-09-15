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
package com.entropy.database.mcp.cdc;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpQueryException;
import com.entropy.database.mcp.exception.McpValidationException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * CDC service implementation using database-specific change capture mechanisms.
 *
 * <p>Strategy selection per dialect:
 * <ul>
 *   <li>Oracle — Flashback Version Query (VERSIONS BETWEEN SCN, real I/U/D per row version)</li>
 *   <li>MySQL — Trigger-based audit tables with a Unix-second watermark (binlog requires Debezium)</li>
 *   <li>PostgreSQL — trigger-based audit with WAL LSN watermark</li>
 * </ul>
 *
 * <p>Failure semantics: every read either returns the changes it found or throws. Returning an
 * empty list (or {@code 0} for a watermark) on error is not allowed, because the caller cannot tell
 * that apart from "the table did not change".
 *
 * <p><b>两类进程内状态，两种边界策略</b>（键都来自调用方，此前三个 map 都是无界无 TTL 的
 * {@code ConcurrentHashMap}，只有显式 unregister 才移除——连接被租约驱逐时，按连接名索引的计数就永远留着）：
 * <ul>
 *   <li>{@link #connectionCounters} 是<b>诊断计数</b>，只喂 {@link #getStatus}。换成有上限 +
 *       {@code expireAfterAccess} 的 Caffeine（写法同 {@code BackupMetadataRepository} /
 *       {@code JobExecutionEngine}）：静默驱逐的代价只是 {@code totalEvents} 归零，而它本来就是
 *       进程内的、重启即丢的量。顺带把原来的 {@code eventCounters} + {@code lastEventTimes} 两个 map
 *       并成一条记录，省掉「计数已加、时间还没写」的中间态。</li>
 *   <li>{@link #subscriptions} 是<b>业务注册表</b>，刻意<b>不加 TTL</b>：订阅表示「这张表的变更要被
 *       持续捕获」，不是缓存。一段时间没人 {@code listSubscriptions} 并不意味着它该消失，静默过期会表现为
 *       「CDC 悄悄停了」，而调用方拿不到任何信号。所以这里只加容量上限，超限时<b>明确报错拒绝新注册</b>，
 *       让调用方知道要先 unregister。</li>
 * </ul>
 */
@Service
public class CdcServiceImpl implements CdcService {

    private static final Logger log = LoggerFactory.getLogger(CdcServiceImpl.class);

    /** Sentinel reported by {@link #getStatus} when the watermark could not be read. */
    static final long LSN_UNAVAILABLE = -1L;

    /**
     * 诊断计数的上限与空闲保留期：键是连接名，连接被租约驱逐后不会有人来清，所以靠
     * {@code expireAfterAccess} 兜。24 小时没被读也没被写的连接，其计数对运维已无意义。
     */
    static final int MAX_TRACKED_CONNECTIONS = 500;
    private static final Duration COUNTER_RETENTION = Duration.ofHours(24);

    /**
     * 订阅数上限。这是防失控的护栏而不是精确并发语义：并发注册可能短暂超出一两条，代价可以忽略，
     * 而为它加锁会把注册路径变成串行。
     */
    static final int MAX_SUBSCRIPTIONS = 200;

    private final DynamicDataSourceManager dataSourceManager;
    /** 见类注释：注册表语义，只加上限、不加 TTL，超限明确报错。 */
    private final ConcurrentMap<String, CdcSubscription> subscriptions = new ConcurrentHashMap<>();
    /** 见类注释：诊断计数，有界 + expireAfterAccess，静默驱逐可接受。 */
    private final Cache<String, ConnectionCounters> counterCache = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_CONNECTIONS)
            .expireAfterAccess(COUNTER_RETENTION)
            .build();
    private final ConcurrentMap<String, ConnectionCounters> connectionCounters = counterCache.asMap();

    public CdcServiceImpl(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    // ─── CDC Support Check ────────────────────────────────────────────────

    @Override
    public boolean isCdcSupported(String connection) {
        ByokDataSourceContext ctx = null;
        try {
            ctx = dataSourceManager.acquire(connection);
            DatabaseDialect dialect = ctx.getDialect();
            String sql = dialect.cdcCheckSupportSql();
            if (sql == null) {
                return false;
            }
            // 用 queryForList + 首行首值，而不是 queryForObject：后者要求结果「恰好一行」，探测 SQL 只要
            // 多返回一行（历史上 Oracle/PostgreSQL 的多段 UNION ALL 在多个分支命中时就是如此）就抛
            // IncorrectResultSizeDataAccessException，被下面的 catch 吞成「不支持」——配置最完整的库反而
            // 被判成不支持。方言侧的契约仍是单行单值，这里只是不再让「恰好一行」成为正确性的前提。
            List<Map<String, Object>> rows = ctx.getJdbcTemplate().queryForList(sql);
            if (rows.isEmpty()) {
                return false;
            }
            return isTrue(rows.getFirst().values().stream().findFirst().orElse(null));
        } catch (Exception e) {
            log.warn("CDC support check failed for '{}': {}", connection, e.getMessage(), e);
            return false;
        }
    }

    /** Reads the probe value as a flag: {@code 1} / {@code true} means supported. */
    private static boolean isTrue(Object value) {
        if (value instanceof Number number) {
            return number.longValue() != 0L;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && ("1".equals(value.toString().trim()) || "true".equalsIgnoreCase(value.toString().trim()));
    }

    // ─── Read Changes ─────────────────────────────────────────────────────

    @Override
    public List<CdcChangeEvent> readChanges(String connection, String schema, String table, long fromLsn) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        JdbcTemplate jdbc = ctx.getJdbcTemplate();

        // The read SQL interpolates schema/table, so whitelist them before they reach the driver.
        requireIdentifier(dialect, table, "table");
        if (schema != null && !schema.isBlank()) {
            requireIdentifier(dialect, schema, "schema");
        }

        String sql = dialect.cdcReadChangesSql(schema, table, fromLsn);
        if (sql == null) {
            throw new McpQueryException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "CDC read is not supported for dialect '%s' (connection=%s)"
                            .formatted(dialect.getDialectName(), connection));
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql, dialect.cdcLsnParameter(fromLsn));
        } catch (DataAccessException e) {
            // Never degrade a failed read to "no changes": the caller would treat it as an
            // up-to-date table and advance its watermark past changes it never saw.
            throw new McpQueryException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Failed to read CDC changes for %s.%s (connection=%s, fromLsn=%d): %s"
                            .formatted(schema, table, connection, fromLsn, e.getMessage()), e);
        }

        List<CdcChangeEvent> events = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // fromCodeOrUnknown warns once per unrecognized code and keeps the event instead of
            // dropping it, so a dialect/enum mismatch can no longer hide changes.
            CdcChangeType changeType = CdcChangeType.fromCodeOrUnknown((String) row.get("change_type"));

            Instant changeTime = extractTimestamp(row.get("change_time"));
            String primaryKeys = toStringOrEmpty(row.get("primary_keys"));
            String beforeJson = (String) row.get("before_json");
            String afterJson = (String) row.get("after_json");
            Long txId = toLongOrNull(row.get("transaction_id"));

            events.add(new CdcChangeEvent(connection, schema, table, changeType,
                    changeTime, primaryKeys, beforeJson, afterJson, txId, CdcEventStatus.PROCESSED));
        }
        // 计数与「最后一次有变更的时刻」写在同一条记录里：两个 map 时会出现「计数已加、时间还没写」的
        // 中间态，getStatus 恰好在此刻读就会报出自相矛盾的一对值。
        connectionCounters.compute(connection, (key, current) -> {
            ConnectionCounters base = current != null ? current : ConnectionCounters.empty();
            return base.plus(events.size());
        });
        return events;
    }

    // ─── Last LSN ─────────────────────────────────────────────────────────

    /**
     * Reads the current watermark and normalizes it through the dialect. Throws instead of returning
     * {@code 0}, which would be indistinguishable from genuinely sitting at position 0.
     */
    @Override
    public long getLastLsn(String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        String sql = dialect.cdcGetLastLsnSql();
        if (sql == null) {
            throw new McpQueryException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Dialect '%s' does not expose a CDC watermark (connection=%s)"
                            .formatted(dialect.getDialectName(), connection));
        }

        Map<String, Object> row;
        try {
            row = ctx.getJdbcTemplate().queryForMap(sql);
        } catch (DataAccessException e) {
            throw new McpQueryException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Failed to read the CDC watermark for '%s': %s".formatted(connection, e.getMessage()), e);
        }
        return dialect.parseLsn(row);
    }

    // ─── Mirror Table ─────────────────────────────────────────────────────

    @Override
    public void createMirrorTable(String connection, String sourceSchema, String sourceTable,
                                  String targetSchema, String targetTable) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        JdbcTemplate jdbc = ctx.getJdbcTemplate();

        // This DDL runs on the raw JdbcTemplate, i.e. outside SqlValidationAspect, so the
        // identifiers are whitelisted here before any of them reaches a SQL string.
        requireIdentifier(dialect, sourceTable, "sourceTable");
        requireIdentifier(dialect, targetSchema, "targetSchema");
        requireIdentifier(dialect, targetTable, "targetTable");
        if (sourceSchema != null && !sourceSchema.isBlank()) {
            requireIdentifier(dialect, sourceSchema, "sourceSchema");
        }

        String qualifiedSource = sourceSchema == null || sourceSchema.isBlank()
                ? dialect.quote(sourceTable)
                : dialect.quote(sourceSchema) + "." + dialect.quote(sourceTable);

        String sql = dialect.cdcCreateMirrorTableSql(targetSchema, targetTable,
                "SELECT * FROM " + qualifiedSource);
        if (sql == null) {
            throw new UnsupportedOperationException(
                    "Mirror table creation not supported for dialect: " + dialect.getDialectName());
        }
        jdbc.execute(sql);
        log.info("Created mirror table {}.{} from {}.{}", targetSchema, targetTable, sourceSchema, sourceTable);
    }

    /**
     * Whitelist check for an identifier that is interpolated into SQL.
     *
     * @throws McpValidationException when the value is not a plain identifier for this dialect
     */
    private static void requireIdentifier(DatabaseDialect dialect, String value, String paramName) {
        if (!dialect.isValidIdentifier(value)) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "%s '%s' is not a valid %s identifier: only unquoted identifier characters are accepted here"
                            .formatted(paramName, value, dialect.getDialectName()));
        }
    }

    // ─── Subscription Management ──────────────────────────────────────────

    /**
     * 注册（或按同名覆盖）一条订阅。
     *
     * <p>超过 {@link #MAX_SUBSCRIPTIONS} 时<b>拒绝并报错</b>，不做 LRU 静默驱逐：订阅消失意味着变更捕获
     * 停止，静默发生时调用方看到的是「CDC 没数据」而不是「注册失败」，排查成本天差地别。同名覆盖不受
     * 上限约束，否则改一条已有订阅会在满载时无故失败。
     */
    @Override
    public void registerSubscription(CdcSubscription subscription) {
        String name = subscription.name();
        if (!subscriptions.containsKey(name) && subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "CDC subscription limit reached (%d): unregister an existing subscription before adding '%s'"
                            .formatted(MAX_SUBSCRIPTIONS, name));
        }
        subscriptions.put(name, subscription);
        log.info("Registered CDC subscription '{}' for {}.{}", name,
                subscription.schema(), subscription.tablePattern());
    }

    @Override
    public List<CdcSubscription> listSubscriptions(String connection) {
        return subscriptions.values().stream()
                .filter(s -> s.connection().equals(connection) || connection == null)
                .toList();
    }

    @Override
    public void unregisterSubscription(String subscriptionName) {
        subscriptions.remove(subscriptionName);
    }

    // ─── Status ───────────────────────────────────────────────────────────

    @Override
    public CdcStatus getStatus(String connection) {
        boolean supported = isCdcSupported(connection);
        long lsn = supported ? readLsnForStatus(connection) : 0L;
        ConnectionCounters counters = connectionCounters.getOrDefault(connection, ConnectionCounters.empty());
        int activeSubs = (int) subscriptions.values().stream()
                .filter(s -> s.connection().equals(connection) && s.active())
                .count();
        return new CdcStatus(connection, supported, lsn, activeSubs,
                counters.totalEvents(), counters.lastEventEpochMs());
    }

    /**
     * 当前被跟踪的连接数，供测试确认计数不会无上限增长。
     *
     * <p>先跑一次 Caffeine 维护：驱逐是异步的，不 {@code cleanUp} 时刚写完的 size 可能短暂超过上限。
     */
    int trackedConnectionCount() {
        counterCache.cleanUp();
        return connectionCounters.size();
    }

    /**
     * Status is a diagnostics view and must stay readable even when the watermark query fails, so
     * the failure is reported as {@link #LSN_UNAVAILABLE} rather than as the plausible value 0.
     */
    private long readLsnForStatus(String connection) {
        try {
            return getLastLsn(connection);
        } catch (RuntimeException e) {
            log.warn("CDC watermark unavailable for '{}': {}", connection, e.getMessage());
            return LSN_UNAVAILABLE;
        }
    }

    // ─── Private Helpers ──────────────────────────────────────────────────

    private static Instant extractTimestamp(Object obj) {
        if (obj instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        } else if (obj instanceof Instant i) {
            return i;
        }
        return Instant.now();
    }

    private static String toStringOrEmpty(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private static Long toLongOrNull(Object obj) {
        return obj instanceof Number n ? n.longValue() : null;
    }

    /**
     * 单个连接的诊断计数。
     *
     * @param totalEvents      本进程内读到的变更条数累计
     * @param lastEventEpochMs 最后一次<b>真的读到变更</b>的时刻；空读不刷新，否则「上次有变更是什么时候」
     *                         会被每次轮询抹平
     */
    private record ConnectionCounters(long totalEvents, long lastEventEpochMs) {

        static ConnectionCounters empty() {
            return new ConnectionCounters(0L, 0L);
        }

        ConnectionCounters plus(int events) {
            return new ConnectionCounters(totalEvents + events,
                    events > 0 ? System.currentTimeMillis() : lastEventEpochMs);
        }
    }
}
