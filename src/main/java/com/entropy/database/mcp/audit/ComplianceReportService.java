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
package com.entropy.database.mcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GDPR/等保 compliance audit report generator.
 * Produces structured reports for data access auditing.
 */
@Component
public class ComplianceReportService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceReportService.class);

    private final AuditLogRepository auditLogRepository;
    private final SqlAuditService sqlAuditService;

    public ComplianceReportService(@org.springframework.lang.Nullable AuditLogRepository auditLogRepository,
                                   SqlAuditService sqlAuditService) {
        this.auditLogRepository = auditLogRepository;
        this.sqlAuditService = sqlAuditService;
    }

    /**
     * Generate a GDPR-style data access report.
     * Lists all queries that may have accessed personal data within a time range.
     */
    public Map<String, Object> generateDataAccessReport(Instant startTime, Instant endTime, int limit) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportType", "GDPR_DATA_ACCESS");
        report.put("generatedAt", Instant.now().toString());
        report.put("timeRange", Map.of("start", startTime.toString(), "end", endTime.toString()));
        report.put("limit", limit);

        if (auditLogRepository == null) {
            report.put("status", "skipped");
            report.put("reason", "Audit log repository not available (no default datasource)");
            return report;
        }

        try {
            List<AuditLogEntity> logs = auditLogRepository.query(null, null, startTime, endTime, limit);
            List<Map<String, Object>> entries = logs.stream()
                    .map(e -> Map.<String, Object>of(
                            "timestamp", e.timestamp().toString(),
                            "tool", e.tool(),
                            "connectionKey", e.connectionKey(),
                            "sql", truncate(e.sql(), 200),
                            "success", e.success(),
                            "rows", e.rows(),
                            "durationMs", e.durationMs()
                    ))
                    .collect(Collectors.toList());

            report.put("status", "completed");
            report.put("totalEntries", logs.size());
            report.put("entries", entries);
        } catch (Exception e) {
            log.warn("Failed to generate data access report: {}", e.getMessage());
            report.put("status", "error");
            report.put("error", e.getMessage());
        }

        return report;
    }

    /**
     * Generate an 等保 (Level Protection) compliance report.
     * Covers: query volume, slow query rate, error rate, data export volume.
     */
    public Map<String, Object> generateProtectionReport(Instant startTime, Instant endTime) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportType", "PROTECTION_LEVEL_COMPLIANCE");
        report.put("generatedAt", Instant.now().toString());
        report.put("timeRange", Map.of("start", startTime.toString(), "end", endTime.toString()));

        if (auditLogRepository == null) {
            report.put("status", "skipped");
            report.put("reason", "Audit log repository not available");
            return report;
        }

        try {
            List<AuditLogEntity> logs = auditLogRepository.query(null, null, startTime, endTime, 10000);

            long totalQueries = logs.size();
            long successCount = logs.stream().filter(AuditLogEntity::success).count();
            long errorCount = totalQueries - successCount;
            long slowQueries = logs.stream()
                    .filter(e -> e.durationMs() >= 5000)
                    .count();
            long exportQueries = logs.stream()
                    .filter(e -> e.tool() != null && e.tool().toLowerCase().contains("export"))
                    .count();
            long totalRowsExported = logs.stream()
                    .filter(e -> e.tool() != null && e.tool().toLowerCase().contains("export"))
                    .mapToLong(AuditLogEntity::rows)
                    .sum();

            // Tool breakdown
            Map<String, Long> toolBreakdown = logs.stream()
                    .filter(e -> e.tool() != null)
                    .collect(Collectors.groupingBy(AuditLogEntity::tool, Collectors.counting()));

            // Connection breakdown
            Map<String, Long> connectionBreakdown = logs.stream()
                    .filter(e -> e.connectionKey() != null)
                    .collect(Collectors.groupingBy(AuditLogEntity::connectionKey, Collectors.counting()));

            report.put("status", "completed");
            report.put("summary", Map.of(
                    "totalQueries", totalQueries,
                    "successCount", successCount,
                    "errorCount", errorCount,
                    "errorRate", totalQueries > 0 ? String.format("%.2f%%", errorCount * 100.0 / totalQueries) : "0.00%",
                    "slowQueryCount", slowQueries,
                    "slowQueryRate", totalQueries > 0 ? String.format("%.2f%%", slowQueries * 100.0 / totalQueries) : "0.00%",
                    "exportQueryCount", exportQueries,
                    "totalRowsExported", totalRowsExported
            ));
            report.put("toolBreakdown", toolBreakdown);
            report.put("connectionBreakdown", connectionBreakdown);
        } catch (Exception e) {
            log.warn("Failed to generate protection report: {}", e.getMessage());
            report.put("status", "error");
            report.put("error", e.getMessage());
        }

        return report;
    }

    /**
     * Get real-time audit metrics from in-memory service.
     */
    public Map<String, Object> getRealtimeMetrics() {
        return sqlAuditService.getAuditSummary();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
