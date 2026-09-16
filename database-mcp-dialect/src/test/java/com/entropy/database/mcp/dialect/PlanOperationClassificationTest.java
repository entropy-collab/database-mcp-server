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
package com.entropy.database.mcp.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DatabaseDialect#classifyPlanLine(String)} 按各方言<em>真实的</em> EXPLAIN 输出片段钉住分类结果。
 *
 * <p>这些用例存在的原因：计划词汇原来只有一份，写在 {@code OptimizerServiceImpl.interpretLine} 里，而且
 * 是 Oracle 的词汇。于是 PostgreSQL 的 {@code Seq Scan} 匹配不上任何分支——最重要的那条全表扫描告警在
 * PG 上根本不会触发；MySQL 的 {@code type} 列词汇几乎全部失配。下面的输入都抄自真实计划输出，所以词汇
 * 走偏会在这里报出来，而不是变成「优化器对着一份全表扫描说没问题」。
 */
class PlanOperationClassificationTest {

    // ─── Oracle：默认实现，改造前的形状必须逐字保留 ────────────────────────

    @Nested
    @DisplayName("Oracle（默认实现）")
    class Oracle {

        private final OracleDialect dialect = new OracleDialect();

        @Test
        @DisplayName("TABLE ACCESS FULL 仍是全表扫描")
        void tableAccessFullIsAFullScan() {
            assertThat(dialect.classifyPlanLine(
                    "|   1 |  TABLE ACCESS FULL           | ORDERS   |  1000 | 24000 |     7   (0)|"))
                    .isEqualTo(PlanOperation.FULL_TABLE_SCAN);
        }

        @Test
        @DisplayName("INDEX RANGE SCAN 是索引范围扫描")
        void indexRangeScan() {
            assertThat(dialect.classifyPlanLine(
                    "|*  2 |   INDEX RANGE SCAN           | IDX_ORDERS_REGION |   50 |"))
                    .isEqualTo(PlanOperation.INDEX_RANGE_SCAN);
        }

        /**
         * 分支顺序有语义：{@code INDEX} + {@code RANGE|SCAN} 排在 {@code INDEX} + {@code UNIQUE} 之前，
         * 所以 {@code INDEX UNIQUE SCAN} 落在范围扫描一支。这不是笔误，是改造前的既有行为，改它会改变
         * {@code interpretPlan} 在 Oracle 上的输出。
         */
        @Test
        @DisplayName("INDEX UNIQUE SCAN 落在范围扫描一支（保留既有分支顺序）")
        void indexUniqueScanKeepsTheHistoricalBranchOrder() {
            assertThat(dialect.classifyPlanLine("|   3 |   INDEX UNIQUE SCAN | PK_CUSTOMERS |"))
                    .isEqualTo(PlanOperation.INDEX_RANGE_SCAN);
        }

        @Test
        @DisplayName("没有 SCAN 字样时才走到唯一索引访问一支")
        void uniqueWithoutScanReachesTheUniqueBranch() {
            assertThat(dialect.classifyPlanLine("INDEX UNIQUE PK_CUSTOMERS"))
                    .isEqualTo(PlanOperation.INDEX_UNIQUE_SCAN);
        }

