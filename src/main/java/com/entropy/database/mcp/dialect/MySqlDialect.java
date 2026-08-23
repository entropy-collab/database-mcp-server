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

import java.util.List;

public class MySqlDialect extends AbstractDatabaseDialect {

    @Override
    public String quote(String name) {
        return "`" + name + "`";
    }

    @Override
    public String tableCommentsQuery() {
        return """
            SELECT table_name, table_comment
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;
    }

    @Override
    public String columnCommentsQuery(String tableName) {
        return """
            SELECT column_name, column_type AS data_type,
                   CASE WHEN is_nullable = 'YES' THEN 1 ELSE 0 END AS nullable,
                   column_comment
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = ?
            ORDER BY ordinal_position
            """;
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
        var schemaFilter = schema != null ? "AND table_schema = ?" : "";
        return """
            SELECT table_name, table_rows AS row_count
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
            %s
            ORDER BY table_name
            """.formatted(schemaFilter);
    }

    @Override
    public String columnsQuery(String table, String schema) {
        var schemaFilter = schema != null ? "AND table_schema = ?" : "";
        return """
            SELECT column_name, column_type, is_nullable
            FROM information_schema.columns
            WHERE table_name = ?
            %s
            ORDER BY ordinal_position
            """.formatted(schemaFilter);
    }

    @Override
    public String indexesQuery(String table, String schema) {
        var schemaFilter = schema != null ? "AND table_schema = ?" : "";
        return """
            SELECT index_name, non_unique, column_name, seq_in_index
            FROM information_schema.statistics
            WHERE table_name = ?
            %s
            ORDER BY index_name, seq_in_index
            """.formatted(schemaFilter);
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
    public void configureDataSource(HikariConfig config, DatabaseProperties properties) {
        config.addDataSourceProperty("cachePrepStmts", "true");
        if (properties != null && properties.preparedStatement() != null) {
            config.addDataSourceProperty("prepStmtCacheSize", String.valueOf(properties.preparedStatement().cacheSize()));
            config.addDataSourceProperty("prepStmtCacheSqlLimit", String.valueOf(properties.preparedStatement().sqlLimit()));
        }
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

    @Override
    public String estimateTableSizeSql(String tableName, String schema) {
        return """
                SELECT table_name AS segment_name, 'TABLE' AS segment_type,
                       round(sum(data_length + index_length) / 1024 / 1024, 2) AS size_mb,
                       count(*) AS extents
                FROM information_schema.tables
                WHERE table_name = ?
                  AND table_schema = COALESCE(?, DATABASE())
                GROUP BY table_name
                """;
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
        return "ANALYZE TABLE ? COALESCE(?, '')";
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

    @Override
    public String foreignKeyDownstreamQuery(String tableName) {
        return """
            SELECT kcu2.table_name AS source_table,
                   kcu1.table_name AS target_table,
                   kcu1.column_name AS source_column,
                   kcu2.column_name AS target_column
            FROM information_schema.key_column_usage kcu1
            JOIN information_schema.referential_constraints rc
              ON rc.constraint_schema = kcu1.table_schema
             AND rc.constraint_name = kcu1.constraint_name
            JOIN information_schema.key_column_usage kcu2
              ON kcu2.constraint_schema = kcu1.table_schema
             AND kcu2.ordinal_position = kcu1.ordinal_position
             AND kcu2.constraint_name = rc.unique_constraint_name
            WHERE kcu1.table_name = ?
              AND kcu1.table_schema = DATABASE()
            ORDER BY kcu2.table_name, kcu1.column_name
            """;
    }

    @Override
    public String foreignKeyUpstreamQuery(String tableName) {
        return """
            SELECT kcu1.table_name AS source_table,
                   kcu2.table_name AS target_table,
                   kcu1.column_name AS source_column,
                   kcu2.column_name AS target_column
            FROM information_schema.key_column_usage kcu1
            JOIN information_schema.referential_constraints rc
              ON rc.constraint_schema = kcu1.table_schema
             AND rc.constraint_name = kcu1.constraint_name
            JOIN information_schema.key_column_usage kcu2
              ON kcu2.constraint_schema = kcu1.table_schema
             AND kcu2.ordinal_position = kcu1.ordinal_position
             AND kcu2.constraint_name = rc.unique_constraint_name
            WHERE kcu2.table_name = ?
              AND kcu2.table_schema = DATABASE()
            ORDER BY kcu1.table_name, kcu2.column_name
            """;
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
        // MySQL: read from performance_schema or use binlog coordinates via SHOW MASTER STATUS
        // Simplified: use INFORMATION_SCHEMA to detect last change time via trigger-based audit table
        return """
            SELECT 'TRIGGER_AUDIT' AS change_type,
                   MAX(event_time) AS change_time,
                   CONCAT(primary_key_col) AS primary_keys,
                   NULL AS before_json,
                   NULL AS after_json,
                   NULL AS transaction_id
            FROM %s.%s_audit
            WHERE event_time > FROM_UNIXTIME(?)
            GROUP BY primary_key_col
            """.formatted(schema != null ? schema : "CURRENT_SCHEMA()", table);
    }

    @Override
    public String cdcGetLastLsnSql() {
        return "SHOW MASTER STATUS";
    }

    @Override
    public String cdcCheckSupportSql() {
        return """
            SELECT 1 FROM information_schema.GLOBAL_VARIABLES
            WHERE VARIABLE_NAME IN ('log_bin', 'binlog_format')
              AND VARIABLE_VALUE != ''
            LIMIT 1
            """;
    }

    @Override
    public String cdcCreateMirrorTableSql(String targetSchema, String targetTable, String sourceQuery) {
        return "CREATE TABLE `%s`.`%s` AS %s".formatted(
                targetSchema != null ? targetSchema : "CURRENT_SCHEMA()", targetTable, sourceQuery);
    }
}
