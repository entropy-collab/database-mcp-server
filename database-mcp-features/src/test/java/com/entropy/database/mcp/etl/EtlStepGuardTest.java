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
package com.entropy.database.mcp.etl;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.ByokInfrastructure;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.byok.StatementTemplates;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.properties.EtlConfig;
import com.entropy.database.mcp.properties.StatementTimeouts;
import com.entropy.database.mcp.security.SqlValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异步 ETL 链路的注入闸门回归测试。
 *
 * <p>背景：同步版 {@code EtlTools.transformAndInsert} 一直有三道校验（列映射标识符、表名标识符、
 * whereClause 白名单），异步版（{@code submitEtlJob} → step handler）曾把三道全丢了，DDL step
 * 更是一条都不过 {@code SqlValidator}。下面每个用例都对应一条当时可用的注入路径，
 * <b>删掉任何一个用例都会让对应的缺口重新变成「没人发现」的状态。</b>
 *
 * <p>引擎用同线程 {@link TaskExecutor} 驱动，所以 {@code submit} 返回时作业已经跑完；
 * 校验失败由引擎记成 step 的 FAILED + error 文本（见 {@code JobExecutionEngine.describeFailure}）。
 */
class EtlStepGuardTest {

    private static final TaskExecutor SAME_THREAD = Runnable::run;

    private static org.h2.jdbcx.JdbcDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void createSource() {
        dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:etlguard;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS GUARD_SRC");
        jdbc.execute("CREATE TABLE GUARD_SRC (ID INT PRIMARY KEY, LABEL VARCHAR(20))");
        jdbc.update("INSERT INTO GUARD_SRC VALUES (1, 'alpha')");
        jdbc.update("INSERT INTO GUARD_SRC VALUES (2, 'beta')");
        jdbc.update("INSERT INTO GUARD_SRC VALUES (3, 'gamma')");
    }

    @BeforeEach
    void freshTargets() {
        jdbc.execute("DROP TABLE IF EXISTS GUARD_DEST");
        jdbc.execute("CREATE TABLE GUARD_DEST (ID INT PRIMARY KEY, LABEL VARCHAR(20))");
        jdbc.execute("DROP TABLE IF EXISTS GUARD_DDL_OK");
    }

    // ─── 表名 ─────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "targetTable ''{0}'' 被拒")
    @ValueSource(strings = {
            "GUARD DEST",                      // 空格
            "GUARD_DEST; DROP TABLE GUARD_SRC",// 分号：多语句
            "\"GUARD_DEST\"",                  // 引号：自带定界符
            "GUARD_DEST --",                   // 注释符
            "GUARD_DEST) SELECT 1 FROM (",     // 括号闭合
    })
    void illegalTargetTableIsRejectedBeforeAnySqlRuns(String targetTable) {
        JobExecutionEngine engine = engine(mock(SqlValidator.class));
        MigrationJob job = new MigrationJob("job-table", "table job", "", List.of(
                new Step("copy", StepType.QUERY_TO_TABLE, List.of(), "src",
                        "SELECT ID, LABEL FROM GUARD_SRC", targetTable, null, Map.of())));

        engine.submit(job);

        assertThat(stateOf(engine, "job-table", "copy").status()).isEqualTo(StepStatus.FAILED);
        assertThat(stateOf(engine, "job-table", "copy").error()).contains("targetTable");
        assertThat(destCount()).isZero();
    }

    // ─── 列名 ─────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "columnMapping ''{0}'' 被拒")
    @ValueSource(strings = {
            "(SELECT LABEL FROM GUARD_SRC):LABEL",  // 子查询混进 SELECT 列表
            "ID, LABEL:ID",                          // 一个映射位塞两列
            "ID--:ID",                               // 注释符
            "ID:LA BEL",                             // 目标列带空格
    })
    void illegalColumnMappingIsRejected(String mapping) {
        JobExecutionEngine engine = engine(mock(SqlValidator.class));
        MigrationJob job = new MigrationJob("job-col", "col job", "", List.of(
                new Step("t", StepType.TRANSFORM, List.of(), "src",
                        "SELECT ID, LABEL FROM GUARD_SRC", "GUARD_DEST", null,
                        Map.of("columnMapping", List.of(mapping)))));

        engine.submit(job);

        assertThat(stateOf(engine, "job-col", "t").status()).isEqualTo(StepStatus.FAILED);
        assertThat(stateOf(engine, "job-col", "t").error()).contains("columnMapping");
        assertThat(destCount()).isZero();
    }

