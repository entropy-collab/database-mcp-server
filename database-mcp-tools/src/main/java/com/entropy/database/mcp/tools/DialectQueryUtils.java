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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.facade.DatabaseAdminOperations;
import com.entropy.database.mcp.facade.DatabaseOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Map;

/**
 * Shared utilities for executing dialect-specific queries.
 *
 * All methods require an explicit connection name; there is no default connection.
 */
public final class DialectQueryUtils {

    private static final Logger log = LoggerFactory.getLogger(DialectQueryUtils.class);

    private DialectQueryUtils() {
    }

    public static String getDialectName(DatabaseAdminOperations db, String connection) {
        try {
            requireConnection(connection);
            return db.getDialect(connection).getClass().getSimpleName();
        } catch (RuntimeException e) {
            // The dialect name is decoration on a response; a caller that cannot resolve it still
            // wants the rows it asked for, so this degrades instead of failing the whole tool.
            log.warn("Failed to determine dialect for connection '{}', returning 'generic'", connection, e);
            return "generic";
        }
    }

    /**
     * Execute a dialect-specific health check query.
     * Connection is required - no default connection is used.
     */
    public static Map<String, Object> checkHealth(DatabaseOperations db, String connection) {
        try {
            requireConnection(connection);
            DatabaseDialect dialect = db.getDialect(connection);

            String sql = dialect.getHealthCheckSql();
            if (sql == null || sql.isBlank()) {
                throw new IllegalStateException("Health check not supported for dialect: " + dialect.getClass().getSimpleName());
            }

            List<Map<String, Object>> rows = db.queryRows(sql, connection);
            return McpToolUtils.success(Map.of(
                    "connection", connection,
                    "dialect", dialect.getClass().getSimpleName(),
                    "status", "healthy",
                    "rows", rows
            ));
        } catch (DataAccessException | IllegalStateException e) {
            // Narrow on purpose: an McpToolException already carries the right classification
            // (missing connection, rejected SQL) and rewriting it as QUERY_EXECUTION_FAILED hid why
            // the call was refused. Only a real driver failure or an unsupported dialect lands here.
            log.warn("Health check failed for connection '{}': {}", connection, e.getMessage(), e);
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED, "Health check failed for connection '" + connection + "'", e);
        }
    }

    public static Map<String, Object> executeDialectQuery(DatabaseOperations db,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider) {
        return executeDialectQuery(db, connection, sqlProvider, List.of());
    }

    public static Map<String, Object> executeDialectQuery(DatabaseOperations db,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider,
                                                           List<Object> argsList) {
        return executeDialectQuery(db, connection, sqlProvider, dialect -> argsList);
    }

    /**
     * Variant whose bind arguments are derived from the resolved dialect, for the metadata queries
     * whose single placeholder must carry the <em>dialect-normalized</em> table name (Oracle stores
     * identifiers upper-cased, so a lowercase argument matches no dictionary row).
     */
    public static Map<String, Object> executeDialectQuery(DatabaseOperations db,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider,
                                                           java.util.function.Function<DatabaseDialect, List<Object>> argsProvider) {
        try {
            requireConnection(connection);
            DatabaseDialect dialect = db.getDialect(connection);

            String sql = sqlProvider.apply(dialect);
            if (sql == null || sql.isBlank()) {
                throw new IllegalStateException("Dialect does not support this operation: " + dialect.getClass().getSimpleName());
            }
            sql = sql.trim();
            if (sql.startsWith("BEGIN") || sql.startsWith("ANALYZE") || sql.toLowerCase().startsWith("analyze")) {
                // Statistics gathering and PL/SQL blocks report no rows; the row count is not meaningful.
                db.executeUpdate(sql, connection);
                return McpToolUtils.success(Map.of("rows", List.of()));
            }

            List<Object> argsList = argsProvider.apply(dialect);
            List<Map<String, Object>> rows;
            if (argsList.isEmpty() || !sql.contains("?")) {
                rows = db.queryRows(sql, connection);
            } else {
                rows = db.queryRows(sql, connection, argsList.toArray());
            }
            return McpToolUtils.success(Map.of("rows", rows));
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("executeDialectQuery failed for connection '{}': {}", connection, e.getMessage(), e);
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED, "Dialect query execution failed", e);
        }
    }

    public static Map<String, Object> executeDialectQuery(DatabaseOperations db,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider,
                                                           Object... args) {
        if (args == null || args.length == 0) {
            return executeDialectQuery(db, connection, sqlProvider, List.of());
        }
        return executeDialectQuery(db, connection, sqlProvider, java.util.Arrays.stream(args).toList());
    }

    private static void requireConnection(String connection) {
        if (connection == null || connection.isBlank()) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Connection is required.");
        }
    }
}
