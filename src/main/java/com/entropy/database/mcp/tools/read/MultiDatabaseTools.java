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

import com.entropy.database.mcp.gateway.FederatedQueryGateway;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.util.Map;

/**
 * Multi-database tools for federated query operations.
 */
@Configuration
@ConditionalOnProperty(name = "entropy.mcp.gateway.enabled", havingValue = "true")
public class MultiDatabaseTools {

    private final FederatedQueryGateway gateway;

    public MultiDatabaseTools(FederatedQueryGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * List all registered databases.
     */
    @McpTool(description = "List all registered databases with connection info")
    public List<Map<String, Object>> listDatabases() {
        return gateway.listDatabases();
    }

    /**
     * Get federated database connection information.
     */
    @McpTool(description = "Get detailed information about a specific database in the federated gateway")
    public Map<String, Object> getFederatedDatabaseInfo(
            @McpToolParam(description = "Database identifier") String databaseId) {
        return gateway.getDatabaseInfo(databaseId);
    }

    /**
     * Execute federated query across multiple databases.
     */
    @McpTool(description = "Execute the same query across multiple databases and aggregate results")
    public Map<String, Object> executeFederatedQuery(
            @McpToolParam(description = "SQL query to execute") String query,
            @McpToolParam(description = "List of database IDs to query") List<String> databases,
            @McpToolParam(description = "Maximum rows per database") Integer maxRows) {
        return gateway.executeFederatedQuery(query, databases, maxRows);
    }

    /**
     * Execute selective queries on different databases.
     */
    @McpTool(description = "Execute different queries on different databases in parallel")
    public Map<String, Object> executeSelectiveQuery(
            @McpToolParam(description = "Map of databaseId to SQL query") Map<String, String> databaseQueries) {
        return gateway.executeSelectiveQuery(databaseQueries);
    }

    /**
     * Get gateway statistics.
     */
    @McpTool(description = "Get the number of registered database clients in the federated gateway")
    public Map<String, Object> getGatewayStatistics() {
        return Map.of(
            "clientCount", gateway.getClientCount(),
            "databases", gateway.listDatabases().size()
        );
    }
}
