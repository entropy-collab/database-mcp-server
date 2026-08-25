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
import com.entropy.database.mcp.facade.DatabaseOperations;
import com.entropy.database.mcp.security.SqlValidator;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

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

    private final DatabaseOperations routingFacade;
    private final SqlValidator sqlValidator;
    private final boolean ddlAllowed;
    private final boolean gatewayEnabled;

    public DdlExecutionTools(DatabaseOperations routingFacade,
                             SqlValidator sqlValidator,
                             org.springframework.core.env.Environment environment) {
        this.routingFacade = routingFacade;
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
            long startTime = System.currentTimeMillis();
            int affected = routingFacade.executeUpdate(ddl, connectionName);
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
            try {
                return routingFacade.inTransaction(connectionName, tx -> {
                    // Whether wrapping DDL in a transaction means anything on this target. Oracle and
                    // MySQL commit DDL implicitly, so a rollback after a mid-batch failure is a
                    // no-op and earlier statements are already permanent. Reporting "rolled back" in
                    // that case would be false.
                    boolean transactional = tx.ddlIsTransactional();

                    long startTime = System.currentTimeMillis();
                    List<Map<String, Object>> results = new ArrayList<>();
                    List<String> applied = new ArrayList<>();
                    boolean allSuccess = true;

                    for (String ddl : statements) {
                        try {
                            sqlValidator.validateDdl(ddl);
                            long stmtStart = System.currentTimeMillis();
                            tx.execute(ddl);
                            long stmtDuration = System.currentTimeMillis() - stmtStart;
                            applied.add(ddl);
                            results.add(Map.of("ddl", ddl, "success", true, "durationMs", stmtDuration));
                        } catch (RuntimeException e) {
                            allSuccess = false;
                            log.warn("DDL statement failed in batch on connection {}: {}",
                                    connectionName, ddl, e);
                            results.add(Map.of("ddl", ddl, "success", false,
                                    "error", "DDL execution failed: " + e.getMessage()));
                            // Stop on first failure — no point applying the rest of a broken batch
                            break;
                        }
                    }

                    if (!allSuccess && transactional) {
                        // The rollback belongs to the facade and only happens if this work throws,
                        // so the report rides out on the exception instead of being returned.
                        applied.clear();
                        throw new DdlBatchRolledBack(buildBatchPayload(connectionName, statements.size(),
                                applied, results, false, true, true,
                                System.currentTimeMillis() - startTime));
                    }
                    return success(buildBatchPayload(connectionName, statements.size(), applied, results,
                            allSuccess, transactional, false, System.currentTimeMillis() - startTime));
                });
            } catch (DdlBatchRolledBack e) {
                return success(e.payload());
            }
        });
    }

    /**
     * Carries the per-statement report out of a transaction that must roll back.
     *
     * <p>Extends {@link McpToolException} so the facade re-throws it unchanged rather than wrapping
     * it, keeping the payload reachable at the catch site.
     */
    private static final class DdlBatchRolledBack extends McpToolException {

        private final transient Map<String, Object> payload;

        private DdlBatchRolledBack(Map<String, Object> payload) {
            super(ErrorCode.QUERY_EXECUTION_FAILED, "DDL batch rolled back");
            this.payload = payload;
        }

        private Map<String, Object> payload() {
            return payload;
        }
    }

    private static Map<String, Object> buildBatchPayload(String connectionName, int totalStatements,
                                                         List<String> applied, List<Map<String, Object>> results,
                                                         boolean allSuccess, boolean transactional,
                                                         boolean rolledBack, long totalDuration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connectionName", connectionName);
        payload.put("totalStatements", totalStatements);
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
        return payload;
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
                } catch (RuntimeException e) {
                    // A dry run reports every statement's verdict, so a rejected statement is a
                    // result rather than a failure. The reason stays in the log because the response
                    // deliberately does not echo validator internals back to the caller.
                    allValid = false;
                    log.debug("DDL validation rejected statement: {}", e.getMessage());
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
