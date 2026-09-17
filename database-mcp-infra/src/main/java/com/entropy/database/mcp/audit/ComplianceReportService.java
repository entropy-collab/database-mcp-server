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

import org.jspecify.annotations.Nullable;
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

    public ComplianceReportService(@Nullable AuditLogRepository auditLogRepository,
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
                    .map(ComplianceReportService::toReportEntry)
                    .toList();

            report.put("status", "completed");
            report.put("totalEntries", logs.size());
            report.put("entries", entries);
        } catch (Exception e) {
            log.warn("Failed to generate data access report: {}", e.getMessage(), e);
            report.put("status", "error");
            report.put("error", "Report generation failed");
        }

        return report;
    }

    /**
     * 报告里的一条明细。
     *
     * <p>用 {@link LinkedHashMap} + {@code put} 而不是 {@link Map#of}：{@code Map.of} 拒绝 null value
     * （{@code ImmutableCollections} 里的 {@code Objects.requireNonNull}），而 {@code connection_key}
     * 是可空列——审计一落库，区间里只要有一条没带连接名的记录，这里就抛 NPE，被下面的 catch 吞成
     * {@code status=error}，报告永远出不来。字段顺序照原来的书写顺序保留，键名与键集合不变。
     */
    private static Map<String, Object> toReportEntry(AuditLogEntity e) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", e.timestamp().toString());
        entry.put("tool", e.tool());
        entry.put("connectionKey", e.connectionKey());
        entry.put("sql", truncate(e.sql(), 200));
        entry.put("success", e.success());
        entry.put("rows", e.rows());
        entry.put("durationMs", e.durationMs());
        return entry;
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
            log.warn("Failed to generate protection report: {}", e.getMessage(), e);
            report.put("status", "error");
            report.put("error", "Report generation failed");
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
