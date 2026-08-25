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
import com.entropy.database.mcp.facade.DatabaseAdminOperations;
import com.entropy.database.mcp.facade.DatabaseWriteOperations;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.entropy.database.mcp.util.ValidationUtils.requireNotBlank;

/**
 * Oracle session management tools.
 */
@Component
public class OracleSessionTools extends McpToolBase {

    private final DatabaseWriteOperations writeOperations;
    private final DatabaseAdminOperations adminOperations;
    private final boolean ddlAllowed;

    public OracleSessionTools(DatabaseWriteOperations writeOperations,
                              DatabaseAdminOperations adminOperations,
                              @Value("${entropy.mcp.database.ddl.allowed:false}") boolean ddlAllowed) {
        this.writeOperations = writeOperations;
        this.adminOperations = adminOperations;
        this.ddlAllowed = ddlAllowed;
    }

    @McpTool(description = "Kill an Oracle database session (requires ALTER SYSTEM privilege)",
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> killSession(
            @McpToolParam(description = "Session identifier in format 'sid,serial#' (e.g. '123,4567')") String sessionId,
            @McpToolParam(description = "Kill mode: IMMEDIATE (default) or POST_TRANSACTION", required = false) String mode,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return safeExecute(() -> {
            requireNotBlank(sessionId, "sessionId");
            requireNotBlank(connection, "connection");
            String trimmed = sessionId.trim();
            if (!trimmed.matches("\\d+,\\d+")) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "sessionId must be in format 'sid,serial#' (e.g. '123,4567') (sessionId=" + sessionId + ")");
            }

            String killMode = (mode == null || mode.isBlank()) ? "IMMEDIATE" : mode.trim().toUpperCase();
            if (!killMode.equals("IMMEDIATE") && !killMode.equals("POST_TRANSACTION")) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "mode must be IMMEDIATE or POST_TRANSACTION");
            }

            if (!ddlAllowed) {
                throw new McpToolException(ErrorCode.SQL_OPERATION_NOT_ALLOWED, "DDL execution is disabled. Set entropy.mcp.database.ddl.allowed=true to enable. (sessionId=" + trimmed + ", connection=" + connection + ")");
            }

            var dialect = adminOperations.getDialect(connection);

            String sql = dialect.killSessionSql(trimmed, killMode);
            if (sql == null) {
                throw new McpToolException(ErrorCode.KILL_SESSION_NOT_SUPPORTED, "Kill session is not supported for dialect: " + dialect.getClass().getSimpleName() + " (sessionId=" + trimmed + ", mode=" + killMode + ", connection=" + connection + ")");
            }

            long startTime = System.currentTimeMillis();
            int affected = writeOperations.executeUpdate(sql, connection);
            return success(Map.of(
                    "sql", sql, "sessionId", trimmed, "mode", killMode,
                    "affectedRows", affected, "durationMs", System.currentTimeMillis() - startTime,
                    "connectionName", connection, "message", "Session killed successfully"
            ));
        });
    }
}
