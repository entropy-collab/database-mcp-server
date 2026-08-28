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

import com.entropy.database.mcp.audit.ComplianceReportService;
import com.entropy.database.mcp.audit.SqlAuditService;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * SQL audit and analysis tools exposed as MCP tools.
 * Provides slow query analysis, SQL pattern statistics, and compliance reports.
 */
@Component
public class SqlAuditTools extends McpToolBase {

    private final QueryAuditLogger queryAuditLogger;
    private final SqlAuditService sqlAuditService;
    private final ComplianceReportService complianceReportService;

    public SqlAuditTools(QueryAuditLogger queryAuditLogger,
                         SqlAuditService sqlAuditService,
                         ComplianceReportService complianceReportService) {
        this.queryAuditLogger = queryAuditLogger;
        this.sqlAuditService = sqlAuditService;
        this.complianceReportService = complianceReportService;
    }

    @McpTool(description = """
            【查看审计明细日志】读取内存缓冲区中最近执行过的查询审计记录，按时间倒序返回。
            前置条件：无需连接参数；缓冲区只保留最近 100 条记录，服务重启后清空。
            使用场景：排查刚刚执行过哪些 SQL、由哪个工具发起、耗时与是否成功。
            返回字段：total（本次返回条数）、entries（数组，每项含 tool、sql、rows、durationMs、success、timestamp、connectionKey）。
            注意：entries 中的 sql 已做敏感值脱敏并按配置长度截断，不是可直接重放的原始语句。
            不要用于：只看慢查询（用 getSlowQueries）；看 SQL 模式聚合统计（用 getSqlPatternStats）；看总量指标汇总（用 getAuditMetrics）；查历史归档区间（用 getDataAccessReport，需持久化审计表）。
            标签：[read, audit, log]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getAuditLogs(@McpToolParam(description = "返回条数上限，必填整数。缓冲区最多 100 条，传更大的值也只会返回实际存在的条数") int limit) {
        return safeExecute(() -> {
            List<Map<String, Object>> entries = queryAuditLogger.getRecentLogs(limit);
            return success(Map.of("total", entries.size(), "entries", entries));
        });
    }

    @McpTool(description = """
            【查看慢查询】返回耗时超过慢查询阈值的查询记录，按最近发生顺序返回，并附带整体审计汇总。
            前置条件：无需连接参数；记录来自内存缓冲区，服务重启后清空。
            使用场景：定位性能瓶颈、找出需要加索引或改写的语句。
            返回字段：summary（totalQueries、slowQueryCount、slowQueryRate、slowQueryThresholdMs、trackedPatterns、maxSlowQueries、maxSqlPatterns）、slowQueries（数组，每项含 tool、sql、rows、durationMs、connectionKey、timestamp）。
            不要用于：查看全部查询明细而非只看慢的（用 getAuditLogs）；按语句模板聚合看调用次数与平均耗时（用 getSqlPatternStats）；只要整体指标不要明细（用 getAuditMetrics）。
            标签：[read, audit, performance, slow-query]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getSlowQueries(@McpToolParam(description = "返回条数上限，必填整数。取值会被夹到 1..500 区间，超出范围自动截断") int limit) {
        return safeExecute(() -> {
            int clamped = Math.min(Math.max(limit, 1), 500);
            return success(Map.of("summary", sqlAuditService.getAuditSummary(), "slowQueries", sqlAuditService.getSlowQueries(clamped)));
        });
    }

    @McpTool(description = """
            【SQL 模式聚合统计】把执行过的 SQL 归一化为语句模板（字面量与数字替换为占位符）后聚合，按调用次数从高到低返回。
            前置条件：无需连接参数；统计来自内存 LRU 表，服务重启后清空，模板数量超过上限时淘汰最久未用的模板。
            使用场景：找出被反复调用的语句、评估哪个语句模板贡献了最多总耗时或返回行数。
            返回字段：totalTracked（当前被跟踪的模板总数）、patterns（数组，每项含 pattern、count、totalDurationMs、avgDurationMs、maxDurationMs、totalRows、avgRows）。
            不要用于：查看逐条执行明细与时间戳（用 getAuditLogs）；只看超过阈值的慢语句（用 getSlowQueries）。
            标签：[read, audit, statistics, sql-pattern]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getSqlPatternStats(@McpToolParam(description = "返回模板条数上限，必填整数。取值会被夹到 1..500 区间，超出范围自动截断") int limit) {
        return safeExecute(() -> {
            int clamped = Math.min(Math.max(limit, 1), 500);
            Map<String, Object> summary = sqlAuditService.getAuditSummary();
            List<Map<String, Object>> patterns = sqlAuditService.getSqlPatternStats(clamped);
            return success(Map.of("totalTracked", summary.get("trackedPatterns"), "patterns", patterns));
        });
    }

    @McpTool(description = """
            【GDPR 数据访问报告】按时间区间导出审计明细，形成个人数据访问台账，用于 GDPR 类合规举证。
            前置条件：需要持久化审计表可用；若服务未配置默认数据源，返回 status=skipped 并在 reason 中说明原因，不会报错。
            使用场景：被要求提供某段时间内谁访问了哪些数据、执行了哪些语句的逐条记录。
            返回字段：reportType（固定 GDPR_DATA_ACCESS）、generatedAt、timeRange（start、end）、limit、status（completed / skipped / error）、totalEntries、entries（数组，每项含 timestamp、tool、connectionKey、sql、success、rows、durationMs）；status=skipped 时含 reason，status=error 时含 error。
            不要用于：要按量化指标看总体合规态势（用 getProtectionReport，返回汇总比率与按工具、按连接的分布，不返回逐条明细）；只看内存中最近记录（用 getAuditLogs）。
            标签：[read, audit, compliance, gdpr, report]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getDataAccessReport(
            @McpToolParam(description = "起始时间，ISO-8601 格式（如 2024-01-01T00:00:00Z），必填；格式错误会报参数校验失败") String startTime,
            @McpToolParam(description = "结束时间，ISO-8601 格式（如 2024-12-31T23:59:59Z），必填；格式错误会报参数校验失败") String endTime,
            @McpToolParam(description = "纳入报告的明细条数上限，必填整数；不做区间夹取，直接作为查询上限") int limit) {
        return safeExecute(() -> {
            Instant start = parseInstant(startTime, "startTime");
            Instant end = parseInstant(endTime, "endTime");
            return success(complianceReportService.generateDataAccessReport(start, end, limit));
        });
    }

    @McpTool(description = """
            【等保合规报告】按时间区间统计审计记录，输出等级保护口径的量化汇总与分布，不返回逐条明细。
            前置条件：需要持久化审计表可用；若服务未配置默认数据源，返回 status=skipped 并在 reason 中说明原因。区间内最多取 10000 条记录参与统计，报告内的慢查询判定固定使用 5000 毫秒阈值，与全局慢查询阈值无关。
            使用场景：出等保自查材料，需要查询总量、成功失败率、慢查询率、导出行数以及按工具、按连接的调用分布。
            返回字段：reportType（固定 PROTECTION_LEVEL_COMPLIANCE）、generatedAt、timeRange（start、end）、status（completed / skipped / error）、summary（totalQueries、successCount、errorCount、errorRate、slowQueryCount、slowQueryRate、exportQueryCount、totalRowsExported）、toolBreakdown（工具名到调用次数）、connectionBreakdown（连接名到调用次数）；status=skipped 时含 reason，status=error 时含 error。
            不要用于：需要逐条访问记录做举证（用 getDataAccessReport）；看实时内存指标（用 getAuditMetrics）。
            标签：[read, audit, compliance, report]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getProtectionReport(
            @McpToolParam(description = "起始时间，ISO-8601 格式（如 2024-01-01T00:00:00Z），必填；格式错误会报参数校验失败") String startTime,
            @McpToolParam(description = "结束时间，ISO-8601 格式（如 2024-12-31T23:59:59Z），必填；格式错误会报参数校验失败") String endTime) {
        return safeExecute(() -> {
            Instant start = parseInstant(startTime, "startTime");
            Instant end = parseInstant(endTime, "endTime");
            return success(complianceReportService.generateProtectionReport(start, end));
        });
    }

    private Instant parseInstant(String value, String paramName) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            // Instant.parse signals a bad literal with DateTimeParseException, not
            // IllegalArgumentException, so the previous catch never fired and a malformed timestamp
            // surfaced as SYSTEM_ERROR instead of a parameter the caller can correct.
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid " + paramName + ": use ISO-8601 (e.g., 2024-01-01T00:00:00Z)");
        }
    }

    @McpTool(description = """
            【实时审计指标】读取内存中的审计计数汇总，一次调用即拿到整体健康度指标，无入参。
            前置条件：无；指标随服务进程存活，重启后归零。
            使用场景：先看一眼整体是否异常（查询总量、慢查询比率），再决定是否深入看明细。
            返回字段：totalQueries、slowQueryCount、slowQueryRate（百分比字符串）、slowQueryThresholdMs、trackedPatterns、maxSlowQueries、maxSqlPatterns。
            不要用于：查看具体是哪些语句慢（用 getSlowQueries）；查看逐条执行明细（用 getAuditLogs）；出合规报告（用 getProtectionReport 或 getDataAccessReport）。
            标签：[read, audit, metrics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getAuditMetrics() {
        return safeExecute(() -> success(complianceReportService.getRealtimeMetrics()));
    }
}
