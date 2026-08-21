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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;

/**
 * Shared utilities for executing dialect-specific queries.
 */
public final class DialectQueryUtils {

    private DialectQueryUtils() {
    }

    public static String getDialectName(DynamicDataSourceManager dataSourceManager, String connection) {
        try {
            if (connection == null || connection.isBlank()) {
                return "generic";
            }
            ByokDataSourceContext context = dataSourceManager.acquire(connection);
            return context.getDialect().getClass().getSimpleName();
        } catch (Exception e) {
            return "generic";
        }
    }

    public static Map<String, Object> executeDialectQuery(DynamicDataSourceManager dataSourceManager,
                                                           DatabaseDialect primaryDialect,
                                                           JdbcTemplate primaryJdbcTemplate,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider) {
        return executeDialectQuery(dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, sqlProvider, List.of());
    }

    public static Map<String, Object> executeDialectQuery(DynamicDataSourceManager dataSourceManager,
                                                           DatabaseDialect primaryDialect,
                                                           JdbcTemplate primaryJdbcTemplate,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider,
                                                           List<Object> argsList) {
        try {
            DatabaseDialect dialect;
            JdbcTemplate jdbcTemplate;
            if (connection == null || connection.isBlank()) {
                dialect = primaryDialect;
                jdbcTemplate = primaryJdbcTemplate;
            } else {
                ByokDataSourceContext context = dataSourceManager.acquire(connection);
                dialect = context.getDialect();
                jdbcTemplate = context.getJdbcTemplate();
            }

            String sql = sqlProvider.apply(dialect);
            if (sql == null || sql.isBlank()) {
                throw new IllegalStateException("Dialect does not support this operation: " + dialect.getClass().getSimpleName());
            }
            sql = sql.trim();
            if (sql.startsWith("BEGIN") || sql.startsWith("ANALYZE") || sql.toLowerCase().startsWith("analyze")) {
                jdbcTemplate.execute(sql);
                return successResponse(Map.of("rows", List.of()));
            }

            List<Map<String, Object>> rows;
            if (argsList.isEmpty()) {
                rows = jdbcTemplate.queryForList(sql);
            } else {
                rows = jdbcTemplate.queryForList(sql, argsList.toArray());
            }
            return successResponse(Map.of("rows", rows));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static Map<String, Object> executeDialectQuery(DynamicDataSourceManager dataSourceManager,
                                                           DatabaseDialect primaryDialect,
                                                           JdbcTemplate primaryJdbcTemplate,
                                                           String connection,
                                                           java.util.function.Function<DatabaseDialect, String> sqlProvider,
                                                           Object... args) {
        if (args == null || args.length == 0) {
            return executeDialectQuery(dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, sqlProvider, List.of());
        }
        return executeDialectQuery(dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, sqlProvider, List.of(args));
    }
}
