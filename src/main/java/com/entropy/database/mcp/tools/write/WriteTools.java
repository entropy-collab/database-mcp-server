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
package com.entropy.database.mcp.tools.write;

import com.entropy.database.mcp.facade.DatabaseFacade;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Write operations tools for database modifications.
 */
@Configuration
public class WriteTools {

    private final DatabaseFacade databaseFacade;
    private final boolean ddlAllowed;

    public WriteTools(DatabaseFacade databaseFacade,
                      @Value("${entropy.mcp.database.ddl.allowed:false}") boolean ddlAllowed) {
        this.databaseFacade = databaseFacade;
        this.ddlAllowed = ddlAllowed;
    }

    /**
     * Execute DDL statement.
     */
    @McpTool(description = "Execute a DDL statement (CREATE/ALTER/DROP)")
    public Map<String, Object> executeDdl(
            @McpToolParam(description = "DDL SQL statement") String sql) {
        if (!ddlAllowed) {
            return Map.of("error", "DDL execution is disabled. Set entropy.mcp.database.ddl.allowed=true to enable.");
        }
        return databaseFacade.executeDdl(sql);
    }

    /**
     * Backup table schema.
     */
    @McpTool(description = "Backup table schema definition as DDL statements")
    public Map<String, Object> backupSchema(
            @McpToolParam(description = "Table name") String tableName) {
        return databaseFacade.backupSchema(tableName);
    }

    /**
     * Backup table data.
     */
    @McpTool(description = "Backup table data as INSERT statements")
    public Map<String, Object> backupData(
            @McpToolParam(description = "Table name") String tableName,
            @McpToolParam(description = "Maximum rows to backup") int maxRows) {
        return databaseFacade.backupData(tableName, maxRows);
    }

    /**
     * Diff two tables schema.
     */
    @McpTool(description = "Compare schema differences between two tables")
    public Map<String, Object> diffSchema(
            @McpToolParam(description = "Source table name") String sourceTable,
            @McpToolParam(description = "Target table name") String targetTable) {
        return databaseFacade.diffSchema(sourceTable, targetTable);
    }
}
