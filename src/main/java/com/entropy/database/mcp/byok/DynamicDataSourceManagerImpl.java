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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpLeaseExpiredException;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.monitor.HikariPoolStats;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.properties.ByokProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import org.springframework.beans.factory.DisposableBean;

/**
 * Central manager for all datasources.
 * Handles lifecycle management with TTL-based lease renewal.
 * All connections are equal BYOK connections; there is no primary/default concept.
 *
 * <p>Uses {@link ByokDataSourceFactory} (factory pattern) to create per-connection
 * infrastructure, following Spring's DataSourceBuilder pattern.
 */
public class DynamicDataSourceManagerImpl implements DynamicDataSourceManager, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceManagerImpl.class);

    private final DialectResolver dialectResolver;
    private final ByokDataSourceFactory dataSourceFactory;
    private final com.github.benmanes.caffeine.cache.Cache<String, LeasedDataSource> leasedCache;

    /**
     * Everything we know about a live connection, as one immutable value.
     *
     * <p>Metadata, the read-only flag and the content fingerprint used to be three separate
     * collections keyed by connection name. Caffeine's {@code removalListener} is asynchronous
     * (it runs on {@link ForkJoinPool#commonPool()} by default), so a lease that expired could have
     * its callback delivered <em>after</em> a business thread had already rebuilt the connection under
     * the same name - and the callback then deleted the fresh entry's read-only flag by name. Since
     * {@code McpToolExceptionAspect} treats a missing flag as "writable", a read-only connection
     * silently became writable.
     *
     * <p>Binding the three facts to the {@link LeasedDataSource} that owns them turns that race into a
     * no-op: a late callback can only remove state it still owns (see
     * {@link #unregisterIfOwnedBy(String, LeasedDataSource)}).
     *
     * @param owner       the leased datasource these facts describe; identity, not equality, decides
     *                    ownership
     * @param metadata    what {@code listConnections} reports
     * @param readonly    whether write tools must be rejected for this connection
     * @param fingerprint content fingerprint for pool de-duplication, {@code null} for externally
     *                    managed datasources that are never de-duplicated
     */
    private record ConnectionRegistration(LeasedDataSource owner,
                                          ConnectionMetadata metadata,
                                          boolean readonly,
                                          String fingerprint) {
    }

    /** connection name → its registration. Mutated only under {@link #registryLock}. */
    private final Map<String, ConnectionRegistration> registrations = new ConcurrentHashMap<>();

    /** content fingerprint → canonical connection name. Mutated only under {@link #registryLock}. */
    private final Map<String, String> contentFingerprintToKey = new ConcurrentHashMap<>();

    /**
     * Fixed set of striped monitors guarding per-key connection creation and eviction.
     *
     * <p>Striping rather than a per-key map with eviction: an evictable lock map can hand two
     * threads two <em>different</em> monitors for the same key (entry expires between the two
     * lookups), silently losing mutual exclusion. A fixed array always maps a key to the same
     * monitor and cannot grow without bound. The cost is that unrelated keys sharing a stripe
     * serialize occasionally, which is harmless for pool creation.
     */
    private static final int LOCK_STRIPES = 64;
    private final Object[] keyLocks = new Object[LOCK_STRIPES];

    /** Guards {@link #registrations} and {@link #contentFingerprintToKey} as one unit. */
    private final Object registryLock = new Object();

    private final Duration leaseDuration;
    private final Duration maxLifetime;
    private final int maxCachedConnections;
    private final ByokProperties byokProperties;
    private final McpMetricsCollector metricsCollector;

    /**
     * Dependencies record for DynamicDataSourceManagerImpl.
     * Groups constructor parameters to simplify bean definition.
     *
     * @param cacheMaintenanceExecutor executor Caffeine uses for eviction bookkeeping and removal
     *                                 notifications; {@code null} keeps Caffeine's default
     *                                 {@link ForkJoinPool#commonPool()}. Tests inject a deterministic
     *                                 executor to reproduce late-callback interleavings.
     */
    public record Dependencies(
            DialectResolver dialectResolver,
            ByokDataSourceFactory dataSourceFactory,
            ByokProperties byokProperties,
            McpMetricsCollector metricsCollector,
            Executor cacheMaintenanceExecutor) {

        public Dependencies(DialectResolver dialectResolver,
                            ByokDataSourceFactory dataSourceFactory,
                            ByokProperties byokProperties,
                            McpMetricsCollector metricsCollector) {
            this(dialectResolver, dataSourceFactory, byokProperties, metricsCollector, null);
        }
    }

    public DynamicDataSourceManagerImpl(Dependencies deps) {
        this.dialectResolver = deps.dialectResolver();
        this.dataSourceFactory = deps.dataSourceFactory();
        this.byokProperties = deps.byokProperties();
        this.leaseDuration = deps.byokProperties().leaseDuration();
        this.maxLifetime = deps.byokProperties().maxLifetime();
        this.maxCachedConnections = deps.byokProperties().maxCachedConnections();
        this.metricsCollector = deps.metricsCollector();

        java.util.Arrays.setAll(this.keyLocks, i -> new Object());

        Executor cacheExecutor = deps.cacheMaintenanceExecutor() != null
                ? deps.cacheMaintenanceExecutor()
                : ForkJoinPool.commonPool();

        this.leasedCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(maxCachedConnections)
                .expireAfterAccess(leaseDuration)
                .executor(cacheExecutor)
                .removalListener(this::onCacheRemoval)
                .build();
    }

    /**
     * Removal callback for every cause (expiry, size, explicit invalidation, replacement).
     *
     * <p>Deliberately does <em>not</em> take the striped lock. It used to, to keep a concurrent
     * {@code acquire()} from handing out a datasource that is being closed - but that never worked,
     * because the fast path of {@code acquire()} reads the cache before taking the lock. What actually
     * makes this safe is that all state removal is scoped to {@code value}'s identity, so a callback
     * that arrives after the connection was rebuilt cannot touch the new connection. Staying off the
     * lock also means pool shutdown never blocks unrelated keys that hash to the same stripe.
     *
     * <p>关闭动作走 {@link #closeIfUnreferenced(LeasedDataSource)}：一个池可能同时挂在 canonical 名字和
     * 若干别名下，别名过期时无条件 close 会把 canonical 正在用的池一起关掉。
     */
    private void onCacheRemoval(String key,
                                LeasedDataSource value,
                                com.github.benmanes.caffeine.cache.RemovalCause cause) {
        if (key != null && value != null) {
            unregisterIfOwnedBy(key, value);
        }
        if (metricsCollector != null) {
            metricsCollector.recordByokConnectionRemoved();
        }
        if (value != null) {
            log.info("Removing datasource: {} (cause: {})", key, cause);
            closeIfUnreferenced(value);
        }
    }

    /**
     * Resolve the striped monitor for a key. Always returns the same monitor for the same key.
     */
    private Object lockFor(String key) {
        return keyLocks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    /**
     * Acquire a datasource context by key.
     * Uses content fingerprint to deduplicate: if the same physical connection (jdbcUrl+username+dialect)
     * is already cached under a different name, this name becomes an alias to the existing pool.
     */
    @Override
    public ByokDataSourceContext acquire(String key, ConnectionProperties connection) {
        guardJdbcUrl(key, connection.jdbcUrl());

        String fingerprint = connection.getCacheKey();

        // Check if an identical physical connection already exists under a different name
        String canonicalKey = contentFingerprintToKey.get(fingerprint);
        if (canonicalKey != null && !canonicalKey.equals(key)) {
            ByokDataSourceContext adopted = adoptAlias(key, canonicalKey, connection);
            if (adopted != null) {
                return adopted;
            }
        }

        LeasedDataSource existing = leasedCache.getIfPresent(key);
        if (existing != null && !existing.isClosed()) {
            try {
                return existing.renewLease();
            } catch (McpLeaseExpiredException e) {
                log.warn("Datasource {} exceeded max lifetime, evicting and recreating", key);
                Object lock = lockFor(key);
                synchronized (lock) {
                    evictIfCurrent(key, existing);
                }
            }
        } else if (existing != null && existing.isClosed()) {
            // Connection is being closed by the removal callback, evict and recreate
            log.warn("Datasource {} is closed (eviction in progress), evicting and recreating", key);
            evictIfCurrent(key, existing);
        }

        Object lock = lockFor(key);
        synchronized (lock) {
            // Double-check after acquiring lock
            existing = leasedCache.getIfPresent(key);
            if (existing != null && !existing.isClosed()) {
                try {
                    return existing.renewLease();
                } catch (McpLeaseExpiredException e) {
                    log.warn("Datasource {} exceeded max lifetime during lock, evicting and recreating", key);
                    evictIfCurrent(key, existing);
                }
            } else if (existing != null && existing.isClosed()) {
                // Evict closed connection and proceed to create new one
                log.warn("Datasource {} is closed (eviction in progress), evicting", key);
                evictIfCurrent(key, existing);
            }

            // Re-check canonical key in case another thread created it
            canonicalKey = contentFingerprintToKey.get(fingerprint);
            if (canonicalKey != null && !canonicalKey.equals(key)) {
                ByokDataSourceContext adopted = adoptAlias(key, canonicalKey, connection);
                if (adopted != null) {
                    return adopted;
                }
            }

            DatabaseDialect dialect = dialectResolver.resolve(connection.dialect(), null);
            LeasedDataSource newLeased = createLeasedDataSource(key, connection, dialect);

            // Register before publishing to the cache: the other order leaves a window in which a
            // concurrent acquire() finds the context but isReadonly() still answers false, which for a
            // read-only connection means write tools are let through.
            register(key, newLeased, connection, dialect.getClass().getSimpleName(), fingerprint);
            leasedCache.put(key, newLeased);
            return newLeased.renewLease();
        }
    }

    /**
     * Let {@code key} share the pool that {@code canonicalKey} already owns.
     *
     * <p>指纹命中过去只是 {@code return existing.renewLease()}，既不注册也不写缓存，于是别名连接对外
     * 等于不存在：{@code acquire(alias)} 抛 "Connection not found"、{@code isReadonly(alias)} 是 false
     * （readonly 连接的写工具会被放过）、{@code listConnections} 也看不到它。这里补上注册与缓存写入，
     * 顺序仍然是「先 register 再 put」，理由见 {@link #acquire(String, ConnectionProperties)}。
     *
     * <p>别名的 fingerprint 传 {@code null}：指纹归属留给 canonical，否则两个名字会在
     * {@link #unregisterIfOwnedBy(String, LeasedDataSource)} 里互相删对方的索引项，最终指纹索引指向一个
     * 已经消失的名字。代价是 canonical 先过期时指纹索引会清空，同内容的下一个名字会另开一个池——只是少了
     * 一次去重，不会出错。
     *
     * <p>这里<em>不</em>取 canonical 的条带锁：本方法的第二个调用点已经持有 {@code key} 的条带锁，再去拿
     * canonical 的锁就会出现「A 等 B、B 等 A」的锁序环（两个名字互为对方指纹的 canonical 时）。不加锁是安全的，
     * 因为所有状态改动都按对象身份收口：{@code evictIfCurrent} 只在缓存里仍是同一个对象时才移除，注册完成后
     * 又会复查 {@code isClosed()} 以防和过期回调撞车。
     *
     * @return 复用成功时的上下文；{@code null} 表示这次没能复用（canonical 已过期/已关闭/归属不一致），
     *         调用方应继续走新建流程
     */
    private ByokDataSourceContext adoptAlias(String key, String canonicalKey, ConnectionProperties connection) {
        LeasedDataSource shared = leasedCache.getIfPresent(canonicalKey);
        if (shared == null) {
            return null;
        }
        if (shared.isClosed()) {
            log.warn("Canonical connection '{}' is closed, evicting", canonicalKey);
            evictIfCurrent(canonicalKey, shared);
            return null;
        }
        ConnectionRegistration canonical = registrations.get(canonicalKey);
        if (canonical == null || canonical.owner() != shared) {
            // 指纹索引与实际归属已经不一致：宁可新建一个池，也不要把别名挂到来历不明的连接上
            log.warn("Canonical connection '{}' has no matching registration, not aliasing '{}'",
                    canonicalKey, key);
            return null;
        }
        ByokDataSourceContext context;
        try {
            context = shared.renewLease();
        } catch (McpLeaseExpiredException e) {
            log.warn("Canonical connection '{}' expired, recreating", canonicalKey);
            evictIfCurrent(canonicalKey, shared);
            return null;
        }

        register(key, shared, connection, canonical.metadata().dialect(), null);
        leasedCache.put(key, shared);
        log.info("Connection '{}' is an alias for existing connection '{}', reusing same pool",
                key, canonicalKey);

        if (shared.isClosed()) {
            // 移除回调不持锁，所以它可能正好在我们注册期间关掉了这个池。别名不能对外暴露一个死池。
            log.warn("Canonical connection '{}' was closed while aliasing '{}', discarding alias",
                    canonicalKey, key);
            evictIfCurrent(key, shared);
            return null;
        }
        return context;
    }

    /**
     * Acquire an existing datasource context by key.
     * Throws IllegalArgumentException if the connection does not exist.
     */
    @Override
    public ByokDataSourceContext acquire(String key) {
        LeasedDataSource existing = leasedCache.getIfPresent(key);
        if (existing == null) {
            throw new IllegalArgumentException("Connection not found: " + key +
                    ". Use createNamedConnection first.");
        }
        try {
            return existing.renewLease();
        } catch (McpLeaseExpiredException e) {
            log.warn("Datasource {} exceeded max lifetime", key);
            throw new IllegalArgumentException("Connection expired: " + key, e);
        }
    }

    /**
     * Register an existing datasource as a BYOK connection.
     * The datasource is NOT closed when the lease expires (managed externally).
     */
    @Override
    public void registerExisting(String key, DataSource existingDataSource, DatabaseDialect dialect) {
        guardJdbcUrl(key, jdbcUrlOf(existingDataSource));

        Object lock = lockFor(key);
        synchronized (lock) {
            LeasedDataSource previous = leasedCache.getIfPresent(key);
            if (previous != null) {
                log.warn("Datasource {} already registered, replacing", key);
                evictIfCurrent(key, previous);
            }

            ByokDataSourceContext context = dataSourceFactory.createExisting(key, existingDataSource, dialect);
            LeasedDataSource leased = new LeasedDataSource(key, context, leaseDuration, maxLifetime, false);

            // Same ordering rule as acquire(): registry first, cache second.
            register(key, leased, null, dialect.getClass().getSimpleName(), null);
            leasedCache.put(key, leased);

            log.info("Registered existing datasource as BYOK connection: {}", key);
            if (metricsCollector != null) {
                metricsCollector.recordByokConnectionCreated();
            }
        }
    }

    /**
     * Create a new LeasedDataSource for the given key and connection.
     */
    private LeasedDataSource createLeasedDataSource(String key, ConnectionProperties connection,
                                                   DatabaseDialect dialect) {
        log.info("Creating new datasource: {}", key);

        ByokDataSourceContext context = null;
        try {
            context = dataSourceFactory.create(key, connection, dialect);

            LeasedDataSource leased = new LeasedDataSource(
                    key, context, leaseDuration, maxLifetime
            );
            if (metricsCollector != null) {
                metricsCollector.recordByokConnectionCreated();
            }
            return leased;
        } catch (Exception e) {
            // Close any partially created resources
            if (context != null) {
                try {
                    context.closePool();
                } catch (Exception closeEx) {
                    log.warn("Failed to close context after creation failure: {}", key, closeEx);
                }
            }
            throw e;
        }
    }

    // ─── JDBC URL guard ─────────────────────────────────────────────────────

    /**
     * Apply {@link ByokProperties.UrlGuard} to a caller-supplied JDBC URL.
     *
     * <p>This is the chokepoint every connection registration passes through, which is why the guard
     * lives here rather than in {@link ConnectionProperties#validate()}: that method is a value-object
     * self-check with no access to configuration and is only called by one tool, so a URL reaching the
     * manager by any other route would skip it.
     *
     * @throws McpValidationException if the URL violates the policy. The message names the offending
     *                                parameter or host but never echoes the URL, which usually carries
     *                                the password.
     */
    private void guardJdbcUrl(String key, String jdbcUrl) {
        String violation = byokProperties.urlGuard().findViolation(jdbcUrl);
        if (violation != null) {
            log.warn("Rejected connection '{}': {}", key, violation);
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "Connection '" + key + "' was rejected: " + violation);
        }
    }

    /**
     * Best-effort JDBC URL of an externally created datasource, so that
     * {@link #registerExisting(String, DataSource, DatabaseDialect)} is guarded too. Returns
     * {@code null} when the URL cannot be determined, in which case there is nothing to guard: the
     * pool was configured by the host application, not by a caller-supplied string.
     */
    private static String jdbcUrlOf(DataSource dataSource) {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            try {
                return hikari.getJdbcUrl();
            } catch (RuntimeException e) {
                log.debug("Could not read jdbcUrl from external datasource: {}", e.getMessage());
            }
        }
        return null;
    }

    // ─── Connection Registry ────────────────────────────────────────────────

    /**
     * Publish metadata, read-only flag and fingerprint for {@code owner} as one atomic unit.
     *
     * @param dialectName 上报给 {@code listConnections} 的方言名。别名路径直接沿用 canonical 已经登记好的
     *                    名字，省掉一次 {@code DialectResolver} 解析，也避免两个名字显示成不同方言
     * @param fingerprint 指纹归属；别名传 {@code null}，见 {@link #adoptAlias}
     */
    private void register(String key, LeasedDataSource owner, ConnectionProperties connection,
                          String dialectName, String fingerprint) {
        var metadata = new ConnectionMetadata(
                key,
                dialectName,
                maskKey(connection != null ? connection.jdbcUrl() : "external"),
                "system",
                java.time.Instant.now(),
                leaseDuration,
                maxLifetime,
                byokProperties.poolSize(),
                0
        );
        boolean readonly = connection != null && Boolean.TRUE.equals(connection.readonly());
        var registration = new ConnectionRegistration(owner, metadata, readonly, fingerprint);

        synchronized (registryLock) {
            ConnectionRegistration previous = registrations.put(key, registration);
            if (previous != null && previous.fingerprint() != null
                    && !previous.fingerprint().equals(fingerprint)) {
                contentFingerprintToKey.remove(previous.fingerprint(), key);
            }
            if (fingerprint != null) {
                contentFingerprintToKey.put(fingerprint, key);
            }
        }
        log.debug("Registered connection: {} -> {} (readonly={})", key, metadata, readonly);
    }

    /**
     * Drop the registration for {@code key} only while it still belongs to {@code owner}.
     *
     * <p>The identity check is the whole point: a removal notification for a lease that expired can be
     * delivered long after the connection was rebuilt, and a by-name removal would then strip the
     * fresh connection of its read-only flag and metadata.
     *
     * @return whether anything was removed
     */
    private boolean unregisterIfOwnedBy(String key, LeasedDataSource owner) {
        synchronized (registryLock) {
            ConnectionRegistration current = registrations.get(key);
            if (current == null || current.owner() != owner) {
                return false;
            }
            registrations.remove(key);
            if (current.fingerprint() != null) {
                contentFingerprintToKey.remove(current.fingerprint(), key);
            }
            return true;
        }
    }

    /**
     * Evict {@code value} and close it, but only remove the cache entry while it still holds exactly
     * that value - a plain {@code invalidate(key)} would throw away a replacement another thread has
     * already installed.
     */
    private void evictIfCurrent(String key, LeasedDataSource value) {
        if (value == null) {
            return;
        }
        unregisterIfOwnedBy(key, value);
        leasedCache.asMap().remove(key, value);
        closeIfUnreferenced(value);
    }

    /**
     * 只有在没有任何注册项还指向 {@code value} 时才关闭它。
     *
     * <p>一个池可以同时挂在 canonical 名字和若干别名下（见 {@link #adoptAlias}）。按名字无条件 close 的话，
     * 别名先过期就会把 canonical 仍在使用的 Hikari 池关掉，之后 canonical 的每次取连接都变成
     * "HikariDataSource has been closed"。引用计数直接从 {@link #registrations} 里按对象身份数出来，
     * 而不是另开一个计数器：计数器和注册表一旦不同步，泄漏或提前关闭都是静默的。
     */
    private void closeIfUnreferenced(LeasedDataSource value) {
        synchronized (registryLock) {
            for (ConnectionRegistration registration : registrations.values()) {
                if (registration.owner() == value) {
                    log.debug("Keeping pool of '{}' open: still referenced by '{}'",
                            value.getKey(), registration.metadata().key());
                    return;
                }
            }
        }
        value.close();
    }

    private String maskKey(String jdbcUrl) {
        if (jdbcUrl == null) return "null";
        int atIndex = jdbcUrl.indexOf('@');
        if (atIndex > 0 && jdbcUrl.contains("//")) {
            String prefix = jdbcUrl.substring(0, jdbcUrl.indexOf("//") + 2);
            String suffix = jdbcUrl.substring(atIndex);
            return prefix + "****" + suffix;
        }
        return jdbcUrl;
    }

    // ─── Public Metadata API ────────────────────────────────────────────────

    @Override
    public ConnectionMetadata getConnectionMetadata(String key) {
        if (key == null || key.isBlank()) return null;
        ConnectionRegistration registration = registrations.get(key);
        return registration != null ? registration.metadata() : null;
    }

    @Override
    public boolean isReadonly(String key) {
        if (key == null || key.isBlank()) return false;
        ConnectionRegistration registration = registrations.get(key);
        return registration != null && registration.readonly();
    }

    @Override
    public Collection<String> listConnectionKeys() {
        return registrations.keySet();
    }

    @Override
    public int getConnectionCount() {
        return registrations.size();
    }

    @Override
    public Collection<ConnectionMetadata> getAllConnectionMetadata() {
        // Return a snapshot to avoid ConcurrentModificationException if registrations change mid-iteration
        return registrations.values().stream()
                .map(ConnectionRegistration::metadata)
                .toList();
    }

    // ─── Shutdown ───────────────────────────────────────────────────────────

    @Override
    public void shutdown() {
        // Snapshot the cache to avoid ConcurrentModificationException during iteration
        Map<String, LeasedDataSource> snapshot = new java.util.HashMap<>(leasedCache.asMap());
        for (var entry : snapshot.entrySet()) {
            String key = entry.getKey();
            LeasedDataSource leased = entry.getValue();
            // Acquire the striped lock before closing to ensure no concurrent access
            synchronized (lockFor(key)) {
                try {
                    leased.close();
                } catch (Exception e) {
                    log.warn("Failed to close datasource during shutdown: {}", key, e);
                }
            }
        }
        leasedCache.invalidateAll();
        synchronized (registryLock) {
            contentFingerprintToKey.clear();
            registrations.clear();
        }
        log.info("All datasources shut down");
    }

    @Override
    public void destroy() {
        shutdown();
    }

    @Override
    public int getActiveConnectionCount() {
        // Use size() on snapshot to ensure consistency during iteration
        return (int) leasedCache.asMap().size();
    }

    /**
     * Force Caffeine to check for expired entries and trigger the removal callback.
     * Safe to call concurrently.
     */
    @Override
    public void evictExpired() {
        leasedCache.cleanUp();
    }

    @Override
    public Map<String, HikariPoolStats> getPoolStats() {
        Map<String, HikariPoolStats> result = new java.util.LinkedHashMap<>();
        // Take a snapshot to avoid ConcurrentModificationException during iteration
        Map<String, LeasedDataSource> snapshot = new java.util.HashMap<>(leasedCache.asMap());
        for (var entry : snapshot.entrySet()) {
            String key = entry.getKey();
            LeasedDataSource leased = entry.getValue();
            // Skip closed datasources to avoid "HikariDataSource has been closed" errors
            if (leased.isClosed()) {
                log.debug("Skipping closed datasource: {}", key);
                continue;
            }
            ByokDataSourceContext context = leased.getContext();
            DataSource ds = context.getDataSource();
            ConnectionMetadata meta = getConnectionMetadata(key);

            try {
                if (ds instanceof com.zaxxer.hikari.HikariDataSource hikari) {
                    com.zaxxer.hikari.HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
                    if (pool != null) {
                        int active = pool.getActiveConnections();
                        int idle = pool.getIdleConnections();
                        int total = pool.getTotalConnections();
                        int waiting = pool.getThreadsAwaitingConnection();
                        java.util.List<String> warnings = new java.util.ArrayList<>();
                        boolean healthy = true;
                        if (waiting > 5) {
                            warnings.add("High wait count: " + waiting + " threads pending");
                            healthy = false;
                        }
                        if (total > 0 && (double) active / total > 0.9) {
                            warnings.add("Pool near exhaustion: " + active + "/" + total + " connections active");
                            healthy = false;
                        }
                        result.put(key, new HikariPoolStats(
                                key,
                                meta != null ? meta.dialect() : "unknown",
                                meta != null ? meta.jdbcUrlMasked() : "unknown",
                                total, active, idle, waiting,
                                hikari.getMaximumPoolSize(),
                                hikari.getMinimumIdle(),
                                hikari.getConnectionTimeout(),
                                hikari.getIdleTimeout(),
                                hikari.getMaxLifetime(),
                                hikari.getLeakDetectionThreshold(),
                                healthy,
                                warnings
                        ));
                    } else {
                        // Pool not initialized yet (HikariCP lazy init)
                        result.put(key, new HikariPoolStats(
                                key,
                                meta != null ? meta.dialect() : "unknown",
                                meta != null ? meta.jdbcUrlMasked() : "unknown",
                                0, 0, 0, 0,
                                hikari.getMaximumPoolSize(),
                                hikari.getMinimumIdle(),
                                hikari.getConnectionTimeout(),
                                hikari.getIdleTimeout(),
                                hikari.getMaxLifetime(),
                                hikari.getLeakDetectionThreshold(),
                                false,
                                java.util.List.of("Pool not yet initialized")
                        ));
                    }
                }
            } catch (IllegalStateException e) {
                // DataSource was closed during iteration
                log.debug("Skipping datasource {} as it was closed during stats collection", key);
            } catch (Exception e) {
                log.warn("Failed to collect stats for datasource {}: {}", key, e.getMessage());
            }
        }
        return result;
    }
}
