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

import java.time.Instant;
import java.util.List;

/**
 * Complete quality check report for a table.
 */
public record QualityReport(
    String tableName,
    String schema,
    String connectionKey,
    Instant checkedAt,
    long totalRows,
    int rulesChecked,
    int issuesFound,
    double overallScore,
    List<QualityIssue> issues,
    List<QualityRule> rules
) {
    public boolean isHealthy() {
        return issues.stream().noneMatch(QualityIssue::isCritical);
    }

    public String statusLabel() {
        if (issues.isEmpty()) return "HEALTHY";
        long critical = issues.stream().filter(QualityIssue::isCritical).count();
        if (critical > 0) return "CRITICAL";
        return "WARNING";
    }
}
