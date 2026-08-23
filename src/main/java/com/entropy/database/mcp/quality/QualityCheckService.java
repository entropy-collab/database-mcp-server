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
package com.entropy.database.mcp.quality;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.properties.QualityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core data quality check engine.
 * Executes quality rules against a table and produces a QualityReport.
 */
@Component
public class QualityCheckService {

    private static final Logger log = LoggerFactory.getLogger(QualityCheckService.class);

    private final QualityRuleRegistry ruleRegistry;
    private final QualityProperties properties;
    private final QualityAlertService alertService;

    public QualityCheckService(QualityRuleRegistry ruleRegistry,
                               DatabaseProperties dbProperties,
                               QualityAlertService alertService) {
        this.ruleRegistry = ruleRegistry;
        this.properties = dbProperties != null && dbProperties.quality() != null
                ? dbProperties.quality() : new QualityProperties();
        this.alertService = alertService;
    }

    /**
     * Run quality checks on a table with the given rules.
     */
    public QualityReport check(String connectionKey, String tableName, String schema,
                               List<QualityRule> rules, DatabaseDialect dialect, JdbcTemplate jdbc) {
        long totalRows = queryRowCount(jdbc, tableName, dialect);
        if (totalRows == 0) {
            return emptyReport(tableName, schema, connectionKey);
        }

        List<QualityIssue> issues = new ArrayList<>();
        List<QualityRule> executedRules = new ArrayList<>();

        // Discover columns
        List<String> columns = queryColumns(jdbc, tableName, dialect);

        // NULL rate per column
        for (String col : columns) {
            long nullCount = queryNullCount(jdbc, tableName, col, dialect);
            double nullRatePct = totalRows > 0 ? (nullCount * 100.0 / totalRows) : 0;
            double thresholdPct = properties.defaultNullRateThreshold() * 100;
            QualityRule.Severity severity = nullRatePct > thresholdPct * 2
                    ? QualityRule.Severity.CRITICAL : QualityRule.Severity.WARNING;
            executedRules.add(QualityRule.nullRate("null-" + col, col, properties.defaultNullRateThreshold(), severity));

            if (nullRatePct > thresholdPct) {
                issues.add(new QualityIssue("null-" + col, "Null Rate: " + col,
                        QualityRule.RuleType.NULL_RATE, col,
                        severity.name(), nullRatePct, thresholdPct,
                        totalRows, nullCount,
                        String.format("Null rate %.2f%% exceeds threshold %.2f%%", nullRatePct, thresholdPct)));
            }
        }

        // Duplicate check on all columns
        if (!columns.isEmpty()) {
            String columnList = columns.stream().map(dialect::quote).reduce((a, b) -> a + ", " + b).orElse("");
            Long dupCount = queryDuplicateGroups(jdbc, tableName, columnList);
            if (dupCount != null && dupCount > 0) {
                double dupRatePct = totalRows > 0 ? (dupCount * 100.0 / totalRows) : 0;
                double thresholdPct = properties.defaultDuplicateRateThreshold() * 100;
                executedRules.add(QualityRule.duplicates("dup-all", columns,
                        properties.defaultDuplicateRateThreshold(), QualityRule.Severity.ERROR));
                issues.add(new QualityIssue("dup-all", "Duplicates (all columns)",
                        QualityRule.RuleType.DUPLICATES, null, "ERROR",
                        dupRatePct, thresholdPct, totalRows, dupCount,
                        String.format("Duplicate groups: %d, rate %.4f%%", dupCount, dupRatePct)));
            }
        }

        // Run user-provided rules
        for (QualityRule rule : rules) {
            if (!rule.enabled()) continue;
            executedRules.add(rule);
            try {
                QualityIssue issue = evaluateRule(jdbc, tableName, rule, dialect, totalRows);
                if (issue != null) {
                    issues.add(issue);
                }
            } catch (Exception e) {
                log.warn("Rule evaluation failed for rule '{}': {}", rule.id(), e.getMessage());
            }
        }

        int rulesChecked = executedRules.size();
        int issuesFound = issues.size();
        double score = rulesChecked > 0 ? (1.0 - (double) issuesFound / rulesChecked) * 100 : 100.0;
        score = Math.max(0, Math.min(100, score));

        QualityReport report = new QualityReport(tableName, schema, connectionKey,
                Instant.now(), totalRows, rulesChecked, issuesFound, score, issues, executedRules);

        // Trigger alert if critical issues exceed threshold
        if (properties.alertOnCritical() && issues.stream().filter(QualityIssue::isCritical).count() >= properties.alertThresholdIssues()) {
            alertService.triggerAlert(report);
        }

        return report;
    }

    // ─── Private Evaluation ──────────────────────────────────────────────

    private QualityIssue evaluateRule(JdbcTemplate jdbc, String tableName,
                                      QualityRule rule, DatabaseDialect dialect, long totalRows) {
        return switch (rule.type()) {
            case FORMAT -> evaluateFormat(jdbc, tableName, rule, dialect, totalRows);
            case ENUM_VALUES -> evaluateEnum(jdbc, tableName, rule, dialect, totalRows);
            case RANGE -> evaluateRange(jdbc, tableName, rule, dialect, totalRows);
            case CUSTOM_SQL -> evaluateCustomSql(jdbc, tableName, rule, totalRows);
            default -> null;
        };
    }

