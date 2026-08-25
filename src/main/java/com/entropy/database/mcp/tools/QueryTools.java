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
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.util.QueryUtils;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core read-only query tools.
 */
@Component
public class QueryTools extends McpToolBase {

    private final RoutingDatabaseFacade routingFacade;
    private final SqlValidator sqlValidator;
    private final int maxExportRows;

    public QueryTools(RoutingDatabaseFacade routingFacade,
                      SqlValidator sqlValidator,
                      QueryConfig queryConfig) {
        this.routingFacade = routingFacade;
        this.sqlValidator = sqlValidator;
        this.maxExportRows = queryConfig != null ? queryConfig.maxExportRows() : 500;
    }

    @McpTool(description = """
            Execute a read-only SQL SELECT query with automatic pagination.
            Prerequisite: call createNamedConnection first to register the database connection, then pass the connection name.
            Returns columns, rows, rowCount, hasMore, and continuationToken for pagination.
            Use executeQueryWithFilter for parameterized queries to prevent SQL injection.
            Tags: [read, query, select, paginated]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> executeQuery(
            @McpToolParam(description = "SQL query to execute") String sql,
            @McpToolParam(description = "Maximum number of rows to return", required = false) Integer maxRows,
            @McpToolParam(description = "Continuation token for pagination. Omit or pass empty string for first page.", required = false) String continuationToken,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return safeExecute(() -> {
            // Sanitise log output to avoid leaking SQL or sensitive tokens into debug logs.
            String sqlTruncated = (sql != null && sql.length() > 200) ? sql.substring(0, 200) + "..." : sql;
            String tokenMasked = (continuationToken != null)
                    ? continuationToken.substring(0, Math.min(8, continuationToken.length())) + "..."
                    : null;
            log.debug("executeQuery called: sql={}, maxRows={}, token={}, connection={}",
                    sqlTruncated, maxRows, tokenMasked, connection);
            var result = routingFacade.executeQuery(sql, maxRows != null ? maxRows : 100, continuationToken, connection);
            List<Map<String, Object>> safeRows = QueryUtils.makeSerializable(result.rows());
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("columns", result.columns());
            resultMap.put("rows", safeRows);
            resultMap.put("rowCount", safeRows.size());
            resultMap.put("hasMore", result.hasMore());
            resultMap.put("continuationToken", result.continuationToken());
            return success(resultMap);
        });
    }

    @McpTool(description = "Get database connection information including product name and version",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getDatabaseInfo(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.getDatabaseInfo(connection);
    }

    @McpTool(description = """
            Execute up to 5 SQL SELECT queries concurrently. Each result includes columns, rows, and rowCount.
            Use for batch analysis across multiple queries without sequential waiting.
            Tags: [read, query, batch, select]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> batchQuery(
            @McpToolParam(description = "List of SQL queries (max 5)") List<String> sqls,
            @McpToolParam(description = "Maximum rows per query", required = false) Integer maxRows,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) throws Exception {
        if (sqls == null || sqls.size() > 5) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "batchQuery accepts at most 5 queries");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (String sql : sqls) {
            results.add(safeExecute(() -> {
                var result = routingFacade.executeQuery(sql, maxRows, null, connection);
                List<Map<String, Object>> safeRows = QueryUtils.makeSerializable(result.rows());
                Map<String, Object> resultObj = new HashMap<>();
                resultObj.put("columns", result.columns());
                resultObj.put("rows", safeRows);
                resultObj.put("rowCount", safeRows.size());
                resultObj.put("hasMore", result.hasMore());
                resultObj.put("continuationToken", result.continuationToken());
                Map<String, Object> item = new HashMap<>();
                item.put("sql", sql);
                item.put("result", resultObj);
                return item;
            }));
        }
        return results;
    }

