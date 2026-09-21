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

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.OracleDialect;
import com.entropy.database.mcp.dialect.PostgresDialect;
import com.entropy.database.mcp.facade.DatabaseAdminOperations;
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import com.entropy.database.mcp.optimizer.OptimizerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code explainPlan} 的告警判据。
 *
 * <p>三处被修掉的错误结论钉在这里：{@code FILTER} 曾被报成「索引跳过扫描」（错误结论，不是漏报）；排序告警
 * 曾要求同一行计划文本里同时含 {@code SORT} 与 {@code ORDER BY}，而 {@code ORDER BY} 是 SQL 关键字，PG 的
 * {@code Sort} 节点上永不成立；告警逐行 add 且不去重，一份多行计划会返回若干条一模一样的告警。
 */
class QueryAnalysisToolsTest {

    private static final String CONNECTION = "plan-under-test";
    private static final String SQL = "SELECT ID FROM ORDERS WHERE REGION = 'CN' ORDER BY CREATED_AT";

    private static final String FULL_SCAN = "检测到全表扫描 (FULL TABLE SCAN)，建议添加索引或 WHERE 条件";
    private static final String NESTED_LOOP = "检测到嵌套循环连接，大数据量时性能较差，建议检查连接条件是否有索引";
    private static final String HASH_JOIN = "使用哈希连接，确保参与连接的列有索引支持";
    private static final String SORT = "检测到排序操作，考虑添加索引避免文件排序";
    private static final String SKIP_SCAN = "检测到索引跳过扫描，可能影响性能";
    private static final String CLEAN = "执行计划正常，无明显性能问题";

    @SuppressWarnings("unchecked")
    private static List<String> warningsFor(DatabaseDialect dialect, String... planLines) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : planLines) {
            rows.add(Map.<String, Object>of("PLAN_TABLE_OUTPUT", line));
        }
        DatabaseReadOperations reads = mock(DatabaseReadOperations.class);
        when(reads.explainPlanRows(anyString(), anyString())).thenReturn(rows);
        DatabaseAdminOperations admin = mock(DatabaseAdminOperations.class);
        when(admin.getDialect(CONNECTION)).thenReturn(dialect);

        Map<String, Object> result = new QueryAnalysisTools(reads, admin, mock(OptimizerService.class))
                .explainPlan(CONNECTION, SQL, null);

        return (List<String>) result.get("warnings");
    }

    // ─── FILTER 不再被报成索引跳过扫描 ─────────────────────────────────────

    @Test
    @DisplayName("FILTER 算子不再产出「索引跳过扫描」告警")
    void aFilterOperationIsNoLongerReportedAsAnIndexSkipScan() {
        List<String> warnings = warningsFor(new OracleDialect(),
                "|*  2 |   FILTER                     |                   |",
                "|   3 |    TABLE ACCESS BY INDEX ROWID| ORDERS           |");

        // FILTER 是常规的谓词过滤，与索引跳过扫描无关；旧判据把它贴上跳过扫描的标签。
        assertThat(warnings).doesNotContain(SKIP_SCAN);
        assertThat(warnings).containsExactly(CLEAN);
    }

    @Test
    @DisplayName("真正的 INDEX SKIP SCAN 仍然告警，文案不变")
    void aRealIndexSkipScanIsStillReported() {
        List<String> warnings = warningsFor(new OracleDialect(),
                "|*  2 |   INDEX SKIP SCAN            | IDX_ORDERS_REGION |");

        assertThat(warnings).containsExactly(SKIP_SCAN);
    }

    // ─── 排序判据不再要求计划里出现 ORDER BY ───────────────────────────────

    @Test
    @DisplayName("PostgreSQL 的 Sort 节点现在会产出排序告警")
    void postgresSortNodeNowRaisesTheSortWarning() {
        List<String> warnings = warningsFor(new PostgresDialect(),
                "Sort  (cost=1.11..1.12 rows=6 width=68)",
                "  Sort Key: o.created_at",
                "  ->  Seq Scan on orders o  (cost=0.00..1.06 rows=6 width=40)");

        // 旧判据要求同一行里同时含 SORT 与 ORDER BY，PG 的计划里没有 ORDER BY 字样，所以永不触发。
        assertThat(warnings).contains(SORT);
        // Seq Scan 的全表扫描告警同样是被修掉的漏报。
        assertThat(warnings).contains(FULL_SCAN);
    }

    @Test
    @DisplayName("Oracle 的 SORT ORDER BY 仍然告警（老判据碰巧成立的那一种）")
    void oracleSortOrderByStillWarns() {
        List<String> warnings = warningsFor(new OracleDialect(),
                "|   4 |   SORT ORDER BY              |                   |");

        assertThat(warnings).containsExactly(SORT);
    }

    // ─── 去重 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("多行同类计划只产出一条告警")
    void repeatedOperationsCollapseIntoASingleWarning() {
        List<String> warnings = warningsFor(new PostgresDialect(),
                "Nested Loop  (cost=0.29..16.34 rows=1 width=280)",
                "  ->  Seq Scan on orders o  (cost=0.00..1.06 rows=6 width=40)",
                "  ->  Nested Loop  (cost=0.29..8.31 rows=1 width=40)",
                "        ->  Seq Scan on customers c  (cost=0.00..1.04 rows=4 width=36)",
                "        ->  Seq Scan on regions r  (cost=0.00..1.02 rows=2 width=8)");

        // 改造前：3 行 Seq Scan + 2 行 Nested Loop = 5 条告警，其中只有 2 条互不相同。
        assertThat(warnings).containsExactly(FULL_SCAN, NESTED_LOOP);
    }

    // ─── Oracle 计划的告警列表 ────────────────────────────────────────────

    @Test
    @DisplayName("Oracle 计划的告警集合与改造前一致（顺序也不变）")
    void oraclePlanWarningsAreUnchanged() {
        List<String> warnings = warningsFor(new OracleDialect(),
                "|   0 | SELECT STATEMENT             |                   |",
                "|*  1 |  HASH JOIN                   |                   |",
                "|   2 |   TABLE ACCESS FULL          | ORDERS            |",
                "|   3 |   NESTED LOOPS               |                   |",
                "|*  4 |    INDEX SKIP SCAN           | IDX_ORDERS_REGION |",
                "|   5 |   SORT ORDER BY              |                   |");

        assertThat(warnings).containsExactly(FULL_SCAN, NESTED_LOOP, HASH_JOIN, SORT, SKIP_SCAN);
    }

    @Test
    @DisplayName("一份走索引的计划不产生告警")
    void anIndexedPlanIsReportedAsClean() {
        List<String> warnings = warningsFor(new PostgresDialect(),
                "Index Scan using idx_orders_region on orders  (cost=0.29..8.31 rows=1 width=244)");

        assertThat(warnings).containsExactly(CLEAN);
    }
}
