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
package com.entropy.database.mcp.repository;

import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.domain.StandardizedPlan;

/**
 * Execution plan analysis repository.
 * Provides standardized EXPLAIN PLAN functionality across dialects.
 */
public interface ExecutionPlanRepository {

    /**
     * Get standardized execution plan for a SQL query.
     */
    StandardizedPlan getExecutionPlan(String sql);

    /**
     * Get execution plan with performance warnings.
     */
    PlanAnalysis analyzeExecutionPlan(String sql);
}
