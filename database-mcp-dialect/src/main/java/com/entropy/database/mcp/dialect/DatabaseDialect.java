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

import java.util.Map;

/**
 * Per-dialect SQL vocabulary.
 *
 * <h2>Bind-parameter contract of the metadata queries</h2>
 * Callers used to derive the argument list by counting {@code ?} in the returned SQL, because each
 * dialect named the table a different number of times. That guess cannot distinguish "the table
 * name twice" from "the table name and then the schema", so it bound the wrong value as soon as two
 * different parameters were involved. The count is therefore fixed per method, and every
 * single-table metadata query declares <strong>exactly one {@code ?}, bound with the
 * dialect-normalized table name</strong>:
 *
 * <ul>
 *   <li>{@link #columnsQuery(String, String)} - 1 placeholder: {@code (tableName)}</li>
 *   <li>{@link #indexesQuery(String, String)} - 1 placeholder: {@code (tableName)}</li>
 *   <li>{@link #columnCommentsQuery(String, String)} - 1 placeholder: {@code (tableName)}</li>
 *   <li>{@link #candidateColumnsForIndexSql(String)} - 1 placeholder: {@code (tableName)}</li>
 *   <li>{@link #listTableIndexesSql(String)} - 1 placeholder: {@code (tableName)}</li>
 *   <li>{@link #estimateTableSizeSql(String, String)} - 1 placeholder: {@code (tableName)}</li>
 *   <li>{@link #foreignKeyUpstreamQuery(String)} - 1 placeholder: {@code (tableName)}</li>
 *   <li>{@link #foreignKeyDownstreamQuery(String)} - 1 placeholder: {@code (tableName)}</li>
 *   <li>{@link #getTableRowCountEstimateSql(String, String)} - 1 placeholder: {@code (tableName)}</li>
 *   <li>{@link #tableCommentQuery(String, String)} - 1 placeholder: {@code (tableName)}</li>
 * </ul>
 *
 * These carry <strong>no placeholder at all</strong>, because they name no single table: either the
 * table appears as an identifier in a {@code FROM} clause, where SQL does not allow a bind parameter
 * (implementations must then run it through {@link #qualifiedTableName(String, String)}), or the
 * query is scoped to a whole schema and the schema is never bound:
 *
 * <ul>
 *   <li>{@link #tablesQuery(String)} - 0 placeholders</li>
 *   <li>{@link #getTableRowCountSql(String, String)} - 0 placeholders</li>
 *   <li>{@link #tableCommentsQuery()} / {@link #tableCommentsQuery(String)} - 0 placeholders</li>
 *   <li>{@link #foreignKeyAllEdgesQuery(String)} - 0 placeholders</li>
 * </ul>
 *
 * <p>A {@code schema} argument is never a placeholder: the dialect resolves it internally, falling
 * back to the session's current schema when it is {@code null}. Generating an {@code IS NULL}
 * comparison instead is what made H2 metadata lookups match nothing, since
 * {@code INFORMATION_SCHEMA.COLUMNS.TABLE_SCHEMA} is never null; making it a conditional {@code ?}
 * is what forced callers to guess an argument list from the SQL text, which they got wrong in both
 * directions - the catalog scan bound a schema to Oracle's schema-less {@code tablesQuery}, and the
 * quality checks bound nothing to a {@code columnsQuery} that always names the table. Non-null
 * schema names are validated with {@link DialectUtils#isPlainIdentifier(String)} before being
 * rendered, so they cannot carry SQL.
 */
public interface DatabaseDialect {
    String quote(String name);

    /**
     * SQL listing the base tables of one schema.
     *
     * <p>Contract: no {@code ?} placeholder; the schema is resolved inside the dialect, the current
     * one when {@code null}. Result columns: {@code table_name}, {@code row_count}.
     */
    String tablesQuery(String schema);

    /**
     * SQL describing the columns of one table.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name; the schema is
     * resolved inside the dialect, the current one when {@code null}. Result columns:
     * {@code column_name}, {@code data_type}, {@code is_nullable}.
     */
    String columnsQuery(String table, String schema);

