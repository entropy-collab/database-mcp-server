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
}
