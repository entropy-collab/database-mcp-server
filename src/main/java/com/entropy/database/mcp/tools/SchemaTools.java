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
            【列出表】列出指定 Schema 下所有数据表及其行数估算。
            
            使用场景：
            - 探索新数据库的表结构
            - 构建复杂查询前了解可用数据源
            - 结合 describeTable 查看具体字段信息
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listTables(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listTables(schema, connection);
    }

    @McpTool(description = """
            【搜索表】按关键词跨所有 Schema 搜索数据表。
            
            使用场景：
            - 不确定表名精确拼写时，用关键词模糊搜索
            - 快速定位目标业务表的所属 Schema
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> searchTables(
            @McpToolParam(description = "搜索关键词（可选，空则返回全部）") String keyword,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.searchTables(keyword, connection);
    }

    @McpTool(description = """
            List all schema (user) names in the database.
            Use this to discover available schemas before querying tables within them.
            Tags: [schema, metadata, list]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<String> listSchemas(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listSchemas(connection);
    }

    @McpTool(description = """
            【表结构描述】查看指定表的列名、数据类型、是否可空等元数据。
            
            使用场景：
            - 构建查询前了解表结构（字段名、类型、约束）
            - 数据迁移时确认字段映射关系
            - 配合 listTables 使用：先列表，再描述具体表
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> describeTable(
            @McpToolParam(description = "表名（必填，如 TBL_STL_TXN_DTL_202405）", required = true) String table,
            @McpToolParam(description = "Schema 名（可选，不填则用 PUBLIC）", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        validateRequired(table, "table");
        return routingFacade.describeTable(table, schema, connection);
    }

    @McpTool(description = """
            【列出索引】查看指定表的所有索引及其列组合信息。
            
            使用场景：
            - 分析查询性能时检查是否存在命中索引
            - 识别缺失索引（对比 explainPlan 警告）
            - 数据建模时评估索引设计合理性
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listIndexes(
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Schema name", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listIndexes(table, schema, connection);
    }

    @McpTool(description = """
            【列出视图】查看指定 Schema 下所有视图及其 SQL 定义。
            
            使用场景：
            - 探索 Schema 时了解哪些视图可用
            - 阅读视图定义辅助理解业务逻辑
            - 结合查询工具通过视图简化复杂 JOIN
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listViews(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listViews(schema, connection);
    }

    @McpTool(description = "List all sequences in the current schema",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listSequences(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listSequences(schema, connection);
    }

    @McpTool(name = "describe", description = """
            Describe any database object: TABLE, SCHEMA, INDEX, or VIEW in one call.
            Pass type=TABEL with table name to get columns and types.
            Pass type=SCHEMA with schema name to list all tables.
            Pass type=INDEX with index name to get index details.
            Pass type=VIEW with view name to get definition.
            Tags: [describe, metadata, introspect]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> describe(
            @McpToolParam(description = "Object type: TABLE, SCHEMA, INDEX, or VIEW") String type,
            @McpToolParam(description = "Object name (required for TABLE, INDEX)") String name,
            @McpToolParam(description = "Schema name", required = false) String schema,
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
