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

public abstract class AbstractDatabaseDialect implements DatabaseDialect {

    @Override
    public String schemasQuery() {
        return "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
    }

    @Override
    public String viewsQuery(String schema) {
        return """
            SELECT table_name AS name, view_definition AS definition
            FROM information_schema.views
            WHERE table_schema = ?
            ORDER BY table_name
            """;
    }

    @Override
    public String sequencesQuery(String schema) {
        return """
            SELECT sequence_name AS name, minimum_value, maximum_value, increment, cache_size
            FROM information_schema.sequences
            WHERE sequence_schema = ?
            ORDER BY sequence_name
            """;
    }

    @Override
    public String searchTablesQuery(String keyword) {
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

    @Override
    public String getTableStatisticsSql(String tableName) {
        return null;
    }

    @Override
    public String getPaginationSql(String sql, int offset, int limit) {
        return null;
    }

    @Override
    public String getExplainPlanSql(String sql) {
        return null;
    }

    @Override
    public boolean supportsExplainPlan() {
        return false;
    }

    @Override
    public boolean supportsTableStatistics() {
        return false;
    }

    @Override
    public String getDialectName() {
        return getClass().getSimpleName().toLowerCase().replace("dialect", "");
    }

    @Override
    public String getHealthCheckSql() {
        return "SELECT 1";
    }

    @Override
    public String connectionTestQuery() {
        return "SELECT 1";
    }

    @Override
    public String currentUserQuery() {
        return "SELECT CURRENT_USER";
    }

    @Override
    public String getTableDdlQuery(String tableName, String schema) {
        return null;
    }

    @Override
    public boolean isValidIdentifier(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }

    @Override
    public String normalizeTableName(String table) {
        return table;
    }

    @Override
    public String killSessionSql(String sessionId, String mode) {
        return null;
    }

    @Override
    public String listActiveSessionsSql() {
        return null;
    }

    @Override
    public String showLocksSql() {
        return null;
    }

    @Override
    public String showBlockingTreeSql() {
        return null;
    }

    @Override
    public String listTablespacesSql() {
        return null;
    }

    @Override
    public String listDataFilesSql() {
        return null;
    }

    @Override
    public String estimateTableSizeSql(String tableName, String schema) {
        return null;
    }

    @Override
    public String listInvalidObjectsSql(String schema) {
        return null;
    }

    @Override
    public String gatherTableStatsSql(String tableName, String schema) {
        return null;
    }

    @Override
    public String showIndexStatusSql(String tableName, String schema) {
        return null;
    }

    @Override
    public String flashbackQuerySql(String tableName) {
        return null;
    }

    @Override
    public String showUndoUsageSql() {
        return null;
    }

    @Override
    public String listCurrentPrivilegesSql() {
        return null;
    }

    @Override
    public String listGrantsSql(String userName) {
        return null;
    }
}
