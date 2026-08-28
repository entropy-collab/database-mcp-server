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
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.facade.DatabaseAdminOperations;
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import com.entropy.database.mcp.quality.QualityAlertService;
import com.entropy.database.mcp.quality.QualityCheckService;
import com.entropy.database.mcp.quality.QualityReport;
import com.entropy.database.mcp.quality.QualityReportService;
import com.entropy.database.mcp.quality.QualityRule;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Data quality check MCP tools.
 */
@Component
public class QualityTools extends McpToolBase {

    /** Quality checks only count and sample rows, so no write capability is injected. */
    private final DatabaseReadOperations readOperations;
    private final DatabaseAdminOperations adminOperations;
    private final QualityCheckService qualityCheckService;
    private final QualityReportService qualityReportService;
    private final QualityAlertService qualityAlertService;

    public QualityTools(DatabaseReadOperations readOperations,
                        DatabaseAdminOperations adminOperations,
                        QualityCheckService qualityCheckService,
                        QualityReportService qualityReportService,
                        QualityAlertService qualityAlertService) {
        this.readOperations = readOperations;
        this.adminOperations = adminOperations;
        this.qualityCheckService = qualityCheckService;
        this.qualityReportService = qualityReportService;
        this.qualityAlertService = qualityAlertService;
    }

