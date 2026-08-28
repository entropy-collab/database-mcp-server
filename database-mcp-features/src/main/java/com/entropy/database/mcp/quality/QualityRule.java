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

import java.util.List;
import java.util.Map;

/**
 * Data quality check rule model.
 * Supports built-in rules (NULL_RATE, DUPLICATES, FORMAT) and custom SQL conditions.
 */
public record QualityRule(
    String id,
    String name,
    RuleType type,
    String column,
    Map<String, Object> params,
    double threshold,
    Severity severity,
    boolean enabled
) {
    public enum RuleType {
        NULL_RATE,
        DUPLICATES,
        FORMAT,
        CUSTOM_SQL,
        ENUM_VALUES,
        RANGE
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }

    public QualityRule {
        params = params != null ? params : Map.of();
        enabled = Boolean.TRUE.equals(enabled);
    }

    public static QualityRule nullRate(String id, String column, double threshold, Severity severity) {
        return new QualityRule(id, "Null Rate: " + column, RuleType.NULL_RATE, column,
                Map.of("threshold", threshold), threshold, severity, true);
    }

    public static QualityRule duplicates(String id, List<String> columns, double threshold, Severity severity) {
        return new QualityRule(id, "Duplicates: " + columns, RuleType.DUPLICATES, null,
                Map.of("columns", columns, "threshold", threshold), threshold, severity, true);
    }

    public static QualityRule format(String id, String column, String pattern, Severity severity) {
        return new QualityRule(id, "Format: " + column, RuleType.FORMAT, column,
                Map.of("pattern", pattern), 0.0, severity, true);
    }

    public static QualityRule customSql(String id, String sql, double threshold, Severity severity) {
        return new QualityRule(id, "Custom SQL: " + sql, RuleType.CUSTOM_SQL, null,
                Map.of("sql", sql, "threshold", threshold), threshold, severity, true);
    }

    public static QualityRule enumValues(String id, String column, List<String> allowedValues, Severity severity) {
        return new QualityRule(id, "Enum: " + column, RuleType.ENUM_VALUES, column,
                Map.of("allowedValues", allowedValues), 0.0, severity, true);
    }

    public static QualityRule range(String id, String column, Number min, Number max, Severity severity) {
        return new QualityRule(id, "Range: " + column, RuleType.RANGE, column,
                Map.of("min", min, "max", max), 0.0, severity, true);
    }
}
