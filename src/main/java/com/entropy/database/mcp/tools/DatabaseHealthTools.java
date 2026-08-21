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

import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static com.entropy.database.mcp.tools.McpToolUtils.errorResponse;
import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;
import static com.entropy.database.mcp.util.ValidationUtils.requireNotBlank;

/**
 * Database health and diagnostics tools.
 */
@Configuration
public class DatabaseHealthTools {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthTools.class);

    private final DynamicDataSourceManager dataSourceManager;
    private final DatabaseDialect primaryDialect;
    private final JdbcTemplate primaryJdbcTemplate;

    public DatabaseHealthTools(DynamicDataSourceManager dataSourceManager,
                               DatabaseDialect primaryDialect,
                               org.springframework.jdbc.core.JdbcTemplate primaryJdbcTemplate) {
        this.dataSourceManager = dataSourceManager;
        this.primaryDialect = primaryDialect;
        this.primaryJdbcTemplate = primaryJdbcTemplate;
    }

    // ─── P0: Session and Concurrency Diagnostics ────────────────────────────

    @McpTool(description = "List active database sessions")
    public Map<String, Object> listActiveSessions(
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.listActiveSessionsSql();
                        if (sql == null) {
                            throw new IllegalStateException("listActiveSessions is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    });
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("connection", connection), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Show database locks and blocking information")
    public Map<String, Object> showLocks(
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.showLocksSql();
                        if (sql == null) {
                            throw new IllegalStateException("showLocks is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    });
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("connection", connection), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Show blocking chain (who is blocking whom)")
    public Map<String, Object> showBlockingTree(
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.showBlockingTreeSql();
                        if (sql == null) {
                            throw new IllegalStateException("showBlockingTree is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    });
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("connection", connection), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── P1: Storage and Capacity Management ─────────────────────────────────

    @McpTool(description = "List tablespaces and usage")
    public Map<String, Object> listTablespaces(
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.listTablespacesSql();
                        if (sql == null) {
                            throw new IllegalStateException("listTablespaces is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    });
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("connection", connection), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "List datafiles status and autoextension")
    public Map<String, Object> listDataFiles(
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.listDataFilesSql();
                        if (sql == null) {
                            throw new IllegalStateException("listDataFiles is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    });
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("connection", connection), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Estimate table size in MB")
    public Map<String, Object> estimateTableSize(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Optional schema name", required = false) String schema,
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        requireNotBlank(tableName, "tableName");
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.estimateTableSizeSql(tableName, schema);
                        if (sql == null) {
                            throw new IllegalStateException("estimateTableSize is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    }, tableName, tableName, tableName, schema);
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "tableName", tableName,
                    "schema", schema,
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("tableName", tableName, "schema", schema, "connection", connection),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── P2: Object Health and Statistics ────────────────────────────────────

    @McpTool(description = "List invalid database objects")
    public Map<String, Object> listInvalidObjects(
            @McpToolParam(description = "Optional schema name filter", required = false) String schema,
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.listInvalidObjectsSql(schema);
                        if (sql == null) {
                            throw new IllegalStateException("listInvalidObjects is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    }, schema == null ? null : schema);
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("schema", schema, "connection", connection), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Gather table statistics for optimizer")
    public Map<String, Object> gatherTableStats(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Optional schema name", required = false) String schema,
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        requireNotBlank(tableName, "tableName");
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.gatherTableStatsSql(tableName, schema);
                        if (sql == null) {
                            throw new IllegalStateException("gatherTableStats is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    });
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "tableName", tableName,
                    "schema", schema,
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("tableName", tableName, "schema", schema, "connection", connection),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Show index status and unusable indexes")
    public Map<String, Object> showIndexStatus(
            @McpToolParam(description = "Optional table name filter") String tableName,
            @McpToolParam(description = "Optional schema name", required = false) String schema,
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.showIndexStatusSql(tableName, schema);
                        if (sql == null) {
                            throw new IllegalStateException("showIndexStatus is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    }, schema, tableName);
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "tableName", tableName,
                    "schema", schema,
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("tableName", tableName, "schema", schema, "connection", connection),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── P3: Flashback and Undo Management ───────────────────────────────────

    @McpTool(description = "Generate flashback query template (AS OF TIMESTAMP)")
    public Map<String, Object> flashbackQuery(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Timestamp in ISO-8601 format", required = false) String timestamp,
            @McpToolParam(description = "Optional schema name", required = false) String schema,
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        requireNotBlank(tableName, "tableName");
        requireNotBlank(timestamp, "timestamp");
        try {
            DatabaseDialect dialect;
            JdbcTemplate jdbcTemplate;
            if (connection == null || connection.isBlank()) {
                dialect = primaryDialect;
                jdbcTemplate = primaryJdbcTemplate;
            } else {
                com.entropy.database.mcp.byok.ByokDataSourceContext context = dataSourceManager.acquire(connection);
                dialect = context.getDialect();
                jdbcTemplate = context.getJdbcTemplate();
            }

            String sqlTemplate = dialect.flashbackQuerySql(tableName);
            if (sqlTemplate == null) {
                sqlTemplate = "SELECT 'SELECT * FROM %s -- Flashback not supported for this dialect' AS sql_template"
                        .formatted(dialect.quote(tableName));
            }

            List<Map<String, Object>> rows;
            if (sqlTemplate.contains("?")) {
                rows = jdbcTemplate.queryForList(sqlTemplate, timestamp);
            } else {
                rows = jdbcTemplate.queryForList(sqlTemplate);
            }

            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "tableName", tableName,
                    "timestamp", timestamp,
                    "schema", schema,
                    "rows", rows
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("tableName", tableName, "timestamp", timestamp, "schema", schema, "connection", connection),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Show undo tablespace usage")
    public Map<String, Object> showUndoUsage(
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.showUndoUsageSql();
                        if (sql == null) {
                            throw new IllegalStateException("showUndoUsage is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    });
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("connection", connection), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── P3: User and Privilege Audit ────────────────────────────────────────

    @McpTool(description = "List current user privileges")
    public Map<String, Object> listCurrentPrivileges(
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.listCurrentPrivilegesSql();
                        if (sql == null) {
                            throw new IllegalStateException("listCurrentPrivileges is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    });
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("connection", connection), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "List grants for a user or role")
    public Map<String, Object> listGrants(
            @McpToolParam(description = "User or role name") String userName,
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        requireNotBlank(userName, "userName");
        try {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(
                    dataSourceManager, primaryDialect, primaryJdbcTemplate, connection, dialect -> {
                        String sql = dialect.listGrantsSql(userName);
                        if (sql == null) {
                            throw new IllegalStateException("listGrants is not supported for dialect: " + dialect.getClass().getSimpleName());
                        }
                        return sql;
                    }, userName, userName, userName, userName);
            return successResponse(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(dataSourceManager, connection),
                    "userName", userName,
                    "rows", result.get("rows")
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("userName", userName, "connection", connection),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }
}
