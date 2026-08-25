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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import com.entropy.database.mcp.security.SqlValidator;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.entropy.database.mcp.util.ValidationUtils.requireNotEmpty;

/**
 * DDL execution tools.
 */
@Component
public class DdlExecutionTools extends McpToolBase {

    private final RoutingDatabaseFacade routingFacade;
    private final DynamicDataSourceManager dataSourceManager;
    private final SqlValidator sqlValidator;
    private final boolean ddlAllowed;
    private final boolean gatewayEnabled;

    public DdlExecutionTools(RoutingDatabaseFacade routingFacade,
                             DynamicDataSourceManager dataSourceManager,
                             SqlValidator sqlValidator,
                             org.springframework.core.env.Environment environment) {
        this.routingFacade = routingFacade;
        this.dataSourceManager = dataSourceManager;
        this.sqlValidator = sqlValidator;
        this.ddlAllowed = Boolean.parseBoolean(environment.getProperty("entropy.mcp.database.ddl.allowed", "false"));
        this.gatewayEnabled = Boolean.parseBoolean(environment.getProperty("entropy.mcp.gateway.enabled", "false"));
    }

    private boolean isGatewayEnabled() {
        return gatewayEnabled;
    }

    // ─── DDL ────────────────────────────────────────────────────────────────

