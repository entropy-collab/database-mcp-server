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
 * Handles EXPORT steps (placeholder).
 */
public class ExportStepHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(ExportStepHandler.class);

    @Override
    public StepType supports() {
        return StepType.EXPORT;
    }

    @Override
    public long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                        Step step, JobExecutionEngine engine) {
        log.info("Export step: {}", step.id());
        return 0;
    }
}
