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

    @McpTool(description = """
            【全部连接池统计】返回所有已建立连接池的 HikariCP 实时指标与健康判定，并给出健康、降级池的数量。无入参。
            前置条件：只统计已实际获取过连接、池已创建的连接；已注册但尚未使用的连接不会出现在结果中。
            使用场景：巡检整体连接资源使用情况、定位是哪个连接的池接近打满或有等待线程。
            口径说明：内容相同的连接会共用同一个物理池，多出来的名字是别名（canonicalName 指向规范名、isAlias 为 true）；totalConnections、healthyPools、degradedPools 均按物理池去重计数，pools 数组仍按连接名逐条返回。
            返回字段：totalConnections（去重后的物理池数量）、totalConnectionNames（连接名个数，含别名）、healthyPools、degradedPools、aliasNames（属于别名的连接名列表）、pools（数组，每项含 connectionName、canonicalName、isAlias、dialect、jdbcUrlMasked、totalConnections、activeConnections、idleConnections、pendingThreads、maxPoolSize、minIdle、connectionTimeoutMs、idleTimeoutMs、maxLifetimeMs、leakDetectionThresholdMs、isPoolHealthy、utilizationRatio、healthWarnings）。
            不要用于：只关心某一个连接的池（用 getPoolStatsForConnection）；查看连接注册元数据与租约到期（用 listConnections 或 describeConnection）；查看工具调用耗时与缓存命中率（用 getMetrics）。
            标签：[read, monitor, pool, metrics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getPoolStats() {
        return safeExecute(() -> {
            Map<String, HikariPoolStats> stats = dataSourceManager.getPoolStats();
            List<Map<String, Object>> poolDetails = stats.values().stream()
                    .map(HikariPoolStats::toMap)
                    .toList();

            // 别名与规范名在注册表里是两个名字指向同一个 Hikari 池，所以 stats 里会有 N 条记录描述同一个池。
            // 过去直接用 stats.size() 和 filter().count()，池数量与健康池数量就按别名个数放大，
            // 运维看到的资源占用是虚高的。这里按 canonicalName 折叠成物理池后再计数，
            // 同时保留每个名字的明细，否则按别名查 getPoolStatsForConnection 会变成「连接不存在」。
            Map<String, HikariPoolStats> physicalPools = new java.util.LinkedHashMap<>();
            for (HikariPoolStats s : stats.values()) {
                physicalPools.putIfAbsent(s.canonicalName(), s);
            }
            List<String> aliasNames = stats.values().stream()
                    .filter(HikariPoolStats::isAlias)
                    .map(HikariPoolStats::connectionName)
                    .toList();

            return success(Map.of(
                    "totalConnections", physicalPools.size(),
                    "totalConnectionNames", stats.size(),
                    "healthyPools", physicalPools.values().stream().filter(HikariPoolStats::isPoolHealthy).count(),
                    "degradedPools", physicalPools.values().stream().filter(s -> !s.isPoolHealthy()).count(),
                    "aliasNames", aliasNames,
                    "pools", poolDetails
            ));
        });
    }

    @McpTool(description = """
            【单连接池统计】返回指定连接的 HikariCP 池实时指标与健康判定。
            前置条件：该连接必须已实际建立过池；连接虽已注册但从未使用过时报连接不存在或尚未获取。
            使用场景：已经锁定某个连接有性能问题，需要看它的活跃、空闲、等待线程与使用率。
            口径说明：传入别名同样能查到，返回的是它所复用的物理池指标，canonicalName 指向规范名、isAlias 为 true。
            返回字段：connectionName、canonicalName、isAlias、dialect、jdbcUrlMasked、totalConnections、activeConnections、idleConnections、pendingThreads、maxPoolSize、minIdle、connectionTimeoutMs、idleTimeoutMs、maxLifetimeMs、leakDetectionThresholdMs、isPoolHealthy、utilizationRatio、healthWarnings。
            不要用于：横向对比所有连接的池状况（用 getPoolStats）；查看连接注册状态与租约（用 describeConnection）。
            标签：[read, monitor, pool, metrics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getPoolStatsForConnection(
            @McpToolParam(description = "要查询的连接名，必填且不可省略。取值为 BYOK 连接名或内置连接名（如 primary）；须与 getPoolStats 返回的 connectionName 一致") String connectionName) {
        return safeExecute(() -> {
            Map<String, HikariPoolStats> allStats = dataSourceManager.getPoolStats();
            HikariPoolStats stats = allStats.get(connectionName);
            if (stats == null) {
                throw new McpToolException(ErrorCode.CONNECTION_NOT_FOUND, "Connection not found or not yet acquired: " + connectionName, connectionName);
            }
            return success(stats.toMap());
        });
    }

    @McpTool(description = """
            【服务指标入口说明】返回本服务指标的采集位置说明，不返回指标数值本身。无入参。
            前置条件：无。工具性能与缓存命中率等指标由切面采集后暴露在 Spring Boot Actuator 与 JMX，需通过 HTTP 或 JMX 抓取，MCP 通道只回传入口地址。
            使用场景：需要知道去哪里拉取服务级监控指标。
            返回字段：message（说明指标经 Actuator 暴露）、endpoints（可抓取的入口列表：/actuator/metrics、/actuator/prometheus、JMX 的 PoolStats MBean）。
            不要用于：想直接拿到连接池数值（用 getPoolStats 或 getPoolStatsForConnection）；想拿到查询与缓存统计数值（用 getStatistics）；想看审计指标（用 getAuditMetrics）。
            标签：[read, monitor, metrics, actuator]
            """,
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
