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

import com.entropy.database.mcp.facade.DatabaseFacade;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Metadata query tools for database schema exploration.
 */
@Configuration
public class ReadMetadataTools {

    private final DatabaseFacade databaseFacade;

    public ReadMetadataTools(DatabaseFacade databaseFacade) {
        this.databaseFacade = databaseFacade;
    }

    /**
     * List all tables in the current schema with row counts.
     */
    @McpTool(description = "List all tables in the current schema with row counts")
    public List<Map<String, Object>> listTables(
            @McpToolParam(description = "Schema name") String schema) {
        return databaseFacade.listTables(schema);
    }

    /**
     * Search tables across all schemas by keyword.
     */
    @McpTool(description = "Search tables across all schemas by keyword (returns schema + table + row count)")
    public List<Map<String, Object>> searchTables(
            @McpToolParam(description = "Keyword to search in table name (optional)") String keyword) {
        return databaseFacade.searchTables(keyword);
    }

    /**
     * List all available schemas in the database.
     */
    @McpTool(description = "List all available schemas in the database")
    public List<String> listSchemas() {
        return databaseFacade.listSchemas();
    }

    /**
     * Describe columns, types, and nullability of a table.
     */
    @McpTool(description = "Describe columns, types, and nullability of a table")
    public Map<String, Object> describeTable(
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Schema name") String schema) {
        return databaseFacade.describeTable(table, schema);
    }

    /**
     * List all indexes for a table.
     */
    @McpTool(description = "List all indexes for a table including column names and uniqueness")
    public List<Map<String, Object>> listIndexes(
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Schema name") String schema) {
        return databaseFacade.listIndexes(table, schema);
    }

    /**
     * List all views in the schema.
     */
    @McpTool(description = "List all views in the current schema with their definitions")
    public List<Map<String, Object>> listViews(
            @McpToolParam(description = "Schema name") String schema) {
        return databaseFacade.listViews(schema);
    }

    /**
     * List all sequences in the schema.
     */
    @McpTool(description = "List all sequences in the current schema")
    public List<Map<String, Object>> listSequences(
            @McpToolParam(description = "Schema name") String schema) {
        return databaseFacade.listSequences(schema);
    }

    /**
     * Describe database objects: TABLE, SCHEMA, INDEX, or VIEW.
     */
    @McpTool(name = "describe", description = "Describe database objects: TABLE, SCHEMA, INDEX, or VIEW")
    public Object describe(
            @McpToolParam(description = "Object type: TABLE, SCHEMA, INDEX, or VIEW") String type,
            @McpToolParam(description = "Object name (required for TABLE, INDEX)") String name,
            @McpToolParam(description = "Schema name") String schema) {
        switch (type.toUpperCase()) {
            case "TABLE": return databaseFacade.describeTable(name, schema);
            case "SCHEMA": return Map.of("tables", databaseFacade.listTables(schema));
            case "INDEX": return databaseFacade.listIndexes(name, schema);
            case "VIEW": return Map.of("views", databaseFacade.listViews(schema));
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }
}
