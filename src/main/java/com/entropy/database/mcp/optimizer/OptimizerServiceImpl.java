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
package com.entropy.database.mcp.optimizer;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class OptimizerServiceImpl implements OptimizerService {

    private static final Logger log = LoggerFactory.getLogger(OptimizerServiceImpl.class);

    private final DynamicDataSourceManager dataSourceManager;

    public OptimizerServiceImpl(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    // ─── Query Analysis ────────────────────────────────────────────────────────

    @Override
    public PerformanceReport analyzeQuery(String sql, String connection) {
        long startMs = System.currentTimeMillis();
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            String trimmedSql = sql.trim();
            String upperSql = trimmedSql.toUpperCase();

            // Run EXPLAIN
            List<String> planRows = getExplainPlan(jdbc, dialect, trimmedSql);

            // Extract table references
            List<String> tables = extractTableNames(trimmedSql);

            // Gather stats
            long estimatedRows = 0;
            long sizeMb = 0;
            if (!tables.isEmpty()) {
                estimatedRows = getEstimatedRowCount(jdbc, dialect, tables.get(0));
                sizeMb = getTableSizeMb(jdbc, dialect, tables.get(0));
            }

            // Analyze for issues
            List<String> warnings = analyzePlan(planRows, upperSql, dialect);
            List<IndexRecommendation> indexRecs = recommendIndexesFromSql(trimmedSql, tables, dialect, connection);
            List<RewriteSuggestion> rewrites = suggestRewrites(trimmedSql);

            long durationMs = System.currentTimeMillis() - startMs;
            List<String> actions = buildActionItems(warnings, indexRecs, rewrites);

            return new PerformanceReport(connection, dialect.getDialectName(), trimmedSql,
                    tables.isEmpty() ? null : tables.get(0), planRows, durationMs,
                    warnings, indexRecs, rewrites, estimatedRows, sizeMb, actions);
        } catch (Exception e) {
            log.warn("Query analysis failed: {}", e.getMessage());
            long durationMs = System.currentTimeMillis() - startMs;
            return new PerformanceReport(connection, "unknown", sql, null, List.of(),
                    durationMs, List.of("分析失败: " + e.getMessage()),
                    List.of(), List.of(), 0, 0, List.of());
        } finally {
            ctx.close();
        }
    }

    // ─── Index Recommendations ─────────────────────────────────────────────────

    @Override
    public List<IndexRecommendation> recommendIndexes(String tableName, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            String normalizedTable = dialect.normalizeTableName(tableName);

            // 1. Get existing indexes
            Set<String> indexedColumns = getIndexedColumns(jdbc, dialect, normalizedTable);

            // 2. Get candidate columns (not yet indexed)
            String candSql = dialect.candidateColumnsForIndexSql(normalizedTable);
            List<Map<String, Object>> candidates = candSql != null
                    ? jdbc.queryForList(candSql, normalizedTable)
                    : List.of();

            List<IndexRecommendation> recs = new ArrayList<>();
            int priority = 1;
            for (Map<String, Object> row : candidates) {
                String col = (String) row.get("column_name");
                if (col == null || indexedColumns.contains(col.toUpperCase())) continue;

                // Determine index type and recommend SQL
                String idxType = determineIndexType(row);
                String createSql = buildCreateIndexSql(dialect, normalizedTable, col, idxType);
                String reason = buildIndexReason(row);

                recs.add(new IndexRecommendation(normalizedTable, col, idxType, createSql, reason, priority++));
            }

            // 3. Composite index suggestion: look for WHERE + JOIN pairs in common queries
            recs.addAll(suggestCompositeIndexes(jdbc, dialect, normalizedTable, indexedColumns));

            return recs;
        } catch (Exception e) {
            log.warn("Index recommendation failed for {}: {}", tableName, e.getMessage());
            return List.of();
        } finally {
            ctx.close();
        }
    }

    // ─── Rewrite Suggestions ───────────────────────────────────────────────────

    @Override
    public List<RewriteSuggestion> suggestRewrites(String sql, String connection) {
        return suggestRewrites(sql);
    }

    public List<RewriteSuggestion> suggestRewrites(String sql) {
        List<RewriteSuggestion> suggestions = new ArrayList<>();
        String upper = sql.toUpperCase().trim();

        // SELECT * → specific columns
        if (upper.matches(".*\\bSELECT\\s+\\*\\b.*")) {
            suggestions.add(new RewriteSuggestion(
                    "SELECT_STAR",
                    "SELECT *",
                    "SELECT <specific_columns>",
                    "SELECT * 会检索所有列，增加 I/O 和网络开销。只选择需要的列可显著降低数据量。",
                    rewriteSelectStar(sql)
            ));
        }

        // Implicit type conversion: string column compared to number
        if (Pattern.compile("\\w+\\s*=\\s*\\d+", Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            suggestions.add(new RewriteSuggestion(
                    "IMPLICIT_CONVERSION",
                    "字符串列与数值比较",
                    "确保列类型与比较值类型一致，避免隐式转换导致索引失效",
                    "如果条件中的列是字符串类型而值是数字，请确保两边类型一致。",
                    sql
            ));
        }

        // LIKE with leading wildcard
        if (upper.contains("LIKE '%'") || upper.matches(".*LIKE\\s+'%.*")) {
            suggestions.add(new RewriteSuggestion(
                    "LEADING_WILDCARD",
                    "LIKE '%...'",
                    "使用全文检索 (FULLTEXT) 或 ELT() 函数替代前导通配符",
                    "前导通配符无法使用 B-tree 索引，考虑使用全文检索或应用层搜索。",
                    sql
            ));
        }

        // OR chain on same column
        Pattern orPattern = Pattern.compile("(\\w+)\\s+IN\\s*\\(", Pattern.CASE_INSENSITIVE);
        Matcher orMatcher = orPattern.matcher(upper);
        if (orMatcher.find()) {
            suggestions.add(new RewriteSuggestion(
                    "OR_TO_IN",
                    "A = x OR A = y OR A = z",
                    "A IN (x, y, z)",
                    "多个 OR 条件可简化为 IN 子句，优化器更容易选择执行计划。",
                    rewriteOrToIn(sql)
            ));
        }

        // NOT IN with subquery (correlated)
        if (upper.contains("NOT IN") && upper.contains("SELECT")) {
            suggestions.add(new RewriteSuggestion(
                    "NOT_IN_SUBQUERY",
                    "NOT IN (SELECT ...)",
                    "LEFT JOIN ... IS NULL 或 NOT EXISTS",
                    "NOT IN 在子查询含 NULL 时结果不可预测，且性能差。改用 NOT EXISTS。",
                    rewriteNotInToNotExists(sql)
            ));
        }

        // Missing WHERE on large table scan indicator
        if (!upper.contains("WHERE") && upper.contains("SELECT") && upper.contains("FROM")) {
            suggestions.add(new RewriteSuggestion(
                    "NO_FILTER",
                    "无 WHERE 条件的全表查询",
                    "添加合适的 WHERE 筛选条件",
                    "无 WHERE 条件会扫描全表，数据量大时性能极差。",
                    sql
            ));
        }

        // ORDER BY without WHERE
        if (upper.contains("ORDER BY") && !upper.contains("WHERE")) {
            suggestions.add(new RewriteSuggestion(
                    "ORDER_BY_NO_WHERE",
                    "无 WHERE 的 ORDER BY",
                    "添加 WHERE 条件缩小结果集后再排序",
                    "全表排序开销巨大，建议先通过 WHERE 过滤再排序。",
                    sql
            ));
        }

        return suggestions;
    }

    // ─── Table Analysis ────────────────────────────────────────────────────────

    @Override
    public PerformanceReport analyzeTable(String tableName, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            String normalizedTable = dialect.normalizeTableName(tableName);

            long rows = getEstimatedRowCount(jdbc, dialect, normalizedTable);
            long sizeMb = getTableSizeMb(jdbc, dialect, normalizedTable);
            List<IndexRecommendation> indexRecs = recommendIndexes(tableName, connection);
            List<String> warnings = new ArrayList<>();

            if (rows > 10_000_000) warnings.add("表数据量超过 1000 万行，建议评估分区策略");
            if (sizeMb > 1024) warnings.add("表大小超过 1GB，建议检查索引覆盖率和填充因子");
            if (indexRecs.isEmpty() && rows > 100_000) warnings.add("大表缺少推荐索引，查询性能可能较差");

            List<String> actions = new ArrayList<>();
            for (IndexRecommendation rec : indexRecs.stream().limit(3).toList()) {
                actions.add(rec.recommendedSql());
            }

            return new PerformanceReport(connection, dialect.getDialectName(), null,
                    normalizedTable, List.of(), 0, warnings, indexRecs,
                    List.of(), rows, sizeMb, actions);
        } catch (Exception e) {
            log.warn("Table analysis failed: {}", e.getMessage());
            return new PerformanceReport(connection, "unknown", null, tableName,
                    List.of(), 0, List.of("分析失败: " + e.getMessage()),
                    List.of(), List.of(), 0, 0, List.of());
        } finally {
            ctx.close();
        }
    }

    // ─── Plan Interpretation ───────────────────────────────────────────────────

    @Override
    public String interpretPlan(String planText, String dialect) {
        StringBuilder sb = new StringBuilder();
        String upper = planText.toUpperCase();
        int lineNum = 1;

        for (String line : planText.split("\n")) {
            String uLine = line.toUpperCase().trim();
            sb.append(String.format("[%d] %s", lineNum++, formatLine(line)));
            interpretLine(uLine, sb);
            sb.append("\n");
        }

        // Summary
        sb.append("\n── 解读摘要 ──\n");
        if (upper.contains("TABLE ACCESS") && upper.contains("FULL")) {
            sb.append("⚠️  检测到全表扫描，考虑添加索引或使用物化视图\n");
        }
        if (upper.contains("NESTED LOOPS")) {
            sb.append("⚠️  嵌套循环连接：大数据量时建议改用 HASH JOIN\n");
        }
        if (upper.contains("HASH JOIN")) {
            sb.append("ℹ️  哈希连接：确保连接列有索引支持\n");
        }
        if (upper.contains("SORT") && upper.contains("HASH")) {
            sb.append("ℹ️  哈希排序：内存不足时会 spill 到磁盘\n");
        }
        if (upper.contains("INDEX")) {
            sb.append("✅ 检测到索引访问，符合预期\n");
        }
        if (!upper.contains("TABLE ACCESS") && !upper.contains("INDEX") && !upper.contains("VIEW")) {
            sb.append("ℹ️  执行计划结构简单，未见明显问题\n");
        }
        return sb.toString();
    }

    // ─── Private Helpers ───────────────────────────────────────────────────────

    private List<String> getExplainPlan(JdbcTemplate jdbc, DatabaseDialect dialect, String sql) {
        try {
            String explainSql = dialect.getExplainPlanSql(sql);
            if (explainSql == null) return List.of("EXPLAIN 不支持当前方言");
            List<Map<String, Object>> rows = jdbc.queryForList(explainSql);
            return rows.stream()
                    .map(row -> row.values().stream()
                            .map(Object::toString).collect(Collectors.joining(" | ")))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of("获取执行计划失败: " + e.getMessage());
        }
    }

    private long getEstimatedRowCount(JdbcTemplate jdbc, DatabaseDialect dialect, String tableName) {
        try {
            String sql = dialect.getTableRowCountSql(tableName);
            if (sql == null) return -1;
            List<Map<String, Object>> rows = jdbc.queryForList(sql);
            if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
                Object val = rows.get(0).values().iterator().next();
                return val instanceof Number n ? n.longValue() : -1;
            }
        } catch (Exception e) {
            log.debug("Row count unavailable: {}", e.getMessage());
        }
        return -1;
    }

    private long getTableSizeMb(JdbcTemplate jdbc, DatabaseDialect dialect, String tableName) {
        try {
            String sql = dialect.estimateTableSizeSql(tableName, null);
            if (sql == null) return -1;
            List<Map<String, Object>> rows = jdbc.queryForList(sql, tableName);
            if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
                Object val = rows.get(0).values().iterator().next();
                return val instanceof Number n ? n.longValue() : -1;
            }
        } catch (Exception e) {
            log.debug("Table size unavailable: {}", e.getMessage());
        }
        return -1;
    }

    private List<String> analyzePlan(List<String> planRows, String upperSql, DatabaseDialect dialect) {
        List<String> warnings = new ArrayList<>();
        String fullPlan = String.join(" ", planRows).toUpperCase();

        if (fullPlan.contains("TABLE ACCESS") && fullPlan.contains("FULL")) {
            warnings.add("⚠️  全表扫描 (FULL TABLE SCAN) — 建议为目标表添加合适的索引");
        }
        if (fullPlan.contains("NESTED LOOPS")) {
            warnings.add("⚠️  嵌套循环连接 — 大数据量时改用 HASH JOIN 或确保连接列有索引");
        }
        if (fullPlan.contains("HASH JOIN")) {
            warnings.add("ℹ️  哈希连接 — 确认参与连接的列已建立索引");
        }
        if (fullPlan.contains("SORT") && upperSql.contains("ORDER BY")) {
            warnings.add("ℹ️  检测到排序操作 — 添加覆盖索引可避免文件排序");
        }
        if (fullPlan.contains("INDEX SKIP SCAN")) {
            warnings.add("⚠️  索引跳过扫描 — 高基数列上可能影响性能");
        }
        if (upperSql.contains("DISTINCT") && !upperSql.contains("GROUP BY")) {
            warnings.add("ℹ️  使用 DISTINCT 去重 — 确认是否真的需要去重，可考虑 GROUP BY 替代");
        }
        if (upperSql.contains("UNION") && !upperSql.contains("UNION ALL")) {
            warnings.add("ℹ️  UNION 会自动去重（排序）— 如不需要去重可改用 UNION ALL");
        }
        return warnings.isEmpty() ? List.of("✅ 执行计划无明显性能问题") : warnings;
    }

    private List<String> extractTableNames(String sql) {
        List<String> tables = new ArrayList<>();
        String upper = sql.toUpperCase();
        Pattern p = Pattern.compile("\\bFROM\\s+([\\w\"\\[\\]`]+)(?:\\s+(?:AS\\s+)?\\w+)?(?:\\s*,|\\s+JOIN|\\s+WHERE|\\s+ORDER|\\s+GROUP|\\s+LIMIT|$)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        while (m.find()) {
            String t = m.group(1).replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
            if (!t.toUpperCase().equals("SELECT") && !tables.contains(t.toUpperCase())) {
                tables.add(t.toUpperCase());
            }
        }
        // Also pick up JOIN tables
        Pattern joinP = Pattern.compile("\\bJOIN\\s+([\\w\"\\[\\]`]+)(?:\\s+(?:AS\\s+)?\\w+)?",
                Pattern.CASE_INSENSITIVE);
        Matcher jm = joinP.matcher(sql);
        while (jm.find()) {
            String t = jm.group(1).replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
            if (!t.toUpperCase().equals("SELECT") && !tables.contains(t.toUpperCase())) {
                tables.add(t.toUpperCase());
            }
        }
        return tables;
    }

    private Set<String> getIndexedColumns(JdbcTemplate jdbc, DatabaseDialect dialect, String tableName) {
        Set<String> cols = new HashSet<>();
        try {
            String sql = dialect.listTableIndexesSql(tableName);
            if (sql == null) return cols;
            List<Map<String, Object>> rows = jdbc.queryForList(sql, tableName);
            for (Map<String, Object> row : rows) {
                Object col = row.get("column_name");
                if (col != null) cols.add(col.toString().toUpperCase());
            }
        } catch (Exception e) {
            log.debug("Could not list indexes: {}", e.getMessage());
        }
        return cols;
    }

    private List<IndexRecommendation> recommendIndexesFromSql(String sql, List<String> tables,
                                                              DatabaseDialect dialect, String connection) {
        List<IndexRecommendation> recs = new ArrayList<>();
        if (tables.isEmpty()) return recs;

        // Extract WHERE clause columns
        String upper = sql.toUpperCase();
        int whereIdx = upper.indexOf("WHERE ");
        if (whereIdx < 0) return recs;

        String whereClause = sql.substring(whereIdx + 6).trim();
        // Simple extraction: column = value or column IN (...)
        Pattern colPattern = Pattern.compile("([\\w.\"\\[\\]`]+)\\s*(=|IN|>=|<=|>|<|LIKE)\\b");
        Matcher m = colPattern.matcher(whereClause.toUpperCase() + " " + whereClause);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String col = m.group(1).replaceAll("[\"\\[\\]`]", "").toUpperCase();
            if (seen.add(col)) {
                String table = tables.get(0);
                // Check if column is already indexed
                ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
                try {
                    Set<String> indexedCols = getIndexedColumns(ctx.getJdbcTemplate(), dialect,
                            dialect.normalizeTableName(table));
                    if (indexedCols.contains(col)) continue;

                    String normalizedTable = dialect.normalizeTableName(table);
                    String createSql = buildCreateIndexSql(dialect, normalizedTable, col, "BTREE");
                    recs.add(new IndexRecommendation(normalizedTable, col, "BTREE",
                            createSql, "WHERE 条件中未索引的列: " + col, 1));
                } finally {
                    ctx.close();
                }
            }
        }
        return recs;
    }

    private List<IndexRecommendation> suggestCompositeIndexes(JdbcTemplate jdbc,
                                                              DatabaseDialect dialect,
                                                              String tableName,
                                                              Set<String> indexedColumns) {
        // Composite index: common pattern WHERE col1 = ? AND col2 = ?
        List<IndexRecommendation> recs = new ArrayList<>();
        try {
            String candSql = dialect.candidateColumnsForIndexSql(tableName);
            if (candSql == null) return recs;
            List<Map<String, Object>> candidates = jdbc.queryForList(candSql, tableName);
            if (candidates.size() >= 2) {
                String col1 = (String) candidates.get(0).get("column_name");
                String col2 = (String) candidates.get(1).get("column_name");
                if (col1 != null && col2 != null && !indexedColumns.contains(col1.toUpperCase())
                        && !indexedColumns.contains(col2.toUpperCase())) {
                    String compositeSql = String.format(
                            "CREATE INDEX idx_%s_%s_%s ON %s (%s, %s)",
                            tableName, col1, col2,
                            dialect.quote(tableName),
                            dialect.quote(col1), dialect.quote(col2));
                    recs.add(new IndexRecommendation(tableName, col1 + ", " + col2, "COMPOSITE",
                            compositeSql, "多个低选择性列组合可构建复合索引提升查询效率", 2));
                }
            }
        } catch (Exception e) {
            log.debug("Composite index suggestion failed: {}", e.getMessage());
        }
        return recs;
    }

    private String determineIndexType(Map<String, Object> row) {
        Object distinct = row.get("num_distinct");
        if (distinct != null) {
            long d = ((Number) distinct).longValue();
            if (d < 10) return "BITMAP";   // low cardinality → bitmap
        }
        return "BTREE";
    }

    private String buildIndexReason(Map<String, Object> row) {
        Object nullable = row.get("nullable");
        if (nullable != null && "YES".equalsIgnoreCase(nullable.toString())) {
            return "可空列建议建立索引以提高过滤效率";
        }
        return "该列无索引，添加后可加速等值/范围查询";
    }

    private String buildCreateIndexSql(DatabaseDialect dialect, String tableName, String columnName, String idxType) {
        String idxName = "idx_" + tableName.toLowerCase() + "_" + columnName.toLowerCase();
        String unique = "BTREE".equalsIgnoreCase(idxType) ? "" : " UNIQUE ";
        return String.format("CREATE %sINDEX %s ON %s (%s)",
                unique, idxName,
                dialect.quote(tableName),
                dialect.quote(columnName));
    }

    private List<String> buildActionItems(List<String> warnings,
                                           List<IndexRecommendation> indexRecs,
                                           List<RewriteSuggestion> rewrites) {
        List<String> actions = new ArrayList<>();
        for (String w : warnings) {
            if (w.startsWith("⚠️")) actions.add(w);
        }
        for (IndexRecommendation rec : indexRecs.stream().limit(3).toList()) {
            actions.add("📌 " + rec.reason() + "\n   SQL: " + rec.recommendedSql());
        }
        for (RewriteSuggestion rs : rewrites.stream().limit(2).toList()) {
            actions.add("💡 " + rs.reason());
        }
        return actions.isEmpty() ? List.of("✅ 无明显优化项") : actions;
    }

    private void interpretLine(String upperLine, StringBuilder sb) {
        if (upperLine.contains("TABLE ACCESS") && upperLine.contains("FULL")) {
            sb.append("  ⚠️ 全表扫描，建议检查索引");
        } else if (upperLine.contains("INDEX") && (upperLine.contains("RANGE") || upperLine.contains("SCAN"))) {
            sb.append("  ✅ 索引范围扫描");
        } else if (upperLine.contains("INDEX") && upperLine.contains("UNIQUE")) {
            sb.append("  ✅ 唯一索引访问");
        } else if (upperLine.contains("NESTED LOOPS")) {
            sb.append("  ⚠️ 嵌套循环连接");
        } else if (upperLine.contains("HASH JOIN")) {
            sb.append("  ℹ️ 哈希连接");
        } else if (upperLine.contains("SORT")) {
            sb.append("  ℹ️ 排序操作");
        }
    }

    private String formatLine(String line) {
        if (line.isBlank()) return line;
        // Truncate long lines
        return line.length() > 120 ? line.substring(0, 117) + "..." : line;
    }

    // ─── Rewrite helpers ───────────────────────────────────────────────────────

    private String rewriteSelectStar(String sql) {
        // Return a template replacing * with <column_list>
        return sql.replaceFirst("(?i)\\bSELECT\\s+\\*", "SELECT <column1>, <column2>, ...");
    }

    private String rewriteOrToIn(String sql) {
        Pattern orPat = Pattern.compile(
                "(\\w+)\\s*=\\s*'([^']+)'\s*OR\\s+\\1\\s*=\\s*'([^']+)'",
                Pattern.CASE_INSENSITIVE);
        Matcher m = orPat.matcher(sql);
        return m.replaceAll("$1 IN ('$2', '$3')");
    }

    private String rewriteNotInToNotExists(String sql) {
        Pattern pat = Pattern.compile(
                "NOT\\s+IN\\s*\\(\\s*SELECT\\s+(\\w+)\\s+FROM\\s+(\\w+)\\s*(WHERE\\s+[^)]+)?\\s*\\)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = pat.matcher(sql);
        if (m.find()) {
            String col = m.group(1);
            String table = m.group(2);
            String where = m.group(3) != null ? m.group(3).trim() : "";
            return sql.replaceAll("(?i)NOT\\s+IN\\s*\\(\\s*SELECT[^)]+\\)",
                    "NOT EXISTS (SELECT 1 FROM " + table
                            + (where.isEmpty() ? "" : " WHERE " + where)
                            + " WHERE " + table + "." + col + " = outer." + col + ")");
        }
        return sql;
    }
}
