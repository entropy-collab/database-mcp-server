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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the batched source reader against the ways a "streaming" rewrite can go wrong: losing rows
 * at a batch boundary, letting an unbounded source decide the heap budget, and — the expensive one —
 * leaving half the rows committed in the target when the step aborts part-way through.
 *
 * <p>Runs against a real H2 database, because batch boundaries, {@code ResultSet} traversal and
 * transaction rollback are exactly what a mocked {@code JdbcTemplate} would paper over.
 */
class EtlRowStreamTest {

    private static final int SOURCE_ROWS = 25;

    private static org.h2.jdbcx.JdbcDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createSchema() {
        dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:etlstream;DB_CLOSE_DELAY=-1");
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
    void handsEveryRowToTheWriterAcrossBatchBoundaries() {
        List<Map<String, Object>> seen = new ArrayList<>();

        long written = EtlRowStream.copyInBatches(jdbcTemplate, jdbcTemplate,
                "SELECT ID, LABEL FROM SRC ORDER BY ID",
                10, 1000, (targetJdbc, columns, batch) -> {
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

        EtlRowStream.copyInBatches(jdbcTemplate, jdbcTemplate, "SELECT ID FROM SRC ORDER BY ID", 10, 1000,
                (targetJdbc, columns, batch) -> {
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
        long written = EtlRowStream.copyInBatches(jdbcTemplate, jdbcTemplate,
                "SELECT ID FROM SRC WHERE ID < 0", 10, 1000,
                (targetJdbc, columns, batch) -> {
                    throw new AssertionError("writer must not be called for an empty source");
                });

        assertThat(written).isZero();
    }

    @Test
    void queryToTableHandlerCopiesEveryRowInBatches() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        // The bulk template, not the read one: an ETL step is not sized by the interactive ceiling.
        when(context.getEtlJdbcTemplate()).thenReturn(jdbcTemplate);
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
        when(context.getEtlJdbcTemplate()).thenReturn(jdbcTemplate);
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

    // ─── One transaction per step ─────────────────────────────────────────

    @Test
    @DisplayName("hitting the row ceiling after several batches leaves the target untouched")
    void rowCeilingBreachRollsBackTheBatchesAlreadyWritten() {
        // batchSize 10 + ceiling 12: the first batch is written, then the 13th row aborts the step.
        // With per-batch autoCommit those 10 rows stayed in DEST and a re-run duplicated them.
        assertThatThrownBy(() -> copyIntoDest(10, 12))
                .isInstanceOf(McpQueryException.class)
                .hasMessageContaining("more than 12 rows")
                .hasMessageContaining("回滚")
                .hasMessageContaining("可以直接重跑");

        assertThat(destCount()).isZero();
    }

    @Test
    @DisplayName("a batch that fails mid-transfer rolls back the batches before it")
    void writeFailureRollsBackEarlierBatches() {
        // ID 15 lands in the second batch, so batch one succeeds and batch two violates the key.
        jdbcTemplate.update("INSERT INTO DEST VALUES (?, ?)", 15, "pre-existing");

        assertThatThrownBy(() -> copyIntoDest(10, 1000))
                .isInstanceOf(McpQueryException.class)
                .hasMessageContaining("回滚");

        assertThat(destCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT LABEL FROM DEST WHERE ID = 15", String.class))
                .isEqualTo("pre-existing");
    }

    @Test
    @DisplayName("the error text tells the model how many rows were written and whether it may re-run")
    void failureMessageStatesTheTargetState() {
        assertThatThrownBy(() -> copyIntoDest(10, 12))
                .hasMessageContaining("已写入的 10 行")
                .hasMessageContaining("目标表未留下部分数据");
    }

    @Test
    @DisplayName("a step whose source and target share a pool holds one connection, not two")
    void sourceAndTargetSharingAPoolUseASingleConnection() {
        CountingDataSource counting = new CountingDataSource(dataSource);
        JdbcTemplate counted = new JdbcTemplate(counting);

        long written = EtlRowStream.copyInBatches(counted, counted,
                "SELECT ID, LABEL FROM SRC ORDER BY ID", 10, 1000,
                (targetJdbc, columns, batch) -> EtlSql.sum(targetJdbc.batchUpdate(
                        "INSERT INTO DEST (ID, LABEL) VALUES (?, ?)", batch, batch.size(),
                        (ps, row) -> {
                            ps.setObject(1, row.get("ID"));
                            ps.setObject(2, row.get("LABEL"));
                        })));

        assertThat(written).isEqualTo(SOURCE_ROWS);
        assertThat(destCount()).isEqualTo(SOURCE_ROWS);
        // The regression this pins: reading through jdbc.query(...) while writing through a second
        // template took two connections from the same pool for the whole transfer, so
        // etl.thread-pool-size x 2 > pool-size deadlocked until connectionTimeout.
        assertThat(counting.peakOpen()).isEqualTo(1);
    }

    private long copyIntoDest(int batchSize, int maxRows) {
        return EtlRowStream.copyInBatches(jdbcTemplate, jdbcTemplate,
                "SELECT ID, LABEL FROM SRC ORDER BY ID", batchSize, maxRows,
                (targetJdbc, columns, batch) -> EtlSql.sum(targetJdbc.batchUpdate(
                        "INSERT INTO DEST (ID, LABEL) VALUES (?, ?)", batch, batch.size(),
                        (ps, row) -> {
                            ps.setObject(1, row.get("ID"));
                            ps.setObject(2, row.get("LABEL"));
                        })));
    }

    private int destCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DEST", Integer.class);
        return count == null ? -1 : count;
    }

    /**
     * Counts how many connections are checked out at once, so "one connection per step" is an
     * assertion rather than a claim in a comment.
     */
    private static final class CountingDataSource implements DataSource {

        private final DataSource delegate;
        private final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        private CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        int peakOpen() {
            return peak.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            peak.accumulateAndGet(open.incrementAndGet(), Math::max);
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            open.decrementAndGet();
                            real.close();
                            return null;
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException();
        }
    }
}
