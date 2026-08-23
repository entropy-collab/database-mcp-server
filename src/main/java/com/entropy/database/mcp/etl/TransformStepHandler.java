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

import com.entropy.database.mcp.config.DatabaseConstants;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Handles TRANSFORM steps: applies column mappings and transforms, then inserts into target table.
 */
public class TransformStepHandler implements StepHandler {

    @Override
    public StepType supports() {
        return StepType.TRANSFORM;
    }

    @Override
    public long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                        Step step, JobExecutionEngine engine) {
        List<String> columnMapping = engine.getListParam(step, "columnMapping", List.of());
        String whereClause = engine.getStringParam(step, "whereClause", null);

        JdbcTemplate jdbcTemplate = source.getJdbcTemplate();
        var dialect = source.getDialect();

        List<String> sourceColumns = new ArrayList<>();
        List<String> targetColumns = new ArrayList<>();
        List<String> transforms = new ArrayList<>();

        for (String mapping : columnMapping) {
            String[] parts = mapping.split(":");
            if (parts.length < 2) continue;
            sourceColumns.add(parts[0]);
            targetColumns.add(parts[1]);
            transforms.add(parts.length >= 3 ? parts[2] : "none");
        }

        StringBuilder selectSql = new StringBuilder("SELECT ");
        List<String> selectExprs = new ArrayList<>();
        for (int i = 0; i < sourceColumns.size(); i++) {
            String src = sourceColumns.get(i);
            String transform = transforms.get(i);
            String expr = switch (transform) {
                case "upper" -> "UPPER(" + src + ")";
                case "lower" -> "LOWER(" + src + ")";
                case "trim" -> "TRIM(" + src + ")";
                case "int" -> "CAST(" + src + " AS INTEGER)";
                case "long" -> "CAST(" + src + " AS BIGINT)";
                case "double" -> "CAST(" + src + " AS DOUBLE)";
                default -> src;
            };
            selectExprs.add(expr + " AS " + dialect.quote(targetColumns.get(i)));
        }
        selectSql.append(String.join(", ", selectExprs));
        selectSql.append(" FROM (").append(step.sourceSql()).append(") AS _src");
        if (whereClause != null && !whereClause.isBlank()) {
            selectSql.append(" WHERE ").append(whereClause);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql.toString());
        if (rows.isEmpty()) return 0;

        String targetTable = dialect.normalizeTableName(step.targetTable());
        String columnList = String.join(", ", targetColumns.stream().map(dialect::quote).toList());
        String placeholderList = String.join(", ", targetColumns.stream().map(c -> "?").toList());
        String insertSql = "INSERT INTO " + targetTable + " (" + columnList + ") VALUES (" + placeholderList + ")";

        int batchSize = engine.getIntParam(step, "batchSize", DatabaseConstants.DEFAULT_BATCH_SIZE);
        int[][] updateCounts = jdbcTemplate.batchUpdate(insertSql, rows, batchSize, (ps, row) -> {
            for (int i = 0; i < targetColumns.size(); i++) {
                ps.setObject(i + 1, row.get(targetColumns.get(i)));
            }
        });

        return Arrays.stream(updateCounts).flatMapToInt(Arrays::stream).sum();
    }
}