    @McpTool(description = "Execute a DDL statement (CREATE/ALTER/DROP)",
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> executeDdl(
            @McpToolParam(description = "DDL SQL statement") String sql,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (!ddlAllowed) {
            throw new McpToolException(ErrorCode.SQL_OPERATION_NOT_ALLOWED, ToolParams.DDL_DISABLED_MSG);
        }
        return routingFacade.executeDdl(sql, connection);
    }

    @McpTool(description = "Backup table data as INSERT statements",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> backupData(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Maximum rows to backup") int maxRows,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (maxRows < 0) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "maxRows must be non-negative (tableName=" + tableName + ", maxRows=" + maxRows + ", connection=" + connection + ")");
        }
        return routingFacade.backupData(tableName, maxRows, connection);
    }

    @McpTool(description = "Compare schema differences between two tables",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> diffSchema(
            @McpToolParam(description = "Source table name") String sourceTable,
            @McpToolParam(description = "Target table name") String targetTable,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.diffSchema(sourceTable, targetTable, connection);
    }

    // ─── Remote DDL ─────────────────────────────────────────────────────────

    @McpTool(description = "Execute a single DDL statement on a remote database",
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> executeDdlRemote(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "DDL statement (CREATE TABLE, ALTER TABLE, DROP INDEX, etc.)") String ddl) throws Exception {
        if (!isGatewayEnabled()) {
            throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Gateway is not enabled");
        }
        validateRequired(connectionName, "connectionName");
        return safeExecute(() -> {
            sqlValidator.validateDdl(ddl);
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
            long startTime = System.currentTimeMillis();
            int affected = jdbcTemplate.update(ddl);
            long duration = System.currentTimeMillis() - startTime;
            return success(Map.of(
                    "connectionName", connectionName,
                    "ddl", ddl,
                    "affectedRows", affected,
                    "durationMs", duration,
                    "message", "DDL executed successfully"
            ));
        });
    }

    @McpTool(description = "Execute multiple DDL statements on a remote database. "
                    + "Atomicity depends on the target: PostgreSQL and SQL Server run DDL inside the "
                    + "transaction and roll back cleanly, while Oracle and MySQL commit each DDL "
                    + "implicitly and cannot be rolled back. The response reports which mode applied "
                    + "via 'transactional', and lists statements already applied when a failure is "
                    + "not reversible.",
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> executeDdlBatch(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "List of DDL statements to execute") List<String> statements) throws Exception {
        if (!isGatewayEnabled()) {
            throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, ToolParams.GATEWAY_NOT_ENABLED_MSG);
        }
        requireNotEmpty(statements, "statements");
        return safeExecute(() -> {
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            Connection connection = null;
            try {
                connection = context.getDataSource().getConnection();

                // Whether wrapping DDL in a transaction means anything on this target. Oracle and
                // MySQL commit DDL implicitly, so a rollback() after a mid-batch failure is a
                // no-op and earlier statements are already permanent. Reporting "rolled back" in
                // that case would be false.
                boolean transactional = !connection.getMetaData().dataDefinitionCausesTransactionCommit();
                if (transactional) {
                    connection.setAutoCommit(false);
                }

                long startTime = System.currentTimeMillis();
                List<Map<String, Object>> results = new ArrayList<>();
                List<String> applied = new ArrayList<>();
                boolean allSuccess = true;

                for (String ddl : statements) {
                    try {
                        sqlValidator.validateDdl(ddl);
                        long stmtStart = System.currentTimeMillis();
                        try (var stmt = connection.createStatement()) {
                            stmt.execute(ddl);
                        }
                        long stmtDuration = System.currentTimeMillis() - stmtStart;
                        applied.add(ddl);
                        results.add(Map.of("ddl", ddl, "success", true, "durationMs", stmtDuration));
                    } catch (Exception e) {
                        allSuccess = false;
                        log.warn("DDL statement failed in batch on connection {}", connectionName, e);
                        results.add(Map.of("ddl", ddl, "success", false,
                                "error", "DDL execution failed: " + e.getMessage()));
                        // Stop on first failure — no point applying the rest of a broken batch
                        break;
                    }
                }

                boolean rolledBack = false;
                if (transactional) {
                    if (allSuccess) {
                        connection.commit();
                    } else {
                        connection.rollback();
                        rolledBack = true;
                        applied.clear();
                    }
                }

                long totalDuration = System.currentTimeMillis() - startTime;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("connectionName", connectionName);
                payload.put("totalStatements", statements.size());
                payload.put("succeeded", applied.size());
                payload.put("failed", allSuccess ? 0 : 1);
                payload.put("results", results);
                payload.put("durationMs", totalDuration);
                payload.put("transactional", transactional);
                payload.put("rolledBack", rolledBack);
                if (!allSuccess && !transactional) {
                    payload.put("appliedBeforeFailure", applied);
                }
                payload.put("message", buildBatchMessage(allSuccess, transactional, applied.size()));
                return success(payload);
            } finally {
                // Close the borrowed physical connection to return it to the pool.
                // Do NOT call closePool() — that would destroy the entire HikariCP pool.
                if (connection != null) {
                    try { connection.close(); } catch (Exception e) { log.warn("Failed to close connection", e); }
                }
            }
        });
    }

    private static String buildBatchMessage(boolean allSuccess, boolean transactional, int appliedCount) {
        if (allSuccess) {
            return "All DDL statements executed successfully";
        }
        if (transactional) {
            return "Transaction rolled back due to failure — the schema is unchanged";
        }
        return "This database commits DDL implicitly, so nothing was rolled back. "
                + appliedCount + " statement(s) are already permanent and must be reversed manually.";
    }

    @McpTool(description = "Validate DDL statements without executing (dry run)",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> validateDdl(
            @McpToolParam(description = "List of DDL statements to validate") List<String> statements) throws Exception {
        if (!isGatewayEnabled()) {
            throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, ToolParams.GATEWAY_NOT_ENABLED_MSG);
        }
        requireNotEmpty(statements, "statements");
        return safeExecute(() -> {
            List<Map<String, Object>> results = new ArrayList<>();
            boolean allValid = true;

            for (String ddl : statements) {
                try {
                    sqlValidator.validateDdl(ddl);
                    results.add(Map.of("ddl", ddl, "valid", true));
                } catch (Exception e) {
                    allValid = false;
                    results.add(Map.of("ddl", ddl, "valid", false, "error", "Validation failed"));
                }
            }

            return success(Map.of(
                    "totalStatements", statements.size(),
                    "validCount", results.stream().filter(r -> (boolean) r.get("valid")).count(),
                    "invalidCount", results.stream().filter(r -> !(boolean) r.get("valid")).count(),
                    "results", results,
                    "message", allValid ? "All DDL statements are valid" : "Some DDL statements have validation errors"
            ));
        });
    }
}
