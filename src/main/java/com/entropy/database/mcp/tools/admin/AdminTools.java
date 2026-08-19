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
package com.entropy.database.mcp.tools.admin;

import com.entropy.database.mcp.facade.DatabaseFacade;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Administration tools for database management.
 */
@Configuration
public class AdminTools {

    private final DatabaseFacade databaseFacade;

    public AdminTools(DatabaseFacade databaseFacade) {
        this.databaseFacade = databaseFacade;
    }

    @McpTool(description = "Clear all cached query results and metadata")
    public Map<String, Object> clearCache() {
        databaseFacade.clearCache();
        return Map.of("status", "Cache cleared successfully");
    }

    @McpTool(description = "Get database query statistics and cache metrics")
    public Map<String, Object> getStatistics() {
        return databaseFacade.getStatistics();
    }
}
