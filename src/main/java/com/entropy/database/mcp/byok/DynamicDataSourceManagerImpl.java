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
    private final Map<String, String> contentFingerprintToKey; // content fingerprint → canonical key
    private final Map<String, Object> keyLocks;
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
        this.keyLocks = new ConcurrentHashMap<>();

        this.leasedCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(maxCachedConnections)
                .expireAfterAccess(leaseDuration)
                .removalListener((String key, LeasedDataSource value, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (value != null) {
                        log.info("Removing expired datasource: {} (cause: {})", key, cause);
                        value.close();
                    }
                    metadataRegistry.remove(key);
                    contentFingerprintToKey.values().remove(key);
                    keyLocks.remove(key);
                    if (metricsCollector != null) {
                        metricsCollector.recordByokConnectionRemoved();
                    }
                })
                .build();
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
            LeasedDataSource existing = leasedCache.getIfPresent(canonicalKey);
            if (existing != null) {
                try {
                    return existing.renewLease();
                } catch (McpLeaseExpiredException e) {
                    log.warn("Canonical connection '{}' expired, recreating", canonicalKey);
                    contentFingerprintToKey.remove(fingerprint);
                    leasedCache.invalidate(canonicalKey);
                    existing.close();
                }
            }
        }

        LeasedDataSource existing = leasedCache.getIfPresent(key);
        if (existing != null) {
            try {
                return existing.renewLease();
            } catch (McpLeaseExpiredException e) {
                log.warn("Datasource {} exceeded max lifetime, evicting and recreating", key);
                leasedCache.invalidate(key);
                contentFingerprintToKey.remove(fingerprint);
                existing.close();
            }
        }

        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        boolean created = false;
        synchronized (lock) {
            // Double-check after acquiring lock
            existing = leasedCache.getIfPresent(key);
            if (existing != null) {
                try {
                    return existing.renewLease();
                } catch (McpLeaseExpiredException e) {
                    log.warn("Datasource {} exceeded max lifetime during lock, evicting and recreating", key);
                    leasedCache.invalidate(key);
                    contentFingerprintToKey.remove(fingerprint);
                    existing.close();
                }
            }

            // Re-check canonical key in case another thread created it
            canonicalKey = contentFingerprintToKey.get(fingerprint);
            if (canonicalKey != null && !canonicalKey.equals(key)) {
                log.info("Another thread created canonical connection '{}', reusing", canonicalKey);
                LeasedDataSource alias = leasedCache.getIfPresent(canonicalKey);
                if (alias != null) {
                    try {
                        return alias.renewLease();
                    } catch (McpLeaseExpiredException e) {
                        log.warn("Canonical connection '{}' expired during lock", canonicalKey);
                        contentFingerprintToKey.remove(fingerprint);
                        leasedCache.invalidate(canonicalKey);
                        alias.close();
                    }
                }
            }

            LeasedDataSource newLeased = createLeasedDataSource(key, connection);
            leasedCache.put(key, newLeased);
            contentFingerprintToKey.put(fingerprint, key);
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
        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            LeasedDataSource existing = leasedCache.getIfPresent(key);
            if (existing != null) {
                log.warn("Datasource {} already registered, replacing", key);
                existing.close();
                leasedCache.invalidate(key);
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
                    context.close();
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
    public Collection<String> listConnectionKeys() {
        return metadataRegistry.keySet();
    }

    @Override
    public int getConnectionCount() {
        return metadataRegistry.size();
    }

    @Override
    public Collection<ConnectionMetadata> getAllConnectionMetadata() {
        return metadataRegistry.values();
    }

    // ─── Shutdown ───────────────────────────────────────────────────────────

    @Override
    public void shutdown() {
        for (LeasedDataSource leased : leasedCache.asMap().values()) {
            try {
                leased.close();
            } catch (Exception e) {
                log.warn("Failed to close datasource during shutdown: {}", leased.getKey(), e);
            }
        }
        leasedCache.invalidateAll();
        contentFingerprintToKey.clear();
        metadataRegistry.clear();
        log.info("All datasources shut down");
    }

    @Override
    public void destroy() {
        shutdown();
    }

    @Override
    public int getActiveConnectionCount() {
        return (int) leasedCache.asMap().size();
    }

    /**
     * Force Caffeine to check for expired entries and trigger removal listener.
     */
    @Override
    public void evictExpired() {
        leasedCache.cleanUp();
    }

    @Override
    public Map<String, HikariPoolStats> getPoolStats() {
        Map<String, HikariPoolStats> result = new java.util.LinkedHashMap<>();
        for (var entry : leasedCache.asMap().entrySet()) {
            String key = entry.getKey();
            LeasedDataSource leased = entry.getValue();
            ByokDataSourceContext context = leased.getContext();
            DataSource ds = context.getDataSource();
            ConnectionMetadata meta = metadataRegistry.get(key);

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
        }
        return result;
    }
}
