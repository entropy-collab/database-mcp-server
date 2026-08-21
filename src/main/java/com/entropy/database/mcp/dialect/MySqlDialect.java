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

public class MySqlDialect extends AbstractDatabaseDialect {

    @Override
    public String quote(String name) {
        return "`" + name + "`";
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
}