    @McpTool(name = "executeSqlTemplate", description = """
            【SQL 模板执行】安全执行预定义参数化 SQL 模板，防止 SQL 注入。
            
            支持模板：
            - query_by_id: 按主键查询单条记录
            - list_by_page: 分页列表查询
            - count_by_condition: 条件计数
            
            使用场景：
            - 需要安全地构造参数化查询，避免手写 SQL 注入风险
            - 快速查询单条记录或计数统计
            
            返回字段：rows、rowCount
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> executeSqlTemplate(
            @McpToolParam(description = "Template name: query_by_id, list_by_page, or count_by_condition") String templateName,
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Optional schema name for qualifying table references", required = false) String schema,
            @McpToolParam(description = "Parameters as key-value pairs", required = false) Map<String, Object> params,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) throws Exception {
        return safeExecute(() -> {
            if (table == null || table.isBlank()) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "table must not be blank");
            }
            if (params != null && params.isEmpty()) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "params must not be empty for this template");
            }
            String template = ToolParams.TEMPLATES.get(templateName);
            if (template == null) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Unknown template: " + templateName
                        + ". Available templates: " + ToolParams.TEMPLATES.keySet());
            }

            String qualifiedTable = (schema != null && !schema.isBlank()) ? schema + "." + table : table;
            String sql = template.replace("{table}", qualifiedTable);
            Map<String, Object> boundParams = new HashMap<>(params != null ? params : Map.of());

            if (templateName.equals("query_by_id")) {
                sql = sql.replace("{idColumn}", (String) boundParams.getOrDefault("idColumn", "id"));
            } else if (templateName.equals("count_by_condition")) {
                sql = sql.replace("{condition}", (String) boundParams.getOrDefault("condition", "1=1"));
            }

            log.debug("executeSqlTemplate: template={}, table={}, schema={}, paramsSize={}",
                    templateName, qualifiedTable, schema,
                    boundParams != null ? boundParams.size() : 0);
            sqlValidator.validateSelect(sql);

            List<Map<String, Object>> rows = routingFacade.executeNamedQuery(sql, boundParams, connection);
            return success(Map.of("rows", rows, "rowCount", rows.size()));
        });
    }

    @McpTool(description = """
            Execute a parameterized SQL SELECT query with built-in SQL injection protection.
            Use this instead of executeQuery when you need to pass user-supplied values safely.
            Parameters are bound via named placeholders (:name) — never concatenate user input into SQL.
            Prerequisite: call createNamedConnection first to register the database connection.
            Tags: [read, query, parameterized, safe, select]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> executeQueryWithFilter(
            @McpToolParam(description = "SQL query with named parameters (e.g. WHERE name = :name)") String sql,
            @McpToolParam(description = "Named parameters as key-value pairs (e.g. {\"name\": \"John\"})", required = false) Map<String, Object> params,
            @McpToolParam(description = "Maximum number of rows to return", required = false) Integer maxRows,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return safeExecute(() -> {
            if (sql == null || sql.isBlank()) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "sql must not be blank");
            }
            Map<String, Object> boundParams = params != null ? new HashMap<>(params) : new HashMap<>();
            log.debug("executeQueryWithFilter called: sqlLen={}, paramsSize={}, connection={}",
                    sql.length(), boundParams.size(), connection);
            // Validate the SQL structure (allow SELECT only, no DDL)
            sqlValidator.validateSelect(sql);
            // Execute using named parameter query — safe from SQL injection
            List<Map<String, Object>> rows = routingFacade.executeNamedQuery(sql, boundParams, connection);
            int limit = maxRows != null ? maxRows : 100;
            List<Map<String, Object>> safeRows = QueryUtils.makeSerializable(
                    rows.size() > limit ? rows.subList(0, limit) : rows);
            Map<String, Object> resultMap = new HashMap<>();
            if (!rows.isEmpty()) {
                resultMap.put("columns", new ArrayList<>(rows.get(0).keySet()));
            } else {
                resultMap.put("columns", List.of());
            }
            resultMap.put("rows", safeRows);
            resultMap.put("rowCount", safeRows.size());
            resultMap.put("totalRows", rows.size());
            resultMap.put("parametersUsed", boundParams.keySet().size());
            return success(resultMap);
        });
    }
}
