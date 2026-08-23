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
            【SQL 性能分析】综合分析 SQL 性能：执行计划、全表扫描检测、缺失索引推荐。
            
            使用场景：
            - 慢查询调优：传入有性能问题的 SQL，获取诊断报告
            - 建索引决策：基于实际查询模式推荐需要创建的索引
            - 预防性优化：上线前对复杂 SQL 进行预检
            
            返回字段：planRows、queryDurationMs、estimatedRowCount、tableSizeMb、warnings、indexRecommendations、rewriteSuggestions、actionItems
            """)
    public Map<String, Object> analyzeQuery(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "要分析的 SQL 语句") String sql) {
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

    @McpTool(description = "分析指定表的索引使用情况，推荐缺失索引（含复合索引建议）")
    public Map<String, Object> recommendIndexes(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "表名") String tableName) {
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
            【查询重写建议】基于 SQL 结构特征提供优化重写方案。
            
            常见优化模式：
            - SELECT * → 指定具体列（减少 IO）
            - OR 条件 → 转换为 IN 列表
            - NOT IN → 转换为 NOT EXISTS
            - 子查询 → 转换为 JOIN
            
            返回字段：suggestionCount、suggestions（每条含 type/description/recommendedSql）
            """)
    public Map<String, Object> suggestRewrites(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "要分析的 SQL 语句") String sql) {
        return safeExecute(() -> {
            validateRequired(sql, "sql");

            List<RewriteSuggestion> suggestions = optimizerService.suggestRewrites(sql, connection);
            return success(context("connection", connection, "sql", sql.trim(),
                    "suggestionCount", suggestions.size(), "suggestions", suggestions));
        });
    }

    // ─── Table Performance Report ────────────────────────────────────────────

    @McpTool(description = "分析指定表的整体性能：行数、大小、索引覆盖率、数据分布，输出优化建议")
    public Map<String, Object> analyzeTable(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "表名") String tableName) {
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

    @McpTool(description = "解读执行计划文本：逐行标注问题类型（全表扫描/嵌套循环/哈希连接等），输出中文摘要")
    public Map<String, Object> interpretPlan(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "执行计划文本（EXPLAIN 输出的原始文本）") String planText,
            @McpToolParam(description = "方言名称，如 oracle/mysql/postgres，默认为连接对应方言") String dialect) {
        return safeExecute(() -> {
            validateRequired(planText, "planText");

            String resolvedDialect = (dialect != null && !dialect.isBlank()) ? dialect : connection;
            String interpretation = optimizerService.interpretPlan(planText, resolvedDialect);
            return success(context("connection", connection, "dialect", resolvedDialect,
                    "interpretation", interpretation));
        });
    }

    // ─── Config ──────────────────────────────────────────────────────────────

    @McpTool(description = "查看优化器配置参数")
    public Map<String, Object> getOptimizerConfig() {
        return success(context(
                "enabled", props.enabled(),
                "maxSuggestionsPerQuery", props.maxSuggestionsPerQuery(),
                "maxIndexRecommendations", props.maxIndexRecommendations(),
                "enableCompositeIndexAnalysis", props.enableCompositeIndexAnalysis()
        ));
    }
}
