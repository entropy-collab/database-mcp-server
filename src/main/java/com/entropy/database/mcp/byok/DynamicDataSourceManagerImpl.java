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

import com.entropy.database.mcp.exception.McpLeaseExpiredException;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.monitor.HikariPoolStats;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.properties.ByokProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<String, ConnectionMetadata> metadataRegistry;
    private final Set<String> readonlyKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, String> contentFingerprintToKey; // content fingerprint → canonical key

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

    /** Guards the metadata / fingerprint / readonly registries as one unit. */
    private final Object registryLock = new Object();

    private final Duration leaseDuration;
    private final Duration maxLifetime;
    private final int maxCachedConnections;
    private final ByokProperties byokProperties;
    private final McpMetricsCollector metricsCollector;

    /**
     * Dependencies record for DynamicDataSourceManagerImpl.
     * Groups constructor parameters to simplify bean definition.
     */
    public record Dependencies(
            DialectResolver dialectResolver,
            ByokDataSourceFactory dataSourceFactory,
            ByokProperties byokProperties,
            McpMetricsCollector metricsCollector) {
    }

    public DynamicDataSourceManagerImpl(Dependencies deps) {
        this.dialectResolver = deps.dialectResolver();
        this.dataSourceFactory = deps.dataSourceFactory();
        this.byokProperties = deps.byokProperties();
        this.leaseDuration = deps.byokProperties().leaseDuration();
        this.maxLifetime = deps.byokProperties().maxLifetime();
        this.maxCachedConnections = deps.byokProperties().maxCachedConnections();
        this.metricsCollector = deps.metricsCollector();

        this.metadataRegistry = new ConcurrentHashMap<>();
        this.contentFingerprintToKey = new ConcurrentHashMap<>();
        java.util.Arrays.setAll(this.keyLocks, i -> new Object());


        this.leasedCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(maxCachedConnections)
                .expireAfterAccess(leaseDuration)
                .removalListener((String key, LeasedDataSource value, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    // Acquire per-key lock to coordinate with acquire() threads
                    Object lock = lockFor(key);
                    synchronized (lock) {
                        synchronized (registryLock) {
                            metadataRegistry.remove(key);
                            readonlyKeys.remove(key);
                            contentFingerprintToKey.values().removeIf(v -> v.equals(key));
                        }
                        if (metricsCollector != null) {
                            metricsCollector.recordByokConnectionRemoved();
                        }
                        // Close datasource while holding the per-key lock to prevent
                        // concurrent acquire() from getting a closed datasource
                        if (value != null) {
                            log.info("Removing expired datasource: {} (cause: {})", key, cause);
                            value.close();
                        }
                    }
                })
                .build();
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
        String fingerprint = connection.getCacheKey();

        // Check if an identical physical connection already exists under a different name
        String canonicalKey = contentFingerprintToKey.get(fingerprint);
        if (canonicalKey != null && !canonicalKey.equals(key)) {
            log.info("Connection '{}' is an alias for existing connection '{}', reusing same pool", key, canonicalKey);
            Object lock = lockFor(canonicalKey);
            synchronized (lock) {
                LeasedDataSource existing = leasedCache.getIfPresent(canonicalKey);
                if (existing != null && !existing.isClosed()) {
                    try {
                        return existing.renewLease();
                    } catch (McpLeaseExpiredException e) {
                        log.warn("Canonical connection '{}' expired, recreating", canonicalKey);
                        contentFingerprintToKey.remove(fingerprint);
                        leasedCache.invalidate(canonicalKey);
                        existing.close();
                    }
                } else if (existing != null && existing.isClosed()) {
                    log.warn("Canonical connection '{}' is closed, evicting", canonicalKey);
                    contentFingerprintToKey.remove(fingerprint);
                    leasedCache.invalidate(canonicalKey);
                }
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
                    leasedCache.invalidate(key);
                    contentFingerprintToKey.remove(fingerprint);
                    existing.close();
                }
            }
        } else if (existing != null && existing.isClosed()) {
            // Connection is being closed by removalListener, evict and recreate
            log.warn("Datasource {} is closed (eviction in progress), evicting and recreating", key);
            leasedCache.invalidate(key);
            contentFingerprintToKey.remove(fingerprint);
        }

        Object lock = lockFor(key);
        boolean created = false;
        synchronized (lock) {
            // Double-check after acquiring lock
            existing = leasedCache.getIfPresent(key);
            if (existing != null && !existing.isClosed()) {
                try {
                    return existing.renewLease();
                } catch (McpLeaseExpiredException e) {
                    log.warn("Datasource {} exceeded max lifetime during lock, evicting and recreating", key);
                    leasedCache.invalidate(key);
                    contentFingerprintToKey.remove(fingerprint);
                    existing.close();
                }
            } else if (existing != null && existing.isClosed()) {
                // Evict closed connection and proceed to create new one
                log.warn("Datasource {} is closed (eviction in progress), evicting", key);
                leasedCache.invalidate(key);
                contentFingerprintToKey.remove(fingerprint);
            }

            // Re-check canonical key in case another thread created it
            canonicalKey = contentFingerprintToKey.get(fingerprint);
            if (canonicalKey != null && !canonicalKey.equals(key)) {
                log.info("Another thread created canonical connection '{}', reusing", canonicalKey);
                LeasedDataSource alias = leasedCache.getIfPresent(canonicalKey);
                if (alias != null && !alias.isClosed()) {
                    try {
                        return alias.renewLease();
                    } catch (McpLeaseExpiredException e) {
                        log.warn("Canonical connection '{}' expired during lock", canonicalKey);
                        contentFingerprintToKey.remove(fingerprint);
                        leasedCache.invalidate(canonicalKey);
                        alias.close();
                    }
                } else if (alias != null && alias.isClosed()) {
                    log.warn("Canonical connection '{}' is closed during lock", canonicalKey);
                    contentFingerprintToKey.remove(fingerprint);
                    leasedCache.invalidate(canonicalKey);
                }
            }

            LeasedDataSource newLeased = createLeasedDataSource(key, connection);
            leasedCache.put(key, newLeased);
            contentFingerprintToKey.put(fingerprint, key);
            registerMetadata(key, connection,
                    dialectResolver.resolve(connection.dialect(), null),
                    newLeased.getContext().getDataSource());
            created = true;
            return newLeased.renewLease();
        }
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
        Object lock = lockFor(key);
        synchronized (lock) {
            LeasedDataSource existing = leasedCache.getIfPresent(key);
            if (existing != null) {
                log.warn("Datasource {} already registered, replacing", key);
                leasedCache.invalidate(key); // Invalidate first to prevent new access
                existing.close(); // Then close (will be idempotent if already closed by removalListener)
            }

            ByokDataSourceContext context = dataSourceFactory.createExisting(key, existingDataSource, dialect);
            LeasedDataSource leased = new LeasedDataSource(key, context, leaseDuration, maxLifetime, false);
            leasedCache.put(key, leased);

            registerMetadata(key, null, dialect, existingDataSource);

            log.info("Registered existing datasource as BYOK connection: {}", key);
            if (metricsCollector != null) {
                metricsCollector.recordByokConnectionCreated();
            }
        }
    }

    /**
     * Create a new LeasedDataSource for the given key and connection.
     */
    private LeasedDataSource createLeasedDataSource(String key, ConnectionProperties connection) {
        log.info("Creating new datasource: {}", key);

        ByokDataSourceContext context = null;
        try {
            // 1. Resolve dialect BEFORE creating datasource
            DatabaseDialect dialect = dialectResolver.resolve(connection.dialect(), null);

            // 2. Create full context via unified factory
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

    // ─── Metadata Registry ──────────────────────────────────────────────────

    private void registerMetadata(String key, ConnectionProperties connection, DatabaseDialect dialect,
                                  DataSource dataSource) {
        var metadata = new ConnectionMetadata(
                key,
                dialect.getClass().getSimpleName(),
                maskKey(connection != null ? connection.jdbcUrl() : "external"),
                "system",
                java.time.Instant.now(),
                leaseDuration,
                maxLifetime,
                byokProperties.poolSize(),
                0
        );
        metadataRegistry.put(key, metadata);
        if (connection != null && Boolean.TRUE.equals(connection.readonly())) {
            readonlyKeys.add(key);
        } else {
            readonlyKeys.remove(key);
        }
        log.debug("Registered connection metadata: {} -> {}", key, metadata);
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
        return metadataRegistry.get(key);
    }

    @Override
    public boolean isReadonly(String key) {
        if (key == null || key.isBlank()) return false;
        return readonlyKeys.contains(key);
    }

    @Override
    public Collection<String> listConnectionKeys() {
        return metadataRegistry.keySet();
    }

    @Override
    public int getConnectionCount() {
        return metadataRegistry.size();
    }

    @Override
    public Collection<ConnectionMetadata> getAllConnectionMetadata() {
        // Return a snapshot to avoid ConcurrentModificationException if metadataRegistry is modified during iteration
        return new java.util.ArrayList<>(metadataRegistry.values());
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
        contentFingerprintToKey.clear();
        metadataRegistry.clear();
        readonlyKeys.clear();
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
     * Force Caffeine to check for expired entries and trigger removal listener.
     * Safe to call concurrently - will acquire per-key locks for any evictions.
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
            ConnectionMetadata meta = metadataRegistry.get(key);

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
