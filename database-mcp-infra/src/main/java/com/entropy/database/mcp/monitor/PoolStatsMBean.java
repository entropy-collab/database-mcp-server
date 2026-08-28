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
package com.entropy.database.mcp.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes real-time HikariCP pool statistics and MCP metrics.
 * Provides {@link #getPoolStats()} and {@link #getMetrics()} methods
 * accessible via Spring Actuator or programmatic access.
 */
@Component
public class PoolStatsMBean {

    private static final Logger log = LoggerFactory.getLogger(PoolStatsMBean.class);

    private final PoolStatsSource poolStatsSource;
    private final McpMetricsCollector metricsCollector;

    public PoolStatsMBean(PoolStatsSource poolStatsSource,
                          McpMetricsCollector metricsCollector) {
        this.poolStatsSource = poolStatsSource;
        this.metricsCollector = metricsCollector;
        log.info("PoolStatsMBean initialized");
    }

    public Map<String, Object> getPoolStats() {
        Map<String, HikariPoolStats> stats = poolStatsSource.getPoolStats();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, HikariPoolStats> entry : stats.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toMap());
        }
        return result;
    }

    public Map<String, Object> getMetrics() {
        return metricsCollector.getMetrics();
    }
}
