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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Handles QUERY_TO_TABLE steps: executes a source SQL and inserts results into a target table.
 */
public class QueryToTableStepHandler implements StepHandler {

    @Override
    public StepType supports() {
        return StepType.QUERY_TO_TABLE;
    }

    @Override
    public long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                        Step step, JobExecutionEngine engine) {
        JdbcTemplate sourceJdbc = source.getJdbcTemplate();
        JdbcTemplate targetJdbc = target.getJdbcTemplate();
        var dialect = target.getDialect();

        String targetTable = dialect.normalizeTableName(step.targetTable());
        List<Map<String, Object>> rows = sourceJdbc.queryForList(step.sourceSql());
        if (rows.isEmpty()) return 0;

        List<String> columns = new java.util.ArrayList<>(rows.get(0).keySet());
        String columnList = String.join(", ", columns.stream().map(dialect::quote).toList());
        String placeholderList = String.join(", ", columns.stream().map(c -> "?").toList());
        String insertSql = "INSERT INTO " + targetTable + " (" + columnList + ") VALUES (" + placeholderList + ")";

        int batchSize = engine.getIntParam(step, "batchSize", 1000);
        int[][] updateCounts = targetJdbc.batchUpdate(insertSql, rows, batchSize, (ps, row) -> {
            for (int i = 0; i < columns.size(); i++) {
                ps.setObject(i + 1, row.get(columns.get(i)));
            }
        });

        return Arrays.stream(updateCounts).flatMapToInt(Arrays::stream).sum();
    }
}
