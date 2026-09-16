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
import com.entropy.database.mcp.byok.ByokInfrastructure;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.byok.StatementTemplates;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import com.entropy.database.mcp.properties.StatementTimeouts;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.dialect.OracleDialect;
import com.entropy.database.mcp.dialect.PlanOperation;
import com.entropy.database.mcp.dialect.PostgresDialect;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the three rewrite rules that were either dead or actively harmful.
 *
 * <p>{@code SELECT_STAR} could never fire because of a word boundary after {@code \*};
 * {@code OR_TO_IN} matched SQL that already used {@code IN} instead of the OR chain it is meant to
 * flag; and the {@code NOT IN} rewrite produced statements no database accepts. The rewrite rules
 * need no connection, so the datasource registry is a bare mock — any use of it would be a defect.
 */
class OptimizerServiceImplTest {

    private final OptimizerServiceImpl optimizer =
            new OptimizerServiceImpl(mock(DynamicDataSourceManager.class),
                    mock(DatabaseReadOperations.class), new DialectResolver());

    private List<String> suggestionTypes(String sql) {
        return optimizer.suggestRewrites(sql).stream().map(RewriteSuggestion::type).toList();
    }

    private String transformed(String sql, String type) {
        return optimizer.suggestRewrites(sql).stream()
                .filter(s -> s.type().equals(type))
                .map(RewriteSuggestion::transformedSql)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " suggestion for: " + sql));
    }

    // ─── SELECT * ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "SELECT_STAR fires for [{0}]")
    @ValueSource(strings = {
            "SELECT * FROM t",
            "select * from t where id = 1",
            "SELECT   *   FROM t",
            "SELECT *\nFROM t\nWHERE id = 1"
    })
    void selectStarIsDetected(String sql) {
        assertThat(suggestionTypes(sql)).contains("SELECT_STAR");
    }

    @Test
    @DisplayName("the suggestion carries a column-list template, not the original SQL")
    void selectStarSuggestsColumnList() {
        assertThat(transformed("SELECT * FROM t", "SELECT_STAR"))
                .isEqualTo("SELECT <column1>, <column2>, ... FROM t");
    }

    @ParameterizedTest(name = "SELECT_STAR stays silent for [{0}]")
    @ValueSource(strings = {
            "SELECT COUNT(*) FROM t",
            "SELECT id, name FROM t"
    })
    void selectStarIsNotOverEager(String sql) {
        assertThat(suggestionTypes(sql)).doesNotContain("SELECT_STAR");
    }

    // ─── OR chains ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an OR chain on one column is flagged and folded into IN")
    void orChainIsDetected() {
        String sql = "SELECT id FROM t WHERE a = 1 OR a = 2";

        assertThat(suggestionTypes(sql)).contains("OR_TO_IN");
        assertThat(transformed(sql, "OR_TO_IN")).isEqualTo("SELECT id FROM t WHERE a IN (1, 2)");
    }

    @Test
    @DisplayName("a longer OR chain folds into a single IN list")
    void longOrChainIsFolded() {
        String sql = "SELECT id FROM t WHERE a = 'x' OR a = 'y' OR a = 'z'";

        assertThat(suggestionTypes(sql)).contains("OR_TO_IN");
        assertThat(transformed(sql, "OR_TO_IN"))
                .isEqualTo("SELECT id FROM t WHERE a IN ('x', 'y', 'z')");
    }

    @ParameterizedTest(name = "OR_TO_IN stays silent for [{0}]")
    @ValueSource(strings = {
            "SELECT id FROM t WHERE a IN (1, 2)",
            "SELECT id FROM t WHERE a = 1 AND b = 2",
            "SELECT id FROM t WHERE a = 1 OR b = 2"
    })
    void orChainIsNotOverEager(String sql) {
        assertThat(suggestionTypes(sql)).doesNotContain("OR_TO_IN");
    }

    // ─── NOT IN → NOT EXISTS ──────────────────────────────────────────────

    @Test
    @DisplayName("the NOT IN rewrite merges the subquery WHERE and correlates on real aliases")
    void notInIsRewrittenToNotExists() {
        String sql = "SELECT * FROM a WHERE id NOT IN (SELECT uid FROM b WHERE x = 1)";

        String rewritten = transformed(sql, "NOT_IN_SUBQUERY");

        assertThat(rewritten).contains("NOT EXISTS");
        assertThat(rewritten).doesNotContain("NOT IN");
        assertThat(rewritten).doesNotContain("WHERE WHERE");
        assertThat(rewritten).doesNotContain("outer.");
        assertThat(rewritten).doesNotContain("id NOT EXISTS");
        assertThat(rewritten).contains("b.uid = a.id");
        assertThatCode(() -> CCJSqlParserUtil.parse(rewritten)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "rewrite of [{0}] parses or is left untouched")
    @ValueSource(strings = {
            "SELECT * FROM a WHERE id NOT IN (SELECT uid FROM b)",
            "SELECT * FROM a WHERE id NOT IN (SELECT uid FROM b WHERE UPPER(name) = 'X' AND (n > 1))",
            "SELECT * FROM a x WHERE x.id NOT IN (SELECT uid FROM b WHERE x = 1)",
            "SELECT * FROM a WHERE k = 1 AND id NOT IN (SELECT uid FROM b) AND j = 2",
            "SELECT * FROM a WHERE id NOT IN (1, 2, 3)",
            "SELECT * FROM a WHERE id IN (SELECT uid FROM b)",
            "SELECT * FROM a WHERE (id, k) NOT IN (SELECT uid, kid FROM b)",
            "this is not sql at all"
    })
    void rewriteNeverProducesInvalidSql(String sql) {
        String rewritten = NotInToNotExistsRewriter.rewrite(sql);

        if (rewritten.equals(sql)) {
            return;   // conservatively left alone, which is always acceptable
        }
        assertThatCode(() -> CCJSqlParserUtil.parse(rewritten)).doesNotThrowAnyException();
        assertThat(rewritten).doesNotContain("WHERE WHERE");
    }

    @Test
    @DisplayName("a subquery holding a function call is rewritten without truncation")
    void functionCallInSubquerySurvives() {
        String sql = "SELECT * FROM a WHERE id NOT IN (SELECT uid FROM b WHERE UPPER(name) = 'X')";

        String rewritten = NotInToNotExistsRewriter.rewrite(sql);

        assertThat(rewritten).contains("UPPER(name) = 'X'");
        assertThat(rewritten).contains("NOT EXISTS");
        assertThatCode(() -> CCJSqlParserUtil.parse(rewritten)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unsupported shape yields the original SQL rather than a broken statement")
    void unsupportedShapeIsLeftAlone() {
        String sql = "SELECT * FROM a WHERE id NOT IN (SELECT uid FROM b UNION SELECT uid FROM c)";

        assertThat(NotInToNotExistsRewriter.rewrite(sql)).isEqualTo(sql);
    }

    @Test
    @DisplayName("NOT_IN_SUBQUERY is still reported, with advice, when no rewrite is possible")
    void adviceSurvivesWithoutRewrite() {
        String sql = "SELECT * FROM a WHERE id NOT IN (SELECT uid FROM b UNION SELECT uid FROM c)";

        RewriteSuggestion suggestion = optimizer.suggestRewrites(sql).stream()
                .filter(s -> s.type().equals("NOT_IN_SUBQUERY"))
                .findFirst()
                .orElseThrow();

        assertThat(suggestion.transformedSql()).isEqualTo(sql);
        assertThat(suggestion.reason()).contains("NOT EXISTS");
    }

    @Test
    @DisplayName("the rewrite carries the NULL guards the equivalence depends on")
    void notInRewriteEmitsNullGuards() {
        String rewritten = NotInToNotExistsRewriter.rewrite(
                "SELECT * FROM a WHERE id NOT IN (SELECT uid FROM b)");

        // 外层列守卫 + 子查询列出现 NULL 的守卫，缺任何一段改写就不再等价
        assertThat(rewritten).contains("a.id IS NOT NULL");
        assertThat(rewritten).contains("b.uid IS NULL");
        assertThat(rewritten).contains("b.uid = a.id");
        assertThatCode(() -> CCJSqlParserUtil.parse(rewritten)).doesNotThrowAnyException();
    }

    // ─── NOT IN → NOT EXISTS, behaviour compared on a real database ────────

    @Nested
    @DisplayName("the NOT IN rewrite returns the same rows as the original")
    class NotInEquivalence {

        private JdbcTemplate jdbcTemplate;

        @BeforeEach
        void createSchema() {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:notinequiv;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            jdbcTemplate = new JdbcTemplate(ds);
            jdbcTemplate.execute("DROP TABLE IF EXISTS A");
            jdbcTemplate.execute("DROP TABLE IF EXISTS B");
            jdbcTemplate.execute("CREATE TABLE A (ID INT)");
            jdbcTemplate.execute("CREATE TABLE B (UID INT, KIND VARCHAR(10))");
            // 外层含 NULL：原式对该行是 UNKNOWN，不返回
            for (Integer id : new Integer[]{1, 2, 3, null}) {
                jdbcTemplate.update("INSERT INTO A VALUES (?)", id);
            }
        }

        @Test
        @DisplayName("with a NULL in the subquery both forms return nothing")
        void nullInSubqueryYieldsNoRows() {
            jdbcTemplate.update("INSERT INTO B VALUES (?, ?)", 1, "keep");
            jdbcTemplate.update("INSERT INTO B VALUES (?, ?)", null, "keep");

            // 裸的 NOT EXISTS 会在这里返回 A 的全部非匹配行，这就是原来那版改写的错处
            assertSameRows("SELECT ID FROM A WHERE ID NOT IN (SELECT UID FROM B)");
        }

        @Test
        @DisplayName("without a NULL in the subquery both forms skip the NULL outer row")
        void noNullInSubquerySkipsTheNullOuterRow() {
            jdbcTemplate.update("INSERT INTO B VALUES (?, ?)", 1, "keep");
            jdbcTemplate.update("INSERT INTO B VALUES (?, ?)", 2, "keep");

            assertSameRows("SELECT ID FROM A WHERE ID NOT IN (SELECT UID FROM B)");
        }

        @Test
        @DisplayName("an empty subquery returns every non-NULL outer row in both forms")
        void emptySubqueryReturnsEveryNonNullRow() {
            assertSameRows("SELECT ID FROM A WHERE ID NOT IN (SELECT UID FROM B)");
        }

        @Test
        @DisplayName("the subquery's own WHERE is preserved by both guards")
        void subqueryWhereIsPreserved() {
            jdbcTemplate.update("INSERT INTO B VALUES (?, ?)", 1, "keep");
            jdbcTemplate.update("INSERT INTO B VALUES (?, ?)", null, "drop");
            jdbcTemplate.update("INSERT INTO B VALUES (?, ?)", 2, "drop");

            // 过滤掉 kind='drop' 后子查询里没有 NULL 了，两种写法都应返回 2 和 3
            assertSameRows("SELECT ID FROM A WHERE ID NOT IN "
                    + "(SELECT UID FROM B WHERE KIND = 'keep')");
        }

        @Test
        @DisplayName("the rewrite keeps its meaning when the NOT IN sits under an OR")
        void rewriteUnderAnOrKeepsPrecedence() {
            jdbcTemplate.update("INSERT INTO B VALUES (?, ?)", 1, "keep");
            jdbcTemplate.update("INSERT INTO B VALUES (?, ?)", null, "keep");

            assertSameRows("SELECT ID FROM A WHERE ID = 3 OR ID NOT IN (SELECT UID FROM B)");
        }

        private void assertSameRows(String sql) {
            String rewritten = NotInToNotExistsRewriter.rewrite(sql);
            assertThat(rewritten).isNotEqualTo(sql);   // 这些形态都必须真的被改写过

            assertThat(rows(rewritten))
                    .describedAs("rewritten [%s]", rewritten)
                    .isEqualTo(rows(sql));
        }

        private List<Map<String, Object>> rows(String sql) {
            return jdbcTemplate.queryForList(sql).stream()
                    .sorted(java.util.Comparator.comparing(row -> String.valueOf(row.get("ID"))))
                    .toList();
        }
    }

    // ─── interpretPlan ────────────────────────────────────────────────────

    @Test
    @DisplayName("plan interpretation annotates full scans and summarises")
    void planInterpretationAnnotatesFullScan() {
        String interpretation = optimizer.interpretPlan(
                "TABLE ACCESS FULL ORDERS\nNESTED LOOPS", "oracle");

        assertThat(interpretation).contains("全表扫描");
        assertThat(interpretation).contains("嵌套循环");
        assertThat(interpretation).contains("解读摘要");
    }

    /** Oracle 计划样例，覆盖 interpretLine 的全部 6 个分支。 */
    private static final String ORACLE_PLAN = """
            SELECT STATEMENT
             HASH JOIN
              TABLE ACCESS FULL ORDERS
              NESTED LOOPS
               INDEX RANGE SCAN IDX_ORDERS_REGION
               INDEX UNIQUE SCAN PK_CUSTOMERS
              SORT ORDER BY""";

    @Test
    @DisplayName("Oracle 计划的逐行标注与摘要逐字不变")
    void oraclePlanInterpretationIsByteForByteUnchanged() {
        assertThat(optimizer.interpretPlan(ORACLE_PLAN, "oracle")).isEqualTo("""
                [1] SELECT STATEMENT
                [2]  HASH JOIN  ℹ️ 哈希连接
                [3]   TABLE ACCESS FULL ORDERS  ⚠️ 全表扫描，建议检查索引
                [4]   NESTED LOOPS  ⚠️ 嵌套循环连接
                [5]    INDEX RANGE SCAN IDX_ORDERS_REGION  ✅ 索引范围扫描
                [6]    INDEX UNIQUE SCAN PK_CUSTOMERS  ✅ 索引范围扫描
                [7]   SORT ORDER BY  ℹ️ 排序操作

                ── 解读摘要 ──
                ⚠️  检测到全表扫描，考虑添加索引或使用物化视图
                ⚠️  嵌套循环连接：大数据量时建议改用 HASH JOIN
                ℹ️  哈希连接：确保连接列有索引支持
                ℹ️  哈希排序：内存不足时会 spill 到磁盘
                ✅ 检测到索引访问，符合预期
                """);
    }

    /** 真实的 PG {@code EXPLAIN} 片段：Seq Scan 是全表扫描，改造前一条都匹配不上。 */
    private static final String POSTGRES_PLAN = """
            Hash Join  (cost=1.09..2.21 rows=6 width=68)
              Hash Cond: (o.customer_id = c.id)
              ->  Seq Scan on orders o  (cost=0.00..1.06 rows=6 width=40)
              ->  Hash  (cost=1.04..1.04 rows=4 width=36)
                    ->  Index Scan using customers_pkey on customers c  (cost=0.00..1.04 rows=4 width=36)""";

    @Test
    @DisplayName("PostgreSQL 的 Seq Scan 现在会触发全表扫描告警")
    void postgresSeqScanNowRaisesTheFullScanWarning() {
        String interpretation = optimizer.interpretPlan(POSTGRES_PLAN, "postgresql");

        // 这就是被修掉的 bug：Seq Scan 不含 "TABLE ACCESS"，Oracle 词汇下这两条都不会出现。
        assertThat(interpretation).contains("⚠️ 全表扫描，建议检查索引");
        assertThat(interpretation).contains("⚠️  检测到全表扫描，考虑添加索引或使用物化视图");
        assertThat(interpretation).contains("ℹ️ 哈希连接");
        assertThat(interpretation).contains("✅ 索引范围扫描");
        assertThat(interpretation).doesNotContain("执行计划结构简单");
    }

    @Test
    @DisplayName("同一份 PG 计划在改造前（Oracle 词汇）拿不到全表扫描结论")
    void theSamePostgresPlanUnderOracleVocabularyMissesTheFullScan() {
        // 未知方言名退回 GenericDialect，即历史上的 Oracle 形状——这里断言的正是「不修就是这样」。
        String asOracle = optimizer.interpretPlan(POSTGRES_PLAN, "oracle");

        assertThat(asOracle).doesNotContain("检测到全表扫描");
    }

    @Test
    @DisplayName("方言名为空或无法识别时不抛异常，退回 Oracle 形状的默认解读")
    void unresolvableDialectNameFallsBackInsteadOfThrowing() {
        String viaConnectionName = optimizer.interpretPlan(ORACLE_PLAN, "prod-oracle-01");
        String viaBlank = optimizer.interpretPlan(ORACLE_PLAN, "");
        String viaNull = optimizer.interpretPlan(ORACLE_PLAN, null);

        assertThat(viaConnectionName).isEqualTo(optimizer.interpretPlan(ORACLE_PLAN, "oracle"));
        assertThat(viaBlank).isEqualTo(viaConnectionName);
        assertThat(viaNull).isEqualTo(viaConnectionName);
    }

    @Test
    @DisplayName("表名里带 INDEX 不再被误报成索引访问")
    void aTableNamedIndexNoLongerCountsAsIndexAccess() {
        String interpretation = optimizer.interpretPlan("Seq Scan on index_registry", "postgresql");

        assertThat(interpretation).doesNotContain("检测到索引访问");
        assertThat(interpretation).contains("检测到全表扫描");
    }

    @Test
    @DisplayName("认不出任何操作时给「结构简单」的结论")
    void anUnrecognisedPlanFallsBackToTheSimpleStructureVerdict() {
        assertThat(optimizer.interpretPlan("Result  (cost=0.00..0.01 rows=1 width=4)", "postgresql"))
                .contains("ℹ️  执行计划结构简单，未见明显问题");
    }

    // ─── analyzeQuery 的计划告警 ──────────────────────────────────────────

    /**
     * {@code analyzeQuery} 的告警判据。
     *
     * <p>改造前 {@code analyzePlan} 把所有计划行 {@code join(" ")} 成一段全文再按 Oracle 词汇
     * {@code contains}，于是 PG 的 {@code Seq Scan} 匹配不上 {@code TABLE ACCESS}——最重要的那条全表扫描
     * 告警在 PG 上根本不触发；而全文匹配还能跨行凑数：一行里的 {@code TABLE ACCESS} 配另一行里的
     * {@code FULL} 就够报一条全表扫描。
     *
     * <p>计划行由 {@link CannedPlanReads} 直接给出，不连真库：这里钉的是词汇解读，不是 EXPLAIN 本身。
     */
    @Nested
    @DisplayName("analyzeQuery 的计划告警按方言词汇解读")
    class PlanWarnings {

        private JdbcTemplate jdbcTemplate;

        @BeforeEach
        void createDataSource() {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:planwarnings;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            jdbcTemplate = new JdbcTemplate(ds);
        }

        private List<String> warningsFor(DatabaseDialect dialect, String sql, String... planLines) {
            ByokDataSourceContext ctx = new ByokDataSourceContext("h2-plan-warnings",
                    jdbcTemplate.getDataSource(), dialect,
                    StatementTemplates.over(jdbcTemplate.getDataSource(), jdbcTemplate,
                            StatementTimeouts.defaults()),
                    new ByokInfrastructure(null, null, null, null, null, null));
            DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
            when(manager.acquire(anyString())).thenReturn(ctx);
            OptimizerServiceImpl service = new OptimizerServiceImpl(manager,
                    new CannedPlanReads(List.of(planLines)), new DialectResolver());

            return service.analyzeQuery(sql, "h2-plan-warnings").warnings();
        }

        @Test
        @DisplayName("PostgreSQL 的 Seq Scan 现在会产出全表扫描告警")
        void postgresSeqScanNowRaisesTheFullScanWarning() {
            List<String> warnings = warningsFor(new PostgresDialect(),
                    "SELECT ID FROM ORDERS WHERE REGION = 'CN' ORDER BY CREATED_AT",
                    "Sort  (cost=1.11..1.12 rows=6 width=68)",
                    "  Sort Key: o.created_at",
                    "  ->  Hash Join  (cost=1.09..2.21 rows=6 width=68)",
                    "        ->  Seq Scan on orders o  (cost=0.00..1.06 rows=6 width=40)",
                    "        ->  Nested Loop  (cost=0.29..8.31 rows=1 width=40)");

            // 这就是被修掉的漏报：Seq Scan 不含 "TABLE ACCESS"，Oracle 词汇下这条告警不会出现。
            assertThat(warnings).contains("⚠️  全表扫描 (FULL TABLE SCAN) — 建议为目标表添加合适的索引");
            assertThat(warnings).contains("⚠️  嵌套循环连接 — 大数据量时改用 HASH JOIN 或确保连接列有索引");
            assertThat(warnings).contains("ℹ️  哈希连接 — 确认参与连接的列已建立索引");
            assertThat(warnings).contains("ℹ️  检测到排序操作 — 添加覆盖索引可避免文件排序");
        }

        @Test
        @DisplayName("Oracle 计划的告警列表与改造前逐字一致")
        void oraclePlanWarningsAreUnchanged() {
            List<String> warnings = warningsFor(new OracleDialect(),
                    "SELECT ID FROM ORDERS WHERE REGION = 'CN' ORDER BY CREATED_AT",
                    "|   0 | SELECT STATEMENT              |                   |",
                    "|*  1 |  HASH JOIN                    |                   |",
                    "|   2 |   TABLE ACCESS FULL           | ORDERS            |",
                    "|   3 |   NESTED LOOPS                |                   |",
                    "|*  4 |    INDEX SKIP SCAN            | IDX_ORDERS_REGION |",
                    "|   5 |   SORT ORDER BY               |                   |");

            assertThat(warnings).containsExactly(
                    "⚠️  全表扫描 (FULL TABLE SCAN) — 建议为目标表添加合适的索引",
                    "⚠️  嵌套循环连接 — 大数据量时改用 HASH JOIN 或确保连接列有索引",
                    "ℹ️  哈希连接 — 确认参与连接的列已建立索引",
                    "ℹ️  检测到排序操作 — 添加覆盖索引可避免文件排序",
                    "⚠️  索引跳过扫描 — 高基数列上可能影响性能");
        }

        /**
         * {@code INDEX SKIP SCAN} 是 Oracle 独有算子，刻意没有进 {@link PlanOperation}，所以它在别家方言
         * 的计划上也仍然只按文本命中——这里只钉住它没有被误伤。
         */
        @Test
        @DisplayName("排序告警仍然要求 SQL 真的写了 ORDER BY")
        void theSortWarningStillRequiresAnOrderByInTheSql() {
            List<String> warnings = warningsFor(new PostgresDialect(),
                    "SELECT REGION, COUNT(*) FROM ORDERS GROUP BY REGION",
                    "GroupAggregate  (cost=1.11..1.20 rows=3 width=12)",
                    "  ->  Sort  (cost=1.11..1.12 rows=6 width=8)",
                    "        ->  Seq Scan on orders o  (cost=0.00..1.06 rows=6 width=8)");

            // 排序来自 GROUP BY，「添加覆盖索引可避免文件排序」在这里是错的建议，判据保留了对 SQL 取的合取项。
            assertThat(warnings).doesNotContain("ℹ️  检测到排序操作 — 添加覆盖索引可避免文件排序");
            assertThat(warnings).contains("⚠️  全表扫描 (FULL TABLE SCAN) — 建议为目标表添加合适的索引");
        }

        @Test
        @DisplayName("一行的 TABLE ACCESS 配另一行的 FULL 不再凑成全表扫描")
        void fullScanIsNoLongerAssembledAcrossRows() {
            List<String> warnings = warningsFor(new OracleDialect(),
                    "SELECT ID FROM ORDERS WHERE REGION = 'CN'",
                    "|   1 |  TABLE ACCESS BY INDEX ROWID | ORDERS            |",
                    "|*  2 |   INDEX RANGE SCAN           | IDX_FULL_ORDERS   |");

            // 全文匹配时 "TABLE ACCESS"（第 1 行）+ "FULL"（第 2 行的索引名）足以报一条全表扫描。
            assertThat(warnings).containsExactly("✅ 执行计划无明显性能问题");
        }
    }

    /**
     * Stands in for the facade with a fixed EXPLAIN result: the plan vocabulary under test is the
     * point, and a real EXPLAIN would only produce H2's wording.
     */
    private record CannedPlanReads(List<String> planLines) implements DatabaseReadOperations {

        @Override
        public List<Map<String, Object>> explainPlanRows(String sql, String connection) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : planLines) {
                rows.add(Map.<String, Object>of("PLAN_TABLE_OUTPUT", line));
            }
            return rows;
        }

        @Override
        public List<Map<String, Object>> queryRows(String sql, String connection, Object... args) {
            return List.of();
        }

        @Override
        public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken,
                                                 String connection) {
            throw new UnsupportedOperationException("the optimizer never paginates");
        }

        @Override
        public List<Map<String, Object>> executeNamedQuery(String sql, Map<String, Object> params,
                                                           String connection) {
            throw new UnsupportedOperationException("the optimizer binds positionally");
        }

        @Override
        public PlanAnalysis explainPlan(String sql, String connection) {
            throw new UnsupportedOperationException("the optimizer reads plan rows, not verdicts");
        }
    }

    // ─── Database-backed analysis ─────────────────────────────────────────

    @Nested
    @DisplayName("against a live H2 database")
    class AgainstDatabase {

        private JdbcTemplate jdbcTemplate;
        private DynamicDataSourceManager manager;
        private DirectReads reads;
        private OptimizerServiceImpl service;

        @BeforeEach
        void createSchema() {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:optimizersvc;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            jdbcTemplate = new JdbcTemplate(ds);
            jdbcTemplate.execute("DROP TABLE IF EXISTS ORDERS");
            jdbcTemplate.execute("""
                    CREATE TABLE ORDERS (
                        ID INT PRIMARY KEY,
                        CUSTOMER_ID INT,
                        REGION VARCHAR(20),
                        STATUS VARCHAR(20)
                    )
                    """);
            jdbcTemplate.execute("CREATE INDEX IDX_ORDERS_STATUS ON ORDERS(STATUS)");

            ByokDataSourceContext ctx = new ByokDataSourceContext("h2-optimizer",
                    jdbcTemplate.getDataSource(), new MetadataAwareH2Dialect(),
                    StatementTemplates.over(jdbcTemplate.getDataSource(), jdbcTemplate,
                            StatementTimeouts.defaults()),
                    new ByokInfrastructure(null, null, null, null, null, null));
            manager = mock(DynamicDataSourceManager.class);
            when(manager.acquire(anyString())).thenReturn(ctx);
            reads = new DirectReads(jdbcTemplate, ctx);
            service = new OptimizerServiceImpl(manager, reads, new DialectResolver());
        }

        @Test
        @DisplayName("index recommendations skip columns that are already indexed")
        void recommendsOnlyUnindexedColumns() {
            List<IndexRecommendation> recs = service.recommendIndexes("ORDERS", "h2-optimizer");

            assertThat(recs).extracting(IndexRecommendation::column)
                    .contains("CUSTOMER_ID", "REGION")
                    .doesNotContain("STATUS", "ID");
            assertThat(recs).allSatisfy(rec ->
                    assertThat(rec.recommendedSql()).contains("\"ORDERS\""));
        }

        @Test
        @DisplayName("the table index list is read once, not once per WHERE column")
        void indexListIsReadOncePerQuery() {
            service.analyzeQuery(
                    "SELECT ID FROM ORDERS WHERE CUSTOMER_ID = 1 AND REGION = 'CN' AND STATUS = 'NEW'",
                    "h2-optimizer");

            // Before the lookup was hoisted out of the loop, each of the three WHERE columns added
            // another index query of its own.
            assertThat(reads.countMatching("INFORMATION_SCHEMA.INDEX_COLUMNS")).isEqualTo(1);
            // A single acquisition covers the whole analysis: the connection is only borrowed for
            // the dialect now, because the reads themselves are routed by the facade.
            verify(manager, times(1)).acquire(anyString());
        }

        @Test
        @DisplayName("query analysis reports a plan, the table and the rewrite suggestions")
        void analyzeQueryProducesAReport() {
            PerformanceReport report = service.analyzeQuery(
                    "SELECT * FROM ORDERS WHERE REGION = 'CN'", "h2-optimizer");

            assertThat(report.tableName()).isEqualTo("ORDERS");
            assertThat(report.planRows()).isNotEmpty();
            assertThat(report.rewriteSuggestions()).extracting(RewriteSuggestion::type)
                    .contains("SELECT_STAR");
            assertThat(report.indexRecommendations()).extracting(IndexRecommendation::column)
                    .contains("REGION");
            assertThat(report.actionItems()).isNotEmpty();
        }

        @Test
        @DisplayName("table analysis survives a dialect without size or row-count support")
        void analyzeTableDegradesGracefully() {
            PerformanceReport report = service.analyzeTable("ORDERS", "h2-optimizer");

            assertThat(report.tableName()).isEqualTo("ORDERS");
            assertThat(report.indexRecommendations()).isNotEmpty();
            assertThat(report.warnings()).doesNotContain("分析失败");
        }

        @Test
        @DisplayName("the row count is read, not reported as unavailable")
        void analyzeTableReadsTheRowCount() {
            jdbcTemplate.update("INSERT INTO ORDERS VALUES (1, 10, 'CN', 'NEW')");
            jdbcTemplate.update("INSERT INTO ORDERS VALUES (2, 11, 'US', 'DONE')");

            PerformanceReport report = service.analyzeTable("ORDERS", "h2-optimizer");

            // getTableRowCountSql declares no placeholder; binding one argument per '?' used to make
            // this path throw and silently degrade to -1.
            assertThat(report.estimatedRowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("the size query gets the table name bound to its single placeholder")
        void analyzeTableBindsTheTableNameForTheSizeQuery() {
            ByokDataSourceContext ctx = new ByokDataSourceContext("h2-sized",
                    jdbcTemplate.getDataSource(), new SizeAwareH2Dialect(),
                    StatementTemplates.over(jdbcTemplate.getDataSource(), jdbcTemplate,
                            StatementTimeouts.defaults()),
                    new ByokInfrastructure(null, null, null, null, null, null));
            when(manager.acquire(anyString())).thenReturn(ctx);

            PerformanceReport report = service.analyzeTable("ORDERS", "h2-sized");

            // The stub echoes 7 only when the placeholder receives 'ORDERS'; an unbound query fails
            // outright and the size falls back to -1.
            assertThat(report.tableSizeMb()).isEqualTo(7);
        }

        @Test
        @DisplayName("a NULL size_mb reports -1 rather than borrowing another numeric column")
        void nullSizeIsReportedAsUnavailable() {
            ByokDataSourceContext ctx = new ByokDataSourceContext("h2-null-size",
                    jdbcTemplate.getDataSource(), new NullSizeH2Dialect(),
                    StatementTemplates.over(jdbcTemplate.getDataSource(), jdbcTemplate,
                            StatementTimeouts.defaults()),
                    new ByokInfrastructure(null, null, null, null, null, null));
            when(manager.acquire(anyString())).thenReturn(ctx);

            PerformanceReport report = service.analyzeTable("ORDERS", "h2-null-size");

            // The fallback used to take the first numeric column — extents, or MySQL's count(*),
            // which is always >= 1 — and report "1 MB" for a table of unknown size.
            assertThat(report.tableSizeMb()).isEqualTo(-1);
            assertThat(report.warnings()).noneSatisfy(warning ->
                    assertThat(warning).contains("表大小超过 1GB"));
        }
    }

    /**
     * Stands in for the facade: routes {@code queryRows} straight at the fixture's template and
     * records every statement it was asked to run.
     *
     * <p>The service reaches the database only through {@link DatabaseReadOperations}, so a test
     * double is all that is needed here — the routing and advice the real implementation adds are
     * its own tests' business, not the optimizer's. {@code explainPlanRows} delegates to the
     * connection context's repository, which is where the two-step Oracle EXPLAIN lives and what the
     * real facade reaches as well.
     */
    private record DirectReads(JdbcTemplate jdbc, ByokDataSourceContext ctx, List<String> queries)
            implements DatabaseReadOperations {

        DirectReads(JdbcTemplate jdbc, ByokDataSourceContext ctx) {
            this(jdbc, ctx, new ArrayList<>());
        }

        @Override
        public List<Map<String, Object>> queryRows(String sql, String connection, Object... args) {
            queries.add(sql);
            return args.length == 0 ? jdbc.queryForList(sql) : jdbc.queryForList(sql, args);
        }

        @Override
        public List<Map<String, Object>> explainPlanRows(String sql, String connection) {
            return ctx.getExecutionPlanRepository().explainPlanRows(sql);
        }

        @Override
        public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken,
                                                 String connection) {
            throw new UnsupportedOperationException("the optimizer never paginates");
        }

        @Override
        public List<Map<String, Object>> executeNamedQuery(String sql, Map<String, Object> params,
                                                           String connection) {
            throw new UnsupportedOperationException("the optimizer binds positionally");
        }

        @Override
        public PlanAnalysis explainPlan(String sql, String connection) {
            throw new UnsupportedOperationException("the optimizer reads plan rows, not verdicts");
        }

        /** How often a statement containing {@code fragment} was issued. */
        long countMatching(String fragment) {
            return queries.stream().filter(q -> q.contains(fragment)).count();
        }
    }

    /** Supplies the index/candidate/EXPLAIN metadata that {@link H2Dialect} leaves unimplemented. */
    private static class MetadataAwareH2Dialect extends H2Dialect {
        @Override
        public String getExplainPlanSql(String sql) {
            return "EXPLAIN " + sql;
        }

        @Override
        public String listTableIndexesSql(String tableName) {
            return """
                    SELECT INDEX_NAME, COLUMN_NAME, 1 AS UNIQUENESS
                    FROM INFORMATION_SCHEMA.INDEX_COLUMNS
                    WHERE TABLE_NAME = ?
                    """;
        }

        @Override
        public String candidateColumnsForIndexSql(String tableName) {
            return """
                    SELECT COLUMN_NAME, IS_NULLABLE AS NULLABLE
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_NAME = ?
                    ORDER BY ORDINAL_POSITION
                    """;
        }
    }

    /**
     * Declares the size query with the contracted single placeholder, so a caller that binds nothing
     * (or binds it twice) fails instead of quietly reporting -1.
     */
    private static final class SizeAwareH2Dialect extends MetadataAwareH2Dialect {
        @Override
        public String estimateTableSizeSql(String tableName, String schema) {
            return """
                    SELECT TABLE_NAME AS segment_name, 'TABLE' AS segment_type,
                           7 AS size_mb, 1 AS extents
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_NAME = ?
                    """;
        }
    }

    /**
     * Declares {@code size_mb} but leaves it NULL, next to a numeric {@code extents} column — the
     * exact shape that made the "first numeric column" fallback report 1 MB.
     */
    private static final class NullSizeH2Dialect extends MetadataAwareH2Dialect {
        @Override
        public String estimateTableSizeSql(String tableName, String schema) {
            return """
                    SELECT TABLE_NAME AS segment_name, 'TABLE' AS segment_type,
                           CAST(NULL AS BIGINT) AS size_mb, 1 AS extents
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_NAME = ?
                    """;
        }
    }
}
