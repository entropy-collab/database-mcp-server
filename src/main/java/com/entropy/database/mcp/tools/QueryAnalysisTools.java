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
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Query analysis tools for execution plan preview and optimization suggestions.
 * Implements Plan B: Always get execution plan before executing complex queries.
 */
@Component
public class QueryAnalysisTools extends McpToolBase {

    private final DynamicDataSourceManager dataSourceManager;
    private final DialectResolver dialectResolver;
    
    // 表行数缓存，避免重复查询（10 分钟过期）
    private final Map<String, Long> rowCountCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10 * 60 * 1000;
    private static final int RISK_THRESHOLD_HIGH = 5;
    private static final int RISK_THRESHOLD_MEDIUM = 2;
    private static final long ROW_COUNT_HIGH_SCORE = 10_000_000L;
    private static final long ROW_COUNT_MED_SCORE = 1_000_000L;
    private static final long ROW_COUNT_LOW_SCORE = 100_000L;
    private static final int EXPLAIN_TIMEOUT_SECONDS = 30;

    public QueryAnalysisTools(DynamicDataSourceManager dataSourceManager,
                              DialectResolver dialectResolver) {
        this.dataSourceManager = dataSourceManager;
        this.dialectResolver = dialectResolver;
    }

    @McpTool(description = "获取 SQL 执行计划，分析性能并返回优化建议（先计划后执行方案）")
    public Map<String, Object> explainPlan(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "要分析的 SQL 语句") String sql) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            validateRequired(sql, "sql");

            String trimmedSql = sql.trim();
            String upperSql = trimmedSql.toUpperCase();
            if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")) {
                throw new McpToolException(ErrorCode.SECURITY_VIOLATION, "Only SELECT queries can be explained (connection=" + connection + ")");
            }

            DatabaseDialect dialect = resolveDialect(connection);

            String explainSql = buildExplainSql(dialect, trimmedSql);
            if (explainSql == null) {
                throw new McpToolException(ErrorCode.EXPLAIN_NOT_SUPPORTED, "EXPLAIN PLAN not supported for this dialect (connection=" + connection + ")");
            }

            List<Map<String, String>> planRows = executeExplainPlan(connection, explainSql);
            List<String> warnings = analyzePlan(planRows, dialect);
            return success(context(
                    "connection", connection, "dialect", dialect.getDialectName(),
                    "originalSql", trimmedSql, "explainSql", explainSql,
                    "plan", planRows, "warnings", warnings, "success", true));
        });
    }

    @McpTool(description = """
            【查询风险评估】评估 SQL 查询的风险等级（low/medium/high），辅助决策是否需要预检执行计划。
            
            评分规则：
            - high: 总分 > 5（如无 WHERE、大数据量表、嵌套子查询）
            - medium: 总分 3-5
            - low: 总分 ≤ 2
            
            使用规则：
            - risk_level=high 时必须先调用 explainPlan 分析再执行
            - risk_level=medium 时建议调用 explainPlan 检查
            - risk_level=low 可直接执行
            
            返回字段：riskScore、riskLevel、tables（各表行数）、suggestions、recommendation
            """)
    public Map<String, Object> assessQueryRisk(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "要评估的 SQL 语句") String sql) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            validateRequired(sql, "sql");

            String trimmedSql = sql.trim();
            DatabaseDialect dialect = resolveDialect(connection);

            List<String> tableNames = extractTableNames(trimmedSql);
            Map<String, Long> tableSizes = new java.util.LinkedHashMap<>();
            try (Connection conn = dataSourceManager.acquire(connection).getConnection()) {
                for (String tableName : tableNames) {
                    Long rowCount = getTableRowCount(conn, dialect, tableName);
                    if (rowCount != null) tableSizes.put(tableName, rowCount);
                }
            }

            int riskScore = calculateRiskScore(trimmedSql, tableSizes);
            String riskLevel = riskScore <= RISK_THRESHOLD_MEDIUM ? "low" : riskScore <= RISK_THRESHOLD_HIGH ? "medium" : "high";
            List<String> suggestions = generateSuggestions(riskLevel, trimmedSql, tableSizes);

            return success(context(
                    "connection", connection, "dialect", dialect.getDialectName(),
                    "sql", trimmedSql, "tables", tableSizes,
                    "riskScore", riskScore, "riskLevel", riskLevel,
                    "suggestions", suggestions, "success", true,
                    "recommendation", switch (riskLevel) {
                        case "high" -> "建议先使用 explainPlan 工具分析执行计划";
                        case "medium" -> "可以考虑使用 explainPlan 工具优化查询";
                        default -> "查询风险较低，可直接执行";
                    }));
        });
    }

    private DatabaseDialect resolveDialect(String connection) {
        return dialectResolver.resolve(
                DialectQueryUtils.getDialectName(dataSourceManager, connection),
                dataSourceManager.acquire(connection).getDataSource());
    }

    private String buildExplainSql(DatabaseDialect dialect, String sql) {
        String dialectExplain = dialect.getExplainPlanSql(sql);
        if (dialectExplain != null) return dialectExplain;
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
            if (explainSql.contains("SET SHOWPLAN")) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(explainSql)) {
                    pstmt.executeQuery();
                }
                return List.of(Map.of("note", "SQL Server execution plan captured in output"));
            }
            try (PreparedStatement pstmt = conn.prepareStatement(explainSql);
                 ResultSet rs = pstmt.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                String[] columnNames = new String[columns];
                for (int i = 0; i < columns; i++) columnNames[i] = rs.getMetaData().getColumnName(i + 1);
                while (rs.next()) {
                    Map<String, String> row = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < columns; i++) row.put(columnNames[i], rs.getString(i + 1));
                    planRows.add(row);
                }
            }
        }
        return planRows;
    }

    private List<String> analyzePlan(List<Map<String, String>> plan, DatabaseDialect dialect) {
        List<String> warnings = new ArrayList<>();
        if (plan.isEmpty()) return List.of("无法获取执行计划，可能是不支持的方言");
        for (Map<String, String> row : plan) {
            String planText = String.join(" ", row.values()).toUpperCase();
            if (planText.contains("TABLE ACCESS") && planText.contains("FULL"))
                warnings.add("检测到全表扫描 (FULL TABLE SCAN)，建议添加索引或 WHERE 条件");
            if (planText.contains("NESTED LOOPS"))
                warnings.add("检测到嵌套循环连接，大数据量时性能较差，建议检查连接条件是否有索引");
            if (planText.contains("HASH JOIN"))
                warnings.add("使用哈希连接，确保参与连接的列有索引支持");
            if (planText.contains("SORT") && planText.contains("ORDER BY"))
                warnings.add("检测到排序操作，考虑添加索引避免文件排序");
            if (planText.contains("FILTER") || planText.contains("INDEX SKIP SCAN"))
                warnings.add("检测到索引跳过扫描，可能影响性能");
        }
        return warnings.isEmpty() ? List.of("执行计划正常，无明显性能问题") : warnings;
    }

    private Long getTableRowCount(Connection conn, DatabaseDialect dialect, String tableName) {
        // 检查缓存
        String cacheKey = tableName.toLowerCase();
        Long cached = rowCountCache.get(cacheKey);
        Long timestamp = cacheTimestamps.get(cacheKey);
        if (cached != null && timestamp != null 
                && (System.currentTimeMillis() - timestamp) < CACHE_TTL_MS) {
            return cached;
        }
        
        try {
            String sql = dialect.getTableRowCountSql(tableName);
            if (sql == null) return null;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        long rowCount = rs.getLong(1);
                        // 更新缓存
                        rowCountCache.put(cacheKey, rowCount);
                        cacheTimestamps.put(cacheKey, System.currentTimeMillis());
                        return rowCount;
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

        // Handle CTE (WITH clause) - extract table names from recursive WITH
        int withIndex = upperSql.indexOf("WITH ");
        if (withIndex >= 0) {
            // Skip CTE names and go to the main query after the last comma or before WHERE/SELECT
            int mainQueryStart = upperSql.indexOf("SELECT ", withIndex);
            if (mainQueryStart > withIndex) {
                extractFromSelect(sql.substring(mainQueryStart), tables);
            }
        } else {
            extractFromSelect(sql, tables);
        }

        return tables.stream().distinct().toList();
    }

    private void extractFromSelect(String sql, List<String> tables) {
        String upperSql = sql.toUpperCase();
        // Find all FROM clauses (handles subqueries by finding each FROM)
        int fromIndex = 0;
        while ((fromIndex = upperSql.indexOf("FROM ", fromIndex)) >= 0) {
            String fragment = sql.substring(fromIndex + 5).trim();
            String tableName = extractTableName(fragment);
            if (tableName != null && !tables.contains(tableName)) tables.add(tableName);
            fromIndex++;
        }
        // Find all JOIN clauses
        int joinIndex = 0;
        while ((joinIndex = upperSql.indexOf(" JOIN ", joinIndex)) >= 0) {
            String fragment = sql.substring(joinIndex + 6).trim();
            String tableName = extractTableName(fragment);
            if (tableName != null && !tables.contains(tableName)) tables.add(tableName);
            joinIndex++;
        }
    }

    private String extractTableName(String fragment) {
        if (fragment == null) return null;
        String[] parts = fragment.split("[\\s,(]+");
        return (parts.length > 0 && !parts[0].isEmpty()) ? parts[0].toUpperCase() : null;
    }

    private int calculateRiskScore(String sql, Map<String, Long> tableSizes) {
        int score = 0;
        String upperSql = sql.toUpperCase();
        for (Long rowCount : tableSizes.values()) {
            if (rowCount == null) continue;
            if (rowCount > ROW_COUNT_HIGH_SCORE) score += 3;
            else if (rowCount > ROW_COUNT_MED_SCORE) score += 2;
            else if (rowCount > ROW_COUNT_LOW_SCORE) score += 1;
        }
        if (!upperSql.contains("WHERE")) score += 3;
        if (upperSql.contains("JOIN")) score += 1;
        if (upperSql.contains("ORDER BY") && !upperSql.contains("WHERE")) score += 2;
        if (upperSql.contains("GROUP BY")) score += 1;
        if (upperSql.contains("DISTINCT")) score += 1;
        // 仅当存在子查询（嵌套 SELECT）时加分，避免普通函数调用误判
        if (upperSql.matches(".*\\bSELECT\\s+.*\\bSELECT\\b.*")) score += 2;
        if (upperSql.contains("UNION")) score += 1;
        return score;
    }

    private List<String> generateSuggestions(String riskLevel, String sql, Map<String, Long> tableSizes) {
        List<String> suggestions = new ArrayList<>();
        switch (riskLevel) {
            case "high":
                suggestions.add("高风险查询，强烈建议先使用 explainPlan 工具分析执行计划");
                suggestions.add("考虑使用分页查询替代全量查询");
                suggestions.add("如果只需要部分列，请明确指定列名而非 SELECT *");
                break;
            case "medium":
                suggestions.add("中等风险，建议使用 explainPlan 工具检查执行计划");
                break;
            default:
                suggestions.add("低风险，可以直接执行");
        }
        for (Map.Entry<String, Long> entry : tableSizes.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > ROW_COUNT_MED_SCORE) {
                suggestions.add(String.format("表 %s 数据量较大 (%d 行)，建议添加筛选条件", entry.getKey(), entry.getValue()));
            }
        }
        String upperSql = sql.toUpperCase();
        if (!upperSql.contains("WHERE")) suggestions.add("建议添加 WHERE 条件以减少扫描范围");
        if (upperSql.contains("ORDER BY") && !upperSql.contains("WHERE")) suggestions.add("无 WHERE 条件的 ORDER BY 性能较差");
        if (upperSql.contains("UNION")) suggestions.add("UNION 会去重，如果不需要可使用 UNION ALL");
        return suggestions.isEmpty() ? List.of("查询结构合理，无需特别优化") : suggestions;
    }
}
