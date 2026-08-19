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
package com.entropy.database.mcp.tools.read;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.entropy.database.mcp.facade.DatabaseFacade;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.stream.SseStreamManager;
import com.entropy.database.mcp.util.QueryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Read-only query tools for database operations.
 */
@Configuration
public class ReadQueryTools {

    private static final Logger log = LoggerFactory.getLogger(ReadQueryTools.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final DatabaseFacade databaseFacade;
    private final SqlValidator sqlValidator;
    private final SseStreamManager sseStreamManager;

    public ReadQueryTools(DatabaseFacade databaseFacade, SqlValidator sqlValidator, SseStreamManager sseStreamManager) {
        this.databaseFacade = databaseFacade;
        this.sqlValidator = sqlValidator;
        this.sseStreamManager = sseStreamManager;
    }

    /**
     * Execute a SQL SELECT query with pagination support.
     */
    @McpTool(description = "Execute a SQL SELECT query with pagination support")
    public Map<String, Object> executeQuery(
            @McpToolParam(description = "SQL query to execute") String sql,
            @McpToolParam(description = "Maximum number of rows to return") int maxRows,
            @McpToolParam(description = "Continuation token for pagination. Omit or pass empty string for first page.") String continuationToken) throws Exception {
        try {
            log.debug("executeQuery called: sql={}, maxRows={}, token={}", sql, maxRows, continuationToken);
            sqlValidator.validateSelect(sql);
            var result = databaseFacade.executeQuery(sql, maxRows, continuationToken);
            // Convert Oracle JDBC special types to standard Java types for safe serialization
            List<Map<String, Object>> safeRows = result.rows().stream()
                .map(row -> {
                    Map<String, Object> safeRow = new java.util.HashMap<>();
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        safeRow.put(entry.getKey(), QueryUtils.convertToSerializable(entry.getValue()));
                    }
                    return safeRow;
                })
                .toList();
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("columns", result.columns());
            response.put("rows", safeRows);
            response.put("rowCount", safeRows.size());
            response.put("hasMore", result.hasMore());
            response.put("continuationToken", result.continuationToken());
            log.debug("executeQuery returning {} rows", safeRows.size());
            return response;
        } catch (Exception e) {
            log.error("executeQuery error", e);
            throw e;
        }
    }

    /**
     * Get database connection information.
     */
    @McpTool(description = "Get database connection information including product name and version")
    public Map<String, Object> getDatabaseInfo() {
        return databaseFacade.getDatabaseInfo();
    }

    /**
     * Execute batch query.
     */
    @McpTool(description = "Execute multiple SQL queries in batch mode (max 5 queries)")
    public List<Map<String, Object>> batchQuery(
            @McpToolParam(description = "List of SQL queries (max 5)") List<String> sqls,
            @McpToolParam(description = "Maximum rows per query") int maxRows) throws Exception {
        if (sqls == null || sqls.size() > 5) {
            return List.of(Map.of("error", "batchQuery accepts at most 5 queries"));
        }
        return sqls.stream()
            .map(sql -> {
                try {
                    sqlValidator.validateSelect(sql);
                    var result = databaseFacade.executeQuery(sql, maxRows, null);
                    List<Map<String, Object>> safeRows = result.rows().stream()
                        .map(row -> {
                            Map<String, Object> safeRow = new java.util.HashMap<>();
                            for (Map.Entry<String, Object> entry : row.entrySet()) {
                                safeRow.put(entry.getKey(), QueryUtils.convertToSerializable(entry.getValue()));
                            }
                            return safeRow;
                        })
                        .toList();
                    Map<String, Object> resultObj = new java.util.LinkedHashMap<>();
                    resultObj.put("columns", result.columns());
                    resultObj.put("rows", safeRows);
                    resultObj.put("rowCount", safeRows.size());
                    resultObj.put("hasMore", result.hasMore());
                    resultObj.put("continuationToken", result.continuationToken());
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("sql", sql);
                    item.put("result", resultObj);
                    return item;
                } catch (Exception e) {
                    Map<String, Object> item = new java.util.HashMap<>();
                    item.put("sql", sql);
                    item.put("error", e.getMessage());
                    return item;
                }
            })
            .toList();
    }
}