    @McpTool(description = """
            【执行表数据质量检查】对指定表执行数据质量检查并生成报告，同时给出结构化结果与可直接展示的格式化文本。
            前置条件：先调用 createNamedConnection 注册连接；表名必须是合法标识符，否则报参数校验失败。表为空（行数 0）时直接返回空报告，评分 100 且不执行任何规则。
            检查内容：内置检查始终执行——逐列统计空值率，并对全部列组合统计重复行；customRules 是在内置检查之外追加的规则，不会替换内置检查。
            使用场景：新表接入前评估数据可用性、上线前核对空值与重复、按业务规则校验枚举值与数值区间。
            返回字段：report（含 tableName、schema、connectionKey、checkedAt、totalRows、rulesChecked、issuesFound、overallScore、issues、rules；issues 每项含 ruleId、ruleName、ruleType、column、severity、actualValue、threshold、totalRows、issueCount、detail）、formattedReport（按 format 渲染的文本）、format（实际使用的格式）。
            不要用于：查看有哪些规则可用及其参数（先用 listQualityRuleTemplates）；查看历史告警汇总（用 getQualityAlertSummary）。推荐顺序：listQualityRuleTemplates 看模板，再用本工具执行检查。
            标签：[read, quality, check, validation]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> checkTableQuality(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "要检查的表名，必填；须为合法标识符（不含引号、空格等特殊字符）") String tableName,
            @McpToolParam(description = "Schema 名，可省略；仅作为报告字段回显，不参与表名限定", required = false) String schema,
            @McpToolParam(description = """
                    追加的自定义规则列表，可省略。每项为键值对：\
                    type 取值 ENUM_VALUES（校验列值在允许集合内，需 params 中的 allowedValues）、\
                    RANGE（校验数值列落在区间内，需 min 或 max）、\
                    CUSTOM_SQL（执行自定义计数 SQL，需 sql；该类型默认被禁用，须开启 \
                    entropy.mcp.database.quality.custom-sql-enabled 并配置非空的表白名单，否则报参数校验失败）；\
                    另有 NULL_RATE、DUPLICATES、FORMAT 三种取值当前不会被执行（空值率与重复行已由内置检查覆盖）。\
                    其余字段：id、name 规则标识与显示名；column 目标列名，须为合法标识符；\
                    threshold 阈值，省略时默认 0.05；severity 取值 INFO、WARNING、ERROR、CRITICAL，省略时默认 WARNING；\
                    enabled 显式传 false 可跳过该规则，默认启用。字段类型不符的规则会被跳过而不中断整体检查。""",
                    required = false) List<Map<String, Object>> customRules,
            @McpToolParam(description = "formattedReport 的渲染格式，取值 json、csv、text（大小写不敏感）；省略或传无法识别的值时按 json 渲染", required = false) String format) {
        return safeExecute(() -> {
            DatabaseDialect dialect = adminOperations.getDialect(connectionName);

            if (!dialect.isValidIdentifier(tableName)) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid table name: " + tableName + " (tableName=" + tableName + ")");
            }
            String normalizedTable = dialect.normalizeTableName(tableName);
            List<QualityRule> rules = buildRules(customRules, dialect);

            QualityReport report = qualityCheckService.check(connectionName, normalizedTable, schema, rules, dialect, readOperations);

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

    @McpTool(description = """
            【列出质量规则模板】列出内置的质量规则模板及其参数说明，作为拼装 checkTableQuality 的 customRules 的参考。无入参，不访问数据库。
            前置条件：无。
            使用场景：执行质量检查前先确认支持哪些规则类型、每种类型需要哪些参数。
            返回字段：templates（数组，每项含 id、name、description、parameters；parameters 为参数名到类型说明的映射）。
            注意：返回的 null_rate 与 duplicates 两个模板对应的规则由检查引擎自动执行，通过 customRules 传入不会额外生效；可实际驱动的自定义规则类型为 ENUM_VALUES、RANGE、CUSTOM_SQL。
            不要用于：真正执行检查（用 checkTableQuality）；查看告警结果（用 getQualityAlertSummary）。
            标签：[read, quality, template, metadata]
            """,
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

    @McpTool(description = """
            【查看质量告警汇总】查看数据质量检查触发的告警汇总。
            前置条件：告警历史需要持久化存储后端；当前实现尚未接入，因此始终返回空结果并在 message 中说明原因，不报错。
            使用场景：确认近期是否有严重质量问题被触发；需要真实告警明细时，请改看 checkTableQuality 报告中的 issues，或查询告警日志。
            返回字段：totalAlerts、recentAlerts（数组）、message（说明当前告警历史依赖持久化存储）。
            不要用于：执行检查并获取本次问题清单（用 checkTableQuality）；查看可用规则（用 listQualityRuleTemplates）。
            标签：[read, quality, alert, summary]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getQualityAlertSummary(
            @McpToolParam(description = "返回的最近告警条数上限，整数；取值会被夹到 1..100 区间。当前实现忽略该参数并始终返回空列表", required = false) int limit) {
        return safeExecute(() -> {
            int clamped = Math.min(Math.max(limit, 1), 100);
            return success(qualityAlertService.getAlertSummary(clamped));
        });
    }

    private List<QualityRule> buildRules(List<Map<String, Object>> customRules, DatabaseDialect dialect) {
        if (customRules == null || customRules.isEmpty()) return List.of();
        return customRules.stream().map(map -> parseRule(map, dialect)).filter(r -> r != null && r.enabled()).toList();
    }

    @SuppressWarnings("unchecked")
    private QualityRule parseRule(Map<String, Object> map, DatabaseDialect dialect) {
        try {
            String id = (String) map.get("id");
            String name = (String) map.get("name");
            String typeStr = (String) map.get("type");
            String column = (String) map.get("column");
            Double threshold = (Double) map.get("threshold");
            String severityStr = (String) map.get("severity");
            boolean enabled = !Boolean.FALSE.equals(map.get("enabled"));

            // A rule column is interpolated into the check SQL, so it must be a plain identifier.
            // This is rejected here, at the tool boundary, rather than left to the check engine's
            // per-rule catch, where it would look like a rule that simply found nothing.
            requireIdentifier(column, "column", dialect);
            if (map.get("columns") instanceof List<?> columns) {
                for (Object candidate : columns) {
                    requireIdentifier(candidate == null ? null : candidate.toString(), "columns", dialect);
                }
            }

            QualityRule.RuleType type = parseEnum(typeStr, QualityRule.RuleType.class);
            QualityRule.Severity severity = parseEnum(severityStr, QualityRule.Severity.class);
            if (severity == null) severity = QualityRule.Severity.WARNING;
            if (threshold == null) threshold = 0.05;

            Map<String, Object> params = new java.util.HashMap<>(map);
            params.remove("id"); params.remove("name"); params.remove("type");
            params.remove("column"); params.remove("threshold"); params.remove("severity");
            params.remove("enabled");

            return new QualityRule(id, name, type, column, params, threshold, severity, enabled);
        } catch (ClassCastException | NullPointerException e) {
            // A malformed custom rule is skipped rather than failing the whole check run, but it is
            // logged: silently dropping a rule the caller asked for looked like the rule passed.
            log.warn("Skipping malformed quality rule {}: {}", map != null ? map.get("id") : null, e.toString());
            return null;
        }
    }

    /** A blank value means "not supplied" and is allowed; a supplied value must be an identifier. */
    private static void requireIdentifier(String value, String paramName, DatabaseDialect dialect) {
        if (value == null || value.isBlank()) return;
        if (!dialect.isValidIdentifier(value)) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "Invalid quality rule " + paramName + ": " + value);
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
