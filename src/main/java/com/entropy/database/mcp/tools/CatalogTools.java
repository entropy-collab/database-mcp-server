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

import com.entropy.database.mcp.catalog.*;
import com.entropy.database.mcp.catalog.DataCatalogService.ClassifiedColumn;
import com.entropy.database.mcp.properties.CatalogProperties;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Data catalog and metadata management tools.
 * Extends {@link McpToolBase} to inherit uniform exception handling, parameter validation,
 * and response building.
 */
@Component
public class CatalogTools extends McpToolBase {

    private final DataCatalogService catalogService;
    private final CatalogProperties props;

    public CatalogTools(DataCatalogService catalogService, CatalogProperties props) {
        this.catalogService = catalogService;
        this.props = props;
    }

    @McpTool(description = "生成指定表的完整数据目录：表注释、字段注释、行数、大小、敏感字段分类")
    public Map<String, Object> generateCatalog(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "表名") String tableName) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            validateRequired(tableName, "tableName");

            DataCatalogEntry entry = catalogService.generateCatalog(tableName, connection);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connection", entry.connection());
            result.put("schema", entry.schema());
            result.put("tableName", entry.tableName());
            result.put("tableComment", entry.tableComment());
            result.put("rowCount", entry.rowCount());
            result.put("tableSizeMb", entry.tableSizeMb());
            result.put("category", entry.overallCategory().getZh() + " (" + entry.overallCategory().getEn() + ")");
            result.put("maxSensitivity", entry.maxSensitivity().getZh() + " (" + entry.maxSensitivity().getEn() + ")");
            result.put("hasSensitiveColumns", entry.hasSensitiveColumns());
            result.put("keywords", entry.keywords());
            result.put("description", entry.description());
            result.put("columnCount", entry.columns().size());
            result.put("columns", formatColumns(entry.columns()));
            return success(result);
        });
    }

    @McpTool(description = "扫描指定 Schema 下所有表的元数据目录，批量返回每表的注释、字段数、最大敏感级别")
    public Map<String, Object> scanSchema(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "Schema 名称，留空使用默认 schema") String schema) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");

            List<DataCatalogEntry> entries = catalogService.scanSchema(schema, connection);
            int sensitiveCount = (int) entries.stream().filter(DataCatalogEntry::hasSensitiveColumns).count();

            Map<String, Object> result = context("connection", connection, "schema", schema);
            result.put("totalTables", entries.size());
            result.put("sensitiveTableCount", sensitiveCount);
            result.put("entries", entries.stream()
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("tableName", e.tableName());
                        m.put("tableComment", e.tableComment());
                        m.put("rowCount", e.rowCount());
                        m.put("category", e.overallCategory().getEn());
                        m.put("maxSensitivity", e.maxSensitivity().getEn());
                        m.put("hasSensitiveColumns", e.hasSensitiveColumns());
                        m.put("columnCount", e.columns().size());
                        return m;
                    })
                    .collect(Collectors.toList()));
            return success(result);
        });
    }

    @McpTool(description = "按关键词搜索数据资产（表名和注释模糊匹配），返回匹配的表及其分类信息")
    public Map<String, Object> searchAssets(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "搜索关键词（表名或注释中包含该词）") String keyword,
            @McpToolParam(description = "最多返回条数") Integer limit) {
        return safeExecute(() -> {
            validateRequired(keyword, "keyword");

            int maxResults = (limit != null && limit > 0) ? limit : props.maxSearchResults();
            List<DataCatalogEntry> entries = catalogService.searchAssets(keyword, connection);
            entries = entries.stream().limit(maxResults).collect(Collectors.toList());

            Map<String, Object> result = context("connection", connection, "keyword", keyword);
            result.put("resultCount", entries.size());
            result.put("maxResults", maxResults);
            result.put("assets", entries.stream()
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("tableName", e.tableName());
                        m.put("tableComment", e.tableComment());
                        m.put("rowCount", e.rowCount());
                        m.put("category", e.overallCategory().getEn());
                        m.put("maxSensitivity", e.maxSensitivity().getEn());
                        return m;
                    })
                    .collect(Collectors.toList()));
            return success(result);
        });
    }

    @McpTool(description = "对单个字段进行数据分类分级：识别敏感级别（PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED/HIGHLY_SENSITIVE）和业务分类")
    public Map<String, Object> classifyColumn(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "字段名（如 customer_id）") String columnName,
            @McpToolParam(description = "字段注释（可选，有助于更准确分类）") String columnComment) {
        return safeExecute(() -> {
            ClassifiedColumn result = catalogService.classifyColumn(columnName, columnComment);

            Map<String, Object> r = context("columnName", result.columnName(), "connection", connection);
            r.put("sensitivityLevel", result.sensitivity().getLevel());
            r.put("sensitivityZh", result.sensitivity().getZh());
            r.put("sensitivityEn", result.sensitivity().getEn());
            r.put("category", result.category().getZh() + " (" + result.category().getEn() + ")");
            r.put("suggestion", result.suggestion());
            return success(r);
        });
    }

    @McpTool(description = "列出指定 Schema 下所有高敏感级别字段（CONFIDENTIAL 及以上），便于合规审查")
    public Map<String, Object> listSensitiveColumns(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "Schema 名称") String schema) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");

            List<DataElement> sensitiveCols = catalogService.getSensitiveColumns(schema, connection);

            Map<String, Object> result = context("connection", connection, "schema", schema);
            result.put("sensitiveColumnCount", sensitiveCols.size());
            result.put("columns", sensitiveCols.stream()
                    .filter(c -> c.sensitivityLevel().getLevel() >= 2)
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("table", c.tableName());
                        m.put("column", c.columnName());
                        m.put("dataType", c.dataType());
                        m.put("comment", c.columnComment());
                        m.put("sensitivity", c.sensitivityLevel().getEn());
                        m.put("category", c.detectedCategory());
                        m.put("suggestedClassification", c.suggestedClassification());
                        return m;
                    })
                    .collect(Collectors.toList()));
            return success(result);
        });
    }

    @McpTool(description = "查看数据目录服务配置参数")
    public Map<String, Object> getCatalogConfig() {
        return success(Map.of(
                "enabled", props.enabled(),
                "autoGenerateComments", props.autoGenerateComments(),
                "enableSensitiveDetection", props.enableSensitiveDetection(),
                "maxSearchResults", props.maxSearchResults()
        ));
    }

    // ─── Private Helpers ──────────────────────────────────────────────────

    private List<Map<String, Object>> formatColumns(List<DataElement> columns) {
        return columns.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.columnName());
            m.put("dataType", c.dataType());
            m.put("nullable", c.nullable() == 1 ? "YES" : "NO");
            m.put("comment", c.columnComment());
            m.put("sensitivity", c.sensitivityLevel().getEn());
            m.put("category", c.detectedCategory());
            m.put("suggestion", c.suggestedClassification());
            return m;
        }).collect(Collectors.toList());
    }
}
