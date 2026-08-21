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

import java.util.List;

/**
 * A single step in an ETL job.
 * <p>
 * Each step has a type that determines its behavior, and can depend on other steps.
 * </p>
 *
 * @param id         unique step identifier within the job
 * @param type       the type of operation this step performs
 * @param dependsOn  list of step IDs that must complete before this step runs
 * @param connection the BYOK connection name to use (if applicable)
 * @param sourceSql  the source SQL query (for QUERY types)
 * @param targetTable the target table name (for write types)
 * @param targetConnection the target BYOK connection name (for cross-connection operations)
 * @param params     additional parameters for the step
 */
public record Step(
        String id,
        StepType type,
        List<String> dependsOn,
        String connection,
        String sourceSql,
        String targetTable,
        String targetConnection,
        java.util.Map<String, Object> params
) {
    public Step {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Step id cannot be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Step type cannot be null");
        }
        if (dependsOn == null) {
            dependsOn = List.of();
        }
        if (params == null) {
            params = java.util.Map.of();
        }
    }

    /**
     * Check if this step is a read step (requires source SQL).
     */
    public boolean isReadStep() {
        return type == StepType.QUERY_TO_TABLE ||
                type == StepType.QUERY_TO_JSON ||
                type == StepType.EXPORT;
    }

    /**
     * Check if this step is a write step (requires target table).
     */
    public boolean isWriteStep() {
        return type == StepType.QUERY_TO_TABLE ||
                type == StepType.TRANSFORM ||
                type == StepType.UPSERT ||
                type == StepType.DDL;
    }
}
