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
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Schema and metadata tools.
 */
@Component
public class SchemaTools extends McpToolBase {

    private final DatabaseOperations routingFacade;

    public SchemaTools(DatabaseOperations routingFacade) {
        this.routingFacade = routingFacade;
    }

    @McpTool(description = """
            【列出表】列出指定 Schema 下的所有数据表及行数估算。
            前置条件：已知 Schema 名；不确定时先调用 listSchemas。
            使用场景：探索新数据库、构建查询前确认可用表。
            返回字段：数组，每项含表名、表类型、行数估算。
            不要用于：表名不确定的模糊查找（用 searchTables 跨 Schema 搜索）；查看单表字段（用 describeTable）。
            标签：[read, schema, metadata, list]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listTables(
            @McpToolParam(description = "Schema 名") String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listTables(schema, connection);
    }

    @McpTool(description = """
            【搜索表】按关键词跨所有 Schema 模糊搜索数据表。
            使用场景：不确定表名精确拼写，或需要定位业务表所属的 Schema。
            返回字段：数组，每项含 Schema 名与表名。
            不要用于：已知 Schema 且要看全量表清单（用 listTables，结果更完整且含行数估算）。
            标签：[read, schema, metadata, search]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> searchTables(
            @McpToolParam(description = "搜索关键词，支持部分匹配；传空则返回全部表") String keyword,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.searchTables(keyword, connection);
    }

    @McpTool(description = """
            【列出 Schema】列出数据库中的所有 Schema（Oracle 中即用户）名称。
            使用场景：探索数据库的第一步——先拿到 Schema 清单，再用 listTables 深入。
            返回字段：Schema 名称字符串数组。
            标签：[read, schema, metadata, list]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<String> listSchemas(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listSchemas(connection);
    }

    @McpTool(description = """
            【查看表结构】查看指定表的列名、数据类型、是否可空等元数据。
            前置条件：已知表名；不确定时先用 listTables 或 searchTables 定位。
            使用场景：写 SQL 前确认字段名与类型、数据迁移时核对字段映射。
            返回字段：表名、Schema 名、columns（每列含列名、数据类型、是否可空）。
            不要用于：查看索引（用 listIndexes）。
            标签：[read, schema, metadata, table]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> describeTable(
            @McpToolParam(description = "表名，必填（如 TBL_STL_TXN_DTL_202405）", required = true) String table,
            @McpToolParam(description = "Schema 名，省略时默认 PUBLIC", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        validateRequired(table, "table");
        return routingFacade.describeTable(table, schema, connection);
    }

    @McpTool(description = """
            【列出索引】查看指定表的所有索引及其列组合。
            使用场景：排查查询慢的原因、核对 explainPlan 中的全表扫描告警、评估索引设计。
            返回字段：数组，每项含索引名、是否唯一、索引列顺序。
            不要用于：获取索引优化建议（用 recommendIndexes）。
            标签：[read, schema, index, performance]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listIndexes(
            @McpToolParam(description = "表名") String table,
            @McpToolParam(description = "Schema 名，可省略", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listIndexes(table, schema, connection);
    }

    @McpTool(description = """
            【列出视图】查看指定 Schema 下的所有视图及其 SQL 定义。
            使用场景：探索 Schema 时了解可用视图、通过阅读视图定义理解业务口径。
            返回字段：数组，每项含视图名与视图 SQL 定义。
            标签：[read, schema, view, metadata]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listViews(
            @McpToolParam(description = "Schema 名") String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listViews(schema, connection);
    }

    @McpTool(description = """
            【列出序列】查看指定 Schema 下的所有序列（sequence）。
            使用场景：确认主键生成器的当前值与步长、数据迁移时同步序列。
            返回字段：数组，每项含序列名、当前值、步长等属性。
            标签：[read, schema, sequence, metadata]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listSequences(
            @McpToolParam(description = "Schema 名") String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listSequences(schema, connection);
    }

    @McpTool(name = "describe", description = """
            【通用对象描述】一次调用即可描述任意数据库对象，由 type 参数决定行为。
            用法：
            - type=TABLE + name=表名：返回该表的列名与类型
            - type=SCHEMA + schema=Schema 名：返回该 Schema 下的表清单
            - type=INDEX + name=表名：返回该表的索引明细
            - type=VIEW + schema=Schema 名：返回该 Schema 下的视图定义
            使用场景：对象类型不固定、想用一个工具覆盖多种探查时。
            不要用于：已确定对象类型的场景——直连专用工具（describeTable / listTables / listIndexes / listViews）语义更清晰。
            标签：[read, describe, metadata, introspect]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> describe(
            @McpToolParam(description = "对象类型，取值：TABLE、SCHEMA、INDEX、VIEW") String type,
            @McpToolParam(description = "对象名。type=TABLE 或 INDEX 时必填（传表名）；type=SCHEMA 或 VIEW 时忽略") String name,
            @McpToolParam(description = "Schema 名。type=SCHEMA 或 VIEW 时必填", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        validateRequired(type, "type");
        return switch (type.toUpperCase()) {
            case "TABLE" -> routingFacade.describeTable(name, schema, connection);
            case "SCHEMA" -> Map.of("tables", routingFacade.listTables(schema, connection));
            case "INDEX" -> Map.of("indexes", routingFacade.listIndexes(name, schema, connection));
            case "VIEW" -> Map.of("views", routingFacade.listViews(schema, connection));
            default -> throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Unknown type: " + type);
        };
    }
}
