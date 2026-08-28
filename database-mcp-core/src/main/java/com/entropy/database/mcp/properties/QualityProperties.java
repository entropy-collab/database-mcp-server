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
package com.entropy.database.mcp.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entropy.mcp.database.quality")
public record QualityProperties(
    boolean enabled,
    int maxSampleRows,
    double defaultNullRateThreshold,
    double defaultDuplicateRateThreshold,
    boolean alertOnCritical,
    int alertThresholdIssues
) {
    public QualityProperties {
        enabled = Boolean.TRUE.equals(enabled);
        maxSampleRows = maxSampleRows > 0 ? maxSampleRows : 10000;
        defaultNullRateThreshold = defaultNullRateThreshold > 0 ? defaultNullRateThreshold : 0.05;
        defaultDuplicateRateThreshold = defaultDuplicateRateThreshold > 0 ? defaultDuplicateRateThreshold : 0.01;
        alertOnCritical = Boolean.TRUE.equals(alertOnCritical);
        alertThresholdIssues = alertThresholdIssues > 0 ? alertThresholdIssues : 3;
    }

    public QualityProperties() {
        this(true, 10000, 0.05, 0.01, true, 3);
    }
}
