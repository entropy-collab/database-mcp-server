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
import com.entropy.database.mcp.dialect.DialectResolver;
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
                    keyLocks.remove(key);
                    if (metricsCollector != null) {
                        metricsCollector.recordByokConnectionRemoved();
                    }
                })
                .build();
    }

    /**
     * Acquire a datasource context by key.
     */
    @Override
    public ByokDataSourceContext acquire(String key, ConnectionProperties connection) {
        LeasedDataSource existing = leasedCache.getIfPresent(key);
        if (existing != null) {
            try {
                return existing.renewLease();
            } catch (LeaseExpiredException e) {
                log.warn("Datasource {} exceeded max lifetime, evicting and recreating", key);
                leasedCache.invalidate(key);
                existing.close();
            }
        }

        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        boolean created = false;
        synchronized (lock) {
            try {
                // Double-check after acquiring lock
                existing = leasedCache.getIfPresent(key);
                if (existing != null) {
                    try {
                        return existing.renewLease();
                    } catch (LeaseExpiredException e) {
                        log.warn("Datasource {} exceeded max lifetime during lock, evicting and recreating", key);
                        leasedCache.invalidate(key);
                        existing.close();
                    }
                }

                LeasedDataSource newLeased = createLeasedDataSource(key, connection);
                leasedCache.put(key, newLeased);
                created = true;
                return newLeased.renewLease();
            } finally {
                if (!created) {
                    keyLocks.remove(key, lock);
                }
            }
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
        } catch (LeaseExpiredException e) {
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
}
