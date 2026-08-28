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

    @McpTool(description = """
            【生成单表数据目录】为一张表生成完整目录：表注释、行数、体积、字段清单及每个字段的敏感级别与业务分类。
            前置条件：先调用 createNamedConnection 注册数据库连接；connection 与 tableName 均必填（为空直接报错）。
            使用场景：接手一张陌生表时一次拿全语义与合规信息、编写数据字典、评估单表脱敏范围。
            返回字段：connection、schema（当前实现恒为 null）、tableName、tableComment、rowCount、tableSizeMb（取不到时为 -1）、category（表级业务分类，格式「中文 (English)」）、maxSensitivity（表内最高敏感级别，格式同上）、hasSensitiveColumns、keywords、description、columnCount、columns（数组，每项含 name、dataType、nullable（YES/NO）、comment、sensitivity、category、suggestion）。
            不要用于：整个 Schema 的批量盘点（用 scanSchema）；按关键词找表（用 searchAssets）；只判断单个字段名的敏感级别且不想查库（用 classifyColumn）；只看列名与类型（用 describeTable，更轻量）。
            标签：[read, catalog, metadata, sensitivity]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> generateCatalog(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "表名，必填；大小写不敏感（内部按方言归一化）") String tableName) {
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

    @McpTool(description = """
            【批量扫描 Schema 目录】逐表生成目录并汇总，返回该 Schema 下每张表的摘要信息。
            前置条件：先调用 createNamedConnection 注册数据库连接；connection 必填。结果只在本次调用内返回，不写入任何持久化目录表。
            使用场景：新库摸底、合规盘点前先看哪些表含敏感字段，再对重点表调用 generateCatalog 取字段级明细。
            返回字段：connection、schema、totalTables、sensitiveTableCount（含 CONFIDENTIAL 及以上字段的表数）、entries（数组，每项含 tableName、tableComment、rowCount、category、maxSensitivity、hasSensitiveColumns、columnCount）。
            注意：每张表需三次元数据往返，并发上限 4，大 Schema 耗时较长；单表失败时该表以「生成失败」占位，不中断整体扫描。
            不要用于：单表明细（用 generateCatalog，entries 里没有字段级信息）；按关键词定位表（用 searchAssets）；只要敏感字段清单（用 listSensitiveColumns）。
            标签：[read, catalog, schema, scan]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> scanSchema(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "Schema 名称；留空或省略时使用连接的默认 Schema") String schema) {
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
                    .toList());
            return success(result);
        });
    }

    @McpTool(description = """
            【检索数据资产】按关键词模糊匹配表名与表注释，返回命中表的目录摘要。
            前置条件：先调用 createNamedConnection 注册数据库连接；keyword 必填。检索直接查库元数据，无需先执行 generateCatalog 或 scanSchema。
            使用场景：只知道业务含义（如「结算」「订单」）却不知表名时定位候选表，再对候选表调用 generateCatalog 看明细。
            返回字段：connection、keyword、resultCount、maxResults（本次生效的条数上限）、assets（数组，每项含 tableName、tableComment、rowCount、category、maxSensitivity）。
            不要用于：全量盘点一个 Schema（用 scanSchema）；只按表名找表且不需要分类信息（用 searchTables，更轻量）；取字段级明细（用 generateCatalog）。
            标签：[read, catalog, search, asset]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> searchAssets(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "搜索关键词，必填；对表名与表注释做包含匹配（自动两侧加通配符）") String keyword,
            @McpToolParam(description = "最多返回条数；省略、传 null 或传 ≤0 时使用配置 entropy.mcp.database.catalog.max-search-results（默认 100）") Integer limit) {
        return safeExecute(() -> {
            validateRequired(keyword, "keyword");

            int maxResults = (limit != null && limit > 0) ? limit : props.maxSearchResults();
            List<DataCatalogEntry> entries = catalogService.searchAssets(keyword, connection);
            entries = entries.stream().limit(maxResults).toList();

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
                    .toList());
            return success(result);
        });
    }

    @McpTool(description = """
            【单字段分类分级】仅凭字段名与字段注释的命名规则判定敏感级别与业务分类，不访问数据库。
            使用场景：设计新表或评审字段命名时预判合规级别；库中还没有这个字段也能判。
            判定规则：默认 INTERNAL（内部）；命中身份证、手机号、邮箱、地址、姓名、账号等模式升为 CONFIDENTIAL（机密）；命中 salary/bank/card_number/medical 升为 RESTRICTED（受限）；命中 password/secret/pin/biometric 升为 HIGHLY_SENSITIVE（高度敏感）。业务分类取 BUSINESS、SYSTEM、CONFIG、ANALYTICS 之一。
            返回字段：columnName、connection（原样回显，不用于查库）、sensitivityLevel（数值等级：1=INTERNAL、2=CONFIDENTIAL、3=RESTRICTED、4=HIGHLY_SENSITIVE）、sensitivityZh、sensitivityEn、category（格式「中文 (English)」）、suggestion（处置建议，如脱敏或加访问控制）。
            不要用于：列出库中已有的敏感字段（用 listSensitiveColumns，它会真实扫描 Schema）；取整表字段的分类结果（用 generateCatalog）。
            标签：[read, catalog, classification, sensitivity]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> classifyColumn(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "字段名，如 customer_id；判定时会剔除非字母数字下划线字符并转小写") String columnName,
            @McpToolParam(description = "字段注释，可省略；提供后会与字段名合并参与匹配，判定更准") String columnComment) {
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

    @McpTool(description = """
            【列出敏感字段】扫描整个 Schema，列出敏感级别在 CONFIDENTIAL（机密）及以上的全部字段。
            前置条件：先调用 createNamedConnection 注册数据库连接；connection 必填。内部会完整扫描该 Schema 的所有表，开销与 scanSchema 相当。
            使用场景：合规审查、脱敏方案盘点、确认哪些表列需要加访问控制与审计。
            返回字段：connection、schema、sensitiveColumnCount、columns（数组，每项含 table、column、dataType、comment、sensitivity（英文级别名）、category、suggestedClassification 处置建议）。
            不要用于：判断一个还不存在或不想查库的字段名（用 classifyColumn）；查看单表全部字段（含非敏感字段）的分类（用 generateCatalog）。
            标签：[read, catalog, sensitivity, compliance]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listSensitiveColumns(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "Schema 名称；留空或省略时使用连接的默认 Schema") String schema) {
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
                    .toList());
            return success(result);
        });
    }

    @McpTool(description = """
            【查看数据目录配置】读取数据目录模块当前生效的配置值，无需任何参数。
            使用场景：searchAssets 返回条数被截断、或需要确认敏感检测与注释自动生成开关状态时先查配置。
            返回字段：enabled（目录模块开关）、autoGenerateComments（自动生成注释开关）、enableSensitiveDetection（敏感检测开关）、maxSearchResults（searchAssets 未传 limit 时的默认上限，默认 100）。
            注意：前三项目前仅作配置回显，当前实现未按其取值改变行为；只有 maxSearchResults 会真实影响 searchAssets。
            标签：[read, catalog, config]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
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
        }).toList();
    }
}
