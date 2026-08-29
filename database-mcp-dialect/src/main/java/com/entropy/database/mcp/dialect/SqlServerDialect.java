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

import java.util.LinkedHashMap;
import java.util.Map;

public class SqlServerDialect extends AbstractDatabaseDialect {

    /**
     * Quotes an identifier, escaping any embedded closing bracket by doubling it so that a
     * delimiter inside {@code name} can never terminate the identifier context.
     */
    @Override
    public String quote(String name) {
        return "[" + name.replace("]", "]]") + "]";
    }

    /**
     * Resolves the schema side of a metadata predicate without spending a placeholder on it.
     * {@code SCHEMA_NAME()} is the caller's default schema, which is what an omitted schema means.
     */
    private String schemaExpression(String schema) {
        return DialectUtils.schemaExpression(schema, "SCHEMA_NAME()");
    }

    @Override
    public String tablesQuery(String schema) {
        return """
            SELECT TABLE_NAME AS table_name, 0 AS row_count
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_TYPE = 'BASE TABLE'
              AND TABLE_SCHEMA = %s
            ORDER BY TABLE_NAME
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String columnsQuery(String table, String schema) {
        return """
            SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_NAME = ?
              AND TABLE_SCHEMA = %s
            ORDER BY ORDINAL_POSITION
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String indexesQuery(String table, String schema) {
        return """
            SELECT i.name AS index_name,
                   CASE WHEN i.is_unique = 1 THEN 0 ELSE 1 END AS non_unique,
                   c.name AS column_name,
                   ic.key_ordinal AS seq_in_index
            FROM sys.indexes i
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE i.type_desc <> 'HEAP'
              AND t.name = ?
              AND s.name = %s
            ORDER BY i.name, ic.key_ordinal
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String applyLimit(String sql, int limit, int offset) {
        if (offset <= 0) {
            // Simple TOP N
            if (sql.trim().toUpperCase().startsWith("SELECT")) {
                return sql.replaceFirst("(?i)SELECT", "SELECT TOP " + limit);
            }
            return "SELECT TOP " + limit + " * FROM (" + sql + ") AS t";
        }
        // OFFSET requires ORDER BY
        return sql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
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
        return "SELECT schema_name FROM INFORMATION_SCHEMA.SCHEMATA ORDER BY schema_name";
    }

    @Override
    public String viewsQuery(String schema) {
        var schemaFilter = schema != null ? "AND TABLE_SCHEMA = ?" : "";
        return """
            SELECT TABLE_NAME AS name, VIEW_DEFINITION AS definition
            FROM INFORMATION_SCHEMA.VIEWS
            WHERE 1=1
            %s
            ORDER BY TABLE_NAME
            """.formatted(schemaFilter);
    }

    @Override
    public String sequencesQuery(String schema) {
        var schemaFilter = schema != null ? "AND s.name = ?" : "";
        return """
            SELECT s.name AS name,
                   CAST(p.value AS VARCHAR(100)) AS minimum_value,
                   CAST(p.value AS VARCHAR(100)) AS maximum_value,
                   CAST(inc.increment AS VARCHAR(100)) AS increment,
                   0 AS cache_size
            FROM sys.sequences s
            JOIN sys.schemas sc ON s.schema_id = sc.schema_id
            LEFT JOIN sys.extended_properties p ON s.object_id = p.object_id AND p.name = 'minimum_value'
            CROSS APPLY (SELECT CAST(s.increment AS INT) AS increment) inc
            WHERE 1=1
              %s
            ORDER BY s.name
            """.formatted(schemaFilter);
    }

    @Override
    public Map<String, String> dataSourceProperties(DatabaseProperties properties) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("cachePrepStmts", "true");
        if (properties != null && properties.preparedStatement() != null) {
            props.put("prepStmtCacheSize", String.valueOf(properties.preparedStatement().cacheSize()));
            props.put("prepStmtCacheSqlLimit", String.valueOf(properties.preparedStatement().sqlLimit()));
        }
        return Map.copyOf(props);
    }

    @Override
    public String getTableRowCountSql(String schema, String tableName) {
        return "SELECT COUNT(*) AS row_count FROM " + qualifiedTableName(schema, tableName);
    }

    /**
     * {@code sys.dm_db_partition_stats.row_count} is maintained per partition, hence the SUM.
     *
     * <p>The schema has to take part in the join: {@code sys.tables.name} is only unique per schema,
     * so without it {@code dbo.ORDERS} and {@code staging.ORDERS} were summed into a single number
     * that belonged to neither. It is resolved here rather than bound, so the one-placeholder
     * contract holds whether or not a schema was supplied.
     */
    @Override
    public String getTableRowCountEstimateSql(String schema, String tableName) {
        return """
            SELECT SUM(ps.row_count) AS row_count
            FROM sys.dm_db_partition_stats ps
            JOIN sys.tables t ON t.object_id = ps.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE ps.index_id IN (0, 1)
              AND t.name = ?
              AND s.name = %s
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String getHealthCheckSql() {
        return "SELECT 'OK' AS status";
    }
}
