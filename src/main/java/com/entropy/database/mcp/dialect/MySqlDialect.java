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

public class MySqlDialect implements DatabaseDialect {

    @Override
    public String quote(String name) {
        return "`" + name + "`";
    }

    @Override
    public String tablesQuery(String schema) {
        var schemaFilter = schema != null ? "AND table_schema = ?" : "";
        return """
            SELECT table_name, table_rows AS row_count
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
            %s
            ORDER BY table_name
            """.formatted(schemaFilter);
    }

    @Override
    public String columnsQuery(String table, String schema) {
        var schemaFilter = schema != null ? "AND table_schema = ?" : "";
        return """
            SELECT column_name, column_type, is_nullable
            FROM information_schema.columns
            WHERE table_name = ?
            %s
            ORDER BY ordinal_position
            """.formatted(schemaFilter);
    }

    @Override
    public String indexesQuery(String table, String schema) {
        var schemaFilter = schema != null ? "AND table_schema = ?" : "";
        return """
            SELECT index_name, non_unique, column_name, seq_in_index
            FROM information_schema.statistics
            WHERE table_name = ?
            %s
            ORDER BY index_name, seq_in_index
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
    public String schemasQuery() {
        return "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
    }

    @Override
    public String viewsQuery(String schema) {
        return """
            SELECT table_name AS name, view_definition AS definition
            FROM information_schema.views
            WHERE table_schema = ?
            ORDER BY table_name
            """;
    }

    @Override
    public String sequencesQuery(String schema) {
        // MySQL does not support sequences; return empty result set with matching column names
        return """
            SELECT '' AS name, '' AS minimum_value, '' AS maximum_value, '' AS increment, 0 AS cache_size
            FROM dual WHERE 1 = 0
            """;
    }
}