    private QualityIssue evaluateFormat(JdbcTemplate jdbc, String tableName,
                                        QualityRule rule, DatabaseDialect dialect, long totalRows) {
        // Format validation requires client-side regex matching on fetched data.
        // For now, return null (skip) — full format check would need row-level scanning.
        log.debug("Format rule '{}' skipped: requires row-level scanning", rule.id());
        return null;
    }

    private QualityIssue evaluateEnum(JdbcTemplate jdbc, String tableName,
                                      QualityRule rule, DatabaseDialect dialect, long totalRows) {
        String col = rule.column();
        if (col == null) return null;
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) rule.params().get("allowedValues");
        if (allowed == null || allowed.isEmpty()) return null;
        try {
            String placeholders = allowed.stream().map(x -> "?").reduce((a, b) -> a + ", " + b).orElse("");
            String sql = "SELECT COUNT(*) FROM " + dialect.quote(tableName)
                    + " WHERE " + dialect.quote(col) + " NOT IN (" + placeholders + ")";
            Integer violationCount = jdbc.queryForObject(sql, Integer.class, allowed.toArray());
            double violationRate = totalRows > 0 ? (violationCount * 100.0 / totalRows) : 0;
            return new QualityIssue(rule.id(), rule.name(), rule.type(), col,
                    rule.severity().name(), violationRate, 0.0,
                    totalRows, violationCount != null ? violationCount : 0,
                    String.format("Enum violation rate: %.2f%% (allowed: %s)", violationRate, allowed));
        } catch (Exception e) {
            log.warn("Enum check failed for column '{}': {}", col, e.getMessage());
            return null;
        }
    }

    private QualityIssue evaluateRange(JdbcTemplate jdbc, String tableName,
                                       QualityRule rule, DatabaseDialect dialect, long totalRows) {
        String col = rule.column();
        if (col == null) return null;
        Number min = (Number) rule.params().get("min");
        Number max = (Number) rule.params().get("max");
        if (min == null && max == null) return null;
        try {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ")
                    .append(dialect.quote(tableName))
                    .append(" WHERE ")
                    .append(dialect.quote(col))
                    .append(" IS NOT NULL");
            Object[] params = new Object[0];
            if (min != null) {
                sql.append(" AND ").append(dialect.quote(col)).append(" < ?");
                params = appendParam(params, min);
            }
            if (max != null) {
                sql.append(" AND ").append(dialect.quote(col)).append(" > ?");
                params = appendParam(params, max);
            }
            Integer violationCount = jdbc.queryForObject(sql.toString(), params, Integer.class);
            double violationRate = totalRows > 0 ? (violationCount * 100.0 / totalRows) : 0;
            return new QualityIssue(rule.id(), rule.name(), rule.type(), col,
                    rule.severity().name(), violationRate, 0.0,
                    totalRows, violationCount != null ? violationCount : 0,
                    String.format("Range violation rate: %.2f%% (min=%s, max=%s)",
                            violationRate, min != null ? min : "null", max != null ? max : "null"));
        } catch (Exception e) {
            log.warn("Range check failed for column '{}': {}", col, e.getMessage());
            return null;
        }
    }

    private QualityIssue evaluateCustomSql(JdbcTemplate jdbc, String tableName,
                                           QualityRule rule, long totalRows) {
        String sql = (String) rule.params().get("sql");
        if (sql == null) return null;
        try {
            Object result = jdbc.queryForObject(sql, Object.class);
            double value = result instanceof Number n ? n.doubleValue() : 0;
            return new QualityIssue(rule.id(), rule.name(), rule.type(), null,
                    rule.severity().name(), value, rule.threshold(), totalRows, 0,
                    "Custom SQL condition: " + truncate(sql, 100));
        } catch (Exception e) {
            log.warn("Custom SQL rule '{}' failed: {}", rule.id(), e.getMessage());
            return null;
        }
    }

    // ─── Helper Queries ──────────────────────────────────────────────────

    private long queryRowCount(JdbcTemplate jdbc, String tableName, DatabaseDialect dialect) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + dialect.quote(tableName), Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Failed to count rows in table '{}': {}", tableName, e.getMessage());
            return 0;
        }
    }

    private List<String> queryColumns(JdbcTemplate jdbc, String tableName, DatabaseDialect dialect) {
        try {
            String colQuery = dialect.columnsQuery(tableName, null);
            return jdbc.queryForList(colQuery).stream()
                    .map(row -> (String) row.get("column_name"))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list columns for table '{}': {}", tableName, e.getMessage());
            return List.of();
        }
    }

    private long queryNullCount(JdbcTemplate jdbc, String tableName, String column,
                                DatabaseDialect dialect) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + dialect.quote(tableName)
                            + " WHERE " + dialect.quote(column) + " IS NULL",
                    Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Null check failed for column '{}': {}", column, e.getMessage());
            return 0;
        }
    }

    private Long queryDuplicateGroups(JdbcTemplate jdbc, String tableName, String columnList) {
        try {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT COUNT(*) cnt FROM " + tableName
                            + " GROUP BY " + columnList + " HAVING COUNT(*) > 1)",
                    Long.class);
        } catch (Exception e) {
            log.warn("Duplicate check failed: {}", e.getMessage());
            return 0L;
        }
    }

    private Object[] appendParam(Object[] existing, Number value) {
        Object[] newArray = new Object[existing.length + 1];
        System.arraycopy(existing, 0, newArray, 0, existing.length);
        newArray[existing.length] = value;
        return newArray;
    }

    private QualityReport emptyReport(String tableName, String schema, String connectionKey) {
        return new QualityReport(tableName, schema, connectionKey, Instant.now(), 0, 0, 0, 100.0,
                List.of(), List.of());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
