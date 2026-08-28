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

/**
 * Result of a single quality rule check.
 */
public record QualityIssue(
    String ruleId,
    String ruleName,
    QualityRule.RuleType ruleType,
    String column,
    String severity,
    double actualValue,
    double threshold,
    long totalRows,
    long issueCount,
    String detail
) {
    public boolean isPassed() {
        if (ruleType == QualityRule.RuleType.NULL_RATE) {
            return actualValue <= threshold;
        }
        if (ruleType == QualityRule.RuleType.DUPLICATES) {
            return actualValue <= threshold;
        }
        if (ruleType == QualityRule.RuleType.FORMAT) {
            return actualValue <= threshold;
        }
        if (ruleType == QualityRule.RuleType.ENUM_VALUES) {
            return actualValue <= threshold;
        }
        return true;
    }

    public boolean isCritical() {
        return "CRITICAL".equals(severity) || "ERROR".equals(severity);
    }
}
