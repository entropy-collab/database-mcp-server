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

public class GenericDialect implements DatabaseDialect {

    @Override
    public String quote(String name) {
        return "`" + name + "`";
    }

    @Override
    public String tablesQuery(String schema) {
        return """
            SELECT table_name, 0 AS row_count
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;
    }

    @Override
    public String columnsQuery(String table, String schema) {
        return """
            SELECT column_name, data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema = ?
              AND table_name = ?
            ORDER BY ordinal_position
            """;
    }

    @Override
    public String indexesQuery(String table, String schema) {
        return """
            SELECT index_name, uniqueness, column_name, ordinal_position
            FROM information_schema.statistics
            WHERE table_schema = ?
              AND table_name = ?
            ORDER BY index_name, ordinal_position
            """;
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
}
