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

import com.entropy.database.mcp.optimizer.IndexRecommendation;
import com.entropy.database.mcp.optimizer.OptimizerService;
import com.entropy.database.mcp.optimizer.PerformanceReport;
import com.entropy.database.mcp.optimizer.RewriteSuggestion;
import com.entropy.database.mcp.properties.OptimizerProperties;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SQL performance optimization tools.
 * Provides query performance analysis, missing index recommendations,
 * query rewrite suggestions, and execution plan interpretation.
 */
@Component
public class OptimizationTools extends McpToolBase {

    private final OptimizerService optimizerService;
    private final OptimizerProperties props;

    public OptimizationTools(OptimizerService optimizerService, OptimizerProperties props) {
        this.optimizerService = optimizerService;
        this.props = props;
    }

    // ─── Query Performance Analysis ──────────────────────────────────────────

    @McpTool(description = """
            【SQL 综合性能分析】对一条 SQL 做一站式诊断：取执行计划、判风险模式、给索引与重写建议。只执行 EXPLAIN，不执行原 SQL 本体。
            前置条件：先调用 createNamedConnection 注册数据库连接；connection 与 sql 均必填。
            使用场景：慢查询调优、上线前对复杂 SQL 预检、需要一次拿全「计划 + 索引 + 重写」结论时。
            推荐顺序：assessQueryRisk 判风险 → 本工具做综合诊断；只需要原始计划文本时用 explainPlan，只需要重写建议时用 suggestRewrites。
            返回字段：connection、dialect、originalSql、tableName（SQL 中识别到的第一张表，识别不到为 null）、planRows（执行计划文本行数组，每行是该行各列用 " | " 拼接的字符串）、queryDurationMs（本次分析耗时）、estimatedRowCount 与 tableSizeMb（取不到时为 -1）、warnings、hasIssues、indexRecommendations（数组，每项含 table、column、indexType、recommendedSql、reason、priority）、rewriteSuggestions（数组，每项含 type、originalPattern、suggestedPattern、reason、transformedSql）、actionItems（可直接执行的动作清单）。
            不要用于：非 SELECT 语句；只要未经解读的原始计划（用 explainPlan）；已有计划文本要解读（用 interpretPlan）；只针对一张表而非某条 SQL 的索引盘点（用 recommendIndexes）。
            标签：[read, optimizer, performance, explain, index]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> analyzeQuery(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "要分析的 SQL 语句，必填；建议为 SELECT 语句") String sql) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            validateRequired(sql, "sql");

            PerformanceReport report = optimizerService.analyzeQuery(sql, connection);
            return success(context(
                    "connection", report.connection(),
                    "dialect", report.dialect(),
                    "originalSql", report.originalSql(),
                    "tableName", report.tableName(),
                    "planRows", report.planRows(),
                    "queryDurationMs", report.queryDurationMs(),
                    "estimatedRowCount", report.estimatedRowCount(),
                    "tableSizeMb", report.tableSizeMb(),
                    "warnings", report.warnings(),
                    "hasIssues", report.hasIssues(),
                    "indexRecommendations", report.indexRecommendations(),
                    "rewriteSuggestions", report.rewriteSuggestions(),
                    "actionItems", report.actionItems()
            ));
        });
    }

    // ─── Index Recommendations ───────────────────────────────────────────────

    @McpTool(description = """
            【索引推荐】基于表的列元数据与已有索引，推荐该表缺失的单列索引与复合索引。
            前置条件：先调用 createNamedConnection 注册数据库连接；connection 与 tableName 均必填。
            使用场景：给一张表做索引体检、建表后补索引；输入是表名而非 SQL。
            返回字段：connection、table、recommendationCount、recommendations（数组，每项含 table、column（复合索引时为「列1, 列2」）、indexType（BTREE / BITMAP / COMPOSITE）、recommendedSql（可直接执行的 CREATE INDEX 语句）、reason、priority（数字越小越优先））。
            不要用于：针对某条 SQL 的索引建议（用 analyzeQuery，它按 WHERE 条件推荐）；查看已有索引明细（用 listIndexes）；本工具只给建议，不会创建索引，需自行用 executeDdl 执行 recommendedSql。
            标签：[read, optimizer, index, recommendation]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> recommendIndexes(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "表名，必填；大小写不敏感（内部按方言归一化）") String tableName) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            validateRequired(tableName, "tableName");

