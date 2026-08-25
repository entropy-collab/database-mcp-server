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

    /**
     * Quotes an identifier, escaping any embedded double quote by doubling it so that a
     * delimiter inside {@code name} can never terminate the identifier context.
     */
    @Override
    public String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
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

    /**
     * Resolves the schema side of a metadata predicate without spending a placeholder on it.
     *
     * <p>{@code CURRENT_SCHEMA} rather than {@code IS NULL}: {@code INFORMATION_SCHEMA} never stores a
     * null {@code TABLE_SCHEMA}, so the {@code IS NULL} form this class used to emit matched no row at
     * all and every schema-less metadata lookup reported "table not found".
     */
    private String schemaExpression(String schema) {
        return DialectUtils.schemaExpression(schema, "CURRENT_SCHEMA");
    }

    /**
     * Schema predicate for the two methods whose placeholder layout is fixed by existing callers:
     * a bound {@code ?} when a schema was given (so {@code (schema, tableName)} keeps working), and
     * {@code CURRENT_SCHEMA} when it was not - never {@code IS NULL}, which matched nothing and is
     * what made backup and {@code diffSchema} report "Table not found" on H2.
     */
    private static String boundSchemaPredicate(String schema, String column) {
        return schema != null ? column + " = ?" : column + " = CURRENT_SCHEMA";
    }

    @Override
    public String columnsQuery(String table, String schema) {
        return """
            SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE %s
              AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """.formatted(boundSchemaPredicate(schema, "TABLE_SCHEMA"));
    }

    @Override
    public String indexesQuery(String table, String schema) {
        return """
            SELECT INDEX_NAME,
                   CASE WHEN IS_GENERATED = TRUE THEN 0 ELSE 1 END AS non_unique,
                   INDEX_NAME AS column_name,
                   0 AS seq_in_index
            FROM INFORMATION_SCHEMA.INDEXES
            WHERE %s
              AND TABLE_NAME = ?
            ORDER BY INDEX_NAME
            """.formatted(boundSchemaPredicate(schema, "TABLE_SCHEMA"));
    }

    // ─── Comments ────────────────────────────────────────────────────────

    @Override
    public String tableCommentsQuery(String schema) {
        return """
            SELECT TABLE_NAME, REMARKS AS TABLE_COMMENT
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_TYPE = 'BASE TABLE'
              AND TABLE_SCHEMA = %s
            ORDER BY TABLE_NAME
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String tableCommentQuery(String schema, String tableName) {
        return """
            SELECT TABLE_NAME, REMARKS AS TABLE_COMMENT
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_TYPE = 'BASE TABLE'
              AND TABLE_SCHEMA = %s
              AND TABLE_NAME = ?
            """.formatted(schemaExpression(schema));
    }

    /**
     * H2 keeps {@code COMMENT ON} text in {@code INFORMATION_SCHEMA.COLUMNS.REMARKS}. Without this
     * override the dialect reported "no comment source", which left data classification permanently
     * unavailable on H2 - the dialect the integration tests and the local profile run on.
     */
    @Override
    public String columnCommentsQuery(String schema, String tableName) {
        return """
            SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE AS NULLABLE,
                   REMARKS AS COLUMN_COMMENT
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = %s
              AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """.formatted(schemaExpression(schema));
    }

    // ─── Row count ───────────────────────────────────────────────────────

    /** H2 exposes no planner estimate, so only the exact count is offered. */
    @Override
    public String getTableRowCountSql(String schema, String tableName) {
        return "SELECT COUNT(*) AS row_count FROM " + qualifiedTableName(schema, tableName);
    }

    // ─── Foreign keys ────────────────────────────────────────────────────

    /**
     * Both ends of every foreign key, joined through {@code REFERENTIAL_CONSTRAINTS} and paired by
     * ordinal position so composite keys stay column-aligned. {@code source_*} is the referenced
     * (parent) side and {@code target_*} the referencing (child) side, as the interface requires.
     */
    private static final String FK_PROJECTION = """
            SELECT parent.TABLE_NAME  AS SOURCE_TABLE,
                   child.TABLE_NAME   AS TARGET_TABLE,
                   parent.COLUMN_NAME AS SOURCE_COLUMN,
                   child.COLUMN_NAME  AS TARGET_COLUMN
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE child
            JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
              ON rc.CONSTRAINT_NAME = child.CONSTRAINT_NAME
             AND rc.CONSTRAINT_SCHEMA = child.CONSTRAINT_SCHEMA
            JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE parent
              ON parent.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME
             AND parent.CONSTRAINT_SCHEMA = rc.UNIQUE_CONSTRAINT_SCHEMA
             AND parent.ORDINAL_POSITION = child.ORDINAL_POSITION
            WHERE 1 = 1
            """;

    /** The queried table is the child, so the placeholder constrains the referencing side. */
    @Override
    public String foreignKeyUpstreamQuery(String tableName) {
        return FK_PROJECTION + """
              AND child.TABLE_SCHEMA = CURRENT_SCHEMA
              AND child.TABLE_NAME = ?
            ORDER BY parent.TABLE_NAME, parent.COLUMN_NAME
            """;
    }

    /** The queried table is the parent, so the placeholder constrains the referenced side. */
    @Override
    public String foreignKeyDownstreamQuery(String tableName) {
        return FK_PROJECTION + """
              AND parent.TABLE_SCHEMA = CURRENT_SCHEMA
              AND parent.TABLE_NAME = ?
            ORDER BY child.TABLE_NAME, child.COLUMN_NAME
            """;
    }

    @Override
    public String foreignKeyAllEdgesQuery(String schema) {
        return FK_PROJECTION + """
              AND child.TABLE_SCHEMA = %s
            ORDER BY parent.TABLE_NAME, child.TABLE_NAME, child.COLUMN_NAME
            """.formatted(schemaExpression(schema));
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

    @Override
    public String getHealthCheckSql() {
        return "SELECT 'OK' AS status";
    }
}
