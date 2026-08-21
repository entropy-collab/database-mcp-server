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
import com.entropy.database.mcp.facade.DatabaseFacade;
import com.entropy.database.mcp.util.QueryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static com.entropy.database.mcp.tools.McpToolUtils.errorResponse;

/**
 * Export tools for CSV and JSON output.
 */
@Configuration
public class ExportTools {

    private static final Logger log = LoggerFactory.getLogger(ExportTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_EXPORT_LIMIT = 10000;

    private final DatabaseFacade databaseFacade;
    private final int maxExportRows;

    public ExportTools(DatabaseFacade databaseFacade,
                       QueryConfig queryConfig) {
        this.databaseFacade = databaseFacade;
        this.maxExportRows = queryConfig != null ? queryConfig.maxExportRows() : 500;
    }

    private int computeExportLimit(int maxRows) {
        return Math.min(maxRows, Math.min(MAX_EXPORT_LIMIT, maxExportRows));
    }

    @McpTool(description = "Execute a query and export results to CSV format")
    public String exportCsv(
            @McpToolParam(description = "SQL query to execute") String sql,
            @McpToolParam(description = "Maximum number of rows") int maxRows) {
        int limit = computeExportLimit(maxRows);
        var result = databaseFacade.executeQuery(sql, limit, null);
        return QueryUtils.toCsv(result.rows(), result.columns());
    }

    @McpTool(description = "Execute a query and export results to JSON format")
    public String exportJson(
            @McpToolParam(description = "SQL query to execute") String sql,
            @McpToolParam(description = "Maximum number of rows") int maxRows) {
        int limit = computeExportLimit(maxRows);
        var result = databaseFacade.executeQuery(sql, limit, null);
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "columns", result.columns(),
                    "rows", result.rows(),
                    "rowCount", result.rows().size()
            ));
        } catch (Exception e) {
            log.warn("exportJson failed: {}", e.getMessage());
            try {
                return OBJECT_MAPPER.writeValueAsString(errorResponse("Export to JSON failed", e.getMessage()));
            } catch (Exception ex) {
                return "{\"error\": \"Export to JSON failed\", \"cause\": \"" + e.getMessage() + "\"}";
            }
        }
    }
}
