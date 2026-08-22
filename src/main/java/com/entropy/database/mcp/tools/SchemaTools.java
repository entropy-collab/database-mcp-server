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

import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import com.entropy.database.mcp.security.SqlValidator;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Schema and metadata tools.
 */
@Configuration
public class SchemaTools {

    private final RoutingDatabaseFacade routingFacade;
    private final SqlValidator sqlValidator;

    public SchemaTools(RoutingDatabaseFacade routingFacade,
                       SqlValidator sqlValidator) {
        this.routingFacade = routingFacade;
        this.sqlValidator = sqlValidator;
    }

    @McpTool(description = "List all tables in the current schema with row counts")
    public List<Map<String, Object>> listTables(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listTables(schema, connection);
    }

    @McpTool(description = "Search tables across all schemas by keyword (returns schema + table + row count)")
    public List<Map<String, Object>> searchTables(
            @McpToolParam(description = "Keyword to search in table name (optional)") String keyword,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.searchTables(keyword, connection);
    }

    @McpTool(description = "List all available schemas in the database")
    public List<String> listSchemas(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listSchemas(connection);
    }

    @McpTool(description = "Describe columns, types, and nullability of a table")
    public Map<String, Object> describeTable(
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Schema name", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.describeTable(table, schema, connection);
    }

    @McpTool(description = "List all indexes for a table including column names and uniqueness")
    public List<Map<String, Object>> listIndexes(
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Schema name", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listIndexes(table, schema, connection);
    }

    @McpTool(description = "List all views in the current schema with their definitions")
    public List<Map<String, Object>> listViews(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listViews(schema, connection);
    }

    @McpTool(description = "List all sequences in the current schema")
    public List<Map<String, Object>> listSequences(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.listSequences(schema, connection);
    }

    @McpTool(name = "describe", description = "Describe database objects: TABLE, SCHEMA, INDEX, or VIEW")
    public Map<String, Object> describe(
            @McpToolParam(description = "Object type: TABLE, SCHEMA, INDEX, or VIEW") String type,
            @McpToolParam(description = "Object name (required for TABLE, INDEX)") String name,
            @McpToolParam(description = "Schema name", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        switch (type.toUpperCase()) {
            case "TABLE": return routingFacade.describeTable(name, schema, connection);
            case "SCHEMA": return Map.of("tables", routingFacade.listTables(schema, connection));
            case "INDEX": return Map.of("indexes", routingFacade.listIndexes(name, schema, connection));
            case "VIEW": return Map.of("views", routingFacade.listViews(schema, connection));
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    @McpTool(description = "Analyze the execution plan for a SELECT query. Returns standardized plan with performance warnings (full table scan, missing indexes, etc.)")
    public PlanAnalysis explainPlan(
            @McpToolParam(description = "SQL SELECT query to analyze") String sql,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.explainPlan(sql, connection);
    }
}
