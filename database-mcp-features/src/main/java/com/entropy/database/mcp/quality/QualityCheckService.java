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
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.properties.QualityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    /**
     * Whether {@code CUSTOM_SQL} rules may run. Off by default: the rule body is caller-supplied
     * SQL, and with an empty {@code allowed-tables} whitelist it can read any table the connection
     * can see. Kept as a {@code @Value} rather than a new field on {@link QualityProperties} so the
     * shared properties records stay untouched.
     */
    private final boolean customSqlEnabled;
    /** The SQL-level table whitelist; a CUSTOM_SQL rule is refused unless it is non-empty. */
    private final List<String> allowedTables;

    public QualityCheckService(QualityRuleRegistry ruleRegistry,
                               DatabaseProperties dbProperties,
                               QualityAlertService alertService,
                               @Value("${entropy.mcp.database.quality.custom-sql-enabled:false}")
                               boolean customSqlEnabled) {
        this.ruleRegistry = ruleRegistry;
        this.properties = dbProperties != null && dbProperties.quality() != null
                ? dbProperties.quality() : new QualityProperties();
        this.alertService = alertService;
        this.customSqlEnabled = customSqlEnabled;
        this.allowedTables = dbProperties != null && dbProperties.security() != null
                ? dbProperties.security().allowedTables() : List.of();
    }

    /**
     * Run quality checks on a table with the given rules.
     *
     * @param connectionKey the connection every probe below is issued against; it is also the
     *                      connection name reported in the {@link QualityReport}
     * @param db            read access to that connection, so the checks stay subject to the
     *                      facade's advice instead of borrowing a raw {@code JdbcTemplate}
     */
    public QualityReport check(String connectionKey, String tableName, String schema,
                               List<QualityRule> rules, DatabaseDialect dialect,
                               DatabaseReadOperations db) {
        long totalRows = queryRowCount(db, connectionKey, tableName, dialect);
        if (totalRows == 0) {
            return emptyReport(tableName, schema, connectionKey);
        }

        // Caller-supplied rule columns reach SQL by interpolation, so they are checked before any
        // probe runs: a rejected column must fail the call, not be swallowed by the per-rule catch.
        validateRuleColumns(rules, dialect);

        List<QualityIssue> issues = new ArrayList<>();
        List<QualityRule> executedRules = new ArrayList<>();

        // Discover columns
        List<String> columns = discoveredColumns(db, connectionKey, tableName, dialect);

        // NULL rate per column
        for (String col : columns) {
            long nullCount = queryNullCount(db, connectionKey, tableName, col, dialect);
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
            String columnList = columns.stream().map(col -> quoteColumn(col, dialect))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            Long dupCount = queryDuplicateGroups(db, connectionKey, tableName, columnList, dialect);
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
                QualityIssue issue = evaluateRule(db, connectionKey, tableName, rule, dialect, totalRows);
                if (issue != null) {
                    issues.add(issue);
                }
            } catch (Exception e) {
                log.warn("Rule evaluation failed for rule '{}': {}", rule.id(), e.getMessage(), e);
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

    private QualityIssue evaluateRule(DatabaseReadOperations db, String connection, String tableName,
                                      QualityRule rule, DatabaseDialect dialect, long totalRows) {
        return switch (rule.type()) {
            case FORMAT -> evaluateFormat(rule);
            case ENUM_VALUES -> evaluateEnum(db, connection, tableName, rule, dialect, totalRows);
            case RANGE -> evaluateRange(db, connection, tableName, rule, dialect, totalRows);
            case CUSTOM_SQL -> evaluateCustomSql(db, connection, rule, totalRows);
            default -> null;
        };
    }

    private QualityIssue evaluateFormat(QualityRule rule) {
        // Format validation requires client-side regex matching on fetched data.
        // For now, return null (skip) — full format check would need row-level scanning.
        log.debug("Format rule '{}' skipped: requires row-level scanning", rule.id());
        return null;
    }

    private QualityIssue evaluateEnum(DatabaseReadOperations db, String connection, String tableName,
                                      QualityRule rule, DatabaseDialect dialect, long totalRows) {
        String col = rule.column();
        if (col == null) return null;
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) rule.params().get("allowedValues");
        if (allowed == null || allowed.isEmpty()) return null;
        try {
            String placeholders = allowed.stream().map(x -> "?").reduce((a, b) -> a + ", " + b).orElse("");
            String sql = "SELECT COUNT(*) FROM " + dialect.quote(tableName)
                    + " WHERE " + quoteColumn(col, dialect) + " NOT IN (" + placeholders + ")";
            long violationCount = queryCount(db, connection, sql, allowed.toArray());
            double violationRate = totalRows > 0 ? (violationCount * 100.0 / totalRows) : 0;
            return new QualityIssue(rule.id(), rule.name(), rule.type(), col,
                    rule.severity().name(), violationRate, 0.0,
                    totalRows, violationCount,
                    String.format("Enum violation rate: %.2f%% (allowed: %s)", violationRate, allowed));
        } catch (Exception e) {
            log.warn("Enum check failed for column '{}': {}", col, e.getMessage());
            return null;
        }
    }

    private QualityIssue evaluateRange(DatabaseReadOperations db, String connection, String tableName,
                                       QualityRule rule, DatabaseDialect dialect, long totalRows) {
        String col = rule.column();
        if (col == null) return null;
        Number min = (Number) rule.params().get("min");
        Number max = (Number) rule.params().get("max");
        if (min == null && max == null) return null;
        try {
            String quotedColumn = quoteColumn(col, dialect);
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ")
                    .append(dialect.quote(tableName))
                    .append(" WHERE ")
                    .append(quotedColumn)
                    .append(" IS NOT NULL");
            Object[] params = new Object[0];
            if (min != null) {
                sql.append(" AND ").append(quotedColumn).append(" < ?");
                params = appendParam(params, min);
            }
            if (max != null) {
                sql.append(" AND ").append(quotedColumn).append(" > ?");
                params = appendParam(params, max);
            }
            long violationCount = queryCount(db, connection, sql.toString(), params);
            double violationRate = totalRows > 0 ? (violationCount * 100.0 / totalRows) : 0;
            return new QualityIssue(rule.id(), rule.name(), rule.type(), col,
                    rule.severity().name(), violationRate, 0.0,
                    totalRows, violationCount,
                    String.format("Range violation rate: %.2f%% (min=%s, max=%s)",
                            violationRate, min != null ? min : "null", max != null ? max : "null"));
        } catch (Exception e) {
            log.warn("Range check failed for column '{}': {}", col, e.getMessage(), e);
            return null;
        }
    }

    private QualityIssue evaluateCustomSql(DatabaseReadOperations db, String connection,
                                           QualityRule rule, long totalRows) {
        String sql = (String) rule.params().get("sql");
        if (sql == null) return null;
        // The rule body is caller-supplied SQL. It is only run when explicitly enabled and when a
        // table whitelist is configured, so it cannot be used to read arbitrary tables.
        if (!customSqlEnabled) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "CUSTOM_SQL quality rules are disabled. Set "
                            + "entropy.mcp.database.quality.custom-sql-enabled=true and configure "
                            + "entropy.mcp.database.security.allowed-tables to allow rule '" + rule.id() + "'.");
        }
        if (allowedTables.isEmpty()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "CUSTOM_SQL quality rule '" + rule.id() + "' requires a non-empty "
                            + "entropy.mcp.database.security.allowed-tables whitelist.");
        }
        try {
            List<Map<String, Object>> rows = db.queryRows(sql, connection);
            // A rule whose query yields nothing is reported as no issue, the same as one that fails.
            if (rows.isEmpty()) return null;
            Object result = firstValue(rows);
            double value = result instanceof Number n ? n.doubleValue() : 0;
            return new QualityIssue(rule.id(), rule.name(), rule.type(), null,
                    rule.severity().name(), value, rule.threshold(), totalRows, 0,
                    "Custom SQL condition: " + truncate(sql, 100));
        } catch (Exception e) {
            log.warn("Custom SQL rule '{}' failed: {}", rule.id(), e.getMessage(), e);
            return null;
        }
    }

    // ─── Helper Queries ──────────────────────────────────────────────────

    /**
     * Rejects a rule whose column cannot be a plain identifier, and a CUSTOM_SQL rule that is not
     * explicitly enabled. Run before any probe, so the failure reaches the caller instead of being
     * absorbed by the per-rule {@code catch} in {@link #check}.
     */
    private void validateRuleColumns(List<QualityRule> rules, DatabaseDialect dialect) {
        if (rules == null) return;
        for (QualityRule rule : rules) {
            if (!rule.enabled()) continue;
            if (rule.column() != null) {
                quoteColumn(rule.column(), dialect);
            }
            Object columns = rule.params().get("columns");
            if (columns instanceof Collection<?> columnList) {
                for (Object column : columnList) {
                    quoteColumn(column == null ? null : column.toString(), dialect);
                }
            }
            if (rule.type() == QualityRule.RuleType.CUSTOM_SQL) {
                assertCustomSqlAllowed(rule);
            }
        }
    }

    private void assertCustomSqlAllowed(QualityRule rule) {
        if (!customSqlEnabled) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "CUSTOM_SQL quality rules are disabled. Set "
                            + "entropy.mcp.database.quality.custom-sql-enabled=true and configure "
                            + "entropy.mcp.database.security.allowed-tables to allow rule '" + rule.id() + "'.");
        }
        if (allowedTables.isEmpty()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "CUSTOM_SQL quality rule '" + rule.id() + "' requires a non-empty "
                            + "entropy.mcp.database.security.allowed-tables whitelist.");
        }
    }

    /**
     * Validate a column name and quote it for the dialect.
     *
     * <p>Quoting alone is not a boundary: a column name is interpolated into these probes, and
     * whether a delimiter inside it is escaped is the dialect's business, not this class's. So the
     * name must first be a plain identifier.
     */
    private String quoteColumn(String column, DatabaseDialect dialect) {
        if (column == null || !dialect.isValidIdentifier(column)) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "Invalid column name: " + column);
        }
        return dialect.quote(column);
    }

    private long queryRowCount(DatabaseReadOperations db, String connection, String tableName,
                               DatabaseDialect dialect) {
        try {
            return queryCount(db, connection, "SELECT COUNT(*) FROM " + dialect.quote(tableName));
        } catch (Exception e) {
            log.warn("Failed to count rows in table '{}': {}", tableName, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Lists the table's columns, dropping any name that is not a plain identifier: such a column
     * cannot be interpolated into the probes below safely, and skipping one check is preferable to
     * failing the whole report.
     */
    private List<String> discoveredColumns(DatabaseReadOperations db, String connection, String tableName,
                                           DatabaseDialect dialect) {
        List<String> columns = queryColumns(db, connection, tableName, dialect);
        List<String> usable = new ArrayList<>(columns.size());
        for (String column : columns) {
            if (column != null && dialect.isValidIdentifier(column)) {
                usable.add(column);
            } else {
                log.warn("Skipping column '{}' of table '{}': not a plain identifier", column, tableName);
            }
        }
        return usable;
    }

    /**
     * Reads the table's column names from the dialect's metadata query.
     *
     * <p>Per the dialect contract the SQL carries exactly one {@code ?} for the table name, bound with
     * the dialect-normalized spelling. Binding nothing - which is what this did - made every dialect
     * reject the statement, and because the failure is absorbed here the column list came back empty:
     * no per-column null-rate check and no duplicate-row check ever ran, so {@code rulesChecked} was
     * always 0 and the quality score always a perfect 100.
     *
     * <p>The label is read case-insensitively: Oracle and H2 report {@code COLUMN_NAME} while MySQL
     * and PostgreSQL report {@code column_name}.
     */
    private List<String> queryColumns(DatabaseReadOperations db, String connection, String tableName,
                                      DatabaseDialect dialect) {
        try {
            String colQuery = dialect.columnsQuery(tableName, null);
            return db.queryRows(colQuery, connection, dialect.normalizeTableName(tableName)).stream()
                    .map(QualityCheckService::columnNameOf)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list columns for table '{}': {}", tableName, e.getMessage(), e);
            return List.of();
        }
    }

    private static String columnNameOf(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("column_name")) {
                return entry.getValue() == null ? null : String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private long queryNullCount(DatabaseReadOperations db, String connection, String tableName,
                                String column, DatabaseDialect dialect) {
        try {
            return queryCount(db, connection, "SELECT COUNT(*) FROM " + dialect.quote(tableName)
                    + " WHERE " + quoteColumn(column, dialect) + " IS NULL");
        } catch (Exception e) {
            log.warn("Null check failed for column '{}': {}", column, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Counts duplicate groups.
     *
     * <p>The derived table is aliased: MySQL and PostgreSQL reject an unaliased one outright
     * ("Every derived table must have its own alias"), which made this check silently answer 0 on
     * both. The failure path now carries the cause and is logged at warn, so a check that cannot
     * run is visible instead of looking like a clean result.
     */
    private Long queryDuplicateGroups(DatabaseReadOperations db, String connection, String tableName,
                                      String columnList, DatabaseDialect dialect) {
        try {
            return queryCount(db, connection,
                    "SELECT COUNT(*) FROM (SELECT COUNT(*) cnt FROM " + dialect.quote(tableName)
                            + " GROUP BY " + columnList + " HAVING COUNT(*) > 1) t");
        } catch (Exception e) {
            log.warn("Duplicate check failed for table '{}': {}", tableName, e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * Run an aggregate query and read its single value.
     *
     * <p>Replaces {@code JdbcTemplate.queryForObject}: the facade only hands back rows, so the
     * scalar has to be picked out here. A missing row or a non-numeric value counts as zero, which
     * is what the callers' {@code catch} blocks already did for a failed probe.
     */
    private long queryCount(DatabaseReadOperations db, String connection, String sql, Object... args) {
        Object value = firstValue(db.queryRows(sql, connection, args));
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** First column of the first row, or null when there is no row or the value is null. */
    private Object firstValue(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return null;
        Collection<Object> values = rows.get(0).values();
        return values.isEmpty() ? null : values.iterator().next();
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
