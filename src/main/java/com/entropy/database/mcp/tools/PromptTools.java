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

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP Prompts that guide AI models on how to use the database MCP server.
 * <p>
 * Prompts appear in {@code prompts/list} and can be invoked by clients
 * to receive structured guidance for common workflows.
 */
@Component
public class PromptTools {

    private static final Logger log = LoggerFactory.getLogger(PromptTools.class);

    // ─── Guided Workflow Prompts ─────────────────────────────────────────

    @McpPrompt(
            name = "database-quick-start",
            title = "Database Quick Start",
            description = "Step-by-step guide to connect to a database and run your first query. Use this when no database is configured yet."
    )
    public McpSchema.GetPromptResult quickStart(
            @McpArg(name = "databaseType", description = "Database type: h2, oracle, mysql, postgres, sqlserver, sqlite, db2", required = false) String databaseType) {

        String type = (databaseType != null && !databaseType.isBlank()) ? databaseType.toLowerCase() : "your database";

        String guide = """
                This MCP server has NO pre-configured database. You must create a connection first.
                
                STEP 1: Create a named connection
                Call createNamedConnection with:
                  - name: a memorable name (e.g. "my-db")
                  - jdbcUrl: the JDBC URL (e.g. "jdbc:postgresql://localhost:5432/mydb")
                  - username: database username
                  - password: database password
                  - dialect: %s (or one of: h2, oracle, mysql, postgres, sqlserver, sqlite, db2)
                
                STEP 2: Use the connection
                Pass the connection name directly to each tool.
                Example: listTables("PUBLIC", "%s")
                
                STEP 3: Explore the database
                Call listTables to see available tables, then describeTable to inspect columns.
                
                STEP 4: Run queries
                Call executeQuery with your SQL. Use executeQueryWithFilter for parameterized queries.
                
                TIP: Always pass the connection name explicitly for clarity.
                """.formatted(type);

        return new McpSchema.GetPromptResult(
                "Database Quick Start Guide",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(guide)
                ))
        );
    }

    @McpPrompt(
            name = "connect-to-database",
            title = "Connect to Database",
            description = "Detailed instructions for creating a BYOK database connection with the correct JDBC URL format."
    )
    public McpSchema.GetPromptResult connectToDatabase(
            @McpArg(name = "databaseType", description = "Database type: h2, oracle, mysql, postgres, sqlserver, sqlite, db2", required = true) String databaseType) {

        String urlTemplate = switch (databaseType.toLowerCase()) {
            case "h2" -> "jdbc:h2:mem:testdb (in-memory) or jdbc:h2:file:/path/to/db (file-based)";
            case "oracle" -> "jdbc:oracle:thin:@//host:port/service_name";
            case "mysql" -> "jdbc:mysql://host:port/database?useSSL=false&serverTimezone=UTC";
            case "postgres", "postgresql" -> "jdbc:postgresql://host:port/database";
            case "sqlserver" -> "jdbc:sqlserver://host:port;databaseName=database";
            case "sqlite" -> "jdbc:sqlite:/path/to/database.db";
            case "db2" -> "jdbc:db2://host:port/database";
            default -> "jdbc:<driver>://host:port/database";
        };

        String guide = """
                To connect to a %s database:
                
                1. Prepare your JDBC URL:
                   %s
                
                2. Call createNamedConnection with:
                   - name: choose a name (e.g. "%s-prod")
                   - jdbcUrl: the URL from step 1
                   - username: your database username
                   - password: your database password
                   - dialect: %s
                
                3. Verify the connection:
                   Call describeConnection with the name you chose.
                """.formatted(databaseType.toLowerCase(), urlTemplate, databaseType.toLowerCase(), databaseType.toLowerCase());

        return new McpSchema.GetPromptResult(
                "Connect to " + databaseType.toUpperCase() + " Database",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(guide)
                ))
        );
    }

    @McpPrompt(
            name = "query-workflow",
            title = "Query Workflow",
            description = "Recommended workflow for exploring and querying a database. Shows how to use listTables, describeTable, executeQuery, and executeQueryWithFilter together."
    )
    public McpSchema.GetPromptResult queryWorkflow() {

        String guide = """
                Recommended workflow for database exploration and querying:
                
                1. Create a connection (if not already done):
                   Call createNamedConnection with your database details.
                
                2. List available tables:
                   Call listTables with the connection name, e.g. listTables("PUBLIC", "my-db").
                   Returns table names and row counts.
                
                3. Inspect a table's schema:
                   Call describeTable with the table name and connection name.
                   Returns column names, data types, and nullability.
                
                4. Run a simple query:
                   Call executeQuery with SQL like "SELECT * FROM table_name WHERE ROWNUM <= 10".
                   Pass the connection name.
                
                5. Run a parameterized query (safer):
                   Call executeQueryWithFilter with:
                   - sql: "SELECT * FROM table_name WHERE id = :id"
                   - params: {"id": 123}
                   - connection: your connection name
                   This prevents SQL injection.
                
                6. Paginate through large results:
                   Call executeQuery with maxRows=100.
                   If hasMore=true, use the continuationToken for the next page.
                
                TIP: Always pass the connection name directly to each tool for clarity.
                """;

        return new McpSchema.GetPromptResult(
                "Database Query Workflow",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(guide)
                ))
        );
    }
}
