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
import com.entropy.database.mcp.facade.DatabaseOperations;
import com.entropy.database.mcp.properties.QueryConfig;
import com.entropy.database.mcp.util.QueryUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
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

    private final DatabaseOperations routingFacade;
    private final int maxExportRows;

    public ExportTools(DatabaseOperations routingFacade, QueryConfig queryConfig) {
        this.routingFacade = routingFacade;
        this.maxExportRows = queryConfig != null ? queryConfig.maxExportRows() : 500;
    }

    private int computeExportLimit(int maxRows) {
        return Math.min(maxRows, Math.min(MAX_EXPORT_LIMIT, maxExportRows));
    }

    @McpTool(description = """
            【导出 CSV 文本】执行只读查询并把结果渲染为 CSV 文本返回。
            前置条件：先调用 createNamedConnection 注册连接；sql 不能为空，否则报参数校验失败。
            返回形态：返回值是 CSV 纯文本字符串，不是结构化对象——不要按字段名取值。第一行是列名表头，之后每行一条记录；空值渲染为空串；值中含逗号、双引号或换行时用双引号包裹并把内部双引号转义为两个双引号。结果为空时只返回表头行。
            行数上限：实际导出行数取 maxRows、10000 与配置项 entropy.mcp.database.query.max-export-rows（默认 500）三者中的最小值，超出部分被静默丢弃，不会提示被截断。
            使用场景：需要把查询结果交给表格工具或直接粘贴成表格。
            不要用于：需要程序化读取字段的场景（用 executeQuery，返回 columns 与 rows）；需要分页续传取全量数据（用 executeQuery 的 continuationToken）；需要嵌套结构（用 exportJson）。
            标签：[read, export, csv, query]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public String exportCsv(
            @McpToolParam(description = "要执行的只读 SQL SELECT 语句，必填且不能为空白") String sql,
            @McpToolParam(description = "期望的最大行数，必填整数；最终仍会被服务端导出上限（默认 500）压低") int maxRows,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (sql == null || sql.isBlank()) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "sql must not be blank");
        }
        int limit = computeExportLimit(maxRows);
        var result = routingFacade.executeQuery(sql, limit, null, connection);
        return QueryUtils.toCsv(result.rows(), result.columns());
    }

    @McpTool(description = """
            【导出 JSON 字符串】执行只读查询并把结果序列化为 JSON 字符串返回。
            前置条件：先调用 createNamedConnection 注册连接；sql 不能为空，否则报参数校验失败。
            返回形态：返回值是一段 JSON 文本字符串，不是已解析的对象——需要自行解析。JSON 顶层含三个键：columns（列名数组）、rows（记录数组）、rowCount（本次导出的行数）。序列化失败时报系统错误。
            行数上限：实际导出行数取 maxRows、10000 与配置项 entropy.mcp.database.query.max-export-rows（默认 500）三者中的最小值，超出部分被静默丢弃，不会提示被截断。
            使用场景：需要把结果整体交给外部系统或落盘为 JSON 文件。
            不要用于：想直接拿到结构化 rows 做后续处理（用 executeQuery，返回的是对象而非字符串）；需要分页续传取全量数据（用 executeQuery 的 continuationToken）；需要表格文本（用 exportCsv）。
            标签：[read, export, json, query]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public String exportJson(
            @McpToolParam(description = "要执行的只读 SQL SELECT 语句，必填且不能为空白") String sql,
            @McpToolParam(description = "期望的最大行数，必填整数；最终仍会被服务端导出上限（默认 500）压低") int maxRows,
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
        } catch (JsonProcessingException e) {
            // Serialisation is the only failure this block can still own; the query above already
            // reports its own errors. Narrowed because a checked exception is the only reason a
            // catch is needed here at all — safeExecute cannot wrap a String-returning tool.
            log.warn("exportJson serialisation failed for {} columns: {}", result.columns().size(), e.getMessage(), e);
            throw new McpToolException(ErrorCode.SYSTEM_ERROR, "JSON export failed", e);
        }
    }
}
