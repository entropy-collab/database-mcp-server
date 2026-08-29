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

import java.util.List;
import java.util.Map;

/**
 * Oracle-specific dialect with EXPLAIN PLAN support.
 */
public class OracleDialect extends AbstractDatabaseDialect {

    /**
     * Quotes an identifier, escaping any embedded double quote by doubling it so that a
     * delimiter inside {@code name} can never terminate the identifier context.
     * Oracle folds unquoted identifiers to upper case, so the name is upper-cased first.
     */
    @Override
    public String quote(String name) {
        return "\"" + name.toUpperCase().replace("\"", "\"\"") + "\"";
    }

    /** Oracle folds identifiers to upper case, so a requested schema is the owner in upper case. */
    private String ownerExpression(String schema) {
        return DialectUtils.schemaExpression(
                schema == null ? null : schema.toUpperCase(), "USER");
    }

    @Override
    public String tableCommentsQuery(String schema) {
        return """
            SELECT table_name, comments AS table_comment
            FROM all_tab_comments
            WHERE table_type = 'TABLE'
              AND owner = %s
            ORDER BY table_name
            """.formatted(ownerExpression(schema));
    }

    @Override
    public String tableCommentQuery(String schema, String tableName) {
        return """
            SELECT table_name, comments AS table_comment
            FROM all_tab_comments
            WHERE table_type = 'TABLE'
              AND owner = %s
              AND table_name = ?
            """.formatted(ownerExpression(schema));
    }

    /**
     * {@code all_col_comments} carries only owner/table/column/comments, so the data type and the
     * nullability have to come from {@code all_tab_columns}; selecting them straight off the comment
     * view failed with ORA-00904 and, because the caller treats a failed lookup as "no columns",
     * silently disabled data classification on Oracle altogether.
     */
    @Override
    public String columnCommentsQuery(String schema, String tableName) {
        return """
            SELECT c.column_name,
                   c.data_type,
                   CASE WHEN c.nullable = 'N' THEN 0 ELSE 1 END AS nullable,
                   cc.comments AS column_comment
            FROM all_tab_columns c
            LEFT JOIN all_col_comments cc
              ON cc.owner = c.owner
             AND cc.table_name = c.table_name
             AND cc.column_name = c.column_name
            WHERE c.owner = %s
              AND c.table_name = ?
            ORDER BY c.column_id
            """.formatted(ownerExpression(schema));
    }

    /**
     * Exact count. {@code all_tables.num_rows} is {@code NULL} until {@code DBMS_STATS} has run and
     * stale afterwards, which is why it is offered separately as
     * {@link #getTableRowCountEstimateSql(String, String)} instead of being the default.
     */
    @Override
    public String getTableRowCountSql(String schema, String tableName) {
        return "SELECT COUNT(*) AS row_count FROM " + qualifiedTableName(schema, tableName);
    }

    @Override
    public String getTableRowCountEstimateSql(String schema, String tableName) {
        return """
            SELECT num_rows AS row_count
            FROM all_tables
            WHERE owner = %s
              AND table_name = ?
            """.formatted(ownerExpression(schema));
    }

    @Override
    public String searchTableCommentsQuery(String keyword) {
        String kw = "%" + keyword.toUpperCase() + "%";
        return """
            SELECT table_name, comments AS table_comment, num_rows AS row_count
            FROM user_tables
            WHERE UPPER(table_name) LIKE ?
               OR UPPER(comments) LIKE ?
            ORDER BY num_rows DESC NULLS LAST
            """;
    }

    @Override
    public String tablesQuery(String schema) {
        return """
            SELECT table_name, num_rows AS row_count
            FROM all_tables
            WHERE owner = %s
            ORDER BY table_name
            """.formatted(ownerExpression(schema));
    }

    @Override
    public String columnsQuery(String table, String schema) {
        return """
            SELECT column_name, data_type, data_length, nullable
            FROM all_tab_columns
            WHERE owner = %s
              AND table_name = ?
            ORDER BY column_id
            """.formatted(ownerExpression(schema));
    }

