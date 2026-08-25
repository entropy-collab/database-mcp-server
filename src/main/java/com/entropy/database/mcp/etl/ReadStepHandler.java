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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles READ steps: executes a SELECT and counts rows.
 *
 * <p>Counts while streaming: the row count was the only thing the previous
 * {@code queryForList(...).size()} needed, and materialising the whole source table to get it was
 * the most wasteful version of this step imaginable.
 */
public class ReadStepHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(ReadStepHandler.class);

    @Override
    public StepType supports() {
        return StepType.READ;
    }

    @Override
    public long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                        Step step, JobExecutionEngine engine) {
        engine.validateSourceSql(step.sourceSql());
        long rows = EtlRowStream.countRows(source.getEtlJdbcTemplate(), step.sourceSql(),
                engine.batchSize(step), engine.maxSourceRows(step));
        log.info("Read step: {} rows", rows);
        return rows;
    }
}