            List<IndexRecommendation> recs = optimizerService.recommendIndexes(tableName, connection);
            return success(context("connection", connection, "table", tableName,
                    "recommendationCount", recs.size(), "recommendations", recs));
        });
    }

    // ─── Query Rewrite Suggestions ───────────────────────────────────────────

    @McpTool(description = """
            【SQL 重写建议】按 SQL 文本结构匹配已知反模式，给出重写方案与可用的改写后 SQL。纯静态分析，不连接数据库、不执行 SQL。
            前置条件：sql 必填；connection 仅作回显，不影响判定结果。
            使用场景：拿不到执行计划或方言不支持 EXPLAIN 时的兜底优化；批量评审 SQL 写法。
            识别的反模式（对应 type 取值）：SELECT_STAR（SELECT * 改为显式列）、IMPLICIT_CONVERSION（列与数值比较可能隐式转换导致索引失效）、LEADING_WILDCARD（LIKE '%x' 无法走 B-tree 索引）、OR_TO_IN（同列多个 OR 等值改 IN）、NOT_IN_SUBQUERY（NOT IN 子查询改 NOT EXISTS）、NO_FILTER（无 WHERE 的全表查询）、ORDER_BY_NO_WHERE（无 WHERE 的排序）。
            返回字段：connection、sql、suggestionCount、suggestions（数组，每项含 type、originalPattern、suggestedPattern、reason、transformedSql——transformedSql 为改写后的 SQL，无法安全改写时回退为原 SQL）。
            不要用于：需要执行计划支撑的诊断（用 analyzeQuery 或 explainPlan）；索引缺失判断（用 recommendIndexes）。
            标签：[read, optimizer, rewrite, sql]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> suggestRewrites(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "要分析的 SQL 语句，必填") String sql) {
        return safeExecute(() -> {
            validateRequired(sql, "sql");

            List<RewriteSuggestion> suggestions = optimizerService.suggestRewrites(sql, connection);
            return success(context("connection", connection, "sql", sql.trim(),
                    "suggestionCount", suggestions.size(), "suggestions", suggestions));
        });
    }

    // ─── Table Performance Report ────────────────────────────────────────────

    @McpTool(description = """
            【单表性能体检】只读分析一张表：估算行数与体积，给出容量告警与缺失索引建议。不修改数据库、不收集统计信息。
            前置条件：先调用 createNamedConnection 注册数据库连接；connection 与 tableName 均必填。
            使用场景：判断大表是否需要分区、索引是否明显不足、表体积是否失控。
            返回字段：connection、dialect、table、estimatedRowCount 与 tableSizeMb（取不到时为 -1）、warnings（超千万行、超 1GB、大表无索引建议等）、indexRecommendations（同 recommendIndexes 的结构）、actionItems（取前 3 条建议的 CREATE INDEX 语句）。
            不要用于：写入优化器统计信息（那是 gatherTableStats，会改数据库状态；本工具纯只读）；针对某条 SQL 的诊断（用 analyzeQuery）；查看表结构（用 describeTable）。
            标签：[read, optimizer, table, performance]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> analyzeTable(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "表名，必填；大小写不敏感（内部按方言归一化）") String tableName) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            validateRequired(tableName, "tableName");

            PerformanceReport report = optimizerService.analyzeTable(tableName, connection);
            return success(context(
                    "connection", report.connection(),
                    "dialect", report.dialect(),
                    "table", report.tableName(),
                    "estimatedRowCount", report.estimatedRowCount(),
                    "tableSizeMb", report.tableSizeMb(),
                    "warnings", report.warnings(),
                    "indexRecommendations", report.indexRecommendations(),
                    "actionItems", report.actionItems()
            ));
        });
    }

    // ─── Plan Interpretation ─────────────────────────────────────────────────

    @McpTool(description = """
            【执行计划解读】把已有的执行计划文本翻译成中文解读：逐行标注访问方式与问题，并附整体摘要。不连接数据库、不重新取计划。
            前置条件：planText 必填，需先由 explainPlan（或数据库端 EXPLAIN）取得计划文本。
            使用场景：拿到 explainPlan 的原始计划后看不懂，需要中文逐行解释与结论。
            识别的模式：全表扫描、索引范围扫描、唯一索引访问、嵌套循环连接、哈希连接、排序操作。
            返回字段：connection、dialect（实际生效的方言标识）、interpretation（多行中文文本：每行形如「[序号] 计划行 + 标注」，末尾附「解读摘要」段落）。
            不要用于：还没有计划文本的情况（先用 explainPlan 取计划，或直接用 analyzeQuery 一步到位）；索引与重写建议（用 recommendIndexes / suggestRewrites）。
            标签：[read, optimizer, plan, interpret]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> interpretPlan(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "执行计划文本，必填；即 EXPLAIN 输出的原始多行文本") String planText,
            @McpToolParam(description = "方言名称，如 oracle、mysql、postgres；留空或省略时按 connection 的取值兜底（不会自动解析连接对应方言），建议显式传入") String dialect) {
        return safeExecute(() -> {
            validateRequired(planText, "planText");

            String resolvedDialect = (dialect != null && !dialect.isBlank()) ? dialect : connection;
            String interpretation = optimizerService.interpretPlan(planText, resolvedDialect);
            return success(context("connection", connection, "dialect", resolvedDialect,
                    "interpretation", interpretation));
        });
    }

    // ─── Config ──────────────────────────────────────────────────────────────

    @McpTool(description = """
            【查看优化器配置】读取 SQL 优化器模块当前生效的配置值，无需任何参数。
            使用场景：确认优化器是否启用、建议条数与复合索引分析开关的当前取值。
            返回字段：enabled（优化器模块开关）、maxSuggestionsPerQuery（单条 SQL 建议条数上限，默认 10）、maxIndexRecommendations（索引建议条数上限，默认 5）、enableCompositeIndexAnalysis（复合索引分析开关）。
            注意：四项目前均为配置回显，当前实现未按其取值裁剪结果——analyzeQuery / recommendIndexes 返回的建议条数不受这些值限制。
            标签：[read, optimizer, config]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getOptimizerConfig() {
        return success(context(
                "enabled", props.enabled(),
                "maxSuggestionsPerQuery", props.maxSuggestionsPerQuery(),
                "maxIndexRecommendations", props.maxIndexRecommendations(),
                "enableCompositeIndexAnalysis", props.enableCompositeIndexAnalysis()
        ));
    }
}
