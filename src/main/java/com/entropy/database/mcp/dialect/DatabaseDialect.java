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

public interface DatabaseDialect {
    String quote(String name);
    String tablesQuery(String schema);
    String columnsQuery(String table, String schema);
    String indexesQuery(String table, String schema);
    String applyLimit(String sql, int limit, int offset);
    boolean supportsLimit();
    boolean supportsSchema();

    /**
     * SQL to list all schemas in the database.
     */
    default String schemasQuery() {
        return "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
    }

    /**
     * SQL to list all views in a schema.
     */
    default String viewsQuery(String schema) {
        return """
            SELECT table_name AS name, view_definition AS definition
            FROM information_schema.views
            WHERE table_schema = ?
            ORDER BY table_name
            """;
    }

    /**
     * SQL to list all sequences in a schema.
     */
    default String sequencesQuery(String schema) {
        return """
            SELECT sequence_name AS name, minimum_value, maximum_value, increment, cache_size
            FROM information_schema.sequences
            WHERE sequence_schema = ?
            ORDER BY sequence_name
            """;
    }

    default String explainPlanSql(String sql) {
        return null; // Not supported
    }

    default String getExecutionPlanSql() {
        return null; // Not supported
    }
}
