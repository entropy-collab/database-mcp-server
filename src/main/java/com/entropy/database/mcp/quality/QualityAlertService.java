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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Alert service for critical data quality issues.
 * Writes alerts to the audit log for downstream monitoring/alerting.
 */
@Component
public class QualityAlertService {

    private static final Logger log = LoggerFactory.getLogger(QualityAlertService.class);
    private static final Logger alertLog = LoggerFactory.getLogger("qualityAlert");

    /**
     * Trigger an alert for a critical quality report.
     * Currently writes to alert logger; can be extended to send notifications.
     */
    public void triggerAlert(QualityReport report) {
        long criticalCount = report.issues().stream()
                .filter(QualityIssue::isCritical)
                .count();

        alertLog.warn(
                "QUALITY_ALERT table={} schema={} totalIssues={} critical={} score={}% checkedAt={}",
                report.tableName(), report.schema(), report.issuesFound(), criticalCount,
                report.overallScore(), report.checkedAt()
        );

        log.warn("[QualityAlert] Table '{}' has {} critical issues (score={}%). See audit log.",
                report.tableName(), criticalCount, report.overallScore());
    }

    /**
     * Get recent alert summary (last N alerts).
     */
    public Map<String, Object> getAlertSummary(int limit) {
        // Future: query from alert repository / audit log
        return Map.of(
                "totalAlerts", 0,
                "recentAlerts", List.<Map<String, Object>>of(),
                "message", "Alert history requires persistent storage backend"
        );
    }
}