        @ParameterizedTest
        @MethodSource("com.entropy.database.mcp.dialect.PlanOperationClassificationTest#oracleJoinsAndSorts")
        void joinsAndSorts(String planLine, PlanOperation expected) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "| Id  | Operation | Name | Rows  | Bytes | Cost (%CPU)|",
                "PLAN_TABLE_OUTPUT",
                "|   0 | SELECT STATEMENT |  |  1000 |",
                ""})
        void nonOperationLinesAreOther(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.OTHER);
        }
    }

    static Stream<Arguments> oracleJoinsAndSorts() {
        return Stream.of(
                Arguments.of("|   1 |  NESTED LOOPS |  |  1000 |", PlanOperation.NESTED_LOOP_JOIN),
                Arguments.of("|*  1 |  HASH JOIN |  |  1000 |", PlanOperation.HASH_JOIN),
                Arguments.of("|   4 |   SORT ORDER BY |  |  1000 |", PlanOperation.SORT),
                Arguments.of("|   5 |   SORT AGGREGATE |  |     1 |", PlanOperation.SORT));
    }

    // ─── PostgreSQL ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("PostgreSQL")
    class Postgres {

        private final PostgresDialect dialect = new PostgresDialect();

        /** 被修掉的 bug 本体：Seq Scan 里没有 "TABLE ACCESS"，Oracle 词汇下一条都不中。 */
        @Test
        @DisplayName("Seq Scan 是全表扫描（这就是被修掉的缺陷）")
        void seqScanIsAFullTableScan() {
            assertThat(dialect.classifyPlanLine(
                    "Seq Scan on orders  (cost=0.00..18.50 rows=850 width=244)"))
                    .isEqualTo(PlanOperation.FULL_TABLE_SCAN);
            // 同一行交给默认（Oracle）实现认不出来——这正是修复前的行为。
            assertThat(new GenericDialect().classifyPlanLine(
                    "Seq Scan on orders  (cost=0.00..18.50 rows=850 width=244)"))
                    .isEqualTo(PlanOperation.OTHER);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "->  Index Scan using idx_orders_region on orders  (cost=0.29..8.31 rows=1 width=244)",
                "->  Index Only Scan using idx_orders_region on orders  (cost=0.29..4.31 rows=1 width=4)",
                "->  Bitmap Heap Scan on orders  (cost=4.20..14.36 rows=10 width=244)",
                "      ->  Bitmap Index Scan on idx_orders_region  (cost=0.00..4.19 rows=10 width=0)"})
        void indexNodesAreRangeScans(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.INDEX_RANGE_SCAN);
        }

        @Test
        @DisplayName("Nested Loop（单数）也要认出来")
        void nestedLoopIsAJoin() {
            assertThat(dialect.classifyPlanLine("Nested Loop  (cost=0.29..16.34 rows=1 width=280)"))
                    .isEqualTo(PlanOperation.NESTED_LOOP_JOIN);
        }

        @Test
        void hashJoinIsAHashJoin() {
            assertThat(dialect.classifyPlanLine("Hash Join  (cost=1.09..2.21 rows=6 width=68)"))
                    .isEqualTo(PlanOperation.HASH_JOIN);
        }

        /** 归并连接不硬塞进 HASH_JOIN：代价模型不同，硬塞会导出错误建议。 */
        @Test
        @DisplayName("Merge Join 不表态")
        void mergeJoinIsOther() {
            assertThat(dialect.classifyPlanLine("Merge Join  (cost=2.11..3.21 rows=6 width=68)"))
                    .isEqualTo(PlanOperation.OTHER);
        }

        @Test
        void sortIsSort() {
            assertThat(dialect.classifyPlanLine("Sort  (cost=1.11..1.12 rows=6 width=68)"))
                    .isEqualTo(PlanOperation.SORT);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "->  Hash  (cost=1.04..1.04 rows=4 width=36)",
                "  Hash Cond: (o.customer_id = c.id)",
                "Planning Time: 0.123 ms",
                "Result  (cost=0.00..0.01 rows=1 width=4)"})
        void otherNodesAreOther(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.OTHER);
        }
    }

    // ─── MySQL：词边界是这里的全部难点 ──────────────────────────────────────

    /**
     * MySQL 的 {@code EXPLAIN} 是一张表，{@code OptimizerServiceImpl.getExplainPlan} 把一行的各列用
     * {@code " | "} 拼成一行文本，所以分类器看到的是整行，{@code type} 只是其中一个词。
     */
    @Nested
    @DisplayName("MySQL")
    class MySql {

        private final MySqlDialect dialect = new MySqlDialect();

        @Test
        @DisplayName("type=ALL 是全表扫描")
        void typeAllIsAFullTableScan() {
            assertThat(dialect.classifyPlanLine(
                    "1 | SIMPLE | orders | NULL | ALL | NULL | NULL | NULL | NULL | 1000 | 100.00 | Using where"))
                    .isEqualTo(PlanOperation.FULL_TABLE_SCAN);
        }

        /**
         * 词边界：{@code SMALLINT}、{@code ALLOCATION}、{@code ALLOW} 都含子串 {@code ALL}。裸的
         * {@code contains("ALL")} 会把这些行判成全表扫描，从而对一条走索引的查询建议「加索引」。
         */
        @ParameterizedTest(name = "含 ALL 子串但 type 不是 ALL：[{0}]")
        @ValueSource(strings = {
                "1 | SIMPLE | allocation | NULL | range | idx_amount | idx_amount | 5 | NULL | 42 | 100.00 | Using index condition",
                "1 | SIMPLE | metrics | NULL | index | idx_smallint_bucket | idx_smallint_bucket | 2 | NULL | 100 | 100.00 | Using index",
                "1 | SIMPLE | audit | NULL | ref | idx_allow_flag | idx_allow_flag | 2 | NULL | 8 | 100.00 | Using where",
                "1 | SIMPLE | types | NULL | index | PRIMARY | PRIMARY | 4 | NULL | 3 | 100.00 | smallint / allow / allocation"})
        void substringsOfAllAreNotFullScans(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine))
                    .as("裸 contains(\"ALL\") 会在这一行上误判成全表扫描")
                    .isNotEqualTo(PlanOperation.FULL_TABLE_SCAN);
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.INDEX_RANGE_SCAN);
        }

        @ParameterizedTest(name = "唯一访问：[{0}]")
        @ValueSource(strings = {
                "1 | SIMPLE | customers | NULL | eq_ref | PRIMARY | PRIMARY | 4 | shop.orders.customer_id | 1 | 100.00 | NULL",
                "1 | SIMPLE | settings | NULL | const | PRIMARY | PRIMARY | 4 | const | 1 | 100.00 | NULL"})
        void uniqueAccessTypes(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.INDEX_UNIQUE_SCAN);
        }

        /** {@code eq_ref} 必须整词比对：切在下划线上会把它降级成 {@code ref}。 */
        @Test
        @DisplayName("eq_ref 不会被切成 eq + ref")
        void eqRefIsNotSplitOnTheUnderscore() {
            assertThat(dialect.classifyPlanLine(
                    "1 | SIMPLE | c | NULL | eq_ref | PRIMARY | PRIMARY | 4 | shop.o.cid | 1 | 100.00 | NULL"))
                    .isEqualTo(PlanOperation.INDEX_UNIQUE_SCAN);
        }

        @ParameterizedTest(name = "范围访问：[{0}]")
        @ValueSource(strings = {
                "1 | SIMPLE | orders | NULL | range | idx_created | idx_created | 5 | NULL | 120 | 100.00 | Using where",
                "1 | SIMPLE | orders | NULL | ref | idx_region | idx_region | 83 | NULL | 12 | 100.00 | Using where",
                "1 | SIMPLE | orders | NULL | index | idx_region | idx_region | 83 | NULL | 1000 | 100.00 | Using index"})
        void rangeAccessTypes(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.INDEX_RANGE_SCAN);
        }

        @Test
        @DisplayName("Extra 里的 Using filesort 是排序")
        void usingFilesortIsASort() {
            assertThat(dialect.classifyPlanLine(
                    "1 | SIMPLE | orders | NULL | NULL | NULL | NULL | NULL | NULL | 6 | 100.00 | Using temporary; Using filesort"))
                    .isEqualTo(PlanOperation.SORT);
        }

        @Test
        @DisplayName("Extra 里的 Using join buffer 说明连接退化成块嵌套循环")
        void usingJoinBufferIsANestedLoop() {
            assertThat(dialect.classifyPlanLine(
                    "1 | SIMPLE | items | NULL | NULL | NULL | NULL | NULL | NULL | 10 | 100.00 | Using join buffer (Block Nested Loop)"))
                    .isEqualTo(PlanOperation.NESTED_LOOP_JOIN);
        }

        @Test
        @DisplayName("全表扫描优先于 Extra 里的附加动作")
        void theFullScanWinsOverExtra() {
            assertThat(dialect.classifyPlanLine(
                    "1 | SIMPLE | items | NULL | ALL | NULL | NULL | NULL | NULL | 900 | 100.00 | Using where; Using join buffer (hash join)"))
                    .isEqualTo(PlanOperation.FULL_TABLE_SCAN);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "1 | SIMPLE | NULL | NULL | NULL | NULL | NULL | NULL | NULL | NULL | NULL | Impossible WHERE",
                "id | select_type | table | partitions | type | possible_keys"})
        void unrecognisedRowsAreOther(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.OTHER);
        }
    }

    // ─── SQL Server ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("SQL Server（SHOWPLAN_TEXT）")
    class SqlServer {

        private final SqlServerDialect dialect = new SqlServerDialect();

        /** 聚簇索引就是表本身，扫它等于扫全表——把它报成「✅ 索引访问」是最误导人的一种解读。 */
        @ParameterizedTest(name = "全表扫描：[{0}]")
        @ValueSource(strings = {
                "  |--Table Scan(OBJECT:([shop].[dbo].[orders]))",
                "  |--Clustered Index Scan(OBJECT:([shop].[dbo].[orders].[PK_orders]))"})
        void tableAndClusteredIndexScansAreFullScans(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.FULL_TABLE_SCAN);
        }

        @ParameterizedTest(name = "索引查找：[{0}]")
        @ValueSource(strings = {
                "  |--Index Seek(OBJECT:([shop].[dbo].[orders].[idx_region]), SEEK:([region]='CN'))",
                "  |--Clustered Index Seek(OBJECT:([shop].[dbo].[customers].[PK_customers]), SEEK:([id]=[@1]))"})
        void seeksAreRangeScans(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.INDEX_RANGE_SCAN);
        }

        @Test
        void nestedLoopsIsAJoin() {
            assertThat(dialect.classifyPlanLine(
                    "|--Nested Loops(Inner Join, OUTER REFERENCES:([o].[customer_id]))"))
                    .isEqualTo(PlanOperation.NESTED_LOOP_JOIN);
        }

        @Test
        @DisplayName("Hash Match 就是哈希连接")
        void hashMatchIsAHashJoin() {
            assertThat(dialect.classifyPlanLine(
                    "|--Hash Match(Inner Join, HASH:([c].[id])=([o].[customer_id]))"))
                    .isEqualTo(PlanOperation.HASH_JOIN);
        }

        @Test
        void sortIsSort() {
            assertThat(dialect.classifyPlanLine("|--Sort(ORDER BY:([o].[created_at] ASC))"))
                    .isEqualTo(PlanOperation.SORT);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "|--Merge Join(Inner Join, MERGE:([c].[id])=([o].[customer_id]))",
                "|--Compute Scalar(DEFINE:([Expr1004]=CONVERT_IMPLICIT(int,[o].[qty],0)))",
                "|--Index Scan(OBJECT:([shop].[dbo].[orders].[idx_region]))"})
        void deliberatelyUnclassified(String planLine) {
            assertThat(dialect.classifyPlanLine(planLine)).isEqualTo(PlanOperation.OTHER);
        }
    }

    // ─── 刻意不覆写的方言 ──────────────────────────────────────────────────

    /** Db2 / SQLite / H2 / Generic 不覆写：走默认（Oracle 形状），未知方言不改变行为。 */
    private static Stream<DatabaseDialect> dialectsOnTheDefault() {
        return Stream.of(new Db2Dialect(), new SqliteDialect(), new H2Dialect(), new GenericDialect());
    }

    @ParameterizedTest
    @MethodSource("dialectsOnTheDefault")
    @DisplayName("未覆写的方言沿用默认实现：Oracle 词汇认得，别家词汇不猜")
    void dialectsWithoutTheirOwnVocabularyKeepTheDefault(DatabaseDialect dialect) {
        assertThat(dialect.classifyPlanLine("TABLE ACCESS FULL ORDERS"))
                .isEqualTo(PlanOperation.FULL_TABLE_SCAN);
        // 不猜：宁可 OTHER，也不要给出一条基于猜测的优化建议。
        assertThat(dialect.classifyPlanLine("Seq Scan on orders  (cost=0.00..18.50 rows=850 width=244)"))
                .isEqualTo(PlanOperation.OTHER);
        assertThat(dialect.classifyPlanLine("SCAN TABLE orders"))
                .isEqualTo(PlanOperation.OTHER);
    }

    private static Stream<DatabaseDialect> allDialects() {
        return Stream.concat(dialectsOnTheDefault(),
                Stream.of(new OracleDialect(), new PostgresDialect(),
                        new MySqlDialect(), new SqlServerDialect()));
    }

    @ParameterizedTest
    @MethodSource("allDialects")
    @DisplayName("null 行不抛异常")
    void nullLineIsOther(DatabaseDialect dialect) {
        assertThat(dialect.classifyPlanLine(null)).isEqualTo(PlanOperation.OTHER);
    }

    @ParameterizedTest
    @MethodSource("allDialects")
    @DisplayName("每个方言都对空行给出 OTHER，且不返回 null")
    void blankLineIsOther(DatabaseDialect dialect) {
        assertThat(dialect.classifyPlanLine("")).isEqualTo(PlanOperation.OTHER);
        assertThat(dialect.classifyPlanLine("   ")).isEqualTo(PlanOperation.OTHER);
    }
}
