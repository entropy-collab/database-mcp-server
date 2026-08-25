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

import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.facade.DatabaseOperations;
import com.entropy.database.mcp.properties.QueryConfig;
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

    private final DatabaseOperations routingFacade;
    private final SqlValidator sqlValidator;
    private final int maxExportRows;

    public QueryTools(DatabaseOperations routingFacade,
                      SqlValidator sqlValidator,
                      QueryConfig queryConfig) {
        this.routingFacade = routingFacade;
        this.sqlValidator = sqlValidator;
        this.maxExportRows = queryConfig != null ? queryConfig.maxExportRows() : 500;
    }

    @McpTool(description = """
            【执行只读查询】执行只读 SQL SELECT 查询，自动分页。
            前置条件：先调用 createNamedConnection 注册数据库连接，再把连接名传入 connection。
            使用场景：SQL 中不含外部输入值，可直接执行的查询。
            返回字段：columns、rows、rowCount、hasMore、continuationToken（用于翻页）。
            不要用于：SQL 中需要拼接用户输入值的场景，请改用 executeQueryWithFilter 做参数化查询以防注入。
            标签：[read, query, select, paginated]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> executeQuery(
            @McpToolParam(description = "要执行的 SQL SELECT 语句") String sql,
            @McpToolParam(description = "返回行数上限，省略时默认 100", required = false) Integer maxRows,
            @McpToolParam(description = "分页续传令牌。首页请省略或传空字符串；后续页传上一次返回的 continuationToken", required = false) String continuationToken,
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

    @McpTool(description = """
            【查看数据库信息】获取当前连接的数据库信息，包括产品名称与版本号。
            使用场景：需要先确认数据库类型（Oracle / MySQL / PostgreSQL 等）以便写出兼容的 SQL 方言。
            返回字段：databaseProductName、databaseProductVersion、driverName 等连接元数据。
            标签：[read, metadata, connection]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getDatabaseInfo(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.getDatabaseInfo(connection);
    }

    @McpTool(description = """
            【批量并发查询】并发执行最多 5 条只读 SQL SELECT 查询。
            前置条件：先调用 createNamedConnection 注册数据库连接。
            使用场景：多条互不依赖的查询需要一次取回，避免逐条串行等待。
            返回字段：数组，每项含 sql 与 result（result 内含 columns、rows、rowCount、hasMore、continuationToken）。
            不要用于：单条查询（用 executeQuery）；超过 5 条会直接报错。
            标签：[read, query, batch, select]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> batchQuery(
            @McpToolParam(description = "SQL SELECT 语句列表，最多 5 条") List<String> sqls,
            @McpToolParam(description = "每条查询的返回行数上限", required = false) Integer maxRows,
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
            【SQL 模板执行】按预定义模板生成并执行只读 SQL，避免手写 SQL 带来的注入风险。
            前置条件：先调用 createNamedConnection 注册数据库连接。
            支持模板：
            - select_sql：按条件查询表数据，需在 params 中提供 condition、limit
            - tables_sql：列出指定 schema 下的所有表（information_schema 语法，仅 MySQL/PostgreSQL 可用）
            - table_detail_sql：列出指定表的列名、数据类型、是否可空
            返回字段：rows、rowCount。
            不要用于：模板覆盖不到的查询（用 executeQueryWithFilter）；跨方言的表结构查询请优先用 listTables / describeTable。
            标签：[read, query, template, safe]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> executeSqlTemplate(
            @McpToolParam(description = "模板名，取值：select_sql、tables_sql、table_detail_sql") String templateName,
            @McpToolParam(description = "表名") String table,
            @McpToolParam(description = "schema 名，用于限定表引用，可省略", required = false) String schema,
            @McpToolParam(description = "模板占位符的取值，键值对形式（如 {\"condition\": \"status = 'A'\", \"limit\": 100}）", required = false) Map<String, Object> params,
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
            【参数化查询】执行参数化 SQL SELECT 查询，内置 SQL 注入防护。
            前置条件：先调用 createNamedConnection 注册数据库连接。
            使用场景：查询条件包含用户提供的值时，一律用本工具而非 executeQuery。
            参数写法：SQL 中用命名占位符 :name，实参放入 params——严禁把用户输入拼接进 SQL 字符串。
            返回字段：columns、rows、rowCount、totalRows、parametersUsed。
            不要用于：需要分页续传的大结果集（用 executeQuery 的 continuationToken）。
            标签：[read, query, parameterized, safe, select]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> executeQueryWithFilter(
            @McpToolParam(description = "带命名占位符的 SQL SELECT 语句（如 WHERE name = :name）") String sql,
            @McpToolParam(description = "命名参数的键值对（如 {\"name\": \"John\"}）", required = false) Map<String, Object> params,
            @McpToolParam(description = "返回行数上限，省略时默认 100", required = false) Integer maxRows,
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
