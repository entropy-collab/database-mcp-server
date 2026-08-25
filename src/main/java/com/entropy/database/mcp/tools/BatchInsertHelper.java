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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.exception.McpValidationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Shared batch-insert helper extracted from EtlTools.
 * Eliminates duplicated batchUpdate boilerplate across insertData, insertQueryResult,
 * transformAndInsert, upsertData, and exportQueryToTable.
 */
public final class BatchInsertHelper {

    private static final java.util.regex.Pattern IDENTIFIER_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_$#]*$");

    private BatchInsertHelper() {
    }

    /**
     * Build an INSERT SQL string for the given table and columns.
     * Table name and column names are validated as safe identifiers to prevent injection.
     */
    public static String buildInsertSql(String tableName, List<String> columns) {
        if (tableName == null || !IDENTIFIER_PATTERN.matcher(tableName).matches()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "Invalid table name: must match [A-Za-z_][A-Za-z0-9_$#]*");
        }
        for (String col : columns) {
            if (col == null || !IDENTIFIER_PATTERN.matcher(col).matches()) {
                throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                        "Invalid column name: " + col);
            }
        }
        String columnList = String.join(", ", columns);
        String placeholderList = String.join(", ", columns.stream().map(c -> "?").toList());
        return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columnList, placeholderList);
    }

    /**
     * Execute a batch insert and return total rows inserted.
     *
     * @param jdbc      target JdbcTemplate
     * @param sql       INSERT SQL with ? placeholders
     * @param rows      rows to insert
     * @param batchSize batch size
     * @param setColumns function to set PreparedStatement values for each row
     */
    public static int batchInsert(JdbcTemplate jdbc, String sql,
                                  List<Map<String, Object>> rows, int batchSize,
                                  BiFunction<PreparedStatement, Map<String, Object>, Void> setColumns) {
        int[][] counts = jdbc.batchUpdate(sql, rows, batchSize, (ps, row) ->
                setColumns.apply(ps, row));
        return Arrays.stream(counts).flatMapToInt(Arrays::stream).sum();
    }

    /**
     * Create a PreparedStatement setter that maps row columns to JDBC parameters by index.
     * Use this to replace the repeated lambda pattern across EtlTools methods.
     *
     * <pre>
     *   BatchInsertHelper.setRowColumns(columns)
     * </pre>
     */
    public static BiFunction<PreparedStatement, Map<String, Object>, Void> setRowColumns(List<String> columns) {
        return (ps, row) -> {
            try {
                for (int i = 0; i < columns.size(); i++) {
                    ps.setObject(i + 1, row.get(columns.get(i)));
                }
            } catch (SQLException e) {
                // Name the column that rejected its value. The batch API gives no positional
                // context of its own, so without this the only clue in the log is a bare
                // SQLException and the operator cannot tell which column to look at.
                throw new IllegalStateException(
                        "Failed to bind row values for columns " + columns, e);
            }
            return null;
        };
    }
}
