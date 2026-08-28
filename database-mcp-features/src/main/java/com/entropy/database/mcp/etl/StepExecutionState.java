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

import java.time.Instant;
import java.util.Map;

/**
 * Execution state of a single step in an ETL job.
 *
 * @param stepId       the step identifier
 * @param status       current status
 * @param startedAt    when the step started
 * @param completedAt  when the step completed
 * @param rowsAffected number of rows affected
 * @param error        error message if failed
 * @param checkpoint   optional checkpoint data for resume
 */
public record StepExecutionState(
        String stepId,
        StepStatus status,
        Instant startedAt,
        Instant completedAt,
        Long rowsAffected,
        String error,
        Map<String, Object> checkpoint
) {
    public StepExecutionState {
        if (checkpoint == null) {
            checkpoint = java.util.Map.of();
        }
    }

    public static StepExecutionState pending(String stepId) {
        return new StepExecutionState(
                stepId,
                StepStatus.PENDING,
                null,
                null,
                null,
                null,
                java.util.Map.of()
        );
    }

    public static StepExecutionState running(String stepId) {
        return new StepExecutionState(
                stepId,
                StepStatus.RUNNING,
                Instant.now(),
                null,
                null,
                null,
                java.util.Map.of()
        );
    }

    public static StepExecutionState completed(String stepId, long rowsAffected) {
        return new StepExecutionState(
                stepId,
                StepStatus.COMPLETED,
                null,
                Instant.now(),
                rowsAffected,
                null,
                java.util.Map.of()
        );
    }

    public static StepExecutionState failed(String stepId, String error) {
        return new StepExecutionState(
                stepId,
                StepStatus.FAILED,
                null,
                Instant.now(),
                null,
                error,
                java.util.Map.of()
        );
    }

    public static StepExecutionState skipped(String stepId) {
        return new StepExecutionState(
                stepId,
                StepStatus.SKIPPED,
                null,
                null,
                null,
                null,
                java.util.Map.of()
        );
    }
}
