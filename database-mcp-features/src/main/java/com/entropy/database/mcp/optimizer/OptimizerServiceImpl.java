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
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.dialect.PlanOperation;
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OptimizerServiceImpl implements OptimizerService {

    private static final Logger log = LoggerFactory.getLogger(OptimizerServiceImpl.class);

    /**
     * {@code SELECT *} detector.
     *
     * <p>Precompiled and used with {@code find()}. The previous
     * {@code upper.matches(".*\\bSELECT\\s+\\*\\b.*")} never fired: {@code \b} after {@code \*}
     * demands a word boundary between {@code *} and the following space, and neither is a word
     * character, so {@code SELECT * FROM t} could not match. {@code matches()} plus a non-DOTALL
     * {@code .} also failed on any multi-line statement.
     */
    private static final Pattern SELECT_STAR = Pattern.compile("\\bSELECT\\s+\\*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** A value that can appear on the right-hand side of an equality comparison. */
    private static final String VALUE = "(?:'[^']*'|[-+]?\\d+(?:\\.\\d+)?|[A-Za-z_][\\w$#]*)";

    /**
     * Two equality comparisons on the <em>same</em> column joined by {@code OR} — the shape that
     * actually benefits from {@code IN}. The old rule matched {@code (\w+)\s+IN\s*\(}, i.e. it
     * detected SQL that already used {@code IN}: it never fired on an OR chain and told authors of
     * correct {@code IN} queries to rewrite them as {@code IN}.
     */
    private static final Pattern OR_CHAIN_ON_SAME_COLUMN = Pattern.compile(
            "([\\w.]+)\\s*=\\s*" + VALUE + "\\s+OR\\s+\\1\\s*=",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** {@code col = a OR col = b} → {@code col IN (a, b)}. */
    private static final Pattern OR_PAIR = Pattern.compile(
            "([\\w.]+)\\s*=\\s*(" + VALUE + ")\\s+OR\\s+\\1\\s*=\\s*(" + VALUE + ")",
            Pattern.CASE_INSENSITIVE);

    /** {@code col IN (a, b) OR col = c} → {@code col IN (a, b, c)}, to fold longer chains. */
    private static final Pattern IN_PLUS_EQUALS = Pattern.compile(
            "([\\w.]+)\\s+IN\\s*\\(([^()]*)\\)\\s+OR\\s+\\1\\s*=\\s*(" + VALUE + ")",
            Pattern.CASE_INSENSITIVE);

    /**
     * Column on the left of a WHERE predicate.
     *
     * <p>The operator alternatives are ordered longest-first and, critically, carry no trailing
     * {@code \b}: a {@code \b} after {@code =} demands a word boundary between {@code =} and the
     * following space, which never holds, so {@code WHERE region = 'CN'} matched nothing and no
     * index was ever recommended for an equality predicate.
     */
    private static final Pattern WHERE_PREDICATE_COLUMN = Pattern.compile(
            "([\\w.\"\\[\\]`]+)\\s*(?:>=|<=|<>|!=|=|>|<|\\bIN\\b|\\bLIKE\\b)",
            Pattern.CASE_INSENSITIVE);

    private final DynamicDataSourceManager dataSourceManager;

    /**
     * Read access to whichever connection a call names.
     *
     * <p>Every metadata probe below goes through this rather than through a {@code JdbcTemplate}
     * borrowed from the connection context, so index lists, candidate columns, row counts and
     * segment sizes are subject to the same timing, audit and read-only advice as any other query.
     * The connection registry is still injected, but only for
     * {@link ByokDataSourceContext#getDialect()} — deciding which SQL to send is this service's job,
     * issuing it is not.
     */
    private final DatabaseReadOperations db;

    /**
     * 把 {@code interpretPlan} 收到的方言名解析成 {@link DatabaseDialect}。
     *
     * <p>这个入参此前从头到尾没被用过：计划文本一律按 Oracle 的词汇解释，所以 PostgreSQL 的
     * {@code Seq Scan} 匹配不上任何分支，最重要的那条全表扫描告警在 PG 上根本不会触发。
     */
    private final DialectResolver dialectResolver;

    /**
     * {@link PlanOperation} → 行内标注。
     *
     * <p>面向用户的文案（emoji、中文标签）留在服务层：方言负责认词汇，不负责措辞。文本与改造前
     * {@code interpretLine} 逐字一致，包括两个前导空格。
     */
    private static final Map<PlanOperation, String> PLAN_LINE_LABELS = Map.of(
            PlanOperation.FULL_TABLE_SCAN, "  ⚠️ 全表扫描，建议检查索引",
            PlanOperation.INDEX_RANGE_SCAN, "  ✅ 索引范围扫描",
            PlanOperation.INDEX_UNIQUE_SCAN, "  ✅ 唯一索引访问",
            PlanOperation.NESTED_LOOP_JOIN, "  ⚠️ 嵌套循环连接",
            PlanOperation.HASH_JOIN, "  ℹ️ 哈希连接",
            PlanOperation.SORT, "  ℹ️ 排序操作");

    public OptimizerServiceImpl(DynamicDataSourceManager dataSourceManager,
                                DatabaseReadOperations db,
                                DialectResolver dialectResolver) {
        this.dataSourceManager = dataSourceManager;
        this.db = db;
        this.dialectResolver = dialectResolver;
    }

    // ─── Query Analysis ────────────────────────────────────────────────────────

    @Override
    public PerformanceReport analyzeQuery(String sql, String connection) {
        long startMs = System.currentTimeMillis();
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            String trimmedSql = sql.trim();
            String upperSql = trimmedSql.toUpperCase();

            // Run EXPLAIN
            List<String> planRows = getExplainPlan(connection, trimmedSql);

            // Extract table references
            List<String> tables = extractTableNames(trimmedSql);

            // Gather stats
            long estimatedRows = 0;
            long sizeMb = 0;
            if (!tables.isEmpty()) {
                estimatedRows = getEstimatedRowCount(connection, dialect, tables.get(0));
                sizeMb = getTableSizeMb(connection, dialect, tables.get(0));
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
            log.warn("Query analysis failed: {}", e.getMessage(), e);
            long durationMs = System.currentTimeMillis() - startMs;
            return new PerformanceReport(connection, "unknown", sql, null, List.of(),
                    durationMs, List.of("分析失败"),
                    List.of(), List.of(), 0, 0, List.of());
        }
    }

    // ─── Index Recommendations ─────────────────────────────────────────────────

    @Override
    public List<IndexRecommendation> recommendIndexes(String tableName, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            String normalizedTable = dialect.normalizeTableName(tableName);

            // 1. Get existing indexes
            Set<String> indexedColumns = getIndexedColumns(connection, dialect, normalizedTable);

            // 2. Get candidate columns (not yet indexed)
            String candSql = dialect.candidateColumnsForIndexSql(normalizedTable);
            List<Map<String, Object>> candidates = candSql != null
                    ? db.queryRows(candSql, connection, normalizedTable)
                    : List.of();

            List<IndexRecommendation> recs = new ArrayList<>();
            int priority = 1;
            for (Map<String, Object> row : candidates) {
                String col = columnNameOf(row);
                if (col == null || indexedColumns.contains(col.toUpperCase())) continue;

                // Determine index type and recommend SQL
                String idxType = determineIndexType(row);
                String createSql = buildCreateIndexSql(dialect, normalizedTable, col, idxType);
                String reason = buildIndexReason(row);

                recs.add(new IndexRecommendation(normalizedTable, col, idxType, createSql, reason, priority++));
            }

            // 3. Composite index suggestion: look for WHERE + JOIN pairs in common queries
            recs.addAll(suggestCompositeIndexes(connection, dialect, normalizedTable, indexedColumns));

            return recs;
        } catch (Exception e) {
            log.warn("Index recommendation failed for {}: {}", tableName, e.getMessage(), e);
            return List.of();
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
        if (SELECT_STAR.matcher(sql).find()) {
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

        // OR chain of equality comparisons on the same column
        if (OR_CHAIN_ON_SAME_COLUMN.matcher(sql).find()) {
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
                    "NOT IN 在子查询结果含 NULL 时整体退化为 UNKNOWN（一行都不返回），且优化器难以走索引。"
                            + "transformedSql 中的 NOT EXISTS 改写已补齐 NULL 守卫（外层列 IS NOT NULL，"
                            + "并在子查询列出现 NULL 时不返回任何行），与原式严格等价；"
                            + "若 transformedSql 与原 SQL 相同，说明该语句形态不支持安全改写，需人工处理。",
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
            String normalizedTable = dialect.normalizeTableName(tableName);

            long rows = getEstimatedRowCount(connection, dialect, normalizedTable);
            long sizeMb = getTableSizeMb(connection, dialect, normalizedTable);
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
            log.warn("Table analysis failed: {}", e.getMessage(), e);
            return new PerformanceReport(connection, "unknown", null, tableName,
                    List.of(), 0, List.of("分析失败"),
                    List.of(), List.of(), 0, 0, List.of());
        }
    }

    // ─── Plan Interpretation ───────────────────────────────────────────────────

    @Override
    public String interpretPlan(String planText, String dialect) {
        DatabaseDialect planDialect = resolvePlanDialect(dialect);
        StringBuilder sb = new StringBuilder();
        // 逐行分类的结果，摘要完全由它驱动。原来摘要是对全文 contains，比逐行更粗：
        // upper.contains("INDEX") 会被表名里的 INDEX 命中，于是一份没有任何索引访问的计划也报
        // 「✅ 检测到索引访问，符合预期」。
        Set<PlanOperation> operations = EnumSet.noneOf(PlanOperation.class);
        int lineNum = 1;

        for (String line : planText.split("\n")) {
            PlanOperation operation = planDialect.classifyPlanLine(line);
            operations.add(operation);
            sb.append(String.format("[%d] %s", lineNum++, formatLine(line)));
            String label = PLAN_LINE_LABELS.get(operation);
            if (label != null) {
                sb.append(label);
            }
            sb.append("\n");
        }
        operations.remove(PlanOperation.OTHER);

        // Summary
        sb.append("\n── 解读摘要 ──\n");
        if (operations.contains(PlanOperation.FULL_TABLE_SCAN)) {
            sb.append("⚠️  检测到全表扫描，考虑添加索引或使用物化视图\n");
        }
        if (operations.contains(PlanOperation.NESTED_LOOP_JOIN)) {
            sb.append("⚠️  嵌套循环连接：大数据量时建议改用 HASH JOIN\n");
        }
        if (operations.contains(PlanOperation.HASH_JOIN)) {
            sb.append("ℹ️  哈希连接：确保连接列有索引支持\n");
        }
        // 「哈希排序」保留原文案，判据改成「同时出现排序与哈希连接」。原判据是全文
        // contains("SORT") && contains("HASH")，会被 HASH GROUP BY、HASH UNIQUE 甚至名字里带 HASH 的
        // 表命中；没有为它新增枚举值，因为一行计划只做一件事，「哈希排序」是跨行的组合而不是一个操作。
        // 这是一处收窄：哈希操作不是连接时不再提示。spill 的成因（工作区内存不足）两者相同，措辞未变。
        if (operations.contains(PlanOperation.SORT) && operations.contains(PlanOperation.HASH_JOIN)) {
            sb.append("ℹ️  哈希排序：内存不足时会 spill 到磁盘\n");
        }
        if (operations.contains(PlanOperation.INDEX_RANGE_SCAN)
                || operations.contains(PlanOperation.INDEX_UNIQUE_SCAN)) {
            sb.append("✅ 检测到索引访问，符合预期\n");
        }
        // 一个已识别操作都没有 = 结构简单。原判据是「全文不含 TABLE ACCESS / INDEX / VIEW」，
        // 所以一份只提到 VIEW 的计划会静默不给任何结论；现在它会落到这一条。
        if (operations.isEmpty()) {
            sb.append("ℹ️  执行计划结构简单，未见明显问题\n");
        }
        return sb.toString();
    }

    /**
     * 把方言名解析成方言，解析不了就用 {@code GenericDialect}。
     *
     * <p>这是个纯文本解读工具，方言名不可靠是常态：{@code OptimizationTools.interpretPlan} 在
     * {@code dialect} 缺省时会把 <em>连接名</em> 传进来兜底。{@link DialectResolver#resolve} 对未知名字
     * 打一条 warn 后返回 {@code GenericDialect}，而 {@code GenericDialect} 用的是
     * {@link DatabaseDialect#classifyPlanLine} 的默认实现（历史上的 Oracle 形状），所以解析失败时的输出
     * 与改造前完全一致，不需要在这里另加兜底，更不该抛异常。
     *
     * <p>{@code dataSource} 传 {@code null}：本方法不连数据库，{@code dialect=auto} 同样退回 generic。
     */
    private DatabaseDialect resolvePlanDialect(String dialectName) {
        return dialectResolver.resolve(dialectName, null);
    }

    // ─── Private Helpers ───────────────────────────────────────────────────────

    /**
     * 取执行计划的文本行。
     *
     * <p>不再自己 {@code jdbc.queryForList(dialect.getExplainPlanSql(sql))}：那样在 Oracle 上永远是空的——
     * {@code EXPLAIN PLAN FOR} 不返回结果集，只往会话级临时表 {@code SYS.PLAN_TABLE$} 写行，
     * 得在同一条物理连接上查回来。两步逻辑只在 {@code ExecutionPlanRepository#explainPlanRows} 里有一份，
     * 这里经 {@link DatabaseReadOperations#explainPlanRows} 走到它，顺带吃到统一的计时与审计。
     */
    private List<String> getExplainPlan(String connection, String sql) {
        try {
            List<Map<String, Object>> rows = db.explainPlanRows(sql, connection);
            if (rows.isEmpty()) return List.of("执行计划为空：当前方言不支持 EXPLAIN，或计划只在会话输出中");
            return rows.stream()
                    .map(row -> row.values().stream()
                            .map(value -> value == null ? "" : value.toString())
                            .collect(java.util.stream.Collectors.joining(" | ")))
                    .toList();
        } catch (Exception e) {
            log.debug("Explain plan unavailable: {}", e.getMessage());
            return List.of("获取执行计划失败");
        }
    }

    private long getEstimatedRowCount(String connection, DatabaseDialect dialect, String tableName) {
        try {
            String queried = dialect.normalizeTableName(tableName);
            // getTableRowCountSql declares no placeholder: the table name is an identifier in the
            // FROM clause, which the dialect quotes into the SQL itself.
            String sql = dialect.getTableRowCountSql(queried);
            if (sql == null) return -1;
            List<Map<String, Object>> rows = db.queryRows(sql, connection);
            if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
                Object val = rows.get(0).values().iterator().next();
                return val instanceof Number n ? n.longValue() : -1;
            }
        } catch (Exception e) {
            log.debug("Row count unavailable: {}", e.getMessage());
        }
        return -1;
    }

    private long getTableSizeMb(String connection, DatabaseDialect dialect, String tableName) {
        try {
            String queried = dialect.normalizeTableName(tableName);
            String sql = dialect.estimateTableSizeSql(queried, null);
            if (sql == null) return -1;
            // One placeholder per the DatabaseDialect contract; GenericDialect renders a constant row
            // with no placeholder, so only bind when the dialect actually declared one.
            List<Map<String, Object>> rows = sql.contains("?")
                    ? db.queryRows(sql, connection, queried)
                    : db.queryRows(sql, connection);
            if (!rows.isEmpty()) {
                // Read the column by name: every dialect's size query starts with segment_name, so
                // taking the first value returned the table name and degraded the size to -1.
                Object val = sizeColumnOf(rows.get(0));
                return val instanceof Number n ? n.longValue() : -1;
            }
        } catch (Exception e) {
            log.debug("Table size unavailable: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * The {@code size_mb} column the dialect's size query declares, or {@code null} when it is
     * absent or NULL.
     *
     * <p>{@link DatabaseReadOperations#queryRows} 返回大小写不敏感的 map，所以一次查找就覆盖了
     * {@code size_mb} 与 {@code SIZE_MB}。这里不再回退到「第一个数值列」：MySQL 的 size 查询带
     * {@code GROUP BY table_name} 且选了 {@code count(*)}/{@code extents} 这类恒 ≥ 1 的列，兜底会把
     * 「1 MB」当成表大小上报，比诚实返回 -1 更有害——下游用它判断是否超过 1GB 并给出建议。
     */
    private static Object sizeColumnOf(Map<String, Object> row) {
        return row.get("size_mb");
    }

    /** Oracle and H2 label the column {@code COLUMN_NAME}; MySQL and PostgreSQL use lower case. */
    private static String columnNameOf(Map<String, Object> row) {
        Object value = row.get("column_name");
        if (value == null) {
            value = row.get("COLUMN_NAME");
        }
        if (value == null) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if ("column_name".equalsIgnoreCase(entry.getKey())) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * 计划告警。前四条由 {@link DatabaseDialect#classifyPlanLine(String)} 的逐行分类结果驱动。
     *
     * <p>原来是把所有计划行 {@code join(" ")} 成一段全文再 {@code contains}，有两处坏处：词汇只对 Oracle
     * 正确（PG 的 {@code Seq Scan} 匹配不上 {@code TABLE ACCESS}，最重要的那条全表扫描告警在 PG 上根本
     * 不触发），而且全文匹配会跨行凑数——一行里的 {@code TABLE ACCESS} 加另一行里的 {@code FULL} 就够
     * 报一条全表扫描。逐行分类同时解决这两点。
     *
     * <p>{@code upperSql} 仍然只用于与计划无关的 SQL 文本判据（{@code ORDER BY} / DISTINCT / UNION）。
     */
    private List<String> analyzePlan(List<String> planRows, String upperSql, DatabaseDialect dialect) {
        List<String> warnings = new ArrayList<>();
        Set<PlanOperation> operations = EnumSet.noneOf(PlanOperation.class);
        for (String row : planRows) {
            operations.add(dialect.classifyPlanLine(row));
        }

        if (operations.contains(PlanOperation.FULL_TABLE_SCAN)) {
            warnings.add("⚠️  全表扫描 (FULL TABLE SCAN) — 建议为目标表添加合适的索引");
        }
        if (operations.contains(PlanOperation.NESTED_LOOP_JOIN)) {
            warnings.add("⚠️  嵌套循环连接 — 大数据量时改用 HASH JOIN 或确保连接列有索引");
        }
        if (operations.contains(PlanOperation.HASH_JOIN)) {
            warnings.add("ℹ️  哈希连接 — 确认参与连接的列已建立索引");
        }
        // 合取的另一半刻意仍对 SQL 文本取：计划里出现排序、且 SQL 真的写了 ORDER BY，才说「加覆盖索引可
        // 避免文件排序」——排序也可能来自 GROUP BY / DISTINCT，那时这条建议是错的。
        if (operations.contains(PlanOperation.SORT) && upperSql.contains("ORDER BY")) {
            warnings.add("ℹ️  检测到排序操作 — 添加覆盖索引可避免文件排序");
        }
        // 刻意的例外：INDEX SKIP SCAN 是 Oracle 独有算子，PlanOperation 的取值是跨方言语义，为它加一个
        // 只有 Oracle 有的枚举值会污染那套语义。所以这一条保留原样的全文 contains，只在 Oracle 词汇下命中。
        if (String.join(" ", planRows).toUpperCase().contains("INDEX SKIP SCAN")) {
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

    private Set<String> getIndexedColumns(String connection, DatabaseDialect dialect, String tableName) {
        Set<String> cols = new HashSet<>();
        try {
            String sql = dialect.listTableIndexesSql(tableName);
            if (sql == null) return cols;
            List<Map<String, Object>> rows = db.queryRows(sql, connection, tableName);
            for (Map<String, Object> row : rows) {
                Object col = row.get("column_name");
                if (col == null) {
                    col = row.get("COLUMN_NAME");
                }
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

        String whereClause = sql.substring(whereIdx + 6).trim().toUpperCase();
        String table = tables.get(0);
        String normalizedTable = dialect.normalizeTableName(table);

        // The index list is a property of the table, not of the column being examined: fetching it
        // once outside the loop replaces one metadata query per distinct WHERE column with a single
        // one, and every one of those queries returned the same rows anyway. The facade resolves the
        // connection itself, so no context has to be acquired here at all.
        Set<String> indexedCols = getIndexedColumns(connection, dialect, normalizedTable);

        // Simple extraction: column = value or column IN (...)
        Matcher m = WHERE_PREDICATE_COLUMN.matcher(whereClause);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String col = m.group(1).replaceAll("[\"\\[\\]`]", "").toUpperCase();
            if (!seen.add(col) || indexedCols.contains(col)) {
                continue;
            }
            String createSql = buildCreateIndexSql(dialect, normalizedTable, col, "BTREE");
            recs.add(new IndexRecommendation(normalizedTable, col, "BTREE",
                    createSql, "WHERE 条件中未索引的列: " + col, 1));
        }
        return recs;
    }

    private List<IndexRecommendation> suggestCompositeIndexes(String connection,
                                                              DatabaseDialect dialect,
                                                              String tableName,
                                                              Set<String> indexedColumns) {
        // Composite index: common pattern WHERE col1 = ? AND col2 = ?
        List<IndexRecommendation> recs = new ArrayList<>();
        try {
            String candSql = dialect.candidateColumnsForIndexSql(tableName);
            if (candSql == null) return recs;
            List<Map<String, Object>> candidates =
                    db.queryRows(candSql, connection, tableName);
            if (candidates.size() >= 2) {
                String col1 = columnNameOf(candidates.get(0));
                String col2 = columnNameOf(candidates.get(1));
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
        String current = sql;
        for (int pass = 0; pass < 10; pass++) {
            String folded = OR_PAIR.matcher(current).replaceAll("$1 IN ($2, $3)");
            folded = IN_PLUS_EQUALS.matcher(folded).replaceAll("$1 IN ($2, $3)");
            if (folded.equals(current)) {
                return current;
            }
            current = folded;
        }
        return current;
    }

    /**
     * Delegates to {@link NotInToNotExistsRewriter}, which rewrites on the JSQLParser AST and
     * returns the original SQL whenever a safe rewrite is not possible. The suggestion text below
     * still explains the transformation, so an unrewritable query yields advice rather than SQL the
     * model must not execute.
     */
    private String rewriteNotInToNotExists(String sql) {
        return NotInToNotExistsRewriter.rewrite(sql);
    }
}
