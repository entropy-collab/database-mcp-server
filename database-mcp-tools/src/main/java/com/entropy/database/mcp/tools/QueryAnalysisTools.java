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
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.facade.DatabaseAdminOperations;
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Query analysis tools for execution plan preview and optimization suggestions.
 * Implements Plan B: Always get execution plan before executing complex queries.
 */
@Component
public class QueryAnalysisTools extends McpToolBase {

    /** Split by capability: this tool only reads rows and inspects the dialect, never writes. */
    private final DatabaseReadOperations readOperations;
    private final DatabaseAdminOperations adminOperations;

    // 表行数缓存，避免重复查询（10 分钟过期）
    private final Map<String, Long> rowCountCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10 * 60 * 1000;
    private static final int RISK_THRESHOLD_HIGH = 5;
    private static final int RISK_THRESHOLD_MEDIUM = 2;
    private static final long ROW_COUNT_HIGH_SCORE = 10_000_000L;
    private static final long ROW_COUNT_MED_SCORE = 1_000_000L;
    private static final long ROW_COUNT_LOW_SCORE = 100_000L;

    public QueryAnalysisTools(DatabaseReadOperations readOperations,
                              DatabaseAdminOperations adminOperations) {
        this.readOperations = readOperations;
        this.adminOperations = adminOperations;
    }

    @McpTool(description = """
            【获取执行计划】对 SELECT 语句执行数据库原生 EXPLAIN，返回原始计划行与规则告警。只跑 EXPLAIN，不执行原查询。
            前置条件：先调用 createNamedConnection 注册数据库连接；connection 与 sql 均必填；sql 必须以 SELECT 或 WITH 开头，否则报安全校验错误。
            使用场景：assessQueryRisk 判定 risk_level=high（必须）或 medium（建议）时，先取计划再决定是否执行；核对是否发生全表扫描。
            返回字段：connection、dialect、originalSql、explainSql（实际下发的 EXPLAIN 语句）、plan（计划行数组，每项为列名到值的键值对；SQL Server 因计划走会话输出，只返回一条 note 提示）、warnings（全表扫描、嵌套循环、哈希连接、排序、索引跳过扫描等提示）、success。
            不要用于：非 SELECT 语句；方言不支持 EXPLAIN 时会返回 EXPLAIN_NOT_SUPPORTED 错误；需要中文逐行解读（把 plan 文本交给 interpretPlan）；需要一次拿到索引与重写建议（用 analyzeQuery）。
            标签：[read, query, explain, plan, performance]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> explainPlan(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "要分析的 SQL 语句，必填；必须是 SELECT 或以 WITH 开头的查询") String sql) {
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
            【查询风险评估】不执行 SQL，仅按语句结构与所涉表的行数估算风险等级（low / medium / high），用于决定是否需要先看执行计划。
            前置条件：先调用 createNamedConnection 注册数据库连接；connection 与 sql 均必填。表行数按表名缓存 10 分钟，取不到行数的表不计分。
            评分规则：所涉每张表按行数累加——超 1000 万 +3、超 100 万 +2、超 10 万 +1；无 WHERE +3；有 ORDER BY 且无 WHERE +2；含嵌套子查询 +2；含 JOIN +1；含 GROUP BY +1；含 DISTINCT +1；含 UNION +1。
            等级划分：总分 ≤2 为 low；3-5 为 medium；>5 为 high。
            决策规则：riskLevel=high 必须先调用 explainPlan 分析执行计划再执行；riskLevel=medium 建议先调用 explainPlan 检查；riskLevel=low 可直接执行。
            返回字段：connection、dialect、sql、tables（表名 → 估算行数）、riskScore、riskLevel、suggestions（针对性优化建议数组）、recommendation（是否需要先看执行计划的结论）、success。
            不要用于：取执行计划本身（用 explainPlan）；需要索引或重写建议（用 analyzeQuery / recommendIndexes / suggestRewrites）。
            标签：[read, query, risk, assessment, performance]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> assessQueryRisk(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection,
            @McpToolParam(description = "要评估的 SQL 语句，必填；支持带 WITH 子句的查询（会从主查询的 FROM / JOIN 中提取表名）") String sql) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            validateRequired(sql, "sql");

            String trimmedSql = sql.trim();
            DatabaseDialect dialect = resolveDialect(connection);

            List<String> tableNames = extractTableNames(trimmedSql);
            Map<String, Long> tableSizes = new java.util.LinkedHashMap<>();
            for (String tableName : tableNames) {
                Long rowCount = getTableRowCount(connection, dialect, tableName);
                if (rowCount != null) tableSizes.put(tableName, rowCount);
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
        // Ask the connection for its dialect instead of re-deriving it by name. The previous route
        // went through DialectQueryUtils.getDialectName(), which returns the dialect class's simple
        // name ("OracleDialect"), and handed that to DialectResolver, which matches short names
        // ("oracle"). Nothing ever matched, so every real connection silently degraded to
        // GenericDialect and explainPlan answered EXPLAIN_NOT_SUPPORTED on databases that do
        // support it. Tests missed it because the test connections register GenericDialect anyway.
        return adminOperations.getDialect(connection);
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

    private List<Map<String, String>> executeExplainPlan(String connection, String explainSql) {
        if (explainSql.contains("SET SHOWPLAN")) {
            // SQL Server emits the plan as session output rather than as a result set, so whatever
            // the batch returns is discarded.
            readOperations.queryRows(explainSql, connection);
            return List.of(Map.of("note", "SQL Server execution plan captured in output"));
        }
        List<Map<String, String>> planRows = new ArrayList<>();
        for (Map<String, Object> row : readOperations.queryRows(explainSql, connection)) {
            Map<String, String> planRow = new java.util.LinkedHashMap<>();
            row.forEach((column, value) -> planRow.put(column, value == null ? null : value.toString()));
            planRows.add(planRow);
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

    private Long getTableRowCount(String connection, DatabaseDialect dialect, String tableName) {
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
            List<Map<String, Object>> rows = readOperations.queryRows(sql, connection);
            if (!rows.isEmpty()) {
                var values = rows.get(0).values();
                Object value = values.isEmpty() ? null : values.iterator().next();
                if (value instanceof Number number) {
                    long rowCount = number.longValue();
                    // 更新缓存
                    rowCountCache.put(cacheKey, rowCount);
                    cacheTimestamps.put(cacheKey, System.currentTimeMillis());
                    return rowCount;
                }
            }
        } catch (RuntimeException e) {
            // Row count is an optional hint used to annotate a plan; the analysis is still useful
            // without it, so a dialect that cannot report it must not fail the whole tool.
            log.debug("Failed to get row count for table '{}' on connection '{}': {}",
                    tableName, connection, e.getMessage());
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
