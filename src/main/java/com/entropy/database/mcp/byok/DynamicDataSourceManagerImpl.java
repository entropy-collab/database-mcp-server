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
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for all datasources (primary and BYOK).
 * Handles lifecycle management with TTL-based lease renewal.
 */
public class DynamicDataSourceManagerImpl implements DynamicDataSourceManager {
    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceManagerImpl.class);

    private final DialectResolver dialectResolver;
    private final ByokInfrastructureFactory infrastructureFactory;
    private final ConnectionPoolFactory connectionPoolFactory;
    private final com.github.benmanes.caffeine.cache.Cache<String, LeasedDataSource> leasedCache;
    private final Map<String, ConnectionMetadata> metadataRegistry;
    private final Map<String, Object> keyLocks;
    private final Duration leaseDuration;
    private final Duration maxLifetime;
    private final int maxCachedConnections;
    private final ByokProperties byokProperties;
    private final int fetchSize;
    private final McpMetricsCollector metricsCollector;

    /**
     * Dependencies record for DynamicDataSourceManagerImpl.
     * Groups constructor parameters to simplify bean definition.
     */
    public record Dependencies(
            DialectResolver dialectResolver,
            ByokInfrastructureFactory infrastructureFactory,
            ConnectionPoolFactory connectionPoolFactory,
            ByokProperties byokProperties,
            int fetchSize,
            McpMetricsCollector metricsCollector) {
    }

    public DynamicDataSourceManagerImpl(Dependencies deps) {
        this.dialectResolver = deps.dialectResolver();
        this.infrastructureFactory = deps.infrastructureFactory();
        this.connectionPoolFactory = deps.connectionPoolFactory();
        this.byokProperties = deps.byokProperties();
        this.leaseDuration = deps.byokProperties().leaseDuration();
        this.maxLifetime = deps.byokProperties().maxLifetime();
        this.maxCachedConnections = deps.byokProperties().maxCachedConnections();
        this.fetchSize = deps.fetchSize();
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
     * For primary datasource, use key="primary".
     * For BYOK, use the connection's cache key.
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
     * Register an existing Spring-managed datasource (e.g. primary) as a BYOK connection.
     * The datasource is NOT closed when the lease expires (managed by Spring).
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

            // Create JdbcTemplate from existing datasource
            JdbcTemplate jdbcTemplate = new JdbcTemplate(existingDataSource);

            // Assemble context (closeable=false because Spring manages this datasource)
            ByokInfrastructure infrastructure = infrastructureFactory.create(key, jdbcTemplate, dialect, fetchSize);
            ByokDataSourceContext context = new ByokDataSourceContext(
                    key, existingDataSource, dialect, jdbcTemplate, infrastructure
            );

            LeasedDataSource leased = new LeasedDataSource(key, context, leaseDuration, maxLifetime, false);
            leasedCache.put(key, leased);

            // Register metadata for primary
            registerMetadata(key, null, dialect, existingDataSource, true);

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

        HikariDataSource dataSource = null;
        try {
            // 1. Resolve dialect BEFORE creating datasource
            DatabaseDialect dialect = dialectResolver.resolve(connection.dialect(), null);

            // 2. Create DataSource using centralized connection pool factory
            dataSource = (HikariDataSource) connectionPoolFactory.createDataSource(connection, dialect);

            // 3. Create JdbcTemplate
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            // 4. Create per-datasource infrastructure
            ByokInfrastructure infrastructure = infrastructureFactory.create(key, jdbcTemplate, dialect, fetchSize);

            // 5. Assemble context
            ByokDataSourceContext context = new ByokDataSourceContext(
                    key, dataSource, dialect, jdbcTemplate, infrastructure
            );

            LeasedDataSource leased = new LeasedDataSource(
                    key, context, leaseDuration, maxLifetime
            );
            if (metricsCollector != null) {
                metricsCollector.recordByokConnectionCreated();
            }
            return leased;
        } catch (Exception e) {
            if (dataSource != null) {
                try {
                    dataSource.close();
                } catch (Exception closeEx) {
                    log.warn("Failed to close datasource after creation failure: {}", key, closeEx);
                }
            }
            throw e;
        }
    }

    // ─── Metadata Registry ──────────────────────────────────────────────────

    /**
     * Register connection metadata for observability and management.
     */
    private void registerMetadata(String key, ConnectionProperties connection, DatabaseDialect dialect,
                                  DataSource dataSource, boolean isPrimary) {
        var metadata = new ConnectionMetadata(
                key,
                dialect.getClass().getSimpleName(),
                maskKey(connection != null ? connection.jdbcUrl() : "primary"),
                "system",
                java.time.Instant.now(),
                leaseDuration,
                maxLifetime,
                isPrimary,
                isPrimary ? 0 : byokProperties.poolSize(),
                0
        );
        metadataRegistry.put(key, metadata);
        log.debug("Registered connection metadata: {} -> {}", key, metadata);
    }

    /**
     * Mask password in URL for logging.
     */
    private String maskKey(String jdbcUrl) {
        if (jdbcUrl == null) return "null";
        // Simple masking: replace password if present
        int atIndex = jdbcUrl.indexOf('@');
        if (atIndex > 0 && jdbcUrl.contains("//")) {
            String prefix = jdbcUrl.substring(0, jdbcUrl.indexOf("//") + 2);
            String suffix = jdbcUrl.substring(atIndex);
            return prefix + "****" + suffix;
        }
        return jdbcUrl;
    }

    // ─── Public Metadata API ────────────────────────────────────────────────

    /**
     * Get metadata for a specific connection.
     *
     * @return ConnectionMetadata or null if not found
     */
    @Override
    public ConnectionMetadata getConnectionMetadata(String key) {
        if (key == null || key.isBlank()) return null;
        return metadataRegistry.get(key);
    }

    /**
     * List all registered connection keys.
     */
    @Override
    public Collection<String> listConnectionKeys() {
        return metadataRegistry.keySet();
    }

    /**
     * Get total number of registered connections.
     */
    @Override
    public int getConnectionCount() {
        return metadataRegistry.size();
    }

    /**
     * Get all connection metadata entries.
     */
    @Override
    public Collection<ConnectionMetadata> getAllConnectionMetadata() {
        return metadataRegistry.values();
    }

    // ─── Shutdown ───────────────────────────────────────────────────────────

    /**
     * Shutdown all datasources.
     */
    @Override
    public void shutdown() {
        for (LeasedDataSource leased : leasedCache.asMap().values()) {
            leased.close();
        }
        leasedCache.invalidateAll();
        metadataRegistry.clear();
        log.info("All datasources shut down");
    }

    /**
     * Get current cache size (number of active leased datasources).
     */
    @Override
    public int getActiveConnectionCount() {
        return (int) leasedCache.asMap().size();
    }
}