    /**
     * SQL describing the indexes of one table.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name; the schema is
     * resolved inside the dialect, the current one when {@code null}. Result columns:
     * {@code index_name} plus whatever of {@code non_unique} / {@code uniqueness},
     * {@code column_name} and {@code seq_in_index} the catalog exposes.
     */
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
     * SQL that counts the rows of {@code tableName} <em>exactly</em>.
     *
     * <p>Contract: <strong>no {@code ?} placeholder</strong> - the table name is an identifier in a
     * {@code FROM} clause, which cannot be bound, so implementations quote it into the SQL with
     * {@link #qualifiedTableName(String, String)}. The single result column holds the count.
     *
     * <p>Exact by design: the callers use the value for "is this table big?" decisions, and an
     * optimizer estimate is {@code NULL} until statistics have been gathered (Oracle
     * {@code all_tables.num_rows}) or off by a wide margin (InnoDB {@code table_rows}), which would
     * make the warning fire at random. Use {@link #getTableRowCountEstimateSql(String, String)} when
     * the caller prefers a cheap answer over an accurate one.
     *
     * @param schema the schema to qualify the table with, or {@code null} for the current one
     * @return the SQL, or {@code null} when the dialect cannot count rows
     */
    default String getTableRowCountSql(String schema, String tableName) {
        return null;
    }

    /** {@link #getTableRowCountSql(String, String)} against the current schema. */
    default String getTableRowCountSql(String tableName) {
        return getTableRowCountSql(null, tableName);
    }

    /**
     * SQL that reads the optimizer's <em>estimated</em> row count of {@code tableName} from the
     * catalog, avoiding a full scan of a large table.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name; the schema is
     * resolved inside the dialect. The single result column holds the estimate, which may be
     * {@code NULL} or negative when statistics have never been gathered - callers must then fall back
     * to {@link #getTableRowCountSql(String, String)}.
     *
     * @return the SQL, or {@code null} when the dialect exposes no estimate
     */
    default String getTableRowCountEstimateSql(String schema, String tableName) {
        return null;
    }

    /** {@link #getTableRowCountEstimateSql(String, String)} against the current schema. */
    default String getTableRowCountEstimateSql(String tableName) {
        return getTableRowCountEstimateSql(null, tableName);
    }

    /** Quotes {@code schema.table}, omitting the schema when it is absent. */
    default String qualifiedTableName(String schema, String tableName) {
        return schema == null || schema.isBlank()
                ? quote(tableName)
                : quote(schema) + "." + quote(tableName);
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
     * SQL that reports the on-disk size of one table.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name. The
     * {@code schema} is resolved inside the dialect - the current schema when {@code null} - so the
     * placeholder count does not depend on it. Result columns: {@code segment_name},
     * {@code segment_type}, {@code size_mb}, {@code extents}, with {@code size_mb} first among the
     * numeric ones the callers read.
     *
     * <p>No deviation: a dialect with no size source returns {@code null} rather than a constant
     * zero-sized row. {@link GenericDialect} used to fabricate one, which is indistinguishable from a
     * genuinely empty table and was the only reason callers had to inspect the SQL for a {@code ?}
     * before binding.
     *
     * @return the SQL, or {@code null} when the dialect exposes no size information
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
     * SQL for the foreign keys that make {@code tableName} the <em>parent</em>: the queried table is
     * the referenced side, and the rows describe the child tables that point at it - its
     * <strong>downstream</strong>.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name, and the
     * {@code WHERE} clause must constrain the <em>referenced</em> (parent) side with it.
     *
     * <p>Result columns, with a fixed meaning that does not depend on the direction:
     * {@code source_table} / {@code source_column} are always the <strong>parent</strong> (referenced)
     * side, {@code target_table} / {@code target_column} always the <strong>child</strong>
     * (referencing) side. An edge therefore always reads "source feeds target", so this query returns
     * {@code queried -> child} and {@link #foreignKeyUpstreamQuery(String)} returns
     * {@code parent -> queried}. Orienting the two queries the other way round is what made MySQL
     * report a table's children as its ancestors.
     *
     * @return the SQL, or {@code null} when the dialect cannot report foreign keys
     */
    default String foreignKeyDownstreamQuery(String tableName) {
        return null;
    }

    /**
     * SQL for the foreign keys that make {@code tableName} the <em>child</em>: the queried table is
     * the referencing side, and the rows describe the parent tables it points at - its
     * <strong>upstream</strong>.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name, and the
     * {@code WHERE} clause must constrain the <em>referencing</em> (child) side with it.
     *
     * <p>Result columns as in {@link #foreignKeyDownstreamQuery(String)}: {@code source_*} is the
     * parent, {@code target_*} the child, so this query yields {@code parent -> queried}.
     *
     * @return the SQL, or {@code null} when the dialect cannot report foreign keys
     */
    default String foreignKeyUpstreamQuery(String tableName) {
        return null;
    }

    /**
     * SQL for <em>every</em> foreign key of one schema in a single round trip.
     *
     * <p>Contract: no {@code ?} placeholder - the schema is resolved inside the dialect, the current
     * one when {@code null}. Result columns are the same as
     * {@link #foreignKeyDownstreamQuery(String)}: {@code source_*} is the parent, {@code target_*}
     * the child.
     *
     * <p>Exists so that a full-graph export does not need one query per table; a thousand-table
     * schema cost a thousand round trips. Returning {@code null} is a valid answer and makes the
     * caller fall back to the per-table queries.
     */
    default String foreignKeyAllEdgesQuery(String schema) {
        return null;
    }

    /**
     * SQL to list existing indexes for a table.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name.
     * Result columns: {@code index_name}, {@code column_name}, {@code uniqueness}.
     *
     * @return the SQL, or {@code null} when not supported
     */
    default String listTableIndexesSql(String tableName) {
        return null;
    }

    /**
     * SQL to identify candidate columns for missing indexes based on table statistics and constraints.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name.
     * The only result column every dialect provides is {@code column_name}; a dialect adds whatever
     * of {@code is_nullable} / {@code nullable} and the cardinality hint {@code num_distinct} its
     * catalog exposes, and callers must treat those as optional.
     *
     * @return the SQL, or {@code null} when not supported
     */
    default String candidateColumnsForIndexSql(String tableName) {
        return null;
    }

    /**
     * SQL for the table comments of the current schema.
     *
     * <p>Contract: no {@code ?} placeholder. Result columns: {@code table_name},
     * {@code table_comment}.
     *
     * @return the SQL, or {@code null} when the dialect stores no table comments
     */
    default String tableCommentsQuery() {
        return tableCommentsQuery(null);
    }

    /**
     * SQL for the table comments of one schema.
     *
     * <p>Contract: no {@code ?} placeholder; the schema is resolved inside the dialect, the current
     * one when {@code null}. Result columns as in {@link #tableCommentsQuery()}.
     *
     * @return the SQL, or {@code null} when the dialect stores no table comments
     */
    default String tableCommentsQuery(String schema) {
        return null;
    }

    /**
     * SQL for the comment of a <em>single</em> table.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name; the schema is
     * resolved inside the dialect. Result columns as in {@link #tableCommentsQuery()}.
     *
     * <p>Exists so that cataloguing one table does not have to read the comments of the whole
     * database and throw all but one row away.
     *
     * @return the SQL, or {@code null} when the dialect stores no table comments
     */
    default String tableCommentQuery(String schema, String tableName) {
        return null;
    }

    /**
     * SQL for the column comments of one table.
     *
     * <p>Contract: exactly one {@code ?}, bound with the dialect-normalized table name; the schema is
     * resolved inside the dialect, the current one when {@code null}. Result columns:
     * {@code column_name}, {@code data_type}, {@code nullable}, {@code column_comment}.
     *
     * @return the SQL, or {@code null} when the dialect stores no column comments
     */
    default String columnCommentsQuery(String schema, String tableName) {
        return null;
    }

    /** {@link #columnCommentsQuery(String, String)} against the current schema. */
    default String columnCommentsQuery(String tableName) {
        return columnCommentsQuery(null, tableName);
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
     * Driver-level datasource properties this dialect wants set on the pool, e.g.
     * {@code oracle.jdbc.ReadTimeout} or {@code cachePrepStmts}. Returns an empty map by default.
     *
     * <p>The caller decides how to apply them — {@code ByokDataSourceFactory} feeds each entry to
     * {@code HikariConfig.addDataSourceProperty}. <strong>Returning values instead of taking a
     * {@code HikariConfig} is deliberate:</strong> this interface is the third-party extension point,
     * and it used to be {@code configureDataSource(HikariConfig, DatabaseProperties)}, which forced
     * every dialect — and this whole module — to compile against HikariCP for the sake of four
     * implementations that only ever called {@code addDataSourceProperty}. A dialect describes a
     * database, not the pool in front of it.
     *
     * @param properties may be null; implementations must tolerate that
     */
    default Map<String, String> dataSourceProperties(DatabaseProperties properties) {
        return Map.of();
    }

    // ─── CDC (Change Data Capture) ───────────────────────────────────────

    /**
     * SQL to read change events from the database's CDC infrastructure.
     * Oracle: Flashback Version Query (VERSIONS BETWEEN SCN); MySQL/PostgreSQL: trigger audit table.
     * Returns rows with columns: change_type, change_time, primary_keys, before_json, after_json, transaction_id.
     * Returns null if CDC is not supported for this dialect.
     *
     * <p>The returned SQL must contain exactly one {@code ?} placeholder, bound with
     * {@link #cdcLsnParameter(long)}. Values in {@code change_type} must be codes that
     * {@code CdcChangeType.fromCode} recognizes.
     *
     * <p>单位契约：该谓词消费的必须正是 {@link #cdcLsnParameter(long)} 产出的单位，而
     * {@code cdcLsnParameter} 的入参又必须是 {@link #parseLsn(java.util.Map)} 从
     * {@link #cdcGetLastLsnSql()} 的结果里解析出的值。三者只要有一处单位不一致（例如 watermark 是
     * binlog 坐标、谓词却按 Unix 秒解释），谓词就会恒不成立，readChanges 恒返回 0 行且与「真的没有
     * 变更」不可区分——占位符个数正确并不能保证这一点。
     */
    default String cdcReadChangesSql(String schema, String table, long fromLsn) {
        return null;
    }

    /**
     * SQL to get the current LSN / SCN / binlog position for a table.
     * Returns null if not supported.
     *
     * <p>结果行交给 {@link #parseLsn(java.util.Map)} 归一化，得到的数值单位必须与
     * {@link #cdcReadChangesSql(String, String, long)} 的谓词一致。
     */
    default String cdcGetLastLsnSql() {
        return null;
    }

    /**
     * SQL to detect whether the database supports the change-reading mechanism this dialect
     * actually implements. Returns null when the dialect implements no CDC read path.
     *
     * <p>契约：必须返回恰好一行一列，值为 {@code 1}（支持）或 {@code 0}（不支持），因此不能用多段
     * {@code UNION ALL}——多个分支同时命中时会返回多行，调用方按「单值」读取就会抛错并把最健全的库
     * 判成不支持。判据也必须对应真实读取机制，而不是罗列同类特性。
     */
    default String cdcCheckSupportSql() {
        return null;
    }

    /**
     * SQL to create a mirror/snapshot table from source table (CREATE TABLE ... AS SELECT).
     * Returns null if not supported.
     *
     * <p>Implementations must run every identifier through {@link #quote(String)}; callers are
     * additionally expected to have whitelisted them with {@link #isValidIdentifier(String)},
     * because this SQL is executed as DDL outside the SQL-validation aspect.
     */
    default String cdcCreateMirrorTableSql(String targetSchema, String targetTable, String sourceQuery) {
        return null;
    }

    /**
     * Normalizes the dialect-specific watermark row returned by {@link #cdcGetLastLsnSql()} into a
     * single {@code long}.
     *
     * <p>Must never guess: a value that cannot be parsed has to raise an error rather than degrade
     * to a hash or to {@code 0}, both of which are indistinguishable from a genuine position and
     * make incremental reads skip or replay changes.
     *
     * @throws com.entropy.database.mcp.exception.McpValidationException when the row holds no parseable watermark
     */
    default long parseLsn(java.util.Map<String, Object> row) {
        return DialectUtils.requireNumericLsn(row, getDialectName());
    }

    /**
     * Converts a normalized watermark back into the bind value that the {@code ?} placeholder of
     * {@link #cdcReadChangesSql(String, String, long)} expects. Default: the {@code long} itself.
     */
    default Object cdcLsnParameter(long lsn) {
        return lsn;
    }
}
