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
import com.entropy.database.mcp.monitor.HikariPoolStats;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Map;

/**
 * Central manager for all datasources.
 * All connections are equal BYOK connections; there is no primary/default concept.
 */
public interface DynamicDataSourceManager {

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
     * Get metadata for a specific connection.
     *
     * @return ConnectionMetadata or null if not found
     */
    ConnectionMetadata getConnectionMetadata(String key);

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
     * Get real-time HikariCP pool statistics for all registered connections.
     *
     * @return map of connection name → pool stats, empty if no HikariDataSource available
     */
    Map<String, HikariPoolStats> getPoolStats();
}
