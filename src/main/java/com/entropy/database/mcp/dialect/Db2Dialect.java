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

public class Db2Dialect extends AbstractDatabaseDialect {

    @Override
    public String quote(String name) {
        return "\"" + name + "\"";
    }

    @Override
    public String tablesQuery(String schema) {
        var schemaFilter = schema != null ? "AND TABLE_SCHEMA = ?" : "";
        return """
            SELECT TABLE_NAME AS table_name, 0 AS row_count
            FROM SYSCAT.TABLES
            WHERE TYPE = 'T'
              AND TABLE_SCHEMA <> 'SYSIBM'
              %s
            ORDER BY TABLE_NAME
            """.formatted(schemaFilter);
    }

    @Override
    public String columnsQuery(String table, String schema) {
        var schemaFilter = schema != null ? "AND TABLE_SCHEMA = ?" : "";
        return """
            SELECT COLNAME AS column_name, TYPENAME AS data_type, NULLS AS is_nullable
            FROM SYSCAT.COLUMNS
            WHERE TABNAME = ?
              %s
            ORDER BY COLNO
            """.formatted(schemaFilter);
    }

    @Override
    public String indexesQuery(String table, String schema) {
        var schemaFilter = schema != null ? "AND i.TABSCHEMA = ?" : "";
        return """
            SELECT i.INDNAME AS index_name,
                   CASE WHEN i.UNIQUERULE = 'U' THEN 0 ELSE 1 END AS non_unique,
                   c.COLNAME AS column_name,
                   c.COLSEQ AS seq_in_index
            FROM SYSCAT.INDEXES i
            JOIN SYSCAT.INDEXCOLS c ON i.INDNAME = c.INDNAME AND i.TABSCHEMA = c.TABSCHEMA AND i.TABNAME = c.TABNAME
            WHERE i.TABNAME = ?
              %s
            ORDER BY i.INDNAME, c.COLSEQ
            """.formatted(schemaFilter);
    }

    @Override
    public String applyLimit(String sql, int limit, int offset) {
        if (offset <= 0) {
            return sql + " FETCH FIRST " + limit + " ROWS ONLY";
        }
        return "SELECT * FROM (SELECT inner_.*, ROW_NUMBER() OVER (ORDER BY (SELECT 1)) AS rn FROM (" +
                sql + ") inner_) WHERE rn BETWEEN " + (offset + 1) + " AND " + (offset + limit);
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
        return "SELECT SCHEMANAME AS schema_name FROM SYSCAT.SCHEMATA WHERE SCHEMANAME <> 'SYSIBM' ORDER BY SCHEMANAME";
    }

    @Override
    public String viewsQuery(String schema) {
        var schemaFilter = schema != null ? "AND TABLE_SCHEMA = ?" : "";
        return """
            SELECT TABNAME AS name, TEXT AS definition
            FROM SYSCAT.VIEWS
            WHERE 1=1
              %s
            ORDER BY TABNAME
            """.formatted(schemaFilter);
    }

    @Override
    public String sequencesQuery(String schema) {
        var schemaFilter = schema != null ? "AND SEQSCHEMA = ?" : "";
        return """
            SELECT SEQNAME AS name,
                   CAST(MINVALUE AS VARCHAR(100)) AS minimum_value,
                   CAST(MAXVALUE AS VARCHAR(100)) AS maximum_value,
                   CAST(INCREMENT AS VARCHAR(100)) AS increment,
                   0 AS cache_size
            FROM SYSCAT.SEQUENCES
            WHERE 1=1
              %s
            ORDER BY SEQNAME
            """.formatted(schemaFilter);
    }
}
