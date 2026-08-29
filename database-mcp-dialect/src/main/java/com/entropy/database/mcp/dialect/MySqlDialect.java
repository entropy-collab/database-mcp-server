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
import java.util.List;
import java.util.Map;

public class MySqlDialect extends AbstractDatabaseDialect {

    /**
     * Quotes an identifier, escaping any embedded backtick by doubling it so that a
     * delimiter inside {@code name} can never terminate the identifier context.
     */
    @Override
    public String quote(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    /** Resolves the schema side of a metadata predicate without spending a placeholder on it. */
    private String schemaExpression(String schema) {
        return DialectUtils.schemaExpression(schema, "DATABASE()");
    }

    @Override
    public String tableCommentsQuery(String schema) {
        return """
            SELECT table_name, table_comment
            FROM information_schema.tables
            WHERE table_schema = %s
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String tableCommentQuery(String schema, String tableName) {
        return """
            SELECT table_name, table_comment
            FROM information_schema.tables
            WHERE table_schema = %s
              AND table_type = 'BASE TABLE'
              AND table_name = ?
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String getTableRowCountSql(String schema, String tableName) {
        return "SELECT COUNT(*) AS row_count FROM " + qualifiedTableName(schema, tableName);
    }

    /**
     * {@code information_schema.tables.table_rows} is an InnoDB sampling estimate that can be off by
     * half, so it is the estimate variant only.
     */
    @Override
    public String getTableRowCountEstimateSql(String schema, String tableName) {
        return """
            SELECT table_rows AS row_count
            FROM information_schema.tables
            WHERE table_schema = %s
              AND table_name = ?
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String columnCommentsQuery(String schema, String tableName) {
        return """
            SELECT column_name, column_type AS data_type,
                   CASE WHEN is_nullable = 'YES' THEN 1 ELSE 0 END AS nullable,
                   column_comment
            FROM information_schema.columns
            WHERE table_schema = %s
              AND table_name = ?
            ORDER BY ordinal_position
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String searchTableCommentsQuery(String keyword) {
        String kw = "%" + keyword + "%";
        return """
            SELECT table_name, table_comment, table_rows AS row_count
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_type = 'BASE TABLE'
              AND (table_name LIKE ? OR table_comment LIKE ?)
            ORDER BY table_rows DESC
            """;
    }

    @Override
    public String tablesQuery(String schema) {
        return """
            SELECT table_name, table_rows AS row_count
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
              AND table_schema = %s
            ORDER BY table_name
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String columnsQuery(String table, String schema) {
        return """
            SELECT column_name, column_type, is_nullable
            FROM information_schema.columns
            WHERE table_name = ?
              AND table_schema = %s
            ORDER BY ordinal_position
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String indexesQuery(String table, String schema) {
        return """
            SELECT index_name, non_unique, column_name, seq_in_index
            FROM information_schema.statistics
            WHERE table_name = ?
              AND table_schema = %s
            ORDER BY index_name, seq_in_index
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
    public String sequencesQuery(String schema) {
        // MySQL does not support sequences; return empty result set with matching column names
        return """
            SELECT '' AS name, '' AS minimum_value, '' AS maximum_value, '' AS increment, 0 AS cache_size
            FROM dual WHERE 1 = 0
            """;
    }

    @Override
    public String connectionTestQuery() {
        return "SELECT 1";
    }

    @Override
    public String getHealthCheckSql() {
        return "SELECT 'OK' AS status";
    }

    @Override
    public String getExplainPlanSql(String sql) {
        return "EXPLAIN " + sql;
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
    public String listActiveSessionsSql() {
        return """
                SELECT id AS sid, id AS serial#, user AS username, db AS status,
                   host AS machine, command AS program,
                   NOW() AS logon_time, time AS last_call_et,
                   state AS event, '' AS wait_class, info AS sql_id
                FROM information_schema.processlist
                ORDER BY id
                """;
    }

    @Override
    public String showLocksSql() {
        return """
                SELECT trx.trx_id AS sid, trx.trx_id AS serial#,
                       trx.trx_mysql_thread_id AS type,
                       trx.trx_id AS id1, 0 AS id2,
                       'IX' AS lmode, 0 AS request,
                       TIMESTAMPDIFF(SECOND, trx.trx_started, NOW()) AS ctime,
                       trx.trx_mysql_thread_id AS username, 'ACTIVE' AS status, '' AS event
                FROM information_schema.innodb_trx trx
                ORDER BY trx.trx_id
                """;
    }

    @Override
    public String showBlockingTreeSql() {
        return """
                SELECT r.trx_mysql_thread_id AS waiter_sid,
                       r.trx_mysql_thread_id AS waiter_serial,
                       r.trx_mysql_thread_id AS waiter_user, '' AS waiter_event,
                       b.trx_mysql_thread_id AS blocker_sid,
                       b.trx_mysql_thread_id AS blocker_serial,
                       b.trx_mysql_thread_id AS blocker_user, '' AS blocker_event
                FROM information_schema.innodb_lock_waits w
                JOIN information_schema.innodb_trx r ON w.requesting_trx_id = r.trx_id
                JOIN information_schema.innodb_trx b ON w.blocking_trx_id = b.trx_id
                """;
    }

    @Override
    public String listTablespacesSql() {
        return """
                SELECT table_schema AS tablespace_name, 'NORMAL' AS contents,
                       'LOCAL' AS extent_management, 'ONLINE' AS status,
                       round(sum(data_length + index_length) / 1024 / 1024, 2) AS size_mb,
                       0 AS used_mb
                FROM information_schema.tables
                WHERE table_schema NOT IN ('information_schema', 'performance_schema', 'mysql', 'sys')
                GROUP BY table_schema
                ORDER BY table_schema
                """;
    }

    @Override
    public String listDataFilesSql() {
        return """
                SELECT table_schema AS tablespace_name, table_name AS file_name,
                       data_length AS bytes, index_length AS blocks,
                       'ONLINE' AS status, 'NO' AS autoextensible, 0 AS maxbytes, 0 AS increment_by, 0 AS max_mb
                FROM information_schema.tables
                WHERE table_schema NOT IN ('information_schema', 'performance_schema', 'mysql', 'sys')
                ORDER BY table_schema, table_name
                """;
    }

    /**
     * One placeholder (the table name); the schema is resolved here instead of being bound. It used
     * to be a second {@code ?}, and every caller bound the table name into it, so the
     * {@code table_schema} predicate never matched and the size came back as -1 on MySQL.
     */
    @Override
    public String estimateTableSizeSql(String tableName, String schema) {
        return """
                SELECT table_name AS segment_name, 'TABLE' AS segment_type,
                       round(sum(data_length + index_length) / 1024 / 1024, 2) AS size_mb,
                       count(*) AS extents
                FROM information_schema.tables
                WHERE table_name = ?
                  AND table_schema = %s
                GROUP BY table_name
                """.formatted(schemaExpression(schema));
    }

    @Override
    public String listInvalidObjectsSql(String schema) {
        return """
                SELECT table_schema AS owner, table_name AS object_name,
                       'TABLE' AS object_type, 'INVALID' AS status
                FROM information_schema.tables
                WHERE table_schema = COALESCE(?, DATABASE())
                ORDER BY table_schema, table_name
                """;
    }

    @Override
    public String gatherTableStatsSql(String tableName, String schema) {
        // ANALYZE TABLE cannot bind an object name, so the identifiers are inlined. Callers
        // whitelist them with ValidationUtils.validateIdentifier first.
        return "ANALYZE TABLE " + qualifiedTableName(schema, tableName);
    }

    @Override
    public String showIndexStatusSql(String tableName, String schema) {
        return """
                SELECT table_schema AS owner, table_name, index_name,
                       'VALID' AS status, CASE non_unique WHEN 0 THEN 'UNIQUE' ELSE 'NONUNIQUE' END AS uniqueness,
                       null AS last_analyzed, 0 AS num_rows, 0 AS distinct_keys
                FROM information_schema.statistics
                WHERE table_schema = COALESCE(?, DATABASE())
                  AND table_name = COALESCE(?, table_name)
                ORDER BY table_schema, table_name, index_name
                """;
    }

    @Override
    public String flashbackQuerySql(String tableName) {
        return null;
    }

    @Override
    public String showUndoUsageSql() {
        return """
                SELECT 'undo' AS tablespace_name, 0 AS size_mb,
                       0 AS used_mb, 0 AS free_mb, 0 AS used_pct
                """;
    }

    @Override
    public String listCurrentPrivilegesSql() {
        return """
                SELECT privilege_type AS privilege, 'YES' AS admin_option, 'YES' AS grantable
                FROM information_schema.user_privileges
                WHERE grantee = current_user
                ORDER BY privilege_type
                """;
    }

    @Override
    public String listGrantsSql(String userName) {
        return """
                SELECT grantee, privilege_type AS privilege, is_grantable AS grantable, grantor
                FROM information_schema.table_privileges
                WHERE grantee = ?
                ORDER BY grantee, privilege
                """;
    }

    @Override
    public String buildUpsertSql(String tableName, List<String> allColumns, List<String> keyColumns) {
        String columnList = String.join(", ", allColumns);
        String placeholderList = String.join(", ", allColumns.stream().map(c -> "?").toList());
        List<String> nonKeyColumns = allColumns.stream().filter(col -> !keyColumns.contains(col)).toList();
        String updateSet = nonKeyColumns.stream()
                .map(col -> col + " = VALUES(" + col + ")")
                .reduce((a, b) -> a + ", " + b).orElse("");
        return String.format("INSERT INTO %s (%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s",
                tableName, columnList, placeholderList, updateSet);
    }

    /**
     * {@code key_column_usage} already carries both ends of a foreign key, so the three-way join
     * through {@code referential_constraints} is unnecessary. {@code source_*} is the referenced
     * (parent) side and {@code target_*} the referencing (child) side, as the interface requires.
     */
    private static final String FK_PROJECTION = """
            SELECT kcu.referenced_table_name  AS source_table,
                   kcu.table_name             AS target_table,
                   kcu.referenced_column_name AS source_column,
                   kcu.column_name            AS target_column
            FROM information_schema.key_column_usage kcu
            WHERE kcu.referenced_table_name IS NOT NULL
            """;

    /** The queried table is the parent, so the placeholder constrains {@code referenced_table_name}. */
    @Override
    public String foreignKeyDownstreamQuery(String tableName) {
        return FK_PROJECTION + """
              AND kcu.table_schema = DATABASE()
              AND kcu.referenced_table_name = ?
            ORDER BY kcu.table_name, kcu.column_name
            """;
    }

    /**
     * The queried table is the child, so the placeholder constrains {@code table_name}.
     *
     * <p>It used to constrain the referenced side instead, which returned the tables that point
     * <em>at</em> the queried one: every upstream lookup on MySQL answered with the downstream graph
     * and vice versa.
     */
    @Override
    public String foreignKeyUpstreamQuery(String tableName) {
        return FK_PROJECTION + """
              AND kcu.table_schema = DATABASE()
              AND kcu.table_name = ?
            ORDER BY kcu.referenced_table_name, kcu.referenced_column_name
            """;
    }

    @Override
    public String foreignKeyAllEdgesQuery(String schema) {
        return FK_PROJECTION + """
              AND kcu.table_schema = %s
            ORDER BY kcu.referenced_table_name, kcu.table_name, kcu.column_name
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String listTableIndexesSql(String tableName) {
        return """
            SELECT s.INDEX_NAME, s.COLUMN_NAME,
                   CASE WHEN s.NON_UNIQUE = 0 THEN 1 ELSE 0 END AS UNIQUENESS
            FROM information_schema.STATISTICS s
            WHERE s.TABLE_SCHEMA = DATABASE()
              AND s.TABLE_NAME = ?
            ORDER BY s.INDEX_NAME, s.SEQ_IN_INDEX
            """;
    }

    @Override
    public String candidateColumnsForIndexSql(String tableName) {
        return """
            SELECT c.COLUMN_NAME, c.IS_NULLABLE, c.COLUMN_KEY
            FROM information_schema.COLUMNS c
            LEFT JOIN information_schema.STATISTICS s
              ON s.TABLE_SCHEMA = DATABASE()
             AND s.TABLE_NAME = c.TABLE_NAME
             AND s.COLUMN_NAME = c.COLUMN_NAME
             AND s.INDEX_NAME = 'PRIMARY'
            WHERE c.TABLE_SCHEMA = DATABASE()
              AND c.TABLE_NAME = ?
              AND s.COLUMN_NAME IS NULL
              AND c.DATA_TYPE IN ('int','bigint','varchar','char','date','datetime','timestamp')
            ORDER BY c.ORDINAL_POSITION
            """;
    }

    // ─── CDC ─────────────────────────────────────────────────────────────

    @Override
    public String cdcReadChangesSql(String schema, String table, long fromLsn) {
        // MySQL has no queryable per-row change log: the binary log is only reachable through a
        // replication client (Debezium). What is left is the project's trigger audit table
        // convention `<table>_audit`, which records *that* a key changed (event_time per key) but
        // carries no operation column, so the operation type genuinely cannot be derived here.
        // It is therefore reported as the explicit CdcChangeType.TRIGGER_AUDIT code rather than a
        // value no enum knows about.
        //
        // 位点单位定为「Unix 秒」，与 cdcGetLastLsnSql 的 UNIX_TIMESTAMP() 严格同一单位。
        // 之所以不改成「审计表增加 event_position BIGINT 存打包后的 binlog 位点、谓词写
        // event_position > ?」那种与 PostgreSQL 的 event_lsn 完全对称的方案：MySQL 没有任何函数能在
        // 触发器里取到当前 binlog 坐标（File/Position 只有 SHOW MASTER STATUS 拿得到，且不能在触发器
        // 中执行），那一列没有可实现的写入方，约定出来必然恒为 NULL——谓词又会恒不成立。
        // PostgreSQL 能走 event_lsn 是因为 pg_current_wal_lsn() 可以在触发器里调用，这是两者的本质差别。
        return """
            SELECT 'TRIGGER_AUDIT' AS change_type,
                   MAX(event_time) AS change_time,
                   primary_key_col AS primary_keys,
                   NULL AS before_json,
                   NULL AS after_json,
                   NULL AS transaction_id
            FROM %s
            WHERE event_time > FROM_UNIXTIME(?)
            GROUP BY primary_key_col
            """.formatted(auditTableName(schema, table));
    }

    /**
     * MySQL 的 CDC watermark 就是 Unix 秒，正是 {@link #cdcReadChangesSql} 里 {@code FROM_UNIXTIME(?)}
     * 期望的单位，因此 {@code parseLsn} 走默认的数值解析即可（{@code current_lsn} 在
     * {@code DialectUtils} 的数值位点列白名单里）。
     *
     * <p>不再用 {@code SHOW MASTER STATUS}：它给出的是 binlog 坐标，打包成
     * {@code (file << 32) | offset} 后落到 {@code FROM_UNIXTIME} 会被当成公元 2922 年那种荒谬时刻
     * （MySQL 8.0.28 之前更是超出 32 位上限直接返回 {@code NULL}，谓词恒为 {@code NULL}），
     * 无论哪种情况 readChanges 都恒返回 0 行，且与「这张表真的没有变更」不可区分。
     * 它还额外要求 REPLICATION CLIENT 权限，而审计表读取路径根本不需要该权限。
     */
    @Override
    public String cdcGetLastLsnSql() {
        return "SELECT UNIX_TIMESTAMP() AS current_lsn";
    }

    @Override
    public String cdcCheckSupportSql() {
        // 判据必须与真实读取机制对应：读变更走触发器审计表、位点走 UNIX_TIMESTAMP()，两者都不依赖
        // binlog 是否开启，所以不再探测 log_bin / binlog_format——information_schema.GLOBAL_VARIABLES
        // 在 MySQL 8.0 已被移除，那条判据在 8.x 上直接抛错并被 isCdcSupported 吞成「不支持」。
        // 剩下的真实前置条件是 per-table 的 `<table>_audit` 是否存在，无法在连接级别探测，只能由
        // readChanges 明确报错。
        // 单行单值：isCdcSupported 只取首行首值，多段 UNION ALL 会撞上「恰好一行」的假设。
        return "SELECT CASE WHEN UNIX_TIMESTAMP() > 0 THEN 1 ELSE 0 END AS supported";
    }

    @Override
    public String cdcCreateMirrorTableSql(String targetSchema, String targetTable, String sourceQuery) {
        String target = targetSchema == null || targetSchema.isBlank()
                ? quote(targetTable)
                : quote(targetSchema) + "." + quote(targetTable);
        return "CREATE TABLE %s AS %s".formatted(target, sourceQuery);
    }

    /** Quotes the {@code <table>_audit} companion table, schema-qualified when a schema is given. */
    private String auditTableName(String schema, String table) {
        String audit = quote(table + "_audit");
        return schema == null || schema.isBlank() ? audit : quote(schema) + "." + audit;
    }
}
