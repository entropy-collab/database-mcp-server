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

import java.util.List;
import java.util.Map;

/**
 * Handles UPSERT steps using dialect-specific buildUpsertSql.
 */
public class UpsertStepHandler implements StepHandler {

    @Override
    public StepType supports() {
        return StepType.UPSERT;
    }

    @Override
    public long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                        Step step, JobExecutionEngine engine) {
        var dialect = target.getDialect();
        JdbcTemplate jdbc = target.getJdbcTemplate();
        List<String> keyColumns = engine.getListParam(step, "keyColumns", List.of());
        String tableName = dialect.normalizeTableName(step.targetTable());

        // Upsert data is passed via step.sourceSql as rows in the params or directly
        // For step-based execution, reuse the source to read rows then upsert into target
        List<Map<String, Object>> rows = source.getJdbcTemplate().queryForList(step.sourceSql());
        if (rows.isEmpty()) return 0;

        List<String> allColumns = new java.util.ArrayList<>(rows.get(0).keySet());
        String upsertSql = dialect.buildUpsertSql(tableName, allColumns, keyColumns);
        if (upsertSql == null) {
            throw new UnsupportedOperationException(
                    "UPSERT not supported for dialect: " + dialect.getClass().getSimpleName());
        }

        int batchSize = engine.getIntParam(step, "batchSize", 1000);
        int[][] updateCounts = jdbc.batchUpdate(upsertSql, rows, batchSize, (ps, row) -> {
            for (int i = 0; i < allColumns.size(); i++) {
                ps.setObject(i + 1, row.get(allColumns.get(i)));
            }
        });

        return java.util.Arrays.stream(updateCounts).flatMapToInt(java.util.Arrays::stream).sum();
    }
}
