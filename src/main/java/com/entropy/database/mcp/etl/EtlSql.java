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

import com.entropy.database.mcp.dialect.DatabaseDialect;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Statement construction and binding shared by the writing step handlers.
 *
 * <p>Each handler used to build its own {@code INSERT} string and its own positional setter, and
 * the copies had already drifted. One place also means the column list that names the placeholders
 * is the same list that binds them.
 */
final class EtlSql {

    private EtlSql() {
    }

    /**
     * {@code INSERT INTO table (quoted columns) VALUES (?, ?, ...)}.
     */
    static String insertInto(DatabaseDialect dialect, String table, List<String> columns) {
        String columnList = String.join(", ", columns.stream().map(dialect::quote).toList());
        String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        return "INSERT INTO " + table + " (" + columnList + ") VALUES (" + placeholders + ")";
    }

    /**
     * Bind one row positionally in {@code columns} order — the same order
     * {@link #insertInto} emitted the placeholders in.
     */
    static ParameterizedPreparedStatementSetter<Map<String, Object>> bindColumns(List<String> columns) {
        return (ps, row) -> {
            for (int i = 0; i < columns.size(); i++) {
                ps.setObject(i + 1, row.get(columns.get(i)));
            }
        };
    }

    /**
     * Total rows reported by a batched update.
     */
    static long sum(int[][] updateCounts) {
        return Arrays.stream(updateCounts).flatMapToInt(Arrays::stream).sum();
    }
}
