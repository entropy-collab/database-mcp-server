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

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import com.entropy.database.mcp.tools.McpToolUtils;

/**
 * Shared utilities for executing dialect-specific queries.
 *
 * All methods require an explicit connection name; there is no default connection.
 */
public final class DialectQueryUtils {

    private static final Logger log = LoggerFactory.getLogger(DialectQueryUtils.class);

    private DialectQueryUtils() {
    }

    public static String getDialectName(DynamicDataSourceManager dataSourceManager, String connection) {
        try {
            requireConnection(connection);
            ByokDataSourceContext context = dataSourceManager.acquire(connection);
            return context.getDialect().getClass().getSimpleName();
        } catch (Exception e) {
            log.warn("Failed to determine dialect for connection '{}', returning 'generic'", connection, e);
            return "generic";
        }
    }

    /**
     * Execute a dialect-specific health check query.
     * Connection is required - no default connection is used.
     */
    public static Map<String, Object> checkHealth(DynamicDataSourceManager dataSourceManager, String connection) {
        try {
            requireConnection(connection);
            ByokDataSourceContext context = dataSourceManager.acquire(connection);
            DatabaseDialect dialect = context.getDialect();
            var jdbcTemplate = context.getJdbcTemplate();

            String sql = dialect.getHealthCheckSql();
            if (sql == null || sql.isBlank()) {
                throw new IllegalStateException("Health check not supported for dialect: " + dialect.getClass().getSimpleName());
            }

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            return McpToolUtils.success(Map.of(
                    "connection", connection,
                    "dialect", dialect.getClass().getSimpleName(),
                    "status", "healthy",
                    "rows", rows
            ));
        } catch (Exception e) {
            log.warn("Health check failed for connection '{}': {}", connection, e.getMessage(), e);
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED, "Health check failed for connection '" + connection + "'", e);
        }
    }

    public static Map<String, Object> executeDialectQuery(DynamicDataSourceManager dataSourceManager,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider) {
        return executeDialectQuery(dataSourceManager, connection, sqlProvider, List.of());
    }

    public static Map<String, Object> executeDialectQuery(DynamicDataSourceManager dataSourceManager,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider,
                                                           List<Object> argsList) {
        try {
            requireConnection(connection);
            ByokDataSourceContext context = dataSourceManager.acquire(connection);
            DatabaseDialect dialect = context.getDialect();
            var jdbcTemplate = context.getJdbcTemplate();

            String sql = sqlProvider.apply(dialect);
            if (sql == null || sql.isBlank()) {
                throw new IllegalStateException("Dialect does not support this operation: " + dialect.getClass().getSimpleName());
            }
            sql = sql.trim();
            if (sql.startsWith("BEGIN") || sql.startsWith("ANALYZE") || sql.toLowerCase().startsWith("analyze")) {
                jdbcTemplate.execute(sql);
                return McpToolUtils.success(Map.of("rows", List.of()));
            }

            List<Map<String, Object>> rows;
            if (argsList.isEmpty() || !sql.contains("?")) {
                rows = jdbcTemplate.queryForList(sql);
            } else {
                rows = jdbcTemplate.queryForList(sql, argsList.toArray());
            }
            return McpToolUtils.success(Map.of("rows", rows));
        } catch (Exception e) {
            log.warn("executeDialectQuery failed for connection '{}': {}", connection, e.getMessage(), e);
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED, "Dialect query execution failed", e);
        }
    }

    public static Map<String, Object> executeDialectQuery(DynamicDataSourceManager dataSourceManager,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider,
                                                           Object... args) {
        if (args == null || args.length == 0) {
            return executeDialectQuery(dataSourceManager, connection, sqlProvider, List.of());
        }
        return executeDialectQuery(dataSourceManager, connection, sqlProvider, java.util.Arrays.stream(args).toList());
    }

    private static void requireConnection(String connection) {
        if (connection == null || connection.isBlank()) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Connection is required.");
        }
    }
}
