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

import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.config.QueryConfig;
import com.entropy.database.mcp.util.ConnectionUtils;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.util.QueryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static com.entropy.database.mcp.tools.McpToolUtils.errorResponse;
import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;

/**
 * Core read-only query tools.
 */
@Configuration
public class QueryTools {

    private static final Logger log = LoggerFactory.getLogger(QueryTools.class);

    private static final Map<String, String> TEMPLATES = Map.of(
            "query_by_id", "SELECT * FROM {table} WHERE {idColumn} = :id",
            "list_by_page", "SELECT * FROM {table} LIMIT :limit OFFSET :offset",
            "count_by_condition", "SELECT COUNT(*) FROM {table} WHERE {condition}"
    );

    private final RoutingDatabaseFacade routingFacade;
    private final SqlValidator sqlValidator;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final int maxExportRows;

    public QueryTools(RoutingDatabaseFacade routingFacade,
                      SqlValidator sqlValidator,
                      NamedParameterJdbcTemplate namedParameterJdbcTemplate,
                      QueryConfig queryConfig) {
        this.routingFacade = routingFacade;
        this.sqlValidator = sqlValidator;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.maxExportRows = queryConfig != null ? queryConfig.maxExportRows() : 500;
    }

    private ConnectionProperties parseConnection(String connectionJson) {
        return ConnectionUtils.parseConnection(connectionJson);
    }

    @McpTool(description = "Execute a SQL SELECT query with pagination support")
    public Map<String, Object> executeQuery(
            @McpToolParam(description = "SQL query to execute") String sql,
            @McpToolParam(description = "Maximum number of rows to return") int maxRows,
            @McpToolParam(description = "Continuation token for pagination. Omit or pass empty string for first page.") String continuationToken,
            @McpToolParam(description = "Optional BYOK connection JSON (jdbcUrl, username, password, dialect). Omit to use primary datasource.", required = false) String connection) {
        try {
            log.debug("executeQuery called: sql={}, maxRows={}, token={}, connection={}", sql, maxRows, continuationToken, connection);
            ConnectionProperties cp = parseConnection(connection);
            var result = routingFacade.executeQuery(sql, maxRows, continuationToken, cp);
            List<Map<String, Object>> safeRows = result.rows().stream()
                    .map(row -> {
                        Map<String, Object> safeRow = new java.util.HashMap<>();
                        for (Map.Entry<String, Object> entry : row.entrySet()) {
                            safeRow.put(entry.getKey(), QueryUtils.convertToSerializable(entry.getValue()));
                        }
                        return safeRow;
                    })
                    .toList();
            return successResponse(Map.of(
                    "columns", result.columns(),
                    "rows", safeRows,
                    "rowCount", safeRows.size(),
                    "hasMore", result.hasMore(),
                    "continuationToken", result.continuationToken()
            ));
        } catch (Exception e) {
            log.warn("executeQuery failed: {}", e.getMessage());
            return errorResponse(Map.of("sql", sql, "maxRows", maxRows, "connection", connection),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Get database connection information including product name and version")
    public Map<String, Object> getDatabaseInfo(
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.getDatabaseInfo(parseConnection(connection));
    }

    @McpTool(description = "Execute multiple SQL queries in batch mode (max 5 queries)")
    public List<Map<String, Object>> batchQuery(
            @McpToolParam(description = "List of SQL queries (max 5)") List<String> sqls,
            @McpToolParam(description = "Maximum rows per query") int maxRows,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) throws Exception {
        if (sqls == null || sqls.size() > 5) {
            return List.of(errorResponse(Map.of("sqls", sqls, "maxRows", maxRows, "connection", connection),
                    "batchQuery accepts at most 5 queries", "ValidationException"));
        }
        ConnectionProperties cp = parseConnection(connection);
        return sqls.stream()
                .map(sql -> {
                    try {
                        var result = routingFacade.executeQuery(sql, maxRows, null, cp);
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
                        return errorResponse(Map.of("sql", sql), e.getMessage(), e.getClass().getSimpleName());
                    }
                })
                .toList();
    }

    @McpTool(name = "executeSqlTemplate", description = "Execute a parameterized SQL template safely")
    public Map<String, Object> executeSqlTemplate(
            @McpToolParam(description = "Template name: query_by_id, list_by_page, or count_by_condition") String templateName,
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Parameters as key-value pairs") Map<String, Object> params) throws Exception {
        String template = TEMPLATES.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + templateName
                    + ". Available templates: " + TEMPLATES.keySet());
        }

        String sql = template.replace("{table}", table);
        Map<String, Object> boundParams = new java.util.HashMap<>(params != null ? params : Map.of());

        if (templateName.equals("query_by_id")) {
            sql = sql.replace("{idColumn}", (String) boundParams.getOrDefault("idColumn", "id"));
        } else if (templateName.equals("count_by_condition")) {
            sql = sql.replace("{condition}", (String) boundParams.getOrDefault("condition", "1=1"));
        }

        log.debug("executeSqlTemplate: template={}, table={}, params={}", templateName, table, boundParams);
        sqlValidator.validateSelect(sql);

        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(sql, boundParams);
        return successResponse(Map.of("rows", rows, "rowCount", rows.size()));
    }
}
