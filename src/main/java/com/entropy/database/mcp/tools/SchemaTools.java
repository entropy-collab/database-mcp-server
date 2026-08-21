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

import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.util.ConnectionUtils;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.facade.DatabaseFacade;
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
    private final DatabaseFacade databaseFacade;

    public SchemaTools(RoutingDatabaseFacade routingFacade,
                       SqlValidator sqlValidator,
                       DatabaseFacade databaseFacade) {
        this.routingFacade = routingFacade;
        this.sqlValidator = sqlValidator;
        this.databaseFacade = databaseFacade;
    }

    private ConnectionProperties parseConnection(String connectionJson) {
        return ConnectionUtils.parseConnection(connectionJson);
    }

    @McpTool(description = "List all tables in the current schema with row counts")
    public List<Map<String, Object>> listTables(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.listTables(schema, parseConnection(connection));
    }

    @McpTool(description = "Search tables across all schemas by keyword (returns schema + table + row count)")
    public List<Map<String, Object>> searchTables(
            @McpToolParam(description = "Keyword to search in table name (optional)") String keyword,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.searchTables(keyword, parseConnection(connection));
    }

    @McpTool(description = "List all available schemas in the database")
    public List<String> listSchemas(
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.listSchemas(parseConnection(connection));
    }

    @McpTool(description = "Describe columns, types, and nullability of a table")
    public Map<String, Object> describeTable(
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Schema name", required = false) String schema,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.describeTable(table, schema, parseConnection(connection));
    }

    @McpTool(description = "List all indexes for a table including column names and uniqueness")
    public List<Map<String, Object>> listIndexes(
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Schema name", required = false) String schema,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.listIndexes(table, schema, parseConnection(connection));
    }

    @McpTool(description = "List all views in the current schema with their definitions")
    public List<Map<String, Object>> listViews(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.listViews(schema, parseConnection(connection));
    }

    @McpTool(description = "List all sequences in the current schema")
    public List<Map<String, Object>> listSequences(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.listSequences(schema, parseConnection(connection));
    }

    @McpTool(name = "describe", description = "Describe database objects: TABLE, SCHEMA, INDEX, or VIEW")
    public Object describe(
            @McpToolParam(description = "Object type: TABLE, SCHEMA, INDEX, or VIEW") String type,
            @McpToolParam(description = "Object name (required for TABLE, INDEX)") String name,
            @McpToolParam(description = "Schema name", required = false) String schema,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        switch (type.toUpperCase()) {
            case "TABLE": return routingFacade.describeTable(name, schema, parseConnection(connection));
            case "SCHEMA": return Map.of("tables", routingFacade.listTables(schema, parseConnection(connection)));
            case "INDEX": return routingFacade.listIndexes(name, schema, parseConnection(connection));
            case "VIEW": return Map.of("views", routingFacade.listViews(schema, parseConnection(connection)));
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    @McpTool(description = "Analyze the execution plan for a SELECT query. Returns standardized plan with performance warnings (full table scan, missing indexes, etc.)")
    public PlanAnalysis explainPlan(
            @McpToolParam(description = "SQL SELECT query to analyze") String sql,
            @McpToolParam(description = "Optional BYOK connection JSON. Omit to use primary datasource.", required = false) String connection) {
        return routingFacade.explainPlan(sql, parseConnection(connection));
    }
}
