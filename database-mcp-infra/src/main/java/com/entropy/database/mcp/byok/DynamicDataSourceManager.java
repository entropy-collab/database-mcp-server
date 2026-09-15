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
package com.entropy.database.mcp.byok;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.byok.ConnectionMetadata;
import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.monitor.PoolStatsSource;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Map;

/**
 * Central manager for all datasources.
 * All connections are equal BYOK connections; there is no primary/default concept.
 *
 * <p>Implements {@link PoolStatsSource} so that monitoring components read pool statistics through
 * that port instead of depending on the registry itself.</p>
 */
public interface DynamicDataSourceManager extends PoolStatsSource {

    /**
     * Acquire a datasource context by key.
     * If the same physical connection (same jdbcUrl + username + dialect) is already cached under a different name,
     * this name is registered as an alias and returns the same underlying pool.
     */
    ByokDataSourceContext acquire(String key, ConnectionProperties connection);

    /**
     * Acquire an existing datasource context by key.
     * Throws IllegalArgumentException if the connection does not exist.
     */
    ByokDataSourceContext acquire(String key);

    /**
     * Register an existing datasource as a BYOK connection.
     * The datasource is NOT closed when the lease expires (managed externally).
     */
    void registerExisting(String key, DataSource existingDataSource, DatabaseDialect dialect);

    /**
     * Register a connection declared in configuration ({@code entropy.mcp.database.connections}) as a
     * <em>pinned</em> connection.
     *
     * <p>Unlike {@link #acquire(String, ConnectionProperties)} the result never expires: no lease, no
     * max lifetime, and it does not count against {@code byok.max-cached-connections}. The lease model
     * exists because BYOK callers bring connections at runtime; a connection the deployment declared
     * has the opposite requirement and must stay available for the life of the process.
     *
     * <p>The pool is built through the same {@code ByokDataSourceFactory} as BYOK connections, so SQL
     * validation, masking, auditing and statement timeouts apply identically.
     */
    void registerPinned(String key, ConnectionProperties connection);

    /**
     * Get metadata for a specific connection.
     *
     * @return ConnectionMetadata or null if not found
     */
    ConnectionMetadata getConnectionMetadata(String key);

    /**
     * Whether the connection was registered as read-only.
     *
     * <p>Returns false for unknown keys: an unregistered connection fails later with
     * {@code CONNECTION_NOT_FOUND}, which is a clearer diagnosis than a read-only rejection.</p>
     */
    boolean isReadonly(String key);

    /**
     * List all registered connection keys.
     */
    Collection<String> listConnectionKeys();

    /**
     * Get total number of registered connections.
     */
    int getConnectionCount();

    /**
     * Get all connection metadata entries.
     */
    Collection<ConnectionMetadata> getAllConnectionMetadata();

    /**
     * Shutdown all datasources.
     */
    void shutdown();

    /**
     * Get current cache size (number of active leased datasources).
     */
    int getActiveConnectionCount();

    /**
     * Force Caffeine to evict expired entries and trigger removal listener.
     * This is a no-op if no entries have expired.
     */
    void evictExpired();

    /**
     * 注册一个「连接名已失效」的回调。
     *
     * <p>存在的理由：连接池的生命周期只有这里知道（租约过期、体积淘汰、显式替换、shutdown），而上层按连接名
     * 缓存的派生对象（{@code RoutingDatabaseFacade} 的 per-connection facade，facade 背后的
     * {@code DatabaseCache}）此前拿不到任何失效信号，只能只增不删——每个历史用过的 BYOK 连接名都会永久钉住
     * 一份查询缓存和一个已经 close 的 Hikari 池。
     *
     * <p>否决了「上层自己用有界 + expireAfterAccess 的缓存兜住」：那样上层的过期时间与真实池寿命互相独立，
     * 既可能在池仍然活着的时候把 facade 扔掉（无害但白建），也会在池已经关掉之后继续钉住缓存到自己过期为止，
     * 而这条时间线上没有任何东西能被断言，测试只能靠 sleep。
     *
     * @param listener 回调实现。会在 Caffeine 的 removal 通知线程上被调用（默认
     *                 {@link java.util.concurrent.ForkJoinPool#commonPool()}），因此实现必须是线程安全的、
     *                 不阻塞、不抛异常；抛出的异常会被吞掉并记日志，不影响其它监听器和池的关闭。
     */
    void addEvictionListener(EvictionListener listener);

    /**
     * 「连接名已失效」的回调契约。
     *
     * <p>只传连接名而不传上下文对象：别名与规范名共享同一个物理池（同一个
     * {@link ByokDataSourceContext}），失效是按<em>名字</em>发生的，把上下文一起传出去会诱导实现方按对象身份
     * 去清理，从而在别名过期时误伤规范名仍在使用的那份状态。
     */
    interface EvictionListener {

        /**
         * @param key 刚从缓存里消失的连接名。可能是别名，也可能是规范名；同一个名字可能被通知多次
         *            （先显式驱逐、再收到异步的 removal 通知），实现方必须幂等。
         */
        void onConnectionEvicted(String key);
    }
}
