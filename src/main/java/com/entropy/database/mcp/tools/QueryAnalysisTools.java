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

import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * Query analysis tools for execution plan preview and optimization suggestions.
 * Implements Plan B: Always get execution plan before executing complex queries.
 */
@Component
public class QueryAnalysisTools {

    private final DynamicDataSourceManager dataSourceManager;
    private final DialectResolver dialectResolver;

    public QueryAnalysisTools(DynamicDataSourceManager dataSourceManager,
                              DialectResolver dialectResolver) {
        this.dataSourceManager = dataSourceManager;
        this.dialectResolver = dialectResolver;
    }

    /**
     * Get the execution plan for a SQL query without actually executing it.
     * Analyzes the plan and provides optimization suggestions.
     */
    @McpTool(description = "获取 SQL 执行计划，分析性能并返回优化建议（先计划后执行方案）")
    public Map<String, Object> explainPlan(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "要分析的 SQL 语句") String sql) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Validate inputs
            if (connection == null || connection.isBlank()) {
                return McpToolUtils.errorResponse(Map.of("connection", connection),
                        "Missing connection name", "ParameterValidationException");
            }
            if (sql == null || sql.isBlank()) {
                return McpToolUtils.errorResponse(Map.of("connection", connection),
                        "Missing SQL statement", "ParameterValidationException");
            }

            // Only allow SELECT statements
            String trimmedSql = sql.trim();
            String upperSql = trimmedSql.toUpperCase();
            if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")) {
                return McpToolUtils.errorResponse(Map.of("connection", connection),
                        "Only SELECT queries can be explained", "SecurityException");
            }

            DatabaseDialect dialect = dialectResolver.resolve(
                    getDialectName(connection), dataSourceManager.acquire(connection).getDataSource());

            // Build explain SQL
            String explainSql = buildExplainSql(dialect, trimmedSql);
            if (explainSql == null) {
                return McpToolUtils.errorResponse(Map.of("connection", connection),
                        "EXPLAIN PLAN not supported for this dialect", "NotSupportedException");
            }

            // Execute explain plan
            List<Map<String, String>> planRows = executeExplainPlan(connection, explainSql);

            // Analyze plan for issues
            List<String> warnings = analyzePlan(planRows, dialect);

            result.put("connection", connection);
            result.put("dialect", dialect.getDialectName());
            result.put("originalSql", trimmedSql);
            result.put("explainSql", explainSql);
            result.put("plan", planRows);
            result.put("warnings", warnings);
            result.put("success", true);
        } catch (Exception e) {
            return McpToolUtils.errorResponse(Map.of("connection", connection, "sql", sql),
                    "Failed to get execution plan: " + e.getMessage(),
                    e.getClass().getSimpleName());
        }

        return result;
    }

    /**
     * Quick risk assessment based on query structure and table statistics.
     * Lightweight alternative to full EXPLAIN.
     */
    @McpTool(description = "评估查询风险等级（低/中/高），判断是否需要执行计划预检")
    public Map<String, Object> assessQueryRisk(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "要评估的 SQL 语句") String sql) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            if (connection == null || connection.isBlank()) {
                return McpToolUtils.errorResponse(Map.of("connection", connection),
                        "Missing connection name", "ParameterValidationException");
            }
            if (sql == null || sql.isBlank()) {
                return McpToolUtils.errorResponse(Map.of("connection", connection),
                        "Missing SQL statement", "ParameterValidationException");
            }

            String trimmedSql = sql.trim();
            DatabaseDialect dialect = dialectResolver.resolve(
                    getDialectName(connection), dataSourceManager.acquire(connection).getDataSource());

            // Extract table names
            List<String> tableNames = extractTableNames(trimmedSql);

            // Check table sizes
            Map<String, Long> tableSizes = new LinkedHashMap<>();
            for (String tableName : tableNames) {
                Long rowCount = getTableRowCount(connection, tableName, dialect);
                if (rowCount != null) {
                    tableSizes.put(tableName, rowCount);
                }
            }

            // Calculate risk score
            int riskScore = calculateRiskScore(trimmedSql, tableSizes);
            String riskLevel = riskScore <= 2 ? "low" : riskScore <= 5 ? "medium" : "high";

            // Generate suggestions
            List<String> suggestions = generateSuggestions(riskLevel, trimmedSql, tableSizes);

            result.put("connection", connection);
            result.put("dialect", dialect.getDialectName());
            result.put("sql", trimmedSql);
            result.put("tables", tableSizes);
            result.put("riskScore", riskScore);
            result.put("riskLevel", riskLevel);
            result.put("suggestions", suggestions);
            result.put("recommendation", riskLevel.equals("high")
                    ? "建议先使用 explainPlan 工具分析执行计划"
                    : riskLevel.equals("medium")
                    ? "可以考虑使用 explainPlan 工具优化查询"
                    : "查询风险较低，可直接执行");
            result.put("success", true);
        } catch (Exception e) {
            return McpToolUtils.errorResponse(Map.of("connection", connection, "sql", sql),
                    "Failed to assess query risk: " + e.getMessage(),
                    e.getClass().getSimpleName());
        }

        return result;
    }

    // ─── Private Helper Methods ────────────────────────────────────────────

    private String getDialectName(String connection) {
        try {
            return dataSourceManager.getConnectionMetadata(connection).dialect();
        } catch (Exception e) {
            return "generic";
        }
    }

    private String buildExplainSql(DatabaseDialect dialect, String sql) {
        // Try dialect-specific explain first
        String dialectExplain = dialect.getExplainPlanSql(sql);
        if (dialectExplain != null) {
            return dialectExplain;
        }

        // Fallback to generic approaches
        return switch (dialect.getDialectName().toLowerCase()) {
            case "oracle" -> "EXPLAIN PLAN FOR " + sql;
            case "postgres", "postgresql" -> "EXPLAIN " + sql;
            case "mysql" -> "EXPLAIN " + sql;
            case "sqlserver", "mssql" -> "SET SHOWPLAN_TEXT ON; " + sql + "; SET SHOWPLAN_TEXT OFF";
            default -> null;
        };
    }

    private List<Map<String, String>> executeExplainPlan(String connection, String explainSql) throws Exception {
        List<Map<String, String>> planRows = new ArrayList<>();

        try (Connection conn = dataSourceManager.acquire(connection).getConnection()) {
            // For SQL Server, we need special handling
            if (explainSql.contains("SET SHOWPLAN")) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(explainSql)) {
                    pstmt.executeQuery();
                }
                // Read plan from special table or output
                return List.of(Map.of("note", "SQL Server execution plan captured in output"));
            }

            try (PreparedStatement pstmt = conn.prepareStatement(explainSql)) {
                try (ResultSet rs = pstmt.executeQuery()) {
                    int columns = rs.getMetaData().getColumnCount();
                    String[] columnNames = new String[columns];
                    for (int i = 0; i < columns; i++) {
                        columnNames[i] = rs.getMetaData().getColumnName(i + 1);
                    }

                    while (rs.next()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 0; i < columns; i++) {
                            row.put(columnNames[i], rs.getString(i + 1));
                        }
                        planRows.add(row);
                    }
                }
            }
        }

        return planRows;
    }

    private List<String> analyzePlan(List<Map<String, String>> plan, DatabaseDialect dialect) {
        List<String> warnings = new ArrayList<>();

        if (plan.isEmpty()) {
            return List.of("⚠️ 无法获取执行计划，可能是不支持的方言");
        }

        for (Map<String, String> row : plan) {
            String planText = String.join(" ", row.values()).toUpperCase();

            // Detect full table scan
            if (planText.contains("TABLE ACCESS") && planText.contains("FULL")) {
                warnings.add("⚠️ 检测到全表扫描 (FULL TABLE SCAN)，建议添加索引或 WHERE 条件");
            }

            // Detect nested loop joins
            if (planText.contains("NESTED LOOPS")) {
                warnings.add("⚠️ 检测到嵌套循环连接，大数据量时性能较差，建议检查连接条件是否有索引");
            }

            // Detect hash joins
            if (planText.contains("HASH JOIN")) {
                warnings.add("ℹ️ 使用哈希连接，确保参与连接的列有索引支持");
            }

            // Detect sort operations
            if (planText.contains("SORT") && planText.contains("ORDER BY")) {
                warnings.add("ℹ️ 检测到排序操作，考虑添加索引避免文件排序");
            }

            // Detect filter operations
            if (planText.contains("FILTER") || planText.contains("INDEX SKIP SCAN")) {
                warnings.add("ℹ️ 检测到索引跳过扫描，可能影响性能");
            }
        }

        return warnings.isEmpty() ? List.of("✅ 执行计划正常，无明显性能问题") : warnings;
    }

    private Long getTableRowCount(String connection, String tableName, DatabaseDialect dialect) {
        try {
            String sql = dialect.getTableRowCountSql(tableName);
            if (sql == null) return null;

            try (Connection conn = dataSourceManager.acquire(connection).getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            }
        } catch (Exception e) {
            // Table not found or no statistics
        }
        return null;
    }

    private List<String> extractTableNames(String sql) {
        List<String> tables = new ArrayList<>();
        String upperSql = sql.toUpperCase();

        // Extract FROM clause tables
        int fromIndex = upperSql.indexOf("FROM ");
        if (fromIndex >= 0) {
            String afterFrom = sql.substring(fromIndex + 5).trim();
            String tableName = extractTableName(afterFrom);
            if (tableName != null) tables.add(tableName);
        }

        // Extract JOIN tables
        int joinIndex = upperSql.indexOf(" JOIN ");
        if (joinIndex >= 0) {
            String afterJoin = sql.substring(joinIndex + 6).trim();
            String tableName = extractTableName(afterJoin);
            if (tableName != null && !tables.contains(tableName)) {
                tables.add(tableName);
            }
        }

        return tables;
    }

    private String extractTableName(String fragment) {
        if (fragment == null) return null;
        // Take first word, remove aliases and parentheses
        String[] parts = fragment.split("[\\s,(]+");
        if (parts.length > 0 && !parts[0].isEmpty()) {
            return parts[0].toUpperCase();
        }
        return null;
    }

    private int calculateRiskScore(String sql, Map<String, Long> tableSizes) {
        int score = 0;
        String upperSql = sql.toUpperCase();

        // Base score from table sizes
        for (Long rowCount : tableSizes.values()) {
            if (rowCount == null) continue;
            if (rowCount > 10_000_000) score += 3;
            else if (rowCount > 1_000_000) score += 2;
            else if (rowCount > 100_000) score += 1;
        }

        // Query structure factors
        if (!upperSql.contains("WHERE")) score += 3;
        if (upperSql.contains("JOIN")) score += 1;
        if (upperSql.contains("ORDER BY") && !upperSql.contains("WHERE")) score += 2;
        if (upperSql.contains("GROUP BY")) score += 1;
        if (upperSql.contains("DISTINCT")) score += 1;
        if (sql.contains("(") && upperSql.contains("SELECT")) score += 2; // subquery
        if (upperSql.contains("UNION")) score += 1;

        return score;
    }

    private List<String> generateSuggestions(String riskLevel, String sql, Map<String, Long> tableSizes) {
        List<String> suggestions = new ArrayList<>();

        switch (riskLevel) {
            case "high":
                suggestions.add("🔴 高风险查询，强烈建议先使用 explainPlan 工具分析执行计划");
                suggestions.add("考虑使用分页查询替代全量查询");
                suggestions.add("如果只需要部分列，请明确指定列名而非 SELECT *");
                break;
            case "medium":
                suggestions.add("🟡 中等风险，建议使用 explainPlan 工具检查执行计划");
                break;
            default:
                suggestions.add("🟢 低风险，可以直接执行");
        }

        // Table-specific suggestions
        for (Map.Entry<String, Long> entry : tableSizes.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 1_000_000) {
                suggestions.add(String.format("表 %s 数据量较大 (%d 行)，建议添加筛选条件",
                        entry.getKey(), entry.getValue()));
            }
        }

        // Query structure suggestions
        String upperSql = sql.toUpperCase();
        if (!upperSql.contains("WHERE")) {
            suggestions.add("建议添加 WHERE 条件以减少扫描范围");
        }
        if (upperSql.contains("ORDER BY") && !upperSql.contains("WHERE")) {
            suggestions.add("无 WHERE 条件的 ORDER BY 性能较差");
        }
        if (upperSql.contains("UNION")) {
            suggestions.add("UNION 会去重，如果不需要可使用 UNION ALL");
        }

        return suggestions.isEmpty() ? List.of("✅ 查询结构合理，无需特别优化") : suggestions;
    }
}
