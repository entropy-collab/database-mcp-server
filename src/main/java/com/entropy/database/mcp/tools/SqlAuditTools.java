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
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SQL audit and analysis tools exposed as MCP tools.
 * Provides slow query analysis, SQL pattern statistics, and compliance reports.
 */
@Configuration
public class SqlAuditTools {

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

    // ─── P0: Audit Log Queries ─────────────────────────────────────────────

    @McpTool(description = "Get recent query audit log entries from in-memory buffer")
    public Map<String, Object> getAuditLogs(
            @McpToolParam(description = "Number of entries to return (default 50)") int limit) {
        try {
            List<Map<String, Object>> logs = queryAuditLogger.getRecentLogs(limit);
            return successResponse(Map.of(
                    "total", logs.size(),
                    "entries", logs
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("limit", limit), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── P1: Slow Query Analysis ──────────────────────────────────────────

    @McpTool(description = "Get the top N slowest queries (queries exceeding the slow query threshold)")
    public Map<String, Object> getSlowQueries(
            @McpToolParam(description = "Maximum number of slow queries to return (default 50, max 500)") int limit) {
        try {
            int clamped = Math.min(Math.max(limit, 1), 500);
            List<Map<String, Object>> slowQueries = sqlAuditService.getSlowQueries(clamped);
            Map<String, Object> summary = sqlAuditService.getAuditSummary();
            return successResponse(Map.of(
                    "summary", summary,
                    "slowQueries", slowQueries
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("limit", limit), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── P1: SQL Pattern Statistics (TOP N) ───────────────────────────────

    @McpTool(description = "Get TOP N most frequent SQL patterns with statistics (count, avg/max duration, total rows)")
    public Map<String, Object> getSqlPatternStats(
            @McpToolParam(description = "Maximum number of patterns to return (default 100, max 500)") int limit) {
        try {
            int clamped = Math.min(Math.max(limit, 1), 500);
            List<Map<String, Object>> patterns = sqlAuditService.getSqlPatternStats(clamped);
            return successResponse(Map.of(
                    "totalTracked", sqlAuditService.getAuditSummary().get("trackedPatterns"),
                    "patterns", patterns
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("limit", limit), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── P2: Compliance Reports ───────────────────────────────────────────

    @McpTool(description = "Generate a GDPR-style data access report for a time range")
    public Map<String, Object> getDataAccessReport(
            @McpToolParam(description = "Start time in ISO-8601 format (e.g., 2024-01-01T00:00:00Z)") String startTime,
            @McpToolParam(description = "End time in ISO-8601 format (e.g., 2024-12-31T23:59:59Z)") String endTime,
            @McpToolParam(description = "Maximum number of entries to include (default 500)") int limit) {
        try {
            Instant start = Instant.parse(startTime);
            Instant end = Instant.parse(endTime);
            Map<String, Object> report = complianceReportService.generateDataAccessReport(start, end, limit);
            return successResponse(report);
        } catch (IllegalArgumentException e) {
            return errorResponse(Map.of("startTime", startTime, "endTime", endTime),
                    "Invalid time format. Use ISO-8601 (e.g., 2024-01-01T00:00:00Z)", "IllegalArgumentException");
        } catch (Exception e) {
            return errorResponse(Map.of("startTime", startTime, "endTime", endTime, "limit", limit),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Generate a 等保 (Level Protection) compliance report for a time range")
    public Map<String, Object> getProtectionReport(
            @McpToolParam(description = "Start time in ISO-8601 format (e.g., 2024-01-01T00:00:00Z)") String startTime,
            @McpToolParam(description = "End time in ISO-8601 format (e.g., 2024-12-31T23:59:59Z)") String endTime) {
        try {
            Instant start = Instant.parse(startTime);
            Instant end = Instant.parse(endTime);
            Map<String, Object> report = complianceReportService.generateProtectionReport(start, end);
            return successResponse(report);
        } catch (IllegalArgumentException e) {
            return errorResponse(Map.of("startTime", startTime, "endTime", endTime),
                    "Invalid time format. Use ISO-8601 (e.g., 2024-01-01T00:00:00Z)", "IllegalArgumentException");
        } catch (Exception e) {
            return errorResponse(Map.of("startTime", startTime, "endTime", endTime),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Get real-time audit metrics summary (total queries, slow query rate, tracked patterns)")
    public Map<String, Object> getAuditMetrics() {
        try {
            Map<String, Object> metrics = complianceReportService.getRealtimeMetrics();
            return successResponse(metrics);
        } catch (Exception e) {
            return errorResponse(Map.of(), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── Private Helpers ──────────────────────────────────────────────────

    private static Map<String, Object> successResponse(Map<String, Object> data) {
        return Map.of("success", true, "data", data);
    }

    private static Map<String, Object> errorResponse(Map<String, Object> context, String message, String type) {
        return Map.of(
                "success", false,
                "error", message,
                "errorType", type,
                "context", context
        );
    }
}
