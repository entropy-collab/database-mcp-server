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
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.exception.McpQueryException;
import com.entropy.database.mcp.properties.EtlConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The QUERY_TO_TABLE handler seen from the capability layer: it must copy every row in batches and
 * it must still refuse to read past the step's row ceiling.
 *
 * <p>这两个用例原先住在 {@code EtlRowStreamTest} 里；批式搬数的管道搬到 infra 的
 * {@code repository} 包之后，handler 留在 features，所以它们跟着 handler 留在这一侧
 * （infra 的测试看不到 {@code QueryToTableStepHandler} / {@code JobExecutionEngine}）。
 * 管道自身的行为（批边界、回滚、单连接）由 infra 的 {@code EtlRowStreamTest} 覆盖。
 */
class QueryToTableStepHandlerTest {

    private static final int SOURCE_ROWS = 25;

    private static org.h2.jdbcx.JdbcDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createSchema() {
        dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:etlhandler;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS SRC");
        jdbcTemplate.execute("CREATE TABLE SRC (ID INT PRIMARY KEY, LABEL VARCHAR(20))");
        for (int i = 1; i <= SOURCE_ROWS; i++) {
            jdbcTemplate.update("INSERT INTO SRC VALUES (?, ?)", i, "row" + i);
        }
    }

    @BeforeEach
    void freshTarget() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS DEST");
        jdbcTemplate.execute("CREATE TABLE DEST (ID INT PRIMARY KEY, LABEL VARCHAR(20))");
    }

    @Test
    void queryToTableHandlerCopiesEveryRowInBatches() {
        ByokDataSourceContext context = contextOverH2();
        Step step = new Step("copy", StepType.QUERY_TO_TABLE, List.of(), "src",
                "SELECT ID, LABEL FROM SRC ORDER BY ID", "DEST", null, Map.of());

        long rows = new QueryToTableStepHandler().execute(context, context, step, engine());

        assertThat(rows).isEqualTo(SOURCE_ROWS);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DEST", Integer.class))
                .isEqualTo(SOURCE_ROWS);
    }

    @Test
    void queryToTableHandlerHonoursTheRowCeiling() {
        ByokDataSourceContext context = contextOverH2();
        Step step = new Step("copy", StepType.QUERY_TO_TABLE, List.of(), "src",
                "SELECT ID, LABEL FROM SRC ORDER BY ID", "DEST", null,
                Map.of("maxSourceRows", 5));

        assertThatThrownBy(() -> new QueryToTableStepHandler().execute(context, context, step, engine()))
                .isInstanceOf(McpQueryException.class);
    }

    private static ByokDataSourceContext contextOverH2() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        // The bulk template, not the read one: an ETL step is not sized by the interactive ceiling.
        when(context.getEtlJdbcTemplate()).thenReturn(jdbcTemplate);
        when(context.getDialect()).thenReturn(new H2Dialect());
        return context;
    }

    private static JobExecutionEngine engine() {
        return new JobExecutionEngine(mock(DynamicDataSourceManager.class), null,
                new EtlConfig(1, 4), Runnable::run);
    }
}
