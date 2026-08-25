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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.entropy.database.mcp.util.ValidationUtils.requireNotBlank;

/**
 * Database health and diagnostics tools.
 */
@Component
public class DatabaseHealthTools extends McpToolBase {

    private final DynamicDataSourceManager dataSourceManager;

    public DatabaseHealthTools(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @McpTool(description = "Check database health using dialect-specific query",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> checkHealth(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return DialectQueryUtils.checkHealth(dataSourceManager, connection);
    }

    @McpTool(description = "List active database sessions",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listActiveSessions(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listActiveSessionsSql();
            if (sql == null) throw new IllegalStateException("listActiveSessions is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = "Show database locks and blocking information",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> showLocks(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.showLocksSql();
            if (sql == null) throw new IllegalStateException("showLocks is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = "Show blocking chain (who is blocking whom)",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> showBlockingTree(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.showBlockingTreeSql();
            if (sql == null) throw new IllegalStateException("showBlockingTree is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = "List tablespaces and usage",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listTablespaces(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listTablespacesSql();
            if (sql == null) throw new IllegalStateException("listTablespaces is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = "List datafiles status and autoextension",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listDataFiles(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listDataFilesSql();
            if (sql == null) throw new IllegalStateException("listDataFiles is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = "Estimate table size in MB",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> estimateTableSize(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Optional schema name", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        requireNotBlank(tableName, "tableName");
        Map<String, Object> result = executeWithDialect(connection, dialect -> dialect.estimateTableSizeSql(tableName, schema));
        result.put("tableName", tableName);
        return result;
    }

    @McpTool(description = "List invalid database objects",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listInvalidObjects(
            @McpToolParam(description = "Optional schema name filter", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listInvalidObjectsSql(schema);
            if (sql == null) throw new IllegalStateException("listInvalidObjects is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        }, schema == null ? null : schema);
    }

    @McpTool(description = "Gather table statistics for optimizer",
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> gatherTableStats(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Optional schema name", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        requireNotBlank(tableName, "tableName");
        Map<String, Object> result = executeWithDialect(connection, dialect -> {
            String sql = dialect.gatherTableStatsSql(tableName, schema);
            if (sql == null) throw new IllegalStateException("gatherTableStats is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
        result.put("tableName", tableName);
        return result;
    }

    @McpTool(description = "Show index status and unusable indexes",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> showIndexStatus(
            @McpToolParam(description = "Optional table name filter") String tableName,
            @McpToolParam(description = "Optional schema name", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.showIndexStatusSql(tableName, schema);
            if (sql == null) throw new IllegalStateException("showIndexStatus is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        }, schema, tableName);
    }

    @McpTool(description = "Generate flashback query template (AS OF TIMESTAMP)",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> flashbackQuery(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Timestamp in ISO-8601 format", required = false) String timestamp,
            @McpToolParam(description = "Optional schema name", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return safeExecute(() -> {
            requireNotBlank(tableName, "tableName");
            requireNotBlank(timestamp, "timestamp");
            ByokDataSourceContext context = dataSourceManager.acquire(connection);
            DatabaseDialect dialect = context.getDialect();
            var jdbcTemplate = context.getJdbcTemplate();
            String sqlTemplate = dialect.flashbackQuerySql(tableName);
            if (sqlTemplate == null) {
                sqlTemplate = "SELECT 'SELECT * FROM %s -- Flashback not supported for this dialect' AS sql_template".formatted(dialect.quote(tableName));
            }
            List<Map<String, Object>> rows = sqlTemplate.contains("?")
                    ? jdbcTemplate.queryForList(sqlTemplate, timestamp)
                    : jdbcTemplate.queryForList(sqlTemplate);
            return success(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "tableName", tableName, "timestamp", timestamp,
                    "rows", rows));
        });
    }

    @McpTool(description = "Show undo tablespace usage",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> showUndoUsage(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.showUndoUsageSql();
            if (sql == null) throw new IllegalStateException("showUndoUsage is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = "List current user privileges",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listCurrentPrivileges(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listCurrentPrivilegesSql();
            if (sql == null) throw new IllegalStateException("listCurrentPrivileges is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = "List grants for a user or role",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listGrants(
            @McpToolParam(description = "User or role name") String userName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        requireNotBlank(userName, "userName");
        Map<String, Object> result = executeWithDialect(connection, dialect -> dialect.listGrantsSql(userName));
        result.put("userName", userName);
        return result;
    }

    private Map<String, Object> executeWithDialect(String connection, java.util.function.Function<DatabaseDialect, String> sqlProvider, Object... params) {
        return safeExecute(() -> {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(dataSourceManager, connection, sqlProvider, params);
            return success(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "rows", result.get("rows")));
        });
    }
}
