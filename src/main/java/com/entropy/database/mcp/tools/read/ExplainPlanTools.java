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
package com.entropy.database.mcp.tools.read;

import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.facade.DatabaseFacade;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;

/**
 * Execution plan analysis tools for query optimization.
 */
@Configuration
public class ExplainPlanTools {

    private final DatabaseFacade databaseFacade;

    public ExplainPlanTools(DatabaseFacade databaseFacade) {
        this.databaseFacade = databaseFacade;
    }

    /**
     * Analyze the execution plan for a SELECT query.
     * Returns a standardized plan with performance warnings across all supported dialects.
     */
    @McpTool(description = "Analyze the execution plan for a SELECT query. Returns standardized plan with performance warnings (full table scan, missing indexes, etc.)")
    public PlanAnalysis explainPlan(
            @McpToolParam(description = "SQL SELECT query to analyze") String sql) {
        return databaseFacade.explainPlan(sql);
    }
}
