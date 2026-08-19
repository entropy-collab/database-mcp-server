/*
 * Copyright 2024-2026 Entropy Pty Ltd.
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
package com.entropy.database.mcp.tools.read;

import com.entropy.database.mcp.facade.DatabaseFacade;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.util.QueryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Data export tools for database results.
 */
@Configuration
public class ReadExportTools {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_EXPORT_ROWS = 500;

    private final DatabaseFacade databaseFacade;
    private final SqlValidator sqlValidator;
    private final int maxRows;

    public ReadExportTools(DatabaseFacade databaseFacade,
                           SqlValidator sqlValidator,
                           @Value("${entropy.mcp.database.query.max-rows:100}") int maxRows) {
        this.databaseFacade = databaseFacade;
        this.sqlValidator = sqlValidator;
        this.maxRows = maxRows;
    }

    /**
     * Export query results to CSV format.
     */
    @McpTool(description = "Execute a query and export results to CSV format (max " + MAX_EXPORT_ROWS + " rows)")
    public String exportCsv(
            @McpToolParam(description = "SQL query to execute") String sql,
            @McpToolParam(description = "Maximum number of rows (capped at " + MAX_EXPORT_ROWS + ")") int maxRows) {
        sqlValidator.validateSelect(sql);
        int limit = Math.min(maxRows, Math.min(this.maxRows, MAX_EXPORT_ROWS));
        var result = databaseFacade.executeQuery(sql, limit, null);
        return QueryUtils.toCsv(result.rows(), result.columns());
    }

    /**
     * Export query results to JSON format.
     */
    @McpTool(description = "Execute a query and export results to JSON format (max " + MAX_EXPORT_ROWS + " rows)")
    public String exportJson(
            @McpToolParam(description = "SQL query to execute") String sql,
            @McpToolParam(description = "Maximum number of rows (capped at " + MAX_EXPORT_ROWS + ")") int maxRows) {
        sqlValidator.validateSelect(sql);
        int limit = Math.min(maxRows, Math.min(this.maxRows, MAX_EXPORT_ROWS));
        var result = databaseFacade.executeQuery(sql, limit, null);
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                "columns", result.columns(),
                "rows", result.rows(),
                "rowCount", result.rows().size()
            ));
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}
