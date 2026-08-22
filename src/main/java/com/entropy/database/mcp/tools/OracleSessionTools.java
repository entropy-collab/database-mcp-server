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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.entropy.database.mcp.tools.McpToolUtils.errorResponse;
import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;
import static com.entropy.database.mcp.util.ValidationUtils.requireNotBlank;

/**
 * Oracle session management tools.
 */
@Configuration
public class OracleSessionTools {

    private static final Logger log = LoggerFactory.getLogger(OracleSessionTools.class);

    private final DynamicDataSourceManager dataSourceManager;
    private final boolean ddlAllowed;

    public OracleSessionTools(DynamicDataSourceManager dataSourceManager,
                              @Value("${entropy.mcp.database.ddl.allowed:false}") boolean ddlAllowed) {
        this.dataSourceManager = dataSourceManager;
        this.ddlAllowed = ddlAllowed;
    }

    @McpTool(description = "Kill an Oracle database session (requires ALTER SYSTEM privilege)")
    public Map<String, Object> killSession(
            @McpToolParam(description = "Session identifier in format 'sid,serial#' (e.g. '123,4567')") String sessionId,
            @McpToolParam(description = "Kill mode: IMMEDIATE (default) or POST_TRANSACTION", required = false) String mode,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        requireNotBlank(sessionId, "sessionId");
        requireNotBlank(connection, "connection");
        String trimmed = sessionId.trim();
        if (!trimmed.matches("\\d+,\\d+")) {
            return errorResponse(Map.of("sessionId", sessionId), "sessionId must be in format 'sid,serial#' (e.g. '123,4567')", "ValidationException");
        }

        String killMode = (mode == null || mode.isBlank()) ? "IMMEDIATE" : mode.trim().toUpperCase();
        if (!killMode.equals("IMMEDIATE") && !killMode.equals("POST_TRANSACTION")) {
            return errorResponse(Map.of("mode", mode), "mode must be IMMEDIATE or POST_TRANSACTION", "ValidationException");
        }

        try {
            Map<String, Object> errorCtx = buildContext(trimmed, killMode, connection);
            if (!ddlAllowed) {
                return errorResponse(errorCtx,
                        "DDL execution is disabled. Set entropy.mcp.database.ddl.allowed=true to enable.",
                        "ConfigurationException");
            }

            ByokDataSourceContext context = dataSourceManager.acquire(connection);
            var dialect = context.getDialect();
            var jdbcTemplate = context.getJdbcTemplate();

            String sql = dialect.killSessionSql(trimmed, killMode);
            if (sql == null) {
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("sessionId", trimmed);
                ctx.put("mode", killMode);
                ctx.put("connection", connection);
                return errorResponse(ctx,
                        "Kill session is not supported for dialect: " + dialect.getClass().getSimpleName(),
                        dialect.getClass().getSimpleName());
            }

            long startTime = System.currentTimeMillis();
            int affected = jdbcTemplate.update(sql);
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sql", sql);
            result.put("sessionId", trimmed);
            result.put("mode", killMode);
            result.put("affectedRows", affected);
            result.put("durationMs", duration);
            result.put("connectionName", connection);
            result.put("message", "Session killed successfully");
            return successResponse(result);
        } catch (Exception e) {
            log.error("killSession failed: sessionId={}, mode={}, connection={}", trimmed, killMode, connection, e);
            return errorResponse(buildContext(trimmed, killMode, connection),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> buildContext(String sessionId, String mode, String connection) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("sessionId", sessionId);
        ctx.put("mode", mode);
        ctx.put("connection", connection);
        return ctx;
    }
}
