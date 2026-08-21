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
import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.facade.DatabaseFacade;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.util.ConnectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.entropy.database.mcp.tools.McpToolUtils.errorResponse;
import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;
import static com.entropy.database.mcp.util.ValidationUtils.requireNotEmpty;

/**
 * DDL execution tools.
 */
@Configuration
public class DdlExecutionTools {

    private static final Logger log = LoggerFactory.getLogger(DdlExecutionTools.class);

    private final RoutingDatabaseFacade routingFacade;
    private final DynamicDataSourceManager dataSourceManager;
    private final SqlValidator sqlValidator;
    private final DatabaseFacade databaseFacade;
    private final boolean ddlAllowed;
    private final boolean gatewayEnabled;

    public DdlExecutionTools(RoutingDatabaseFacade routingFacade,
                             DynamicDataSourceManager dataSourceManager,
                             SqlValidator sqlValidator,
                             DatabaseFacade databaseFacade,
                             org.springframework.core.env.Environment environment) {
        this.routingFacade = routingFacade;
        this.dataSourceManager = dataSourceManager;
        this.sqlValidator = sqlValidator;
        this.databaseFacade = databaseFacade;
        this.ddlAllowed = Boolean.parseBoolean(environment.getProperty("entropy.mcp.database.ddl.allowed", "false"));
        this.gatewayEnabled = Boolean.parseBoolean(environment.getProperty("entropy.mcp.gateway.enabled", "false"));
    }

    private boolean isGatewayEnabled() {
        return gatewayEnabled;
    }

    private ConnectionProperties parseConnection(String connectionJson) {
        return ConnectionUtils.parseConnection(connectionJson);
    }

    // ─── DDL (from WriteTools) ───────────────────────────────────────────────

    @McpTool(description = "Execute a DDL statement (CREATE/ALTER/DROP)")
    public Map<String, Object> executeDdl(
            @McpToolParam(description = "DDL SQL statement") String sql,
            @McpToolParam(description = "Optional BYOK connection JSON (jdbcUrl, username, password, dialect). Omit to use primary datasource.", required = false) String connection) {
        if (!ddlAllowed) {
            return errorResponse(Map.of(), "DDL execution is disabled. Set entropy.mcp.database.ddl.allowed=true to enable.", "DisabledException");
        }
        return routingFacade.executeDdl(sql, parseConnection(connection));
    }

    @McpTool(description = "Backup table schema definition as DDL statements")
    public Map<String, Object> backupSchema(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.backupSchema(tableName, parseConnection(connection));
    }

    @McpTool(description = "Backup table data as INSERT statements")
    public Map<String, Object> backupData(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Maximum rows to backup") int maxRows,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.backupData(tableName, maxRows, parseConnection(connection));
    }

    @McpTool(description = "Compare schema differences between two tables")
    public Map<String, Object> diffSchema(
            @McpToolParam(description = "Source table name") String sourceTable,
            @McpToolParam(description = "Target table name") String targetTable,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.diffSchema(sourceTable, targetTable, parseConnection(connection));
    }

    // ─── Remote DDL (from RemoteDdlTools) ───────────────────────────────────

    @McpTool(description = "Execute a single DDL statement on a remote database")
    public Map<String, Object> executeDdlRemote(
            @McpToolParam(description = "BYOK connection name") String connectionName,
            @McpToolParam(description = "DDL statement (CREATE TABLE, ALTER TABLE, DROP INDEX, etc.)") String ddl) {
        if (!isGatewayEnabled()) {
            return errorResponse(Map.of(), "Gateway is not enabled", "DisabledException");
        }
        try {
            sqlValidator.validateDdl(ddl);
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
            long startTime = System.currentTimeMillis();
            int affected = jdbcTemplate.update(ddl);
            long duration = System.currentTimeMillis() - startTime;
            return successResponse(Map.of(
                    "connectionName", connectionName,
                    "ddl", ddl,
                    "affectedRows", affected,
                    "durationMs", duration,
                    "message", "DDL executed successfully"
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("connectionName", connectionName, "ddl", ddl),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Execute multiple DDL statements in a transaction on a remote database")
    public Map<String, Object> executeDdlBatch(
            @McpToolParam(description = "BYOK connection name") String connectionName,
            @McpToolParam(description = "List of DDL statements to execute") List<String> statements) {
        if (!isGatewayEnabled()) {
            return errorResponse(Map.of(), "Gateway is not enabled", "DisabledException");
        }
        requireNotEmpty(statements, "statements");

        Connection connection = null;
        try {
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
            long startTime = System.currentTimeMillis();
            List<Map<String, Object>> results = new ArrayList<>();
            boolean allSuccess = true;

            connection = jdbcTemplate.getDataSource().getConnection();
            connection.setAutoCommit(false);

            for (String ddl : statements) {
                try {
                    sqlValidator.validateDdl(ddl);
                    long stmtStart = System.currentTimeMillis();
                    jdbcTemplate.execute(ddl);
                    long stmtDuration = System.currentTimeMillis() - stmtStart;
                    results.add(Map.of("ddl", ddl, "success", true, "durationMs", stmtDuration));
                } catch (Exception e) {
                    allSuccess = false;
                    results.add(Map.of("ddl", ddl, "success", false, "error", e.getMessage()));
                    break;
                }
            }

            if (allSuccess) {
                connection.commit();
            } else {
                connection.rollback();
            }

            long totalDuration = System.currentTimeMillis() - startTime;
            return successResponse(Map.of(
                    "connectionName", connectionName,
                    "totalStatements", statements.size(),
                    "succeeded", results.stream().filter(r -> (boolean) r.get("success")).count(),
                    "failed", results.stream().filter(r -> !(boolean) r.get("success")).count(),
                    "results", results,
                    "durationMs", totalDuration,
                    "message", allSuccess ? "All DDL statements executed successfully" : "Transaction rolled back due to failure"
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("connectionName", connectionName), e.getMessage(), e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                try {
                    connection.setAutoCommit(true);
                } catch (Exception e) {
                    log.warn("Failed to reset autoCommit", e);
                }
                try {
                    connection.close();
                } catch (Exception e) {
                    log.warn("Failed to close connection", e);
                }
            }
        }
    }

    @McpTool(description = "Validate DDL statements without executing (dry run)")
    public Map<String, Object> validateDdl(
            @McpToolParam(description = "List of DDL statements to validate") List<String> statements) {
        if (!isGatewayEnabled()) {
            return errorResponse(Map.of(), "Gateway is not enabled", "DisabledException");
        }
        requireNotEmpty(statements, "statements");

        List<Map<String, Object>> results = new ArrayList<>();
        boolean allValid = true;

        for (String ddl : statements) {
            try {
                sqlValidator.validateDdl(ddl);
                results.add(Map.of("ddl", ddl, "valid", true));
            } catch (Exception e) {
                allValid = false;
                results.add(Map.of("ddl", ddl, "valid", false, "error", e.getMessage()));
            }
        }

        return successResponse(Map.of(
                "totalStatements", statements.size(),
                "validCount", results.stream().filter(r -> (boolean) r.get("valid")).count(),
                "invalidCount", results.stream().filter(r -> !(boolean) r.get("valid")).count(),
                "results", results,
                "message", allValid ? "All DDL statements are valid" : "Some DDL statements have validation errors"
        ));
    }
}
