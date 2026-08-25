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

public class SqliteDialect extends AbstractDatabaseDialect {

    /**
     * Quotes an identifier, escaping any embedded double quote by doubling it so that a
     * delimiter inside {@code name} can never terminate the identifier context.
     */
    @Override
    public String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String tablesQuery(String schema) {
        return """
            SELECT name AS table_name, 0 AS row_count
            FROM sqlite_master
            WHERE type = 'table'
              AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """;
    }

    @Override
    public String columnsQuery(String table, String schema) {
        return """
            PRAGMA table_info(%s)
            """.formatted(quote(table));
    }

    @Override
    public String indexesQuery(String table, String schema) {
        return """
            PRAGMA index_list(%s)
            """.formatted(quote(table));
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
        return false;
    }

    @Override
    public boolean supportsLimit() {
        return true;
    }

    @Override
    public String schemasQuery() {
        // SQLite does not have schemas; return empty result set
        return "SELECT 'main' AS schema_name WHERE 1 = 0";
    }

    @Override
    public String viewsQuery(String schema) {
        return """
            SELECT name AS name, sql AS definition
            FROM sqlite_master
            WHERE type = 'view'
            ORDER BY name
            """;
    }

    @Override
    public String sequencesQuery(String schema) {
        // SQLite does not support sequences; return empty result set
        return """
            SELECT '' AS name, '' AS minimum_value, '' AS maximum_value, '' AS increment, 0 AS cache_size
            WHERE 1 = 0
            """;
    }

    @Override
    public String getTableRowCountSql(String schema, String tableName) {
        return "SELECT COUNT(*) AS row_count FROM " + quote(tableName);
    }

    @Override
    public String getHealthCheckSql() {
        return "SELECT 'OK' AS status";
    }
}