    @Test
    @DisplayName("逗号分隔的字符串形态 columnMapping 也要过闸，不能绕过")
    void stringFormColumnMappingIsAlsoGuarded() {
        // getListParam 允许字符串形态；闸门必须用同一个 reader 读，否则这条路径能绕过校验。
        JobExecutionEngine engine = engine(mock(SqlValidator.class));
        MigrationJob job = new MigrationJob("job-colstr", "col job", "", List.of(
                new Step("t", StepType.TRANSFORM, List.of(), "src",
                        "SELECT ID, LABEL FROM GUARD_SRC", "GUARD_DEST", null,
                        Map.of("columnMapping", "ID:ID,(SELECT LABEL FROM GUARD_SRC):LABEL"))));

        engine.submit(job);

        assertThat(stateOf(engine, "job-colstr", "t").status()).isEqualTo(StepStatus.FAILED);
        assertThat(destCount()).isZero();
    }

    @Test
    void illegalKeyColumnOnUpsertIsRejected() {
        JobExecutionEngine engine = engine(mock(SqlValidator.class));
        MigrationJob job = new MigrationJob("job-key", "key job", "", List.of(
                new Step("u", StepType.UPSERT, List.of(), "src",
                        "SELECT ID, LABEL FROM GUARD_SRC", "GUARD_DEST", null,
                        Map.of("keyColumns", List.of("ID) --")))));

        engine.submit(job);

        assertThat(stateOf(engine, "job-key", "u").status()).isEqualTo(StepStatus.FAILED);
        assertThat(stateOf(engine, "job-key", "u").error()).contains("keyColumns");
    }

    // ─── whereClause ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "whereClause ''{0}'' 被拒")
    @ValueSource(strings = {
            "1=1; DROP TABLE GUARD_SRC",                        // 多语句
            "1=1 -- ",                                          // 注释符
            "ID IN (SELECT ID FROM GUARD_SRC)",                 // 子查询
            "ID = 1 UNION SELECT 1, 2 FROM GUARD_SRC",          // 集合运算
            "ID = LENGTH(LABEL)",                               // 函数调用
    })
    void maliciousWhereClauseIsRejected(String whereClause) {
        JobExecutionEngine engine = engine(mock(SqlValidator.class));
        MigrationJob job = new MigrationJob("job-where", "where job", "", List.of(
                new Step("t", StepType.TRANSFORM, List.of(), "src",
                        "SELECT ID, LABEL FROM GUARD_SRC", "GUARD_DEST", null,
                        Map.of("columnMapping", List.of("ID:ID", "LABEL:LABEL"),
                                "whereClause", whereClause))));

        engine.submit(job);

        assertThat(stateOf(engine, "job-where", "t").status()).isEqualTo(StepStatus.FAILED);
        assertThat(stateOf(engine, "job-where", "t").error()).contains("whereClause");
        assertThat(destCount()).isZero();
    }

    // ─── DDL ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DDL step 的每条语句都过 SqlValidator.validateDdl，被拒时一条都不执行")
    void ddlStatementsGoThroughTheValidatorAndAreAllOrNothing() {
        // 白名单本身归 SqlValidator 实现管；这里只钉住「handler 真的调了它」这件事。
        SqlValidator validator = mock(SqlValidator.class);
        doThrow(new McpValidationException(ErrorCode.SQL_VALIDATION_FAILED,
                "DDL statement type not allowed: CREATE ALIAS"))
                .when(validator).validateDdl(contains("CREATE ALIAS"));

        JobExecutionEngine engine = engine(validator);
        MigrationJob job = new MigrationJob("job-ddl", "ddl job", "", List.of(
                new Step("d", StepType.DDL, List.of(), "src", null, null, null,
                        Map.of("statements", List.of(
                                "CREATE TABLE GUARD_DDL_OK (ID INT)",
                                "CREATE ALIAS EXEC_CMD FOR \"java.lang.Runtime.getRuntime\"")))));

        engine.submit(job);

        assertThat(stateOf(engine, "job-ddl", "d").status()).isEqualTo(StepStatus.FAILED);
        assertThat(stateOf(engine, "job-ddl", "d").error()).contains("CREATE ALIAS");
        verify(validator).validateDdl("CREATE TABLE GUARD_DDL_OK (ID INT)");
        // 先全部校验再执行：否则第一条已经建好表了，调用方只看到一条校验失败。
        assertThat(tableExists("GUARD_DDL_OK")).isFalse();
    }

