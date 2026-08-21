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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution state of a MigrationJob.
 * <p>
 * Tracks the status of each step and the overall job status.
 * Supports checkpoint/resume for long-running jobs.
 * </p>
 *
 * @param jobId         the job identifier
 * @param jobName       the job name
 * @param status        overall job status
 * @param startedAt     when the job started
 * @param completedAt   when the job completed (null if still running)
 * @param stepStates    map of step ID to step execution state
 */
public record JobExecution(
        String jobId,
        String jobName,
        JobStatus status,
        Instant startedAt,
        Instant completedAt,
        Map<String, StepExecutionState> stepStates
) {
    public JobExecution {
        if (stepStates == null) {
            stepStates = new HashMap<>();
        }
    }

    public static JobExecution create(String jobId, String jobName, List<Step> steps) {
        Map<String, StepExecutionState> stepStates = new HashMap<>();
        for (Step step : steps) {
            stepStates.put(step.id(), StepExecutionState.pending(step.id()));
        }
        return new JobExecution(
                jobId,
                jobName,
                JobStatus.PENDING,
                Instant.now(),
                null,
                stepStates
        );
    }

    /**
     * Get the overall progress percentage.
     */
    public double getProgress() {
        if (stepStates.isEmpty()) return 0.0;
        long completed = stepStates.values().stream()
                .filter(s -> s.status() == StepStatus.COMPLETED)
                .count();
        return (double) completed / stepStates.size() * 100.0;
    }

    /**
     * Get the list of completed step IDs.
     */
    public List<String> getCompletedStepIds() {
        return stepStates.entrySet().stream()
                .filter(e -> e.getValue().status() == StepStatus.COMPLETED)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Get the list of failed step IDs.
     */
    public List<String> getFailedStepIds() {
        return stepStates.entrySet().stream()
                .filter(e -> e.getValue().status() == StepStatus.FAILED)
                .map(Map.Entry::getKey)
                .toList();
    }
}
