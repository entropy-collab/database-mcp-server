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
package com.entropy.database.mcp.dialect;

import com.entropy.database.mcp.properties.DatabaseProperties;
import com.zaxxer.hikari.HikariConfig;

/**
 * H2 database dialect.
 * H2 stores INFORMATION_SCHEMA objects in uppercase when DATABASE_TO_UPPER=false.
 */
public class H2Dialect extends AbstractDatabaseDialect {

    @Override
    public String quote(String name) {
        return "\"" + name + "\"";
    }

    @Override
    public String schemasQuery() {
        return """
            SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA
            ORDER BY SCHEMA_NAME
            """;
    }

    @Override
    public String tablesQuery(String schema) {
        var schemaFilter = schema != null ? "AND TABLE_SCHEMA = ?" : "";
        return """
            SELECT TABLE_NAME, 0 AS ROW_COUNT
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_TYPE = 'BASE TABLE'
            %s
            ORDER BY TABLE_NAME
            """.formatted(schemaFilter);
    }

    @Override
    public String searchTablesQuery(String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return """
                SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME, 0 AS row_count
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_TYPE = 'BASE TABLE'
                  AND TABLE_NAME LIKE ?
                ORDER BY TABLE_SCHEMA, TABLE_NAME
                """;
        }
        return """
            SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME, 0 AS row_count
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_TYPE = 'BASE TABLE'
            ORDER BY TABLE_SCHEMA, TABLE_NAME
            """;
    }

    @Override
    public String columnsQuery(String table, String schema) {
        var schemaFilter = schema != null ? "TABLE_SCHEMA = ? AND" : "TABLE_SCHEMA IS NULL AND";
        return """
            SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE %s
              TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """.formatted(schemaFilter);
    }

    @Override
    public String indexesQuery(String table, String schema) {
        var schemaFilter = schema != null ? "TABLE_SCHEMA = ? AND" : "TABLE_SCHEMA IS NULL AND";
        return """
            SELECT INDEX_NAME,
                   CASE WHEN IS_GENERATED = TRUE THEN 0 ELSE 1 END AS non_unique,
                   INDEX_NAME AS column_name,
                   0 AS seq_in_index
            FROM INFORMATION_SCHEMA.INDEXES
            WHERE %s
              TABLE_NAME = ?
            ORDER BY INDEX_NAME
            """.formatted(schemaFilter);
    }

    @Override
    public String viewsQuery(String schema) {
        var schemaFilter = schema != null ? "AND TABLE_SCHEMA = ?" : "";
        return """
            SELECT TABLE_NAME AS NAME, VIEW_DEFINITION AS DEFINITION
            FROM INFORMATION_SCHEMA.VIEWS
            WHERE 1=1
            %s
            ORDER BY TABLE_NAME
            """.formatted(schemaFilter);
    }

    @Override
    public String sequencesQuery(String schema) {
        var schemaFilter = schema != null ? "AND SEQUENCE_SCHEMA = ?" : "";
        return """
            SELECT SEQUENCE_NAME AS NAME, MIN_VALUE, MAX_VALUE, INCREMENT, CACHE_SIZE
            FROM INFORMATION_SCHEMA.SEQUENCES
            WHERE 1=1
            %s
            ORDER BY SEQUENCE_NAME
            """.formatted(schemaFilter);
    }

    @Override
    public String applyLimit(String sql, int limit, int offset) {
        if (offset <= 0) {
            return sql + " LIMIT " + limit;
        }
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public boolean supportsSchema() {
        return true;
    }

    @Override
    public boolean supportsLimit() {
        return true;
    }

    @Override
    public void configureDataSource(HikariConfig config, DatabaseProperties properties) {
        // H2 specific configuration if needed
    }

    public String healthCheckSql() {
        return "SELECT 'OK' AS status";
    }
}
