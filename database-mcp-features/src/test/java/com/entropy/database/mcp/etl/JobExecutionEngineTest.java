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
import com.entropy.database.mcp.properties.EtlConfig;
import com.entropy.database.mcp.properties.StatementTimeouts;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards two things {@link JobExecutionEngine} used to get wrong about failure:
 *
 * <ul>
 *   <li><b>What the caller is told.</b> A failed step recorded the fixed string {@code "执行失败"},
 *       dropping the cause entirely — and {@code EtlTools.getJobStatus} hands {@code state.error()}
 *       straight to the LLM, so the only thing a caller could learn was "something failed".</li>
 *   <li><b>Whether the failure stops.</b> The dependency check only looked for {@code FAILED}, so a
 *       failure escaped after one hop: A fails, B is {@code SKIPPED}, and C — depending on B — saw
 *       no {@code FAILED} dependency and ran against a source that was never produced.</li>
 * </ul>
 *
 * <p>The engine is driven with a synchronous {@link TaskExecutor} so the job has finished by the
 * time {@code submit} returns.
 */
class JobExecutionEngineTest {

    private static final TaskExecutor SAME_THREAD = Runnable::run;

    private JobExecutionEngine engine(DynamicDataSourceManager manager) {
        return new JobExecutionEngine(manager, null, new EtlConfig(1, 100), SAME_THREAD);
    }

    private static Step readStep(String id, String connection, String sql, List<String> dependsOn) {
        return new Step(id, StepType.READ, dependsOn, connection, sql, null, null, Map.of());
    }

    @Test
    void failedStepKeepsTheRealErrorInsteadOfAFixedString() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire("src")).thenThrow(
                new IllegalStateException("Table ORDERS not found while filtering NAME = 'alice'"));

        JobExecutionEngine engine = engine(manager);
        MigrationJob job = new MigrationJob("job-error", "error job", "",
                List.of(readStep("a", "src", "SELECT * FROM ORDERS", List.of())));

        engine.submit(job);

        StepExecutionState state = engine.getExecution("job-error").orElseThrow()
                .stepStates().get("a");
        assertThat(state.status()).isEqualTo(StepStatus.FAILED);
        assertThat(state.error())
                .contains("IllegalStateException")
                .contains("Table ORDERS not found");
        // Redacted: single-quoted literals are where row data shows up in driver messages.
        assertThat(state.error()).doesNotContain("alice");
    }

    @Test
    void failedStepErrorDoesNotEchoTheSourceSql() {
        String sql = "SELECT ID, EMAIL FROM CUSTOMERS WHERE EMAIL LIKE %@example.com";
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire("src")).thenThrow(
                new IllegalStateException("Syntax error in statement: " + sql));

        JobExecutionEngine engine = engine(manager);
        MigrationJob job = new MigrationJob("job-sql", "sql job", "",
                List.of(readStep("a", "src", sql, List.of())));

        engine.submit(job);

        StepExecutionState state = engine.getExecution("job-sql").orElseThrow().stepStates().get("a");
        assertThat(state.error())
                .contains("Syntax error in statement")
                .contains("[sql omitted]")
                .doesNotContain("CUSTOMERS");
    }

    @Test
    void aStepDependingOnASkippedStepIsSkippedRatherThanRun() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire("src")).thenThrow(new IllegalStateException("source unavailable"));

        JobExecutionEngine engine = engine(manager);
        MigrationJob job = new MigrationJob("job-chain", "chain job", "", List.of(
                readStep("a", "src", "SELECT 1", List.of()),
                readStep("b", "b-conn", "SELECT 1", List.of("a")),
                readStep("c", "c-conn", "SELECT 1", List.of("b"))));

        engine.submit(job);

        Map<String, StepExecutionState> states =
                engine.getExecution("job-chain").orElseThrow().stepStates();
        assertThat(states.get("a").status()).isEqualTo(StepStatus.FAILED);
        assertThat(states.get("b").status()).isEqualTo(StepStatus.SKIPPED);
        // The regression: c used to run here, writing whatever b never produced.
        assertThat(states.get("c").status()).isEqualTo(StepStatus.SKIPPED);
        verify(manager, never()).acquire("b-conn");
        verify(manager, never()).acquire("c-conn");
    }

    @Test
    void jobWithAFailedStepIsReportedAsFailed() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire(anyString())).thenThrow(new IllegalStateException("nope"));

        JobExecutionEngine engine = engine(manager);
        MigrationJob job = new MigrationJob("job-status", "status job", "",
                List.of(readStep("a", "src", "SELECT 1", List.of())));

        engine.submit(job);

        JobExecution execution = engine.getExecution("job-status").orElseThrow();
        assertThat(execution.status()).isEqualTo(JobStatus.FAILED);
        assertThat(execution.getFailedStepIds()).containsExactly("a");
    }

    @Test
    void exportStepNeedsNoSourceAndCompletes() {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire("any")).thenReturn(null);

        JobExecutionEngine engine = engine(manager);
        MigrationJob job = new MigrationJob("job-export", "export job", "", List.of(
                new Step("e", StepType.EXPORT, List.of(), "any", null, null, null, Map.of())));

        engine.submit(job);

        JobExecution execution = engine.getExecution("job-export").orElseThrow();
        assertThat(execution.stepStates().get("e").status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(execution.status()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void abortedTransferStepReportsThatTheTargetIsCleanAndMayBeRerun() {
        JdbcTemplate jdbc = h2("jobengine");
        jdbc.execute("DROP TABLE IF EXISTS ENGINE_SRC");
        jdbc.execute("DROP TABLE IF EXISTS ENGINE_DEST");
        jdbc.execute("CREATE TABLE ENGINE_SRC (ID INT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE ENGINE_DEST (ID INT PRIMARY KEY)");
        for (int i = 1; i <= 6; i++) {
            jdbc.update("INSERT INTO ENGINE_SRC VALUES (?)", i);
        }

        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire(anyString())).thenReturn(contextOver(jdbc));

        JobExecutionEngine engine = engine(manager);
        // Two rows per batch with a ceiling of 2: batch one is written, then row 3 aborts the step.
        MigrationJob job = new MigrationJob("job-abort", "abort job", "", List.of(
                new Step("copy", StepType.QUERY_TO_TABLE, List.of(), "src",
                        "SELECT ID FROM ENGINE_SRC ORDER BY ID", "ENGINE_DEST", null,
                        Map.of("maxSourceRows", 2, "batchSize", 2))));

        engine.submit(job);

        StepExecutionState state = engine.getExecution("job-abort").orElseThrow()
                .stepStates().get("copy");
        assertThat(state.status()).isEqualTo(StepStatus.FAILED);
        // getJobStatus hands this straight to the LLM, whose default reaction to a failure is to
        // re-run the job — so it has to say what the target table looks like now.
        assertThat(state.error())
                .contains("more than 2 rows")
                .contains("回滚")
                .contains("可以直接重跑");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ENGINE_DEST", Integer.class)).isZero();
    }

    private static JdbcTemplate h2(String name) {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return new JdbcTemplate(ds);
    }

    private static ByokDataSourceContext contextOver(JdbcTemplate jdbc) {
        return new ByokDataSourceContext("h2-etl", jdbc.getDataSource(), new H2Dialect(),
                StatementTemplates.over(jdbc.getDataSource(), jdbc, StatementTimeouts.defaults()),
                new ByokInfrastructure(null, null, null, null, null, null));
    }
}
