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

public interface DatabaseDialect {
    String quote(String name);
    String tablesQuery(String schema);
    String columnsQuery(String table, String schema);
    String indexesQuery(String table, String schema);
    String applyLimit(String sql, int limit, int offset);
    boolean supportsLimit();
    boolean supportsSchema();

    /**
     * Rewrites a user-provided SQL that contains LIMIT/FETCH clauses into a dialect-compatible form.
     * Default: returns SQL unchanged (dialect natively supports LIMIT).
     * Oracle overrides to convert LIMIT → ROWNUM or FETCH FIRST.
     */
    default String rewriteLimitInSql(String sql) {
        return sql;
    }

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

    /**
     * Generate EXPLAIN PLAN SQL for the given query.
     * Returns null if not supported.
     */
    String getExplainPlanSql(String sql);

    /**
     * Get table row count SQL for the given table name.
     * Returns null if not supported.
     */
    default String getTableRowCountSql(String tableName) {
        return null;
    }

    /**
     * Build UPSERT SQL for the given table and columns.
     * Returns null if not supported.
     */
    default String buildUpsertSql(String tableName, java.util.List<String> allColumns, java.util.List<String> keyColumns) {
        return null;
    }

    /**
     * Get table statistics SQL for the given table name.
     * Returns null if not supported.
     */
    default String getTableStatisticsSql(String tableName) {
        return null;
    }

    /**
     * Apply pagination to the given SQL.
     * Returns null if not supported.
     */
    default String getPaginationSql(String sql, int offset, int limit) {
        return null;
    }

    /**
     * Check if this dialect supports EXPLAIN PLAN.
     */
    default boolean supportsExplainPlan() {
        return false;
    }

    /**
     * Check if this dialect supports table statistics queries.
     */
    default boolean supportsTableStatistics() {
        return false;
    }

    /**
     * Get the dialect name (e.g., "oracle", "mysql", "postgres").
     */
    String getDialectName();

    /**
     * Get the health check SQL for this dialect.
     */
    String getHealthCheckSql();

