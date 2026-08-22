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

import com.zaxxer.hikari.HikariConfig;
import com.entropy.database.mcp.properties.DatabaseProperties;

import java.util.List;

public class PostgresDialect extends AbstractDatabaseDialect {

    @Override
    public String quote(String name) {
        return "\"" + name + "\"";
    }

    @Override
    public String tablesQuery(String schema) {
        var schemaFilter = schema != null ? "AND table_schema = ?" : "";
        return """
            SELECT table_name, 0 AS row_count
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
            SELECT column_name, data_type, is_nullable
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
            SELECT indexname AS index_name,
                   indexdef AS definition
            FROM pg_indexes
            WHERE tablename = ?
            %s
            ORDER BY indexname
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
        return """
            SELECT sequence_name AS name, minimum_value, maximum_value, increment, 0 AS cache_size
            FROM information_schema.sequences
            WHERE sequence_schema = ?
            ORDER BY sequence_name
            """;
    }

    public String healthCheckSql() {
        return "SELECT 'OK' AS status";
    }

    @Override
    public void configureDataSource(HikariConfig config, DatabaseProperties properties) {
        config.addDataSourceProperty("prepareThreshold", "3");
    }

    @Override
    public String listActiveSessionsSql() {
        return """
                SELECT pid AS sid, pid AS serial#, usename AS username, state AS status,
                       client_addr AS machine, application_name AS program,
                       backend_start AS logon_time, state_change AS last_call_et,
                       wait_event_type AS event, state AS wait_class, query AS sql_id
                FROM pg_stat_activity
                WHERE state IS NOT NULL
                ORDER BY pid
                """;
    }

    @Override
    public String showLocksSql() {
        return """
                SELECT l.pid AS sid, l.pid AS serial#,
                       'RowExclusiveLock' AS type,
                       l.relation::regclass::text AS id1,
                       0 AS id2,
                       l.mode AS lmode, 0 AS request,
                       EXTRACT(EPOCH FROM now() - l.granted)::int AS ctime,
                       a.usename AS username, a.state AS status, a.wait_event_type AS event
                FROM pg_locks l
                JOIN pg_stat_activity a ON l.pid = a.pid
                WHERE l.granted = true OR l.pid IN (
                    SELECT pid FROM pg_locks WHERE NOT granted
                )
                ORDER BY l.pid
                """;
    }

    @Override
    public String showBlockingTreeSql() {
        return """
                SELECT w.pid AS waiter_sid, w.pid AS waiter_serial,
                       w.usename AS waiter_user, w.wait_event_type AS waiter_event,
                       b.pid AS blocker_sid, b.pid AS blocker_serial,
                       b.usename AS blocker_user, b.wait_event_type AS blocker_event
                FROM pg_locks lw
                JOIN pg_stat_activity w ON lw.pid = w.pid
                JOIN pg_locks lb ON lw.relation = lb.relation AND lw.pid <> lb.pid
                JOIN pg_stat_activity b ON lb.pid = b.pid
                WHERE NOT lw.granted AND lb.granted
                """;
    }

    @Override
    public String listTablespacesSql() {
        return """
                SELECT spcname AS tablespace_name, 'NORMAL' AS contents,
                       'LOCAL' AS extent_management, 'ONLINE' AS status,
                       round(pg_tablespace_size(spcname) / 1024.0 / 1024.0, 2) AS size_mb,
                       0 AS used_mb
                FROM pg_tablespace
                ORDER BY spcname
                """;
    }

    @Override
    public String listDataFilesSql() {
        return """
                SELECT pg_relation_filepath(oid) AS file_name,
                       nspname AS tablespace_name,
                       pg_total_relation_size(relid) AS bytes,
                       0 AS blocks, 'ONLINE' AS status,
                       'NO' AS autoextensible, 0 AS maxbytes, 0 AS increment_by, 0 AS max_mb
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE relkind = 'r'
                ORDER BY nspname, file_name
                """;
    }

    @Override
    public String estimateTableSizeSql(String tableName, String schema) {
        return """
                SELECT relname AS segment_name, 'TABLE' AS segment_type,
                       round(pg_total_relation_size(?) / 1024.0 / 1024.0, 2) AS size_mb,
                       count(*) AS extents
                FROM pg_statio_user_tables
                WHERE relname = ?
                GROUP BY relname
                """;
    }

    @Override
    public String listInvalidObjectsSql(String schema) {
        return """
                SELECT n.nspname AS owner, c.relname AS object_name,
                       CASE c.relkind WHEN 'r' THEN 'TABLE' WHEN 'i' THEN 'INDEX'
                           WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW'
                           WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE c.relkind::text END AS object_type,
                       CASE WHEN c.relkind IN ('r','v','m','S','i')
                            THEN 'VALID' ELSE 'INVALID' END AS status
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = COALESCE(?, current_schema())
                  AND c.relkind IN ('r','v','m','S','i')
                  AND NOT c.relispartition
                ORDER BY n.nspname, c.relkind, c.relname
                """;
    }

    @Override
    public String gatherTableStatsSql(String tableName, String schema) {
        return "ANALYZE ? COALESCE(?, '')";
    }

    @Override
    public String showIndexStatusSql(String tableName, String schema) {
        return """
                SELECT n.nspname AS owner, c.relname AS table_name,
                       i.relname AS index_name, 'VALID' AS status,
                       CASE i.indisunique WHEN true THEN 'UNIQUE' ELSE 'NONUNIQUE' END AS uniqueness,
                       null AS last_analyzed, c.reltuples::bigint AS num_rows, 0 AS distinct_keys
                FROM pg_index ix
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_class c ON c.oid = ix.indrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = COALESCE(?, current_schema())
                  AND c.relname = COALESCE(?, c.relname)
                ORDER BY n.nspname, c.relname, i.relname
                """;
    }

    @Override
    public String flashbackQuerySql(String tableName) {
        return "SELECT 'SELECT * FROM %s AS OF TIMESTAMP ?::timestamp' AS sql_template"
                .formatted(quote(tableName));
    }

    @Override
    public String showUndoUsageSql() {
        return """
                SELECT 'pg_default' AS tablespace_name,
                       round(sum(pg_tablespace_size(spcname)) / 1024 / 1024, 2) AS size_mb,
                       0 AS used_mb, 0 AS free_mb, 0 AS used_pct
                FROM pg_tablespace
                """;
    }

    @Override
    public String listCurrentPrivilegesSql() {
        return """
                SELECT privilege, grantable AS admin_option, grantee AS grantable
                FROM information_schema.role_table_grants
                WHERE grantee = current_user
                UNION ALL
                SELECT privilege, grantable, grantee
                FROM information_schema.role_routine_grants
                WHERE grantee = current_user
                ORDER BY privilege
                """;
    }

    @Override
    public String listGrantsSql(String userName) {
        return """
                SELECT grantee, privilege, grantable, grantor
                FROM information_schema.role_table_grants
                WHERE grantee = ?
                UNION ALL
                SELECT grantee, privilege, grantable, grantor
                FROM information_schema.role_routine_grants
                WHERE grantee = ?
                ORDER BY grantee, privilege
                """;
    }

    @Override
    public String buildUpsertSql(String tableName, List<String> allColumns, List<String> keyColumns) {
        String columnList = String.join(", ", allColumns);
        String placeholderList = String.join(", ", allColumns.stream().map(c -> "?").toList());
        String keyList = String.join(", ", keyColumns);
        List<String> nonKeyColumns = allColumns.stream().filter(col -> !keyColumns.contains(col)).toList();
        if (nonKeyColumns.isEmpty()) {
            return String.format("INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO NOTHING",
                    tableName, columnList, placeholderList, keyList);
        }
        String updateSet = nonKeyColumns.stream()
                .map(col -> col + " = EXCLUDED." + col)
                .reduce((a, b) -> a + ", " + b).orElse("");
        return String.format("INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO UPDATE SET %s",
                tableName, columnList, placeholderList, keyList, updateSet);
    }
}
