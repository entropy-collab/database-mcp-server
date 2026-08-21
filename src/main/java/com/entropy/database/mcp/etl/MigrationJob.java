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
import java.util.ArrayList;
import java.util.List;

/**
 * Declarative ETL job definition.
 * <p>
 * A MigrationJob consists of multiple Steps that are executed in dependency order.
 * Steps can depend on other steps, forming a DAG.
 * </p>
 *
 * @param id          unique job identifier
 * @param name        human-readable job name
 * @param description job description
 * @param steps       ordered list of steps to execute
 */
public record MigrationJob(
        String id,
        String name,
        String description,
        List<Step> steps
) {
    public MigrationJob {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Job id cannot be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Job name cannot be blank");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Job must have at least one step");
        }
    }

    /**
     * Validate the job definition.
     *
     * @throws IllegalArgumentException if the job is invalid
     */
    public void validate() {
        // Check for duplicate step IDs
        List<String> stepIds = steps.stream()
                .map(Step::id)
                .toList();
        long uniqueCount = stepIds.stream().distinct().count();
        if (uniqueCount != stepIds.size()) {
            throw new IllegalArgumentException("Duplicate step IDs found in job");
        }

        // Check that all dependencies exist
        for (Step step : steps) {
            for (String dep : step.dependsOn()) {
                if (!stepIds.contains(dep)) {
                    throw new IllegalArgumentException(
                            "Step '%s' depends on unknown step '%s'".formatted(step.id(), dep));
                }
            }
        }

        // Check for cycles (simple DFS)
        List<String> visited = new ArrayList<>();
        List<String> recursionStack = new ArrayList<>();
        for (Step step : steps) {
            if (!visited.contains(step.id())) {
                if (hasCycle(step, stepIds, visited, recursionStack)) {
                    throw new IllegalArgumentException("Circular dependency detected in job steps");
                }
            }
        }
    }

    private boolean hasCycle(Step step, List<String> stepIds, List<String> visited, List<String> recursionStack) {
        visited.add(step.id());
        recursionStack.add(step.id());

        for (String dep : step.dependsOn()) {
            if (recursionStack.contains(dep)) {
                return true;
            }
            if (!visited.contains(dep)) {
                Step depStep = steps().stream()
                        .filter(s -> s.id().equals(dep))
                        .findFirst()
                        .orElse(null);
                if (depStep != null && hasCycle(depStep, stepIds, visited, recursionStack)) {
                    return true;
                }
            }
        }

        recursionStack.remove(step.id());
        return false;
    }
}
