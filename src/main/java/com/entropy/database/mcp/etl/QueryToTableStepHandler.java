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

/**
 * Handles QUERY_TO_TABLE steps: executes a source SQL and inserts results into a target table.
 *
 * <p>Reads the source in batches rather than into one list: this is the bulk data-movement step,
 * so the source table's size must not decide peak heap usage.
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

        engine.validateSourceSql(step.sourceSql());

        String targetTable = dialect.normalizeTableName(step.targetTable());
        int batchSize = engine.batchSize(step);

        return EtlRowStream.copyInBatches(sourceJdbc, step.sourceSql(), batchSize,
                engine.maxSourceRows(step),
                (columns, batch) -> {
                    String insertSql = EtlSql.insertInto(dialect, targetTable, columns);
                    return EtlSql.sum(targetJdbc.batchUpdate(insertSql, batch, batch.size(),
                            EtlSql.bindColumns(columns)));
                });
    }
}
