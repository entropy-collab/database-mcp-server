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

import com.entropy.database.mcp.config.QueryConfig;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import com.entropy.database.mcp.util.QueryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Export tools for CSV and JSON output.
 */
@Component
public class ExportTools extends McpToolBase {

    private static final int MAX_EXPORT_LIMIT = 10000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RoutingDatabaseFacade routingFacade;
    private final int maxExportRows;

    public ExportTools(RoutingDatabaseFacade routingFacade, QueryConfig queryConfig) {
        this.routingFacade = routingFacade;
        this.maxExportRows = queryConfig != null ? queryConfig.maxExportRows() : 500;
    }

    private int computeExportLimit(int maxRows) {
        return Math.min(maxRows, Math.min(MAX_EXPORT_LIMIT, maxExportRows));
    }

    @McpTool(description = "Execute a query and export results to CSV format",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public String exportCsv(
            @McpToolParam(description = "SQL query to execute") String sql,
            @McpToolParam(description = "Maximum number of rows") int maxRows,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (sql == null || sql.isBlank()) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "sql must not be blank");
        }
        int limit = computeExportLimit(maxRows);
        var result = routingFacade.executeQuery(sql, limit, null, connection);
        return QueryUtils.toCsv(result.rows(), result.columns());
    }

    @McpTool(description = "Execute a query and export results to JSON format",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public String exportJson(
            @McpToolParam(description = "SQL query to execute") String sql,
            @McpToolParam(description = "Maximum number of rows") int maxRows,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (sql == null || sql.isBlank()) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "sql must not be blank");
        }
        int limit = computeExportLimit(maxRows);
        var result = routingFacade.executeQuery(sql, limit, null, connection);
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "columns", result.columns(),
                    "rows", result.rows(),
                    "rowCount", result.rows().size()
            ));
        } catch (Exception e) {
            log.warn("exportJson failed: {}", e.getMessage(), e);
            throw new McpToolException(ErrorCode.SYSTEM_ERROR, "JSON export failed");
        }
    }
}
