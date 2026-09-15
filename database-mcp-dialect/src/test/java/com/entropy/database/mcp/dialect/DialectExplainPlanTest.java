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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code getExplainPlanSql} must answer for every dialect that can explain a query.
 *
 * <p>These cases exist because the SQL for Postgres / MySQL / SQL Server used to live in a
 * {@code switch (dialect.getDialectName())} inside {@code QueryAnalysisTools.buildExplainSql} —
 * only Oracle answered from its dialect. The strings asserted below are byte-for-byte what that
 * switch produced, so a regression in the move shows up here rather than as "explainPlan quietly
 * reports EXPLAIN_NOT_SUPPORTED on a database that supports it".
 */
class DialectExplainPlanTest {

    private static final String QUERY = "SELECT 1 FROM t";

    private static Stream<Arguments> supported() {
        return Stream.of(
                Arguments.of(new OracleDialect(), "EXPLAIN PLAN FOR " + QUERY),
                Arguments.of(new PostgresDialect(), "EXPLAIN " + QUERY),
                Arguments.of(new MySqlDialect(), "EXPLAIN " + QUERY),
                Arguments.of(new SqlServerDialect(),
                        "SET SHOWPLAN_TEXT ON; " + QUERY + "; SET SHOWPLAN_TEXT OFF"));
    }

    /** Dialects with no EXPLAIN of their own: null is the caller's "not supported" signal. */
    private static Stream<DatabaseDialect> unsupported() {
        return Stream.of(new H2Dialect(), new Db2Dialect(), new SqliteDialect(), new GenericDialect());
    }

    private static Stream<DatabaseDialect> allDialects() {
        return Stream.concat(
                supported().map(args -> (DatabaseDialect) args.get()[0]),
                unsupported());
    }

    @ParameterizedTest
    @MethodSource("supported")
    void explainSqlMatchesTheDialect(DatabaseDialect dialect, String expected) {
        assertThat(dialect.getExplainPlanSql(QUERY)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("unsupported")
    void unsupportedDialectsReturnNull(DatabaseDialect dialect) {
        assertThat(dialect.getExplainPlanSql(QUERY)).isNull();
    }

    /**
     * SQL Server is the only dialect whose plan arrives as session output. Callers branch on this
     * instead of sniffing the SQL for {@code SET SHOWPLAN}, so the flag has to stay accurate.
     */
    @ParameterizedTest
    @MethodSource("allDialects")
    void onlySqlServerWithholdsThePlanFromTheResultSet(DatabaseDialect dialect) {
        assertThat(dialect.explainPlanReturnsRows())
                .isEqualTo(!(dialect instanceof SqlServerDialect));
    }

    /**
     * Oracle 是唯一「EXPLAIN 分两步」的方言，且这件事必须由方言自己声明。
     *
     * <p>调用方原来按 {@code "OracleDialect".equals(dialect.getClass().getSimpleName())} 判断，子类、
     * CGLIB 代理、将来的 {@code Oracle23Dialect} 都会静默走单步分支，而单步在 Oracle 上恒返回空计划。
     */
    @ParameterizedTest
    @MethodSource("allDialects")
    void onlyOracleWritesItsPlanToAPlanTable(DatabaseDialect dialect) {
        boolean oracle = dialect instanceof OracleDialect;
        assertThat(dialect.explainWritesToPlanTable()).isEqualTo(oracle);
        // 声明走计划表就必须给出读回与清理语句，否则调用方只能空手回。
        assertThat(dialect.planTableFetchSql() != null).isEqualTo(oracle);
        assertThat(dialect.planTableCleanupSql() != null).isEqualTo(oracle);
    }

    @Test
    void oraclePlanTableStatementsCarryOnePlaceholderForTheStatementId() {
        OracleDialect oracle = new OracleDialect();

        assertThat(oracle.explainPlanStatement("mcp_query_7", QUERY))
                .isEqualTo("EXPLAIN PLAN SET STATEMENT_ID = 'mcp_query_7' FOR " + QUERY);
        assertThat(oracle.planTableFetchSql()).contains("FROM plan_table WHERE statement_id = ?");
        assertThat(oracle.planTableCleanupSql()).isEqualTo(
                "DELETE FROM plan_table WHERE statement_id = ?");
    }

    /** 不走计划表的方言：这个方法退化成单步的 EXPLAIN 语句，调用方无需另判。 */
    @ParameterizedTest
    @MethodSource("supported")
    void explainPlanStatementFallsBackToTheSingleStepSql(DatabaseDialect dialect, String expected) {
        if (dialect.explainWritesToPlanTable()) {
            return;
        }
        assertThat(dialect.explainPlanStatement("mcp_query_1", QUERY)).isEqualTo(expected);
    }
}
