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

import java.util.Map;

/**
 * Prometheus-compatible metrics collector for MCP server.
 * Exposes: tool execution duration, cache hit rate, slow query count, BYOK connection count, ETL success rate.
 */
public interface McpMetricsCollector {

    /**
     * Record tool execution time.
     */
    void recordToolExecution(String toolName, long durationMs);

    /**
     * Record cache hit.
     */
    void recordCacheHit();

    /**
     * Record cache miss.
     */
    void recordCacheMiss();

    /**
     * Record BYOK connection created.
     */
    void recordByokConnectionCreated();

    /**
     * Record BYOK connection removed.
     */
    void recordByokConnectionRemoved();

    /**
     * Record ETL job submitted.
     */
    void recordEtlJobSubmitted();

    /**
     * Record ETL job completed.
     *
     * @param success true if job completed successfully, false if failed
     */
    void recordEtlJobCompleted(boolean success);

    /**
     * Get current metrics snapshot as Map.
     */
    Map<String, Object> getMetrics();
}
