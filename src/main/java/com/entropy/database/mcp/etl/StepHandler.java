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

/**
 * Command processor for a single ETL step type.
 * <p>
 * Each implementation handles one {@link StepType}, keeping
 * {@link JobExecutionEngine} free of switch statements.
 * New step types only require adding a new handler bean.
 * </p>
 */
public interface StepHandler {

    /**
     * Returns the step type this handler processes.
     */
    StepType supports();

    /**
     * Execute the step and return the number of rows affected.
     *
     * @param source  source connection context (may be same as target)
     * @param target  target connection context
     * @param step    the step definition
     * @param engine  the parent engine (for config/params access)
     */
    long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                 Step step, JobExecutionEngine engine);
}