    @Test
    void blankDdlStatementIsRejected() {
        JobExecutionEngine engine = engine(mock(SqlValidator.class));

        assertThatThrownBy(() -> engine.validateDdl("  "))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("DDL statement cannot be blank");
    }

    // ─── 回归保护：合法作业照常跑通 ─────────────────────────────────────────

    @Test
    @DisplayName("合法 QUERY_TO_TABLE 作业照常搬数（表名 quote 后仍能命中目标表）")
    void legalQueryToTableJobStillRuns() {
        JobExecutionEngine engine = engine(mock(SqlValidator.class));
        MigrationJob job = new MigrationJob("job-ok", "ok job", "", List.of(
                new Step("copy", StepType.QUERY_TO_TABLE, List.of(), "src",
                        "SELECT ID, LABEL FROM GUARD_SRC ORDER BY ID", "GUARD_DEST", null, Map.of())));

        engine.submit(job);

        assertThat(engine.getExecution("job-ok").orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(destCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("合法 TRANSFORM 作业（含转换与 whereClause）照常跑通")
    void legalTransformJobStillRuns() {
        JobExecutionEngine engine = engine(mock(SqlValidator.class));
        MigrationJob job = new MigrationJob("job-ok-t", "ok transform", "", List.of(
                new Step("t", StepType.TRANSFORM, List.of(), "src",
                        "SELECT ID, LABEL FROM GUARD_SRC", "GUARD_DEST", null,
                        Map.of("columnMapping", List.of("ID:ID", "LABEL:LABEL:upper"),
                                "whereClause", "ID <= 2"))));

        engine.submit(job);

        assertThat(stateOf(engine, "job-ok-t", "t").error()).isNull();
        assertThat(engine.getExecution("job-ok-t").orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(jdbc.queryForList("SELECT LABEL FROM GUARD_DEST ORDER BY ID", String.class))
                .containsExactly("ALPHA", "BETA");
    }

    @Test
    @DisplayName("合法 DDL 作业照常跑通")
    void legalDdlJobStillRuns() {
        JobExecutionEngine engine = engine(mock(SqlValidator.class));
        MigrationJob job = new MigrationJob("job-ok-d", "ok ddl", "", List.of(
                new Step("d", StepType.DDL, List.of(), "src", null, null, null,
                        Map.of("statements", List.of("CREATE TABLE GUARD_DDL_OK (ID INT)")))));

        engine.submit(job);

        assertThat(stateOf(engine, "job-ok-d", "d").status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(tableExists("GUARD_DDL_OK")).isTrue();
    }

    @Test
    @DisplayName("EtlSql.insertInto 对表名断言合法并 quote，而不是裸拼")
    void insertIntoQuotesAndValidatesTheTable() {
        H2Dialect dialect = new H2Dialect();

        assertThat(EtlSql.insertInto(dialect, "GUARD_DEST", List.of("ID", "LABEL")))
                .isEqualTo("INSERT INTO \"GUARD_DEST\" (\"ID\", \"LABEL\") VALUES (?, ?)");
        assertThatThrownBy(() -> EtlSql.insertInto(dialect, "GUARD_DEST; DROP TABLE GUARD_SRC",
                List.of("ID")))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("targetTable");
    }

    // ─── 脚手架 ───────────────────────────────────────────────────────────

    private JobExecutionEngine engine(SqlValidator validator) {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire(anyString())).thenReturn(contextOver());
        return new JobExecutionEngine(manager, null, new EtlConfig(1, 100), SAME_THREAD, validator);
    }

    private static ByokDataSourceContext contextOver() {
        return new ByokDataSourceContext("h2-guard", dataSource, new H2Dialect(),
                StatementTemplates.over(dataSource, jdbc, StatementTimeouts.defaults()),
                new ByokInfrastructure(null, null, null, null, null, null));
    }

    private static StepExecutionState stateOf(JobExecutionEngine engine, String jobId, String stepId) {
        return engine.getExecution(jobId).orElseThrow().stepStates().get(stepId);
    }

    private static int destCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM GUARD_DEST", Integer.class);
    }

    private static boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
                Integer.class, table);
        return count != null && count > 0;
    }
}