    @Override
    public String indexesQuery(String table, String schema) {
        return """
            SELECT i.index_name,
                   i.uniqueness,
                   ic.column_name,
                   ic.column_position
            FROM all_indexes i
            JOIN all_ind_columns ic
              ON i.index_name = ic.index_name
              AND i.owner = ic.index_owner
            WHERE i.table_owner = %s
              AND i.table_name = ?
            ORDER BY i.index_name, ic.column_position
            """.formatted(ownerExpression(schema));
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
     * Rewrite user SQL containing LIMIT/FETCH FIRST into Oracle-compatible ROWNUM syntax.
     */
    @Override
    public String rewriteLimitInSql(String sql) {
        if (sql == null) return null;
        String s = sql.trim();
        // Handle "FETCH FIRST N ROWS ONLY"
        java.util.regex.Pattern fetchPattern = java.util.regex.Pattern.compile(
                "\\bFETCH\\s+FIRST\\s+(\\d+)\\s+ROWS\\s+ONLY\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = fetchPattern.matcher(s);
        if (m.find()) {
            int limit = Integer.parseInt(m.group(1));
            s = m.replaceFirst("");
            return applyLimit(s, limit, 0);
        }
        // Handle plain "LIMIT N" or "LIMIT N OFFSET M"
        java.util.regex.Pattern limitPattern = java.util.regex.Pattern.compile(
                "\\bLIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+(\\d+))?", java.util.regex.Pattern.CASE_INSENSITIVE);
        m = limitPattern.matcher(s);
        if (m.find()) {
            int limit = Integer.parseInt(m.group(1));
            int offset = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
            s = m.replaceFirst("");
            return applyLimit(s, limit, offset);
        }
        return sql;
    }

    /**
     * Apply ROWNUM-based cursor pagination for stable deep-page queries.
     */
    public String applyRowidPagination(String sql, int limit, String lastRowid) {
        if (lastRowid == null || lastRowid.isBlank()) {
            return "SELECT * FROM (" + sql + ") WHERE ROWNUM <= " + limit;
        }
        long offset;
        try { offset = Long.parseLong(lastRowid); }
        catch (NumberFormatException e) { return "SELECT * FROM (" + sql + ") WHERE ROWNUM <= " + limit; }
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
    @Override
    public String getExplainPlanSql(String sql) {
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

    @Override
    public String searchTablesQuery(String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return """
                SELECT owner AS schema_name, table_name, num_rows AS row_count
                FROM all_tables
                WHERE UPPER(table_name) LIKE UPPER(?)
                ORDER BY owner, table_name
                """;
        }
        return """
            SELECT owner AS schema_name, table_name, num_rows AS row_count
            FROM all_tables
            ORDER BY owner, table_name
            """;
    }

    @Override
    public String connectionTestQuery() {
        return "SELECT 1 FROM DUAL";
    }

    @Override
    public String getHealthCheckSql() {
        return "SELECT 'OK' AS status FROM DUAL";
    }

    /**
     * SQL to get the current database user.
     */
    public String currentUserQuery() {
        return "SELECT USER FROM DUAL";
    }

    /**
     * SQL to get DDL for a table using DBMS_METADATA.
     */
    public String getTableDdlQuery(String tableName, String schema) {
        return """
            SELECT DBMS_METADATA.GET_DDL('TABLE', :table, :schema) AS ddl
            FROM DUAL
            """;
    }

    /**
     * Validate Oracle identifier (table name, column name, etc.).
     */
    public boolean isValidIdentifier(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.matches("[A-Za-z][A-Za-z0-9_$#]*");
    }

    @Override
    public String normalizeTableName(String table) {
        return table.toUpperCase();
    }

    @Override
    public String killSessionSql(String sessionId, String mode) {
        return "ALTER SYSTEM KILL SESSION '" + sessionId + "' " + mode;
    }

    @Override
    public String listActiveSessionsSql() {
        return """
                SELECT s.sid, s.serial#, s.username, s.status, s.machine, s.program,
                       s.logon_time, s.last_call_et, s.event, s.wait_class, s.sql_id
                FROM v$session s
                WHERE s.type = 'USER'
                ORDER BY s.sid
                """;
    }

    @Override
    public String showLocksSql() {
        return """
                SELECT l.sid, l.serial#, l.type, l.id1, l.id2, l.lmode, l.request,
                       l.ctime, s.username, s.status, s.event
                FROM v$lock l
                JOIN v$session s ON l.sid = s.sid
                WHERE s.type = 'USER'
                ORDER BY l.sid
                """;
    }

    @Override
    public String showBlockingTreeSql() {
        return """
                SELECT s.sid AS waiter_sid, s.serial# AS waiter_serial,
                       s.username AS waiter_user, s.event AS waiter_event,
                       b.sid AS blocker_sid, b.serial# AS blocker_serial,
                       b.username AS blocker_user, b.event AS blocker_event
                FROM v$session s
                JOIN v$lock l1 ON s.sid = l1.sid
                JOIN v$lock l2 ON l1.id1 = l2.id1 AND l1.id2 = l2.id2
                JOIN v$session b ON l2.sid = b.sid
                WHERE s.type = 'USER'
                  AND b.type = 'USER'
                  AND l1.request > 0
                  AND l2.lmode > 0
                """;
    }

    @Override
    public String listTablespacesSql() {
        return """
                SELECT tablespace_name, contents, extent_management, status,
                       round(bytes / 1024 / 1024, 2) AS size_mb,
                       round((bytes - NVL(free_space, 0)) / 1024 / 1024, 2) AS used_mb
                FROM dba_tablespaces
                ORDER BY tablespace_name
                """;
    }

    @Override
    public String listDataFilesSql() {
        return """
                SELECT file_name, tablespace_name, bytes, blocks, status,
                       autoextensible, maxbytes, increment_by, round(maxbytes / 1024 / 1024, 2) AS max_mb
                FROM dba_data_files
                ORDER BY tablespace_name, file_name
                """;
    }

    /**
     * One placeholder (the segment/table name); the owner is resolved here rather than bound, so the
     * argument list no longer depends on whether a schema was supplied. Without the owner predicate
     * a same-named segment in another schema was summed in as well.
     */
    @Override
    public String estimateTableSizeSql(String tableName, String schema) {
        return """
                SELECT segment_name, segment_type,
                       round(sum(bytes) / 1024 / 1024, 2) AS size_mb,
                       count(*) AS extents
                FROM dba_segments
                WHERE segment_name = ?
                  AND owner = %s
                  AND segment_type IN ('TABLE', 'TABLE PARTITION')
                GROUP BY segment_name, segment_type
                """.formatted(ownerExpression(schema));
    }

    @Override
    public String listInvalidObjectsSql(String schema) {
        return """
                SELECT owner, object_name, object_type, status
                FROM dba_objects
                WHERE status = 'INVALID'
                  AND owner = COALESCE(?, USER)
                ORDER BY owner, object_type, object_name
                """;
    }

    @Override
    public String gatherTableStatsSql(String tableName, String schema) {
        // DBMS_STATS takes the owner and table as string literals, so both identifiers are inlined
        // rather than bound. Callers whitelist them with ValidationUtils.validateIdentifier first.
        String owner = (schema == null || schema.isBlank()) ? "USER" : "'" + schema.toUpperCase() + "'";
        return """
                BEGIN
                    DBMS_STATS.GATHER_TABLE_STATS(
                        ownname => %s,
                        tabname => '%s',
                        estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,
                        method_opt => 'FOR ALL COLUMNS SIZE AUTO',
                        cascade => TRUE
                    );
                END;
                """.formatted(owner, tableName.toUpperCase());
    }

    @Override
    public String showIndexStatusSql(String tableName, String schema) {
        return """
                SELECT i.owner, i.table_name, i.index_name, i.status, i.uniqueness,
                       i.last_analyzed, i.num_rows, i.distinct_keys
                FROM dba_indexes i
                WHERE i.owner = COALESCE(?, USER)
                  AND i.table_name = COALESCE(?, i.table_name)
                ORDER BY i.owner, i.table_name, i.index_name
                """;
    }

    @Override
    public String flashbackQuerySql(String tableName) {
        return "SELECT 'SELECT * FROM %s AS OF TIMESTAMP TO_TIMESTAMP(?, ''YYYY-MM-DD HH24:MI:SS'')' AS sql_template FROM dual"
                .formatted(quote(tableName));
    }

    @Override
    public String showUndoUsageSql() {
        return """
                SELECT tablespace_name, round(sum(bytes) / 1024 / 1024, 2) AS size_mb,
                       round(sum(bytes) / 1024 / 1024, 2) - round(sum(nvl(free_space, 0)) / 1024 / 1024, 2) AS used_mb,
                       round(sum(nvl(free_space, 0)) / 1024 / 1024, 2) AS free_mb,
                       round((sum(bytes) - sum(nvl(free_space, 0))) / sum(bytes) * 100, 2) AS used_pct
                FROM dba_undo_extents
                GROUP BY tablespace_name
                ORDER BY tablespace_name
                """;
    }

    @Override
    public String listCurrentPrivilegesSql() {
        return """
                SELECT privilege, admin_option, grantable
                FROM user_sys_privs
                UNION ALL
                SELECT privilege, admin_option, grantable
                FROM user_tab_privs
                ORDER BY privilege
                """;
    }

    @Override
    public String listGrantsSql(String userName) {
        return """
                SELECT grantee, privilege, admin_option, grantable
                FROM dba_tab_privs
                WHERE grantee = ?
                UNION ALL
                SELECT grantee, privilege, admin_option, grantable
                FROM dba_sys_privs
                WHERE grantee = ?
                ORDER BY grantee, privilege
                """;
    }

    @Override
    public Map<String, String> dataSourceProperties(DatabaseProperties properties) {
        return Map.of(
                "oracle.jdbc.ReadTimeout", "30000",
                "oracle.net.CONNECT_TIMEOUT", "10000");
    }

    public String buildUpsertSql(String tableName, List<String> allColumns, List<String> keyColumns) {
        String columnList = String.join(", ", allColumns);
        String keyCondition = keyColumns.stream()
                .map(k -> "target." + k + " = source." + k)
                .reduce((a, b) -> a + " AND " + b).orElse("1=1");
        List<String> nonKeyColumns = allColumns.stream().filter(col -> !keyColumns.contains(col)).toList();
        String updateSet = nonKeyColumns.stream()
                .map(col -> "target." + col + " = source." + col)
                .reduce((a, b) -> a + ", " + b).orElse("");
        // Oracle MERGE takes its incoming row from a subquery, so the bind parameters belong in
        // that SELECT — one per column, aliased to the column name. The INSERT branch then reads
        // them back as source.<col> rather than binding a second time. Emitting bare column names
        // here (`SELECT COL AS COL FROM DUAL`) fails with ORA-00904, since DUAL has no such
        // column; the placeholder count happened to match, so the breakage only showed at runtime.
        String selectFromDual = allColumns.stream()
                .map(c -> "? AS " + c)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String insertValues = allColumns.stream()
                .map(c -> "source." + c)
                .reduce((a, b) -> a + ", " + b).orElse("");
        return String.format(
                "MERGE INTO %s target USING (SELECT %s FROM DUAL) source ON (%s) WHEN MATCHED THEN UPDATE SET %s WHEN NOT MATCHED THEN INSERT (%s) VALUES (%s)",
                tableName, selectFromDual, keyCondition, updateSet, columnList, insertValues);
    }

    /** Shared parent/child join over the two constraint views; {@code source_*} is always the parent. */
    private static final String FK_PROJECTION = """
            SELECT uc_pk.table_name AS source_table,
                   uc_fk.table_name AS target_table,
                   c_pk.column_name AS source_column,
                   c_fk.column_name AS target_column
            FROM all_constraints uc_fk
            JOIN all_constraints uc_pk
              ON uc_fk.r_constraint_name = uc_pk.constraint_name
             AND uc_fk.r_owner = uc_pk.owner
             AND uc_pk.constraint_type IN ('P', 'U')
            JOIN all_cons_columns c_fk
              ON c_fk.constraint_name = uc_fk.constraint_name
             AND c_fk.owner = uc_fk.owner
            JOIN all_cons_columns c_pk
              ON c_pk.constraint_name = uc_pk.constraint_name
             AND c_pk.owner = uc_pk.owner
             AND c_pk.position = c_fk.position
            WHERE uc_fk.constraint_type = 'R'
            """;

    /** The queried table is the parent, so the placeholder constrains the referenced side. */
    @Override
    public String foreignKeyDownstreamQuery(String tableName) {
        return FK_PROJECTION + """
              AND uc_pk.owner = USER
              AND uc_pk.table_name = ?
            ORDER BY uc_fk.table_name, c_fk.column_name
            """;
    }

    /** The queried table is the child, so the placeholder constrains the referencing side. */
    @Override
    public String foreignKeyUpstreamQuery(String tableName) {
        return FK_PROJECTION + """
              AND uc_fk.owner = USER
              AND uc_fk.table_name = ?
            ORDER BY uc_pk.table_name, c_pk.column_name
            """;
    }

    @Override
    public String foreignKeyAllEdgesQuery(String schema) {
        return FK_PROJECTION + """
              AND uc_fk.owner = %s
            ORDER BY uc_pk.table_name, uc_fk.table_name, c_fk.column_name
            """.formatted(ownerExpression(schema));
    }

    @Override
    public String listTableIndexesSql(String tableName) {
        return """
            SELECT i.index_name, ic.column_name,
                   CASE WHEN i.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS uniqueness
            FROM user_indexes i
            JOIN user_ind_columns ic ON ic.index_name = i.index_name AND ic.table_name = i.table_name
            WHERE i.table_name = ?
            ORDER BY i.index_name, ic.column_position
            """;
    }

    /**
     * One placeholder: the second occurrence of the table name in the {@code NOT IN} subquery is
     * replaced by a correlated {@code NOT EXISTS} on the outer row, so the argument list matches the
     * contract without repeating the value.
     */
    @Override
    public String candidateColumnsForIndexSql(String tableName) {
        return """
            SELECT c.column_name, c.nullable, c.num_distinct
            FROM user_tab_columns c
            WHERE c.table_name = ?
              AND c.num_distinct IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_ind_columns ic
                  WHERE ic.table_name = c.table_name
                    AND ic.column_name = c.column_name
              )
            ORDER BY c.num_distinct ASC
            """;
    }

    // ─── CDC ─────────────────────────────────────────────────────────────

    @Override
    public String cdcReadChangesSql(String schema, String table, long fromLsn) {
        // Oracle Flashback Version Query: the VERSIONS_OPERATION pseudo-column reports the real
        // DML operation of every row version in the SCN range as 'I' / 'U' / 'D', which is exactly
        // the CdcChangeType code set. Rows whose version predates the range have a NULL
        // VERSIONS_OPERATION (they were not changed), so they are filtered out instead of being
        // reported as an untyped "FLASHBACK" event.
        // VERSIONS_XID is a RAW transaction id and does not fit the numeric transaction_id contract,
        // so it is not projected.
        return """
            SELECT t.VERSIONS_OPERATION AS change_type,
                   t.VERSIONS_STARTTIME AS change_time,
                   ROWIDTOCHAR(t.ROWID) AS primary_keys,
                   NULL AS before_json,
                   NULL AS after_json,
                   NULL AS transaction_id
            FROM %s VERSIONS BETWEEN SCN ? AND MAXVALUE t
            WHERE t.VERSIONS_OPERATION IS NOT NULL
            ORDER BY t.VERSIONS_STARTTIME
            """.formatted(qualifiedName(schema, table));
    }

    @Override
    public String cdcGetLastLsnSql() {
        // Oracle uses SCN (System Change Number) as its LSN equivalent
        return "SELECT CURRENT_SCN FROM v$database";
    }

    @Override
    public String cdcCheckSupportSql() {
        // 判据对应真实读取机制：readChanges 走 Flashback Version Query，它依赖的是 undo 保留期
        // （undo_retention）与目标表上的 SELECT 权限，而 recyclebin（回收站，只影响 DROP 的对象）和
        // v$flashback_database_log（整库闪回日志，只有 FLASHBACK DATABASE 才需要）都不是必要条件，
        // 按它们判断会把「闪回版本查询完全可用」的库判成不支持。
        // 表级 SELECT 权限是 per-table 的，只能在 readChanges 时暴露，这里只能覆盖实例级条件。
        // 单行单值：原先两段 UNION ALL 在两个分支都命中时返回 2 行，queryForObject 抛
        // IncorrectResultSizeDataAccessException 后被 isCdcSupported 吞成「不支持」。聚合函数不带
        // GROUP BY 保证恰好一行，即使 undo_retention 这条参数记录不存在也是一行 0。
        return """
            SELECT CASE WHEN NVL(MAX(TO_NUMBER(value)), 0) > 0 THEN 1 ELSE 0 END AS supported
            FROM v$parameter
            WHERE name = 'undo_retention'
            """;
    }

    @Override
    public String cdcCreateMirrorTableSql(String targetSchema, String targetTable, String sourceQuery) {
        return "CREATE TABLE %s AS %s".formatted(qualifiedName(targetSchema, targetTable), sourceQuery);
    }

    /** Quotes {@code schema.table}, omitting the schema when it is absent. */
    private String qualifiedName(String schema, String table) {
        return schema == null || schema.isBlank()
                ? quote(table)
                : quote(schema) + "." + quote(table);
    }
}