    /**
     * SQL to search tables by keyword across all schemas.
     * Returns SQL with a LIKE ? placeholder for the keyword.
     */
    default String searchTablesQuery(String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return """
                SELECT table_schema AS schema_name, table_name, 0 AS row_count
                FROM information_schema.tables
                WHERE table_type = 'BASE TABLE'
                  AND table_name LIKE ?
                ORDER BY table_schema, table_name
                """;
        }
        return """
            SELECT table_schema AS schema_name, table_name, 0 AS row_count
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
            ORDER BY table_schema, table_name
            """;
    }

    /**
     * Connection validation query for keepalive.
     */
    default String connectionTestQuery() {
        return "SELECT 1";
    }

    /**
     * SQL to get the current database user.
     */
    default String currentUserQuery() {
        return "SELECT CURRENT_USER";
    }

    /**
     * SQL to get DDL for a table using database-specific metadata functions.
     */
    default String getTableDdlQuery(String tableName, String schema) {
        return null;
    }

    /**
     * Validate database identifier (table name, column name, etc.).
     */
    default boolean isValidIdentifier(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }

    /**
     * Normalize table name for the current dialect.
     * Oracle stores identifiers in uppercase by default.
     */
    default String normalizeTableName(String table) {
        return table;
    }

    /**
     * SQL to kill a database session. Returns null if the dialect does not support session killing.
     */
    default String killSessionSql(String sessionId, String mode) {
        return null;
    }

    /**
     * SQL to list active database sessions.
     */
    default String listActiveSessionsSql() {
        return null;
    }

    /**
     * SQL to show database locks and blocking information.
     */
    default String showLocksSql() {
        return null;
    }

    /**
     * SQL to show blocking chain (who is blocking whom).
     */
    default String showBlockingTreeSql() {
        return null;
    }

    /**
     * SQL to list tablespaces and usage.
     */
    default String listTablespacesSql() {
        return null;
    }

    /**
     * SQL to list datafiles status and autoextension.
     */
    default String listDataFilesSql() {
        return null;
    }

    /**
     * SQL to estimate table size in MB. Returns SQL with ? placeholders for tableName and schema.
     */
    default String estimateTableSizeSql(String tableName, String schema) {
        return null;
    }

    /**
     * SQL to list invalid database objects. Returns SQL with ? placeholder for schema.
     */
    default String listInvalidObjectsSql(String schema) {
        return null;
    }

    /**
     * SQL to gather table statistics for optimizer. Returns SQL with ? placeholders for tableName and schema.
     */
    default String gatherTableStatsSql(String tableName, String schema) {
        return null;
    }

    /**
     * SQL to show index status and unusable indexes. Returns SQL with ? placeholders for tableName and schema.
     */
    default String showIndexStatusSql(String tableName, String schema) {
        return null;
    }

    /**
     * SQL to generate flashback query template. Returns formatted SQL with quoted tableName.
     */
    default String flashbackQuerySql(String tableName) {
        return null;
    }

    /**
     * SQL to show undo tablespace usage.
     */
    default String showUndoUsageSql() {
        return null;
    }

    /**
     * SQL to list current user privileges.
     */
    default String listCurrentPrivilegesSql() {
        return null;
    }

    /**
     * SQL to list grants for a user or role. Returns SQL with ? placeholder for userName.
     */
    default String listGrantsSql(String userName) {
        return null;
    }

    /**
     * SQL to get foreign key constraints where the given table is the referenced (parent) table.
     * Returns rows with columns: source_table, target_table, source_column, target_column.
     * Returns null if not supported.
     */
    default String foreignKeyDownstreamQuery(String tableName) {
        return null;
    }

    /**
     * SQL to get foreign key constraints where the given table is the referencing (child) table.
     * Returns rows with columns: source_table, target_table, source_column, target_column.
     * Returns null if not supported.
     */
    default String foreignKeyUpstreamQuery(String tableName) {
        return null;
    }

    /**
     * SQL to list existing indexes for a table.
     * Returns rows with columns: index_name, column_name, uniqueness.
     * Returns null if not supported.
     */
    default String listTableIndexesSql(String tableName) {
        return null;
    }

    /**
     * SQL to identify candidate columns for missing indexes based on table statistics and constraints.
     * Returns rows with columns: column_name, is_nullable, distinct_count.
     * Returns null if not supported.
     */
    default String candidateColumnsForIndexSql(String tableName) {
        return null;
    }

    /**
     * SQL to get table-level comments/descriptions.
     * Returns rows with columns: table_name, table_comment.
     * Returns null if not supported.
     */
    default String tableCommentsQuery() {
        return null;
    }

    /**
     * SQL to get column-level comments/descriptions for a table.
     * Returns rows with columns: column_name, data_type, nullable, column_comment.
     * Returns null if not supported.
     */
    default String columnCommentsQuery(String tableName) {
        return null;
    }

    /**
     * SQL to search tables and their comments by keyword (full-text asset search).
     * Returns rows with columns: table_schema, table_name, table_comment, row_count.
     * Returns null if not supported.
     */
    default String searchTableCommentsQuery(String keyword) {
        return null;
    }

    /**
     * Configure dialect-specific HikariCP datasource properties.
     * Called after common pool settings are applied.
     */
    default void configureDataSource(HikariConfig config, DatabaseProperties properties) {
        // No-op by default
    }

    // ─── CDC (Change Data Capture) ───────────────────────────────────────

    /**
     * SQL to read change events from the database's CDC infrastructure.
     * Oracle: uses LOGMNRC or AUDIT_TRAIL; MySQL: uses binlog position; PostgreSQL: uses WAL/LSN.
     * Returns rows with columns: change_type, change_time, primary_keys, before_json, after_json, transaction_id.
     * Returns null if CDC is not supported for this dialect.
     */
    default String cdcReadChangesSql(String schema, String table, long fromLsn) {
        return null;
    }

    /**
     * SQL to get the current LSN / SCN / binlog position for a table.
     * Returns null if not supported.
     */
    default String cdcGetLastLsnSql() {
        return null;
    }

    /**
     * SQL to detect whether the database supports native CDC (e.g., Oracle Flashback, MySQL binlog, PG pgoutput).
     * Returns a query that yields a row if CDC-capable, empty otherwise.
     */
    default String cdcCheckSupportSql() {
        return null;
    }

    /**
     * SQL to create a mirror/snapshot table from source table (CREATE TABLE ... AS SELECT).
     * Returns null if not supported.
     */
    default String cdcCreateMirrorTableSql(String targetSchema, String targetTable, String sourceQuery) {
        return null;
    }
}
