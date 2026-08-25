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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.quality.QualityAlertService;
import com.entropy.database.mcp.quality.QualityCheckService;
import com.entropy.database.mcp.quality.QualityReport;
import com.entropy.database.mcp.quality.QualityReportService;
import com.entropy.database.mcp.quality.QualityRule;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Data quality check MCP tools.
 */
@Component
public class QualityTools extends McpToolBase {

    private final DynamicDataSourceManager dataSourceManager;
    private final QualityCheckService qualityCheckService;
    private final QualityReportService qualityReportService;
    private final QualityAlertService qualityAlertService;

    public QualityTools(DynamicDataSourceManager dataSourceManager,
                        QualityCheckService qualityCheckService,
                        QualityReportService qualityReportService,
                        QualityAlertService qualityAlertService) {
        this.dataSourceManager = dataSourceManager;
        this.qualityCheckService = qualityCheckService;
        this.qualityReportService = qualityReportService;
        this.qualityAlertService = qualityAlertService;
    }

    @McpTool(description = "Run data quality checks on a table",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> checkTableQuality(
            @McpToolParam(description = "Connection name", required = false) String connectionName,
            @McpToolParam(description = "Table name to check") String tableName,
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Custom quality rules as JSON list (optional)", required = false) List<Map<String, Object>> customRules,
            @McpToolParam(description = "Report format: json, csv, or text (default json)", required = false) String format) {
        return safeExecute(() -> {
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            JdbcTemplate jdbc = context.getJdbcTemplate();
            DatabaseDialect dialect = context.getDialect();

            if (!dialect.isValidIdentifier(tableName)) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid table name: " + tableName + " (tableName=" + tableName + ")");
            }
            String normalizedTable = dialect.normalizeTableName(tableName);
            List<QualityRule> rules = buildRules(customRules);

            QualityReport report = qualityCheckService.check(connectionName, normalizedTable, schema, rules, dialect, jdbc);

            String reportStr = switch (format != null ? format.toLowerCase() : "json") {
                case "csv" -> qualityReportService.exportCsv(report);
                case "text" -> qualityReportService.exportText(report);
                default -> qualityReportService.exportJson(report);
            };

            return success(Map.of(
                    "report", report,
                    "formattedReport", reportStr,
                    "format", format != null ? format : "json"
            ));
        });
    }

    @McpTool(description = "List all built-in quality rule templates",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listQualityRuleTemplates() {
        return safeExecute(() -> success(Map.of("templates", List.of(
                ruleTemplate("null_rate", "Null Rate Check",
                        "Counts NULL values per column. Triggered when null rate exceeds threshold.",
                        Map.of("column", "string", "threshold", "double")),
                ruleTemplate("duplicates", "Duplicate Row Check",
                        "Counts duplicate rows based on specified columns.",
                        Map.of("columns", "list of strings", "threshold", "double")),
                ruleTemplate("enum_values", "Enum Value Check",
                        "Checks that column values are within an allowed set.",
                        Map.of("column", "string", "allowedValues", "list of strings")),
                ruleTemplate("range", "Numeric Range Check",
                        "Checks that numeric column values fall within min/max bounds.",
                        Map.of("column", "string", "min", "number", "max", "number")),
                ruleTemplate("custom_sql", "Custom SQL Condition",
                        "Runs a custom SQL query that returns a numeric count/value.",
                        Map.of("sql", "string", "threshold", "double"))
        ))));
    }

    @McpTool(description = "Get recent quality alert summary",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getQualityAlertSummary(
            @McpToolParam(description = "Number of recent alerts to return (default 20)", required = false) int limit) {
        return safeExecute(() -> {
            int clamped = Math.min(Math.max(limit, 1), 100);
            return success(qualityAlertService.getAlertSummary(clamped));
        });
    }

    private List<QualityRule> buildRules(List<Map<String, Object>> customRules) {
        if (customRules == null || customRules.isEmpty()) return List.of();
        return customRules.stream().map(this::parseRule).filter(r -> r != null && r.enabled()).toList();
    }

    @SuppressWarnings("unchecked")
    private QualityRule parseRule(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String name = (String) map.get("name");
            String typeStr = (String) map.get("type");
            String column = (String) map.get("column");
            Double threshold = (Double) map.get("threshold");
            String severityStr = (String) map.get("severity");
            boolean enabled = !Boolean.FALSE.equals(map.get("enabled"));

            QualityRule.RuleType type = parseEnum(typeStr, QualityRule.RuleType.class);
            QualityRule.Severity severity = parseEnum(severityStr, QualityRule.Severity.class);
            if (severity == null) severity = QualityRule.Severity.WARNING;
            if (threshold == null) threshold = 0.05;

            Map<String, Object> params = new java.util.HashMap<>(map);
            params.remove("id"); params.remove("name"); params.remove("type");
            params.remove("column"); params.remove("threshold"); params.remove("severity");
            params.remove("enabled");

            return new QualityRule(id, name, type, column, params, threshold, severity, enabled);
        } catch (Exception e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> enumClass) {
        if (value == null || value.isBlank()) return null;
        try { return Enum.valueOf(enumClass, value.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static Map<String, Object> ruleTemplate(String id, String name, String description, Map<String, Object> paramsExample) {
        return Map.of("id", id, "name", name, "description", description, "parameters", paramsExample);
    }
}
