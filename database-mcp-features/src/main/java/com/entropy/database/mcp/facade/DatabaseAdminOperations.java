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
package com.entropy.database.mcp.facade;

import com.entropy.database.mcp.dialect.DatabaseDialect;

import java.util.Map;

/**
 * Cache, statistics and driver-level introspection for a single connection.
 *
 * <p>Everything here is answerable from one connection's own context, which is what keeps
 * {@code ByokDatabaseFacade} able to implement this interface honestly. Capabilities that need
 * more than one connection or a collaborating service live in {@link DatabaseBackupOperations}
 * and {@link CrossConnectionOperations}.
 */
public interface DatabaseAdminOperations {

    void clearCache(String connection);

    Map<String, Object> getStatistics(String connection);

    /**
     * The dialect resolved for a connection.
     *
     * <p>Exposed because tools legitimately need to build dialect-specific SQL (Oracle DB links,
     * flashback queries, upsert syntax) before handing it to {@link DatabaseReadOperations} or
     * {@link DatabaseWriteOperations}. Handing out the dialect is safe; handing out the
     * {@code JdbcTemplate} is what lets a caller escape the facade's advice.
     */
    DatabaseDialect getDialect(String connection);

    /**
     * Inspect driver-level metadata for a connection.
     *
     * <p>The escape hatch of last resort, for probes that JDBC only exposes through
     * {@link java.sql.DatabaseMetaData}. Scoped to a callback so the connection's lifetime stays
     * with the facade.
     */
    <T> T withMetaData(String connection, MetaDataCallback<T> callback);
}
