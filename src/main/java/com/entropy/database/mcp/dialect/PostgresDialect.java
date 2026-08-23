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
    public String tableCommentsQuery() {
        return """
            SELECT c.relname AS table_name, obj_description(c.oid) AS table_comment
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r'
              AND n.nspname = current_schema()
            ORDER BY c.relname
            """;
    }

    @Override
    public String columnCommentsQuery(String tableName) {
        return """
            SELECT a.attname AS column_name,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                   CASE WHEN a.attnotnull THEN 0 ELSE 1 END AS nullable,
                   d.description AS column_comment
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_description d ON d.objoid = a.attrelid AND d.objsubid = a.attnum
            WHERE c.relname = ?
              AND n.nspname = current_schema()
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY a.attnum
            """;
    }

    @Override
    public String searchTableCommentsQuery(String keyword) {
        String kw = "%" + keyword + "%";
        return """
            SELECT c.relname AS table_name,
                   obj_description(c.oid) AS table_comment,
                   c.reltuples::bigint AS row_count
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r'
              AND n.nspname = current_schema()
              AND (c.relname ILIKE ? OR obj_description(c.oid) ILIKE ?)
            ORDER BY c.reltuples DESC
            """;
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

    @Override
    public String getHealthCheckSql() {
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

    @Override
    public String foreignKeyDownstreamQuery(String tableName) {
        return """
            SELECT ccu2.table_name AS source_table,
                   ccu1.table_name AS target_table,
                   ccu1.column_name AS source_column,
                   ccu2.column_name AS target_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu1
              ON kcu1.constraint_name = tc.constraint_name
             AND kcu1.table_name = tc.table_name
            JOIN information_schema.constraint_column_usage ccu1
              ON ccu1.constraint_name = tc.constraint_name
             AND ccu1.table_name = tc.table_name
            JOIN information_schema.referential_constraints rc
              ON rc.constraint_name = tc.constraint_name
            JOIN information_schema.key_column_usage kcu2
              ON kcu2.constraint_name = rc.unique_constraint_name
             AND kcu2.table_name = ccu1.table_name
            JOIN information_schema.constraint_column_usage ccu2
              ON ccu2.constraint_name = rc.unique_constraint_name
             AND ccu2.table_name = ccu1.table_name
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND tc.table_name = ?
              AND tc.table_schema = current_schema()
            ORDER BY ccu2.table_name, kcu1.column_name
            """;
    }

    @Override
    public String foreignKeyUpstreamQuery(String tableName) {
        return """
            SELECT ccu1.table_name AS source_table,
                   ccu2.table_name AS target_table,
                   ccu1.column_name AS source_column,
                   ccu2.column_name AS target_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu1
              ON kcu1.constraint_name = tc.constraint_name
             AND kcu1.table_name = tc.table_name
            JOIN information_schema.constraint_column_usage ccu1
              ON ccu1.constraint_name = tc.constraint_name
             AND ccu1.table_name = tc.table_name
            JOIN information_schema.referential_constraints rc
              ON rc.constraint_name = tc.constraint_name
            JOIN information_schema.key_column_usage kcu2
              ON kcu2.constraint_name = rc.unique_constraint_name
             AND kcu2.table_name = ccu1.table_name
            JOIN information_schema.constraint_column_usage ccu2
              ON ccu2.constraint_name = rc.unique_constraint_name
             AND ccu2.table_name = ccu1.table_name
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND ccu1.table_name = ?
              AND tc.table_schema = current_schema()
            ORDER BY ccu2.table_name, ccu1.column_name
            """;
    }

    @Override
    public String listTableIndexesSql(String tableName) {
        return """
            SELECT i.relname AS index_name, a.attname AS column_name,
                   CASE WHEN ix.indisunique THEN 1 ELSE 0 END AS uniqueness
            FROM pg_index ix
            JOIN pg_class t ON t.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            WHERE t.relname = ?
              AND n.nspname = current_schema()
            ORDER BY i.relname, a.attnum
            """;
    }

    @Override
    public String candidateColumnsForIndexSql(String tableName) {
        return """
            SELECT c.column_name, c.is_nullable,
                   (SELECT COUNT(DISTINCT c2.column_name) FROM information_schema.columns c2
                    WHERE c2.table_schema = current_schema() AND c2.table_name = ?) AS distinct_count
            FROM information_schema.columns c
            WHERE c.table_schema = current_schema()
              AND c.table_name = ?
              AND NOT EXISTS (
                  SELECT 1 FROM pg_indexes px
                  WHERE px.schemaname = current_schema()
                    AND px.tablename = ?
                    AND px.indexdef ILIKE '%' || c.column_name || '%'
              )
            ORDER BY c.ordinal_position
            """;
    }

    // ─── CDC ─────────────────────────────────────────────────────────────

    @Override
    public String cdcReadChangesSql(String schema, String table, long fromLsn) {
        // PostgreSQL: use pg_logical_slot_get_changes (requires pgoutput) or
        // query a trigger-based audit table with LSN tracking
        return """
            SELECT 'TRIGGER_AUDIT' AS change_type,
                   MAX(event_time) AS change_time,
                   primary_key_col AS primary_keys,
                   NULL AS before_json,
                   NULL AS after_json,
                   NULL::bigint AS transaction_id
            FROM %s.%s_audit
            WHERE event_lsn > pg_lsn(?)
            GROUP BY primary_key_col
            """.formatted(schema != null ? schema : "current_schema()", table);
    }

    @Override
    public String cdcGetLastLsnSql() {
        return "SELECT pg_current_wal_lsn()::text AS current_lsn";
    }

    @Override
    public String cdcCheckSupportSql() {
        return """
            SELECT 1 FROM pg_extension WHERE extname = 'pglogical'
            UNION ALL
            SELECT 1 FROM pg_subscription WHERE subenabled
            UNION ALL
            SELECT 1 FROM pg_publication LIMIT 1
            """;
    }

    @Override
    public String cdcCreateMirrorTableSql(String targetSchema, String targetTable, String sourceQuery) {
        return "CREATE TABLE \"%s\".\"%s\" AS %s".formatted(targetSchema, targetTable, sourceQuery);
    }
}
