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
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpQueryException;
import com.entropy.database.mcp.properties.EtlConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the batched source reader against the two ways a "streaming" rewrite can go wrong: losing
 * rows at a batch boundary, and letting an unbounded source decide the heap budget.
 *
 * <p>Runs against a real H2 database, because batch boundaries and {@code ResultSet} traversal are
 * exactly what a mocked {@code JdbcTemplate} would paper over.
 */
class EtlRowStreamTest {

    private static final int SOURCE_ROWS = 25;

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createSchema() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:etlstream;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);
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
    void handsEveryRowToTheWriterAcrossBatchBoundaries() {
        List<Map<String, Object>> seen = new ArrayList<>();

        long written = EtlRowStream.copyInBatches(jdbcTemplate, "SELECT ID, LABEL FROM SRC ORDER BY ID",
                10, 1000, (columns, batch) -> {
                    assertThat(columns).containsExactly("ID", "LABEL");
                    seen.addAll(batch);
                    return batch.size();
                });

        assertThat(written).isEqualTo(SOURCE_ROWS);
        assertThat(seen).hasSize(SOURCE_ROWS);
        assertThat(seen.stream().map(row -> row.get("ID")).toList())
                .isEqualTo(java.util.stream.IntStream.rangeClosed(1, SOURCE_ROWS).boxed().toList());
    }

    @Test
    void splitsIntoBatchesOfTheRequestedSize() {
        List<Integer> batchSizes = new ArrayList<>();

        EtlRowStream.copyInBatches(jdbcTemplate, "SELECT ID FROM SRC ORDER BY ID", 10, 1000,
                (columns, batch) -> {
                    batchSizes.add(batch.size());
                    return batch.size();
                });

        assertThat(batchSizes).containsExactly(10, 10, 5);
    }

    @Test
    void countsWithoutMaterialising() {
        long rows = EtlRowStream.countRows(jdbcTemplate, "SELECT ID FROM SRC", 10, 1000);

        assertThat(rows).isEqualTo(SOURCE_ROWS);
    }

    @Test
    void refusesToReadPastTheRowCeiling() {
        assertThatThrownBy(() -> EtlRowStream.countRows(jdbcTemplate, "SELECT ID FROM SRC", 5, 10))
                .isInstanceOf(McpQueryException.class)
                .hasMessageContaining("more than 10 rows")
                .extracting(e -> ((McpQueryException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUERY_RESULT_TOO_LARGE);
    }

    @Test
    void emptySourceWritesNothing() {
        long written = EtlRowStream.copyInBatches(jdbcTemplate,
                "SELECT ID FROM SRC WHERE ID < 0", 10, 1000,
                (columns, batch) -> {
                    throw new AssertionError("writer must not be called for an empty source");
                });

        assertThat(written).isZero();
    }

    @Test
    void queryToTableHandlerCopiesEveryRowInBatches() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        when(context.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(context.getDialect()).thenReturn(new H2Dialect());

        JobExecutionEngine engine = new JobExecutionEngine(
                mock(com.entropy.database.mcp.byok.DynamicDataSourceManager.class),
                null, new EtlConfig(1, 4), Runnable::run);
        Step step = new Step("copy", StepType.QUERY_TO_TABLE, List.of(), "src",
                "SELECT ID, LABEL FROM SRC ORDER BY ID", "DEST", null, Map.of());

        long rows = new QueryToTableStepHandler().execute(context, context, step, engine);

        assertThat(rows).isEqualTo(SOURCE_ROWS);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DEST", Integer.class))
                .isEqualTo(SOURCE_ROWS);
    }

    @Test
    void queryToTableHandlerHonoursTheRowCeiling() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        when(context.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(context.getDialect()).thenReturn(new H2Dialect());

        JobExecutionEngine engine = new JobExecutionEngine(
                mock(com.entropy.database.mcp.byok.DynamicDataSourceManager.class),
                null, new EtlConfig(1, 4), Runnable::run);
        Step step = new Step("copy", StepType.QUERY_TO_TABLE, List.of(), "src",
                "SELECT ID, LABEL FROM SRC ORDER BY ID", "DEST", null,
                Map.of("maxSourceRows", 5));

        assertThatThrownBy(() -> new QueryToTableStepHandler().execute(context, context, step, engine))
                .isInstanceOf(McpQueryException.class);
    }
}
