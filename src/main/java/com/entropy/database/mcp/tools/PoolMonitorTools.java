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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.monitor.HikariPoolStats;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Real-time connection pool monitoring tools.
 * Exposes HikariCP pool metrics per BYOK connection via MCP.
 */
@Component
public class PoolMonitorTools extends McpToolBase {

    private final DynamicDataSourceManager dataSourceManager;

    public PoolMonitorTools(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @McpTool(description = "Get real-time HikariCP pool statistics for all connections",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getPoolStats() {
        return safeExecute(() -> {
            Map<String, HikariPoolStats> stats = dataSourceManager.getPoolStats();
            List<Map<String, Object>> poolDetails = stats.values().stream()
                    .map(HikariPoolStats::toMap)
                    .toList();
            return success(Map.of(
                    "totalConnections", stats.size(),
                    "healthyPools", stats.values().stream().filter(HikariPoolStats::isPoolHealthy).count(),
                    "degradedPools", stats.values().stream().filter(s -> !s.isPoolHealthy()).count(),
                    "pools", poolDetails
            ));
        });
    }

    @McpTool(description = "Get HikariCP pool statistics for a specific connection",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getPoolStatsForConnection(
            @McpToolParam(description = "Connection name (e.g. 'primary' or BYOK key)") String connectionName) {
        return safeExecute(() -> {
            Map<String, HikariPoolStats> allStats = dataSourceManager.getPoolStats();
            HikariPoolStats stats = allStats.get(connectionName);
            if (stats == null) {
                throw new McpToolException(ErrorCode.CONNECTION_NOT_FOUND, "Connection not found or not yet acquired: " + connectionName, connectionName);
            }
            return success(stats.toMap());
        });
    }

    @McpTool(description = "Get overall MCP server metrics including tool performance and cache hit rate",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getMetrics() {
        return safeExecute(() -> {
            // Metrics are recorded through the PerformanceTimingAspect and McpMetricsCollector
            // This is a placeholder that returns structural info about what metrics are tracked
            return success(Map.of(
                    "message", "Metrics are tracked via Spring Boot Actuator /actuator/metrics endpoint",
                    "endpoints", List.of(
                            "/actuator/metrics",
                            "/actuator/prometheus",
                            "JMX: com.entropy.database.mcp:type=PoolStats"
                    )
            ));
        });
    }
}
