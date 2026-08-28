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

import com.entropy.database.mcp.byok.ConnectionUtils;
import com.entropy.database.mcp.facade.DatabaseOperations;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP Resources for template queries and schema metadata.
 */
@Component
public class ResourceTools {

    private static final Logger log = LoggerFactory.getLogger(ResourceTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    private final DatabaseOperations routingFacade;

    public ResourceTools(DatabaseOperations routingFacade) {
        this.routingFacade = routingFacade;
    }

    @McpResource(
            uri = "query-templates://{templateName}",
            name = "SQL 查询模板",
            description = "预定义的 SQL 查询模板文本。templateName 取值：select_sql、tables_sql、table_detail_sql；模板中的 {table}、{schema}、{condition}、{limit} 为待替换占位符。",
            mimeType = "text/plain"
    )
    public McpSchema.ReadResourceResult getQueryTemplate(String templateName) {
        String template = ToolParams.TEMPLATES.get(templateName);
        if (template == null) {
            String available = String.join(", ", ToolParams.TEMPLATES.keySet());
            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(
                            "query-templates://" + templateName,
                            "text/plain",
                            "模板不存在。可用模板：" + available
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
            name = "数据库表清单",
            description = "指定 BYOK 连接下的全部表清单，JSON 数组，每项含表名、表类型与行数估算。connection 为 createNamedConnection 注册的连接名。",
            mimeType = "application/json"
    )
    public McpSchema.ReadResourceResult getTables(String connection) {
        try {
            var tables = routingFacade.listTables(null, connection);
            String json = OBJECT_MAPPER.writeValueAsString(tables);
            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(
                            "schema://tables/" + connection,
                            "application/json",
                            json
                    )
            ));
        } catch (RuntimeException | JsonProcessingException e) {
            // A resource read must always answer with a resource; the error travels in the payload
            // instead of as an exception. Narrowed to the two families that can actually occur —
            // the facade's runtime failures and serialisation — so nothing else is silently absorbed.
            log.warn("Failed to list tables for connection '{}'", connection, e);
            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(
                            "schema://tables/" + connection,
                            "application/json",
                            "{\"error\": \"Table listing failed\"}"
                    )
            ));
        }
    }
}
