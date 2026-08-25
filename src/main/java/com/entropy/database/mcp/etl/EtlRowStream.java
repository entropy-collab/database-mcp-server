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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpQueryException;
import com.entropy.database.mcp.exception.McpToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Batch-at-a-time reader for ETL source queries, with one transaction per step.
 *
 * <p>Every step handler used to do {@code queryForList(step.sourceSql())} and hold the entire
 * source table in a {@code List<Map>} before writing a single row — on the highest-volume path in
 * the server, with no row ceiling anywhere ({@code SqlValidator.validateSelect} only checks
 * syntax). This walks the {@link ResultSet} instead, handing the caller one batch at a time so peak
 * memory is bounded by the batch size rather than by the source table, and refuses to read past a
 * caller-visible row ceiling.
 *
 * <h2>为什么这里自己管连接和事务</h2>
 * <p>批式搬数如果跑在池连接的默认 {@code autoCommit=true} 上，每一批都是一个独立事务：读到第
 * N 批才发现超过行数上限（或某一批写失败）时，前面的批次已经落库，而 {@link JobExecutionEngine}
 * 只是把这个 step 标成 FAILED，没有任何补偿；重跑 job 会新建 JobExecution、所有步骤回到 PENDING，
 * 「已 COMPLETED 则跳过」的幂等逻辑失效，于是目标表被二次写入、出现重复行。所以一个 step 的全部
 * 批次必须落在同一个事务里，成功才提交，失败整体回滚。
 *
 * <p>而事务是连接上的状态，{@link JdbcTemplate} 每次调用都会自己取一条连接，因此这里必须自己从
 * {@link DataSource} 取连接、把写入钉在这条连接上（见 {@link #boundTo}）。顺带解决了连接占用：
 * 源与目标是同一个池时（{@code step.targetConnection() == null}，也是最常见的情况）整个 step 只
 * 占一条连接，不再出现「同池 2 条连接 × 4 个并发 step」在 {@code pool-size} 偏小时自我死锁。
 */
final class EtlRowStream {

    private static final Logger log = LoggerFactory.getLogger(EtlRowStream.class);

    private static final int FALLBACK_BATCH_SIZE = 1000;

    /**
     * MySQL Connector/J 只在 fetchSize 恰好等于这个值时逐行流式取数；任何正数都被忽略。
     */
    private static final int MYSQL_STREAMING_FETCH_SIZE = Integer.MIN_VALUE;

    /**
     * 驱动无法流式取数时启用的保守行数上限。
     *
     * <p>此时结果集整份缓存在客户端，默认的 {@link JobExecutionEngine#DEFAULT_MAX_SOURCE_ROWS}
     * （一百万行）就不再是内存上界而只是行数上界，所以把上限压到这个值，让内存有一个确定的边界，
     * 并在日志里说明降级原因。
     */
    static final long NON_STREAMING_MAX_ROWS = 100_000L;

    private EtlRowStream() {
    }

    /**
     * Receives one batch of source rows and writes it, returning the number of rows written.
     *
     * <p>{@code targetJdbc} 是绑定在本 step 事务连接上的模板，必须用它来写，用别的模板写就落到
     * 另一条连接、另一个事务上，回滚也就管不到了。
     */
    @FunctionalInterface
    interface BatchWriter {
        long write(JdbcTemplate targetJdbc, List<String> columns, List<Map<String, Object>> batch);
    }

    /**
     * Read {@code sql} from {@code sourceJdbc} in batches of {@code batchSize}, handing each batch to
     * {@code writer} inside a single transaction.
     *
     * @param maxRows hard ceiling on source rows; exceeding it aborts the step with
     *                {@link ErrorCode#QUERY_RESULT_TOO_LARGE} rather than filling the heap, and the
     *                rows written so far are rolled back
     * @return total rows reported written by {@code writer}
     */
    static long copyInBatches(JdbcTemplate sourceJdbc, JdbcTemplate targetJdbc, String sql,
                              int batchSize, int maxRows, BatchWriter writer) {
        return run(sourceJdbc, targetJdbc, sql, batchSize, maxRows, writer);
    }

    /**
     * Count the rows {@code sql} returns without materialising them, subject to the same ceiling.
     */
    static long countRows(JdbcTemplate jdbc, String sql, int batchSize, int maxRows) {
        return run(jdbc, jdbc, sql, batchSize, maxRows, null);
    }

    private static long run(JdbcTemplate sourceJdbc, JdbcTemplate targetJdbc, String sql,
                            int batchSize, int maxRows, BatchWriter writer) {
        int effectiveBatchSize = batchSize > 0 ? batchSize : FALLBACK_BATCH_SIZE;
        DataSource sourceDataSource = dataSourceOf(sourceJdbc, "source");

        try (Connection source = sourceDataSource.getConnection()) {
            if (writer == null) {
                // 纯计数路径：没有写入，也就没有要提交或回滚的东西，只需要读的流式设置。
                Fetch fetch = Fetch.forSource(source, effectiveBatchSize, maxRows, false);
                return read(source, sql, fetch, effectiveBatchSize, sourceJdbc.getQueryTimeout(), null);
            }
            DataSource targetDataSource = dataSourceOf(targetJdbc, "target");
            if (targetDataSource == sourceDataSource) {
                return transfer(source, source, true, sql, effectiveBatchSize, maxRows,
                        writer, sourceJdbc, targetJdbc);
            }
            // 真正跨库的 step：两条连接来自两个不同的池，不会互相抢占。
            try (Connection target = targetDataSource.getConnection()) {
                return transfer(source, target, false, sql, effectiveBatchSize, maxRows,
                        writer, sourceJdbc, targetJdbc);
            }
        } catch (SQLException e) {
            throw new McpQueryException(ErrorCode.ETL_EXECUTION_FAILED,
                    "ETL 步骤无法获取或归还数据库连接：" + e.getMessage(), e);
        }
    }

    /**
     * Move rows from {@code source} to {@code target} as one transaction on {@code target}.
     */
    private static long transfer(Connection source, Connection target, boolean sharedConnection,
                                 String sql, int batchSize, int maxRows, BatchWriter writer,
                                 JdbcTemplate sourceJdbc, JdbcTemplate targetJdbc) throws SQLException {
        boolean targetAutoCommit = target.getAutoCommit();
        boolean sourceAutoCommit = sharedConnection ? targetAutoCommit : source.getAutoCommit();

        target.setAutoCommit(false);
        if (!sharedConnection) {
            // PostgreSQL JDBC 只在 autoCommit=false 时把正数 fetchSize 变成服务端游标，所以读连接
            // 也要进事务；读连接上不会有写入，结束时提交或回滚都只是关掉游标。
            source.setAutoCommit(false);
        }

        Fetch fetch = Fetch.forSource(source, batchSize, maxRows, sharedConnection);
        JdbcTemplate boundTarget = boundTo(target, targetJdbc.getQueryTimeout());
        long[] writtenSoFar = {0L};

        try {
            long written = read(source, sql, fetch, batchSize, sourceJdbc.getQueryTimeout(),
                    (columns, batch) -> {
                        long rows = writer.write(boundTarget, columns, batch);
                        long counted = rows < 0 ? batch.size() : rows;
                        writtenSoFar[0] += counted;
                        return counted;
                    });
            // 先结束读事务（只是关掉游标），再提交写事务：反过来的话读连接提交失败时目标已经落库，
            // 回滚就已经无从下手了。
            if (!sharedConnection) {
                source.commit();
            }
            target.commit();
            return written;
        } catch (RuntimeException | SQLException e) {
            throw rollback(target, source, sharedConnection, writtenSoFar[0], e);
        } finally {
            restoreAutoCommit(target, targetAutoCommit);
            if (!sharedConnection) {
                restoreAutoCommit(source, sourceAutoCommit);
            }
        }
    }

    /**
     * Roll the step's writes back and turn {@code failure} into a message the caller can act on.
     *
     * <p>{@code JobExecutionEngine} 把这段文字原样交给模型，而模型看到失败的第一反应是重跑，所以
     * 「目标表现在是什么状态」必须写在消息里：回滚成功就明确说可以直接重跑，回滚失败就明确说目标
     * 表可能有部分数据、重跑会产生重复行。
     */
    private static RuntimeException rollback(Connection target, Connection source,
                                             boolean sharedConnection, long written, Exception failure) {
        String outcome;
        try {
            target.rollback();
            if (!sharedConnection) {
                source.rollback();
            }
            outcome = "本步骤已写入的 " + written + " 行随事务整体回滚，目标表未留下部分数据，可以直接重跑该 step。";
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            log.error("ETL step rollback failed after {} rows", written, rollbackFailure);
            outcome = "本步骤已写入 " + written + " 行且回滚失败（" + rollbackFailure.getMessage()
                    + "），目标表可能残留部分数据，直接重跑会产生重复行：请先核对并清理目标表再重试。";
        }
        if (failure instanceof McpToolException known) {
            // getRawMessage：避免把 connection=... 前缀叠加两次
            return new McpQueryException(known.getErrorCode(), known.getRawMessage() + " " + outcome, known);
        }
        return new McpQueryException(ErrorCode.ETL_EXECUTION_FAILED,
                "ETL 步骤搬数失败：" + failure.getMessage() + " " + outcome, failure);
    }

    /**
     * Walk {@code sql}'s result set, handing {@code sink} one batch at a time.
     */
    private static long read(Connection connection, String sql, Fetch fetch, int batchSize,
                             int queryTimeoutSeconds, BatchSink sink) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            // JdbcTemplate 的 queryTimeout 只作用于它自己创建的语句；这条语句是我们自己建的，
            // 所以超时上限要自己套上，否则 ETL 取数会完全没有时间边界。
            if (queryTimeoutSeconds > 0) {
                ps.setQueryTimeout(queryTimeoutSeconds);
            }
            ps.setFetchSize(fetch.fetchSize());
            try (ResultSet rs = ps.executeQuery()) {
                return consume(rs, fetch.maxRows(), batchSize, sink);
            }
        }
    }

    private static long consume(ResultSet rs, long ceiling, int batchSize, BatchSink sink)
            throws SQLException {
        List<String> columns = columnLabels(rs.getMetaData());
        List<Map<String, Object>> batch = new ArrayList<>(batchSize);
        long written = 0;
        long read = 0;

        while (rs.next()) {
            read++;
            if (read > ceiling) {
                throw new McpQueryException(ErrorCode.QUERY_RESULT_TOO_LARGE,
                        "ETL source query returned more than " + ceiling + " rows. "
                                + "Narrow the query, or raise the step's maxSourceRows parameter.");
            }
            batch.add(readRow(rs, columns));
            if (batch.size() >= batchSize) {
                written += flush(sink, columns, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            written += flush(sink, columns, batch);
        }
        return written;
    }

    private static long flush(BatchSink sink, List<String> columns, List<Map<String, Object>> batch) {
        if (sink == null) {
            return batch.size();
        }
        return sink.write(columns, List.copyOf(batch));
    }

    /**
     * A {@link JdbcTemplate} pinned to {@code connection}, so every batch lands in the same
     * transaction.
     *
     * <p>{@code suppressClose} 让模板内部的 close 不会真的关掉这条连接 —— 它的归还由
     * {@link #run} 的 try-with-resources 负责。
     */
    private static JdbcTemplate boundTo(Connection connection, int queryTimeoutSeconds) {
        JdbcTemplate template = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        if (queryTimeoutSeconds > 0) {
            template.setQueryTimeout(queryTimeoutSeconds);
        }
        return template;
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            if (connection.getAutoCommit() != autoCommit) {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            // 连接即将归还给池，池自己也会重置 autoCommit；这里失败不该盖掉真正的失败原因。
            log.debug("Could not restore autoCommit before returning the connection: {}", e.getMessage());
        }
    }

    private static DataSource dataSourceOf(JdbcTemplate jdbc, String role) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            throw new McpQueryException(ErrorCode.ETL_EXECUTION_FAILED,
                    "ETL " + role + " JdbcTemplate has no DataSource");
        }
        return dataSource;
    }

    private static List<String> columnLabels(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<String> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(meta.getColumnLabel(i));
        }
        return columns;
    }

    private static Map<String, Object> readRow(ResultSet rs, List<String> columns) throws SQLException {
        // LinkedHashMap rather than Map.of: column order is the insert order the handlers bind by,
        // and a null cell is a legitimate value.
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i), rs.getObject(i + 1));
        }
        return row;
    }

    /** Receives one batch, already read; the internal counterpart of {@link BatchWriter}. */
    @FunctionalInterface
    private interface BatchSink {
        long write(List<String> columns, List<Map<String, Object>> batch);
    }

    /**
     * How the source result set is fetched, and the row ceiling that setting justifies.
     *
     * <p>fetchSize 不是一个跨驱动通用的旋钮，所以这里按实际驱动行为分派，而不是设一个正数就假装
     * 内存有上界：
     * <ul>
     *   <li>MySQL Connector/J 忽略正数 fetchSize，只认 {@link #MYSQL_STREAMING_FETCH_SIZE}；</li>
     *   <li>PostgreSQL 的正数 fetchSize 只在连接 autoCommit=false 时才变成服务端游标，调用方
     *       已经保证读连接在事务里；</li>
     *   <li>其余方言（H2 / Oracle / SQL Server / DB2 / SQLite）按正数 fetchSize 分批取数。</li>
     * </ul>
     */
    private record Fetch(int fetchSize, long maxRows) {

        static Fetch forSource(Connection connection, int batchSize, int maxRows,
                               boolean sharedWithWriter) {
            long requested = maxRows > 0 ? maxRows : Long.MAX_VALUE;
            String product = productName(connection);
            if (product == null) {
                return capped(batchSize, requested, "无法识别数据库产品，无法确认驱动是否支持流式取数");
            }
            if (product.contains("mysql") || product.contains("mariadb")) {
                if (sharedWithWriter) {
                    // MySQL 的逐行流式取数在结果集读完前禁止同一连接上再执行任何语句，而本 step 的
                    // 写入正走这条连接（这是换取「一个 step 一条连接 + 一个事务」的代价），所以放弃
                    // 流式，改用保守的行数上限来兜住内存。
                    return capped(batchSize, requested,
                            "MySQL 读写共用一条连接，逐行流式取数会禁止同连接写入");
                }
                return new Fetch(MYSQL_STREAMING_FETCH_SIZE, requested);
            }
            return new Fetch(batchSize, requested);
        }

        private static Fetch capped(int batchSize, long requested, String reason) {
            if (requested <= NON_STREAMING_MAX_ROWS) {
                return new Fetch(batchSize, requested);
            }
            log.warn("{}：驱动会把整个结果集缓存在客户端，本次源行数上限由 {} 降到 {}",
                    reason, requested, NON_STREAMING_MAX_ROWS);
            return new Fetch(batchSize, NON_STREAMING_MAX_ROWS);
        }

        private static String productName(Connection connection) {
            try {
                String name = connection.getMetaData().getDatabaseProductName();
                return name == null ? null : name.toLowerCase(Locale.ROOT);
            } catch (SQLException e) {
                log.debug("Database product name unavailable, assuming no streaming: {}", e.getMessage());
                return null;
            }
        }
    }
}
