/*
 * Copyright 2024-2026 Entropy Pty Ltd.
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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import com.entropy.database.mcp.properties.DatabaseProperties;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus-compatible metrics collector for MCP server.
 * Exposes: tool execution duration, cache hit rate, slow query count, BYOK connection count, ETL success rate.
 */
@Component
@ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
public class McpMetricsCollectorImpl implements McpMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(McpMetricsCollectorImpl.class);

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> toolTimers = new ConcurrentHashMap<>();
    private final AtomicLong slowQueryCount = new AtomicLong(0);
    private final AtomicLong totalQueryCount = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong cacheMissCount = new AtomicLong(0);
    private final AtomicLong byokConnectionCreatedCount = new AtomicLong(0);
    private final AtomicLong byokConnectionRemovedCount = new AtomicLong(0);
    private final AtomicLong etlJobSubmittedCount = new AtomicLong(0);
    private final AtomicLong etlJobSuccessCount = new AtomicLong(0);
    private final AtomicLong etlJobFailureCount = new AtomicLong(0);
    private final long slowQueryThresholdMs;

    public McpMetricsCollectorImpl(MeterRegistry registry, DatabaseProperties properties) {
        this.registry = registry;
        this.slowQueryThresholdMs = properties != null && properties.metrics() != null
                ? properties.metrics().slowQueryThresholdMs()
                : 5000;
        log.info("McpMetricsCollector initialized: slowQueryThresholdMs={}", slowQueryThresholdMs);
    }

    /**
     * Record tool execution time.
     */
    @Override
    public void recordToolExecution(String toolName, long durationMs) {
        totalQueryCount.incrementAndGet();
        Timer timer = toolTimers.computeIfAbsent(toolName,
            name -> Timer.builder("mcp.tool.execution.duration")
                .tag("tool", name)
                .description("Tool execution duration in milliseconds")
                .register(registry));
        timer.record(durationMs, TimeUnit.MILLISECONDS);

        // Track P99 via cumulative timer
        if (durationMs > slowQueryThresholdMs) {
            slowQueryCount.incrementAndGet();
            log.warn("Slow query detected: tool={}, duration={}ms", toolName, durationMs);
        }
    }

    /**
     * Record cache hit.
     */
    @Override
    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
    }

    /**
     * Record cache miss.
     */
    @Override
    public void recordCacheMiss() {
        cacheMissCount.incrementAndGet();
    }

    /**
     * Record BYOK connection created.
     */
    @Override
    public void recordByokConnectionCreated() {
        byokConnectionCreatedCount.incrementAndGet();
    }

    /**
     * Record BYOK connection removed.
     */
    @Override
    public void recordByokConnectionRemoved() {
        byokConnectionRemovedCount.incrementAndGet();
    }

    /**
     * Record ETL job submitted.
     */
    @Override
    public void recordEtlJobSubmitted() {
        etlJobSubmittedCount.incrementAndGet();
    }

    /**
     * Record ETL job completed.
     *
     * @param success true if job completed successfully, false if failed
     */
    @Override
    public void recordEtlJobCompleted(boolean success) {
        if (success) {
            etlJobSuccessCount.incrementAndGet();
        } else {
            etlJobFailureCount.incrementAndGet();
        }
    }

    /**
     * Get current metrics snapshot as Map.
     */
    @Override
    public java.util.Map<String, Object> getMetrics() {
        java.util.Map<String, Object> metrics = new java.util.HashMap<>();

        metrics.put("totalQueries", totalQueryCount.get());
        metrics.put("slowQueryCount", slowQueryCount.get());

        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        metrics.put("cacheHits", hits);
        metrics.put("cacheMisses", misses);
        metrics.put("cacheHitRate", total > 0 ? String.format("%.2f%%", hits * 100.0 / total) : "N/A");

        metrics.put("byokConnectionsCreated", byokConnectionCreatedCount.get());
        metrics.put("byokConnectionsRemoved", byokConnectionRemovedCount.get());

        long etlSubmitted = etlJobSubmittedCount.get();
        long etlSuccess = etlJobSuccessCount.get();
        long etlFailure = etlJobFailureCount.get();
        metrics.put("etlJobsSubmitted", etlSubmitted);
        metrics.put("etlJobsSuccess", etlSuccess);
        metrics.put("etlJobsFailure", etlFailure);
        metrics.put("etlSuccessRate", etlSubmitted > 0 ? String.format("%.2f%%", etlSuccess * 100.0 / etlSubmitted) : "N/A");

        // Per-tool P99 approximation
        toolTimers.forEach((name, timer) -> {
            double p95 = timer.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS);
            double p99 = timer.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS);
            metrics.put(name + ".p95_ms", String.format("%.0f", p95));
            metrics.put(name + ".p99_ms", String.format("%.0f", p99));
            metrics.put(name + ".count", timer.count());
            metrics.put(name + ".total_time_ms", String.format("%.0f", timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)));
        });

        return metrics;
    }
}
