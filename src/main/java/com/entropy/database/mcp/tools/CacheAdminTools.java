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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;

/**
 * Cache administration tools.
 */
@Configuration
public class CacheAdminTools {

    private static final Logger log = LoggerFactory.getLogger(CacheAdminTools.class);

    private final RoutingDatabaseFacade routingFacade;

    public CacheAdminTools(RoutingDatabaseFacade routingFacade) {
        this.routingFacade = routingFacade;
    }

    @McpTool(description = "Clear all cached query results and metadata")
    public Map<String, Object> clearCache(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        routingFacade.clearCache(connection);
        return successResponse(Map.of("status", "Cache cleared successfully"));
    }

    @McpTool(description = "Get database query statistics and cache metrics")
    public Map<String, Object> getStatistics(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.getStatistics(connection);
    }
}
