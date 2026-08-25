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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch-at-a-time reader for ETL source queries.
 *
 * <p>Every step handler used to do {@code queryForList(step.sourceSql())} and hold the entire
 * source table in a {@code List<Map>} before writing a single row — on the highest-volume path in
 * the server, with no row ceiling anywhere ({@code SqlValidator.validateSelect} only checks
 * syntax). This walks the {@link ResultSet} instead, handing the caller one batch at a time so peak
 * memory is bounded by the batch size rather than by the source table, and refuses to read past a
 * caller-visible row ceiling.
 */
final class EtlRowStream {

    private static final int FALLBACK_BATCH_SIZE = 1000;

    private EtlRowStream() {
    }

    /**
     * Receives one batch of source rows and writes it, returning the number of rows written.
     */
    @FunctionalInterface
    interface BatchWriter {
        long write(List<String> columns, List<Map<String, Object>> batch);
    }

    /**
     * Read {@code sql} from {@code jdbc} in batches of {@code batchSize}, handing each batch to
     * {@code writer}.
     *
     * @param maxRows hard ceiling on source rows; exceeding it aborts the step with
     *                {@link ErrorCode#QUERY_RESULT_TOO_LARGE} rather than filling the heap
     * @return total rows reported written by {@code writer}
     */
    static long copyInBatches(JdbcTemplate jdbc, String sql, int batchSize, int maxRows, BatchWriter writer) {
        return stream(jdbc, sql, batchSize, maxRows, writer);
    }

    /**
     * Count the rows {@code sql} returns without materialising them, subject to the same ceiling.
     */
    static long countRows(JdbcTemplate jdbc, String sql, int batchSize, int maxRows) {
        return stream(jdbc, sql, batchSize, maxRows, null);
    }

    private static long stream(JdbcTemplate jdbc, String sql, int batchSize, int maxRows, BatchWriter writer) {
        int effectiveBatchSize = batchSize > 0 ? batchSize : FALLBACK_BATCH_SIZE;
        long ceiling = maxRows > 0 ? maxRows : Long.MAX_VALUE;

        Long total = jdbc.query(
                connection -> {
                    // Forward-only + a fetch size the driver can honour: without it MySQL and
                    // PostgreSQL buffer the whole result client-side, which would defeat the point
                    // of batching.
                    PreparedStatement ps = connection.prepareStatement(
                            sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                    ps.setFetchSize(effectiveBatchSize);
                    return ps;
                },
                (ResultSetExtractor<Long>) rs -> {
                    List<String> columns = columnLabels(rs.getMetaData());
                    List<Map<String, Object>> batch = new ArrayList<>(effectiveBatchSize);
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
                        if (batch.size() >= effectiveBatchSize) {
                            written += flush(writer, columns, batch);
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        written += flush(writer, columns, batch);
                    }
                    return written;
                });

        return total == null ? 0L : total;
    }

    private static long flush(BatchWriter writer, List<String> columns, List<Map<String, Object>> batch) {
        if (writer == null) {
            return batch.size();
        }
        long written = writer.write(columns, List.copyOf(batch));
        // A driver may answer Statement.SUCCESS_NO_INFO (-2) per entry instead of a row count,
        // which sums to a negative total. The batch succeeded without reporting counts, so fall
        // back to the rows submitted rather than returning a negative row count.
        return written < 0 ? batch.size() : written;
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
}
