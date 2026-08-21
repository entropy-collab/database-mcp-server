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
import com.entropy.database.mcp.util.ConnectionUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * MCP Resources for template queries and schema metadata.
 */
@Configuration
public class ResourceTools {

    private static final Logger log = LoggerFactory.getLogger(ResourceTools.class);

    private static final Map<String, String> TEMPLATES = Map.of(
            "query_by_id", "SELECT * FROM {table} WHERE {idColumn} = :id",
            "list_by_page", "SELECT * FROM {table} LIMIT :limit OFFSET :offset",
            "count_by_condition", "SELECT COUNT(*) FROM {table} WHERE {condition}"
    );

    private final RoutingDatabaseFacade routingFacade;

    public ResourceTools(RoutingDatabaseFacade routingFacade) {
        this.routingFacade = routingFacade;
    }

    @McpResource(
            uri = "query-templates://{templateName}",
            name = "Query Template",
            description = "Predefined SQL query templates",
            mimeType = "text/plain"
    )
    public McpSchema.ReadResourceResult getQueryTemplate(String templateName) {
        String template = TEMPLATES.get(templateName);
        if (template == null) {
            String available = String.join(", ", TEMPLATES.keySet());
            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(
                            "query-templates://" + templateName,
                            "text/plain",
                            "Template not found. Available templates: " + available
                    )
            ));
        }
        return new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(
                        "query-templates://" + templateName,
                        "text/plain",
                        template
                )
        ));
    }

    @McpResource(
            uri = "schema://tables/{connection}",
            name = "Database Tables",
            description = "List of tables in the database",
            mimeType = "application/json"
    )
    public McpSchema.ReadResourceResult getTables(String connection) {
        try {
            var cp = ConnectionUtils.parseConnection(connection);
            var tables = routingFacade.listTables(null, cp);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tables);
            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(
                            "schema://tables/" + (connection != null ? connection : "primary"),
                            "application/json",
                            json
                    )
            ));
        } catch (Exception e) {
            log.warn("Failed to list tables: {}", e.getMessage());
            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(
                            "schema://tables/" + (connection != null ? connection : "primary"),
                            "application/json",
                            "{\"error\": \"" + e.getMessage() + "\"}"
                    )
            ));
        }
    }
}
