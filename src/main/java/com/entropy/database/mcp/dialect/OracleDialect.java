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

/**
 * Oracle-specific dialect with EXPLAIN PLAN support.
 */
public class OracleDialect implements DatabaseDialect {

    @Override
    public String quote(String name) {
        return "\"" + name.toUpperCase() + "\"";
    }

    @Override
    public String tablesQuery(String schema) {
        var owner = schema != null ? schema.toUpperCase() : "USER";
        return """
            SELECT table_name, num_rows AS row_count
            FROM all_tables
            WHERE owner = ?
            ORDER BY table_name
            """;
    }

    @Override
    public String columnsQuery(String table, String schema) {
        var owner = schema != null ? schema.toUpperCase() : "USER";
        return """
            SELECT column_name, data_type, data_length, nullable
            FROM all_tab_columns
            WHERE owner = ?
              AND table_name = ?
            ORDER BY column_id
            """;
    }

    @Override
    public String indexesQuery(String table, String schema) {
        var owner = schema != null ? schema.toUpperCase() : "USER";
        return """
            SELECT i.index_name,
                   i.uniqueness,
                   ic.column_name,
                   ic.column_position
            FROM all_indexes i
            JOIN all_ind_columns ic
              ON i.index_name = ic.index_name
              AND i.owner = ic.index_owner
            WHERE i.table_owner = ?
              AND i.table_name = ?
            ORDER BY i.index_name, ic.column_position
            """;
    }

    @Override
    public String applyLimit(String sql, int limit, int offset) {
        if (offset <= 0) {
            return "SELECT * FROM (" + sql + ") WHERE ROWNUM <= " + limit;
        }
        return """
            SELECT * FROM (
                SELECT inner_.*, ROWNUM rnum FROM (
                    %s
                ) inner_ WHERE ROWNUM <= %d
            ) WHERE rnum > %d
            """.formatted(sql, limit + offset, offset);
    }

    /**
     * Apply ROWNUM-based cursor pagination for stable deep-page queries.
     */
    public String applyRowidPagination(String sql, int limit, String lastRowid) {
        if (lastRowid == null || lastRowid.isBlank()) {
            return "SELECT * FROM (" + sql + ") WHERE ROWNUM <= " + limit;
        }
        long offset = Long.parseLong(lastRowid);
        return """
            SELECT * FROM (
                SELECT inner_.*, ROWNUM rnum FROM (
                    %s
                ) inner_ WHERE ROWNUM <= %d
            ) WHERE rnum > %d
            """.formatted(sql, limit + offset, offset);
    }

    @Override
    public boolean supportsSchema() {
        return true;
    }

    @Override
    public boolean supportsLimit() {
        return false;
    }

    @Override
    public String schemasQuery() {
        return "SELECT DISTINCT owner AS schema_name FROM all_tables ORDER BY owner";
    }

    @Override
    public String viewsQuery(String schema) {
        return """
            SELECT view_name AS name, text AS definition
            FROM all_views
            WHERE owner = ?
            ORDER BY view_name
            """;
    }

    @Override
    public String sequencesQuery(String schema) {
        return """
            SELECT sequence_name AS name, min_value, max_value, increment_by, cache_size
            FROM all_sequences
            WHERE sequence_owner = ?
            ORDER BY sequence_name
            """;
    }

    /**
     * Generate EXPLAIN PLAN SQL for the given query.
     */
    public String explainPlanSql(String sql) {
        return "EXPLAIN PLAN FOR " + sql;
    }

    /**
     * Query the execution plan from PLAN_TABLE.
     */
    public String getExecutionPlan() {
        return """
            SELECT LPAD(' ', 2*(LEVEL-1)) || operation || ' ' || options || 
                   DECODE(object_name, NULL, '', object_name) ||
                   DECODE(object_alias, NULL, '', ' ' || object_alias) ||
                   DECODE(cost, NULL, '', ' (cost=' || cost || ')') AS plan_line
            FROM plan_table
            START WITH id = 0
            CONNECT BY PRIOR id = parent_id
            ORDER BY sid, id
            """;
    }
}
