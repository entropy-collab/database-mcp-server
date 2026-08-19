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

import com.entropy.database.mcp.security.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL template tools for executing parameterized queries safely.
 */
@Configuration
public class SqlTemplateTools {

    private static final Logger log = LoggerFactory.getLogger(SqlTemplateTools.class);

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final SqlValidator sqlValidator;

    public SqlTemplateTools(NamedParameterJdbcTemplate namedParameterJdbcTemplate, SqlValidator sqlValidator) {
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.sqlValidator = sqlValidator;
    }

    private static final Map<String, String> TEMPLATES = Map.of(
            "query_by_id", "SELECT * FROM {table} WHERE {idColumn} = :id",
            "list_by_page", "SELECT * FROM {table} LIMIT :limit OFFSET :offset",
            "count_by_condition", "SELECT COUNT(*) FROM {table} WHERE {condition}"
    );

    @McpTool(name = "executeSqlTemplate", description = "Execute a parameterized SQL template safely")
    public Map<String, Object> executeSqlTemplate(
            @McpToolParam(description = "Template name: query_by_id, list_by_page, or count_by_condition") String templateName,
            @McpToolParam(description = "Table name") String table,
            @McpToolParam(description = "Parameters as key-value pairs") Map<String, Object> params) throws Exception {
        String template = TEMPLATES.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + templateName
                    + ". Available templates: " + TEMPLATES.keySet());
        }

        // Validate and replace {table} placeholder with raw table name
        String sql = template.replace("{table}", table);

        // Inject table name into params so validator can check it
        Map<String, Object> boundParams = new HashMap<>(params != null ? params : Map.of());

        // Extract placeholders from template for replacement
        if (templateName.equals("query_by_id")) {
            sql = sql.replace("{idColumn}", (String) boundParams.getOrDefault("idColumn", "id"));
        } else if (templateName.equals("count_by_condition")) {
            sql = sql.replace("{condition}", (String) boundParams.getOrDefault("condition", "1=1"));
        }

        log.debug("executeSqlTemplate: template={}, table={}, params={}", templateName, table, boundParams);

        // Validate the final SQL
        sqlValidator.validateSelect(sql);

        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(sql, boundParams);
        return Map.of("rows", rows, "rowCount", rows.size());
    }
}
