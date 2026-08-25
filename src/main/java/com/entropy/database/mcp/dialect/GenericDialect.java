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

public class GenericDialect extends AbstractDatabaseDialect {

    /**
     * Quotes an identifier, escaping any embedded backtick by doubling it so that a
     * delimiter inside {@code name} can never terminate the identifier context.
     */
    @Override
    public String quote(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    @Override
    public String tablesQuery(String schema) {
        return """
            SELECT table_name, 0 AS row_count
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;
    }

    @Override
    public String columnsQuery(String table, String schema) {
        return """
            SELECT column_name, data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema = ?
              AND table_name = ?
            ORDER BY ordinal_position
            """;
    }

    @Override
    public String indexesQuery(String table, String schema) {
        return """
            SELECT index_name, uniqueness, column_name, ordinal_position
            FROM information_schema.statistics
            WHERE table_schema = ?
              AND table_name = ?
            ORDER BY index_name, ordinal_position
            """;
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
    public String listActiveSessionsSql() {
        return """
                SELECT 1 AS sid, 1 AS serial_no, CURRENT_USER AS username,
                       'ACTIVE' AS status, 'localhost' AS machine, 'unknown' AS program,
                       CURRENT_TIMESTAMP AS logon_time, 0 AS last_call_et,
                       'NONE' AS event, 'NONE' AS wait_class, '' AS sql_id
                """;
    }

    @Override
    public String showLocksSql() {
        return """
                SELECT 1 AS sid, 1 AS serial_no, 'NONE' AS type,
                       0 AS id1, 0 AS id2, 'NONE' AS lmode, 0 AS request,
                       0 AS ctime, CURRENT_USER AS username, 'ACTIVE' AS status, '' AS event
                """;
    }

    @Override
    public String showBlockingTreeSql() {
        return """
                SELECT 1 AS waiter_sid, 1 AS waiter_serial,
                       CURRENT_USER AS waiter_user, '' AS waiter_event,
                       1 AS blocker_sid, 1 AS blocker_serial,
                       CURRENT_USER AS blocker_user, '' AS blocker_event
                """;
    }

    @Override
    public String listTablespacesSql() {
        return """
                SELECT 'USERS' AS tablespace_name, 'PERMANENT' AS contents,
                       'LOCAL' AS extent_management, 'ONLINE' AS status,
                       0 AS size_mb, 0 AS used_mb
                """;
    }

    @Override
    public String listDataFilesSql() {
        return """
                SELECT 'data' AS file_name, 'USERS' AS tablespace_name,
                       0 AS bytes, 0 AS blocks, 'ONLINE' AS status,
                       'NO' AS autoextensible, 0 AS maxbytes, 0 AS increment_by, 0 AS max_mb
                """;
    }

    @Override
    public String getTableRowCountSql(String schema, String tableName) {
        return "SELECT COUNT(*) AS row_count FROM " + qualifiedTableName(schema, tableName);
    }

    /**
     * The generic dialect has no portable size source, so it echoes a zero-sized row with the shape
     * the callers expect.
     *
     * <p>Deviates from the one-placeholder contract of
     * {@link DatabaseDialect#estimateTableSizeSql(String, String)}: there is nothing to filter, so
     * the row is a constant and declares no bind parameter. The name is still checked with
     * {@link DialectUtils#isPlainIdentifier(String)} before being concatenated - it used to be
     * spliced into a string literal unchecked, where a single quote in the table name escaped the
     * literal.
     */
    @Override
    public String estimateTableSizeSql(String tableName, String schema) {
        String name = DialectUtils.isPlainIdentifier(tableName) ? tableName.trim() : "unknown";
        return "SELECT '" + name + "' AS segment_name, 'TABLE' AS segment_type, "
                + "0 AS size_mb, 0 AS extents";
    }

    @Override
    public String listInvalidObjectsSql(String schema) {
        String owner = schema != null ? schema : "SA";
        return "SELECT '" + owner + "' AS owner, 'NONE' AS object_name, 'NONE' AS object_type, 'VALID' AS status";
    }

    @Override
    public String gatherTableStatsSql(String tableName, String schema) {
        return "SELECT 1";
    }

    @Override
    public String showIndexStatusSql(String tableName, String schema) {
        return """
                SELECT 'NONE' AS owner, 'NONE' AS table_name,
                       'NONE' AS index_name, 'VALID' AS status,
                       'NONUNIQUE' AS uniqueness, null AS last_analyzed, 0 AS num_rows, 0 AS distinct_keys
                """;
    }

    @Override
    public String flashbackQuerySql(String tableName) {
        return "SELECT 'SELECT * FROM %s -- Flashback not supported for this dialect' AS sql_template"
                .formatted(tableName);
    }

    @Override
    public String showUndoUsageSql() {
        return "SELECT 'undo' AS tablespace_name, 0 AS size_mb, 0 AS used_mb, 0 AS free_mb, 0 AS used_pct";
    }

    @Override
    public String listCurrentPrivilegesSql() {
        return "SELECT 'SELECT' AS privilege, 'NO' AS admin_option, 'NO' AS grantable";
    }

    @Override
    public String listGrantsSql(String userName) {
        return "SELECT '" + userName + "' AS grantee, 'SELECT' AS privilege, 'NO' AS grantable, CURRENT_USER AS grantor";
    }

    @Override
    public String getHealthCheckSql() {
        return "SELECT 'OK' AS status";
    }
}
