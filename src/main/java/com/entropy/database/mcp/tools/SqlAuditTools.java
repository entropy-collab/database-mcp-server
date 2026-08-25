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

    @McpTool(description = "Get recent query audit log entries from in-memory buffer",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getAuditLogs(@McpToolParam(description = "Number of entries to return (default 50)") int limit) {
        return safeExecute(() -> {
            List<Map<String, Object>> entries = queryAuditLogger.getRecentLogs(limit);
            return success(Map.of("total", entries.size(), "entries", entries));
        });
    }

    @McpTool(description = "Get the top N slowest queries (queries exceeding the slow query threshold)",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getSlowQueries(@McpToolParam(description = "Maximum number of slow queries to return (default 50, max 500)") int limit) {
        return safeExecute(() -> {
            int clamped = Math.min(Math.max(limit, 1), 500);
            return success(Map.of("summary", sqlAuditService.getAuditSummary(), "slowQueries", sqlAuditService.getSlowQueries(clamped)));
        });
    }

    @McpTool(description = "Get TOP N most frequent SQL patterns with statistics (count, avg/max duration, total rows)",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getSqlPatternStats(@McpToolParam(description = "Maximum number of patterns to return (default 100, max 500)") int limit) {
        return safeExecute(() -> {
            int clamped = Math.min(Math.max(limit, 1), 500);
            Map<String, Object> summary = sqlAuditService.getAuditSummary();
            List<Map<String, Object>> patterns = sqlAuditService.getSqlPatternStats(clamped);
            return success(Map.of("totalTracked", summary.get("trackedPatterns"), "patterns", patterns));
        });
    }

    @McpTool(description = "Generate a GDPR-style data access report for a time range",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getDataAccessReport(
            @McpToolParam(description = "Start time in ISO-8601 format (e.g., 2024-01-01T00:00:00Z)") String startTime,
            @McpToolParam(description = "End time in ISO-8601 format (e.g., 2024-12-31T23:59:59Z)") String endTime,
            @McpToolParam(description = "Maximum number of entries to include (default 500)") int limit) {
        return safeExecute(() -> {
            Instant start = parseInstant(startTime, "startTime");
            Instant end = parseInstant(endTime, "endTime");
            return success(complianceReportService.generateDataAccessReport(start, end, limit));
        });
    }

    @McpTool(description = "Generate a 等保 (Level Protection) compliance report for a time range",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getProtectionReport(
            @McpToolParam(description = "Start time in ISO-8601 format (e.g., 2024-01-01T00:00:00Z)") String startTime,
            @McpToolParam(description = "End time in ISO-8601 format (e.g., 2024-12-31T23:59:59Z)") String endTime) {
        return safeExecute(() -> {
            Instant start = parseInstant(startTime, "startTime");
            Instant end = parseInstant(endTime, "endTime");
            return success(complianceReportService.generateProtectionReport(start, end));
        });
    }

    private Instant parseInstant(String value, String paramName) {
        try {
            return Instant.parse(value);
        } catch (IllegalArgumentException e) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid " + paramName + ": use ISO-8601 (e.g., 2024-01-01T00:00:00Z)");
        }
    }

    @McpTool(description = "Get real-time audit metrics summary (total queries, slow query rate, tracked patterns)",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getAuditMetrics() {
        return safeExecute(() -> success(complianceReportService.getRealtimeMetrics()));
    }
}
