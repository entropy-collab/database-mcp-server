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

    /**
     * Quotes an identifier, escaping any embedded double quote by doubling it so that a
     * delimiter inside {@code name} can never terminate the identifier context.
     */
    @Override
    public String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** Resolves the schema side of a metadata predicate without spending a placeholder on it. */
    private String schemaExpression(String schema) {
        return DialectUtils.schemaExpression(schema, "current_schema()");
    }

    @Override
    public String tableCommentsQuery(String schema) {
        return """
            SELECT c.relname AS table_name, obj_description(c.oid) AS table_comment
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r'
              AND n.nspname = %s
            ORDER BY c.relname
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String tableCommentQuery(String schema, String tableName) {
        return """
            SELECT c.relname AS table_name, obj_description(c.oid) AS table_comment
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r'
              AND n.nspname = %s
              AND c.relname = ?
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String getTableRowCountSql(String schema, String tableName) {
        return "SELECT COUNT(*) AS row_count FROM " + qualifiedTableName(schema, tableName);
    }

    /**
     * {@code pg_class.reltuples} is the planner's estimate: {@code -1} before the first
     * {@code ANALYZE}, which is why this is the estimate variant and not the default.
     */
    @Override
    public String getTableRowCountEstimateSql(String schema, String tableName) {
        return """
            SELECT c.reltuples::bigint AS row_count
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = %s
              AND c.relname = ?
            """.formatted(schemaExpression(schema));
    }

    @Override
    public String columnCommentsQuery(String schema, String tableName) {
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
              AND n.nspname = %s
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY a.attnum
            """.formatted(schemaExpression(schema));
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

    /**
     * One placeholder (the table name); the schema is resolved here. {@code pg_total_relation_size}
     * is fed the relation OID instead of a bound name, so the size no longer depends on the search
     * path resolving the string, and {@code pg_statio_user_tables} is not needed at all.
     */
    @Override
    public String estimateTableSizeSql(String tableName, String schema) {
        return """
                SELECT c.relname AS segment_name, 'TABLE' AS segment_type,
                       round(pg_total_relation_size(c.oid) / 1024.0 / 1024.0, 2) AS size_mb,
                       1 AS extents
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname = ?
                  AND n.nspname = %s
                  AND c.relkind = 'r'
                """.formatted(schemaExpression(schema));
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
        // ANALYZE cannot bind an object name, so the identifiers are inlined. Callers whitelist
        // them with ValidationUtils.validateIdentifier first.
        return "ANALYZE " + qualifiedTableName(schema, tableName);
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

    /**
     * The standard three-view foreign-key join: {@code table_constraints} and
     * {@code key_column_usage} describe the <em>referencing</em> (child) side,
     * {@code constraint_column_usage} the <em>referenced</em> (parent) side. {@code source_*} is
     * therefore the parent and {@code target_*} the child, as the interface requires.
     *
     * <p>The previous version joined {@code constraint_column_usage} with
     * {@code ccu.table_name = tc.table_name}. For a foreign key those two are different tables by
     * definition - {@code tc.table_name} is the child, {@code ccu.table_name} the parent - so the
     * predicate only held for self-references and both lineage queries returned nothing on
     * PostgreSQL.
     *
     * <p>Composite keys: {@code constraint_column_usage} does not expose an ordinal, so a two-column
     * foreign key yields the cross product of its column pairs. The lineage consumer deduplicates by
     * table pair, so the extra rows are harmless; a per-column contract would have to read
     * {@code pg_constraint.conkey} instead.
     */
    private static final String FK_PROJECTION = """
            SELECT ccu.table_name  AS source_table,
                   tc.table_name   AS target_table,
                   ccu.column_name AS source_column,
                   kcu.column_name AS target_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_name = tc.constraint_name
             AND kcu.constraint_schema = tc.constraint_schema
            JOIN information_schema.constraint_column_usage ccu
              ON ccu.constraint_name = tc.constraint_name
             AND ccu.constraint_schema = tc.constraint_schema
            WHERE tc.constraint_type = 'FOREIGN KEY'
            """;

    /** The queried table is the parent, so the placeholder constrains the referenced side. */
    @Override
    public String foreignKeyDownstreamQuery(String tableName) {
        return FK_PROJECTION + """
              AND tc.table_schema = current_schema()
              AND ccu.table_name = ?
            ORDER BY tc.table_name, kcu.column_name
            """;
    }

    /** The queried table is the child, so the placeholder constrains the referencing side. */
    @Override
    public String foreignKeyUpstreamQuery(String tableName) {
        return FK_PROJECTION + """
              AND tc.table_schema = current_schema()
              AND tc.table_name = ?
            ORDER BY ccu.table_name, ccu.column_name
            """;
    }

    @Override
    public String foreignKeyAllEdgesQuery(String schema) {
        return FK_PROJECTION + """
              AND tc.table_schema = %s
            ORDER BY ccu.table_name, tc.table_name, kcu.column_name
            """.formatted(schemaExpression(schema));
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

    /**
     * One placeholder (the table name). The previous version named the table three times and derived
     * {@code distinct_count} from a subquery that counted <em>columns</em>, not distinct values, then
     * excluded a column whenever its name appeared anywhere in any index definition - so
     * {@code id} disqualified {@code customer_id}. The indexed-column test now compares attribute
     * names.
     */
    @Override
    public String candidateColumnsForIndexSql(String tableName) {
        return """
            SELECT c.column_name, c.is_nullable
            FROM information_schema.columns c
            WHERE c.table_schema = current_schema()
              AND c.table_name = ?
              AND NOT EXISTS (
                  SELECT 1
                  FROM pg_index ix
                  JOIN pg_class t ON t.oid = ix.indrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                  JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
                  WHERE n.nspname = c.table_schema
                    AND t.relname = c.table_name
                    AND a.attname = c.column_name
              )
            ORDER BY c.ordinal_position
            """;
    }

    // ─── CDC ─────────────────────────────────────────────────────────────

    @Override
    public String cdcReadChangesSql(String schema, String table, long fromLsn) {
        // PostgreSQL: logical decoding (pg_logical_slot_get_changes) needs a replication slot and
        // returns opaque WAL text, so the readable source here is the project's trigger audit table
        // convention `<table>_audit`. That table records the LSN and time of a change per key but
        // has no operation column, so the operation type cannot be derived and is reported as the
        // explicit CdcChangeType.TRIGGER_AUDIT code rather than an unknown literal.
        return """
            SELECT 'TRIGGER_AUDIT' AS change_type,
                   MAX(event_time) AS change_time,
                   primary_key_col AS primary_keys,
                   NULL AS before_json,
                   NULL AS after_json,
                   NULL::bigint AS transaction_id
            FROM %s
            WHERE event_lsn > CAST(? AS pg_lsn)
            GROUP BY primary_key_col
            """.formatted(auditTableName(schema, table));
    }

    @Override
    public String cdcGetLastLsnSql() {
        return "SELECT pg_current_wal_lsn()::text AS current_lsn";
    }

    /**
     * WAL LSNs are printed as two hexadecimal halves ({@code 0/16B3748}); hashing that text loses
     * all ordering, so it is decoded into {@code (high << 32) | low} - the same value
     * {@code pg_wal_lsn_diff(lsn, '0/0')} yields.
     */
    @Override
    public long parseLsn(java.util.Map<String, Object> row) {
        Object raw = row == null ? null : firstNonNull(row, "current_lsn", "lsn", "pg_current_wal_lsn");
        if (raw == null) {
            return DialectUtils.requireNumericLsn(row, getDialectName());
        }
        return DialectUtils.parsePostgresLsn(raw.toString());
    }

    /** The {@code ?} in {@link #cdcReadChangesSql} is a {@code pg_lsn}, so it is bound as 'X/Y' text. */
    @Override
    public Object cdcLsnParameter(long lsn) {
        return DialectUtils.formatPostgresLsn(lsn);
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

    private static Object firstNonNull(java.util.Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
