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
import com.entropy.database.mcp.facade.DatabaseFacade;
import com.entropy.database.mcp.tools.McpToolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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
    private final DatabaseFacade databaseFacade;
    private final DatabaseDialect primaryDialect;
    private final JdbcTemplate primaryJdbcTemplate;
    private final boolean ddlAllowed;

    public OracleSessionTools(DynamicDataSourceManager dataSourceManager,
                              DatabaseFacade databaseFacade,
                              DatabaseDialect primaryDialect,
                              org.springframework.jdbc.core.JdbcTemplate primaryJdbcTemplate,
                              @org.springframework.beans.factory.annotation.Value("${entropy.mcp.database.ddl.allowed:false}") boolean ddlAllowed) {
        this.dataSourceManager = dataSourceManager;
        this.databaseFacade = databaseFacade;
        this.primaryDialect = primaryDialect;
        this.primaryJdbcTemplate = primaryJdbcTemplate;
        this.ddlAllowed = ddlAllowed;
    }

    @McpTool(description = "Kill an Oracle database session (requires ALTER SYSTEM privilege)")
    public Map<String, Object> killSession(
            @McpToolParam(description = "Session identifier in format 'sid,serial#' (e.g. '123,4567')") String sessionId,
            @McpToolParam(description = "Kill mode: IMMEDIATE (default) or POST_TRANSACTION", required = false) String mode,
            @McpToolParam(description = "Optional BYOK connection name. Omit to use primary datasource.", required = false) String connection) {
        requireNotBlank(sessionId, "sessionId");
        String trimmed = sessionId.trim();
        if (!trimmed.matches("\\d+,\\d+")) {
            return errorResponse(Map.of("sessionId", sessionId), "sessionId must be in format 'sid,serial#' (e.g. '123,4567')", "ValidationException");
        }

        String killMode = (mode == null || mode.isBlank()) ? "IMMEDIATE" : mode.trim().toUpperCase();
        if (!killMode.equals("IMMEDIATE") && !killMode.equals("POST_TRANSACTION")) {
            return errorResponse(Map.of("mode", mode), "mode must be IMMEDIATE or POST_TRANSACTION", "ValidationException");
        }

        DatabaseDialect dialect;
        JdbcTemplate jdbcTemplate;
        if (connection == null || connection.isBlank()) {
            dialect = primaryDialect;
            jdbcTemplate = primaryJdbcTemplate;
        } else {
            ByokDataSourceContext context = dataSourceManager.acquire(connection);
            dialect = context.getDialect();
            jdbcTemplate = context.getJdbcTemplate();
        }

        String sql = dialect.killSessionSql(trimmed, killMode);
        if (sql == null) {
            return McpToolUtils.errorResponse(Map.of("sessionId", trimmed, "mode", killMode),
                    "Kill session is not supported for dialect: " + dialect.getClass().getSimpleName(),
                    dialect.getClass().getSimpleName());
        }

        try {
            if (connection == null || connection.isBlank()) {
                if (!ddlAllowed) {
                    return errorResponse(Map.of("sessionId", trimmed, "mode", killMode),
                            "DDL execution is disabled. Set entropy.mcp.database.ddl.allowed=true to enable.",
                            "ConfigurationException");
                }
                Map<String, Object> result = databaseFacade.executeDdl(sql);
                result.put("sql", sql);
                result.put("sessionId", trimmed);
                result.put("mode", killMode);
                return result;
            } else {
                long startTime = System.currentTimeMillis();
                int affected = jdbcTemplate.update(sql);
                long duration = System.currentTimeMillis() - startTime;
                return successResponse(Map.of(
                        "connectionName", connection,
                        "sql", sql,
                        "sessionId", trimmed,
                        "mode", killMode,
                        "affectedRows", affected,
                        "durationMs", duration,
                        "message", "Session killed successfully"));
            }
        } catch (Exception e) {
            return McpToolUtils.errorResponse(Map.of("sessionId", trimmed, "sql", sql, "mode", killMode),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }
}
