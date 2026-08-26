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

import java.util.List;
import java.util.Map;

/**
 * Read-only snapshot of a HikariCP connection pool at a point in time.
 * All values are captured atomically per-pool on each read.
 *
 * @param connectionName   logical name of the BYOK connection
 * @param canonicalName    名字所指向的<b>物理池</b>身份：规范名自身填自己，别名填它复用的规范名。
 *                         一个 Hikari 池可以同时挂在规范名和若干别名下，汇总统计必须按这个字段去重，
 *                         否则「池数量 / 健康池数量」会按别名个数放大
 * @param dialect          database dialect (e.g. OracleDialect)
 * @param jdbcUrlMasked    masked JDBC URL for identification
 * @param totalConnections current total pool size
 * @param activeConnections connections currently checked out
 * @param idleConnections  connections sitting idle in the pool
 * @param pendingThreads   threads waiting to acquire a connection
 * @param maxPoolSize      configured maximum pool size
 * @param minIdle          configured minimum idle connections
 * @param connectionTimeoutMs  milliseconds a thread waits before giving up
 * @param idleTimeoutMs    time before an idle connection is evicted
 * @param maxLifetimeMs    maximum lifetime of a connection
 * @param leakDetectionThresholdMs  threshold for connection leak detection
 * @param isPoolHealthy    true if no critical pool conditions detected
 * @param healthWarnings   non-empty list of pool health warnings (empty if healthy)
 */
public record HikariPoolStats(
        String connectionName,
        String canonicalName,
        String dialect,
        String jdbcUrlMasked,
        int totalConnections,
        int activeConnections,
        int idleConnections,
        int pendingThreads,
        int maxPoolSize,
        int minIdle,
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        long leakDetectionThresholdMs,
        boolean isPoolHealthy,
        java.util.List<String> healthWarnings
) {
    public HikariPoolStats {
        if (connectionName == null || connectionName.isBlank()) {
            throw new IllegalArgumentException("connectionName is required");
        }
        // 缺省指向自己：调用方漏填时退化成「每个名字一个池」，只会少去重一次，
        // 不会把某个池误判成别名而从汇总里消失
        if (canonicalName == null || canonicalName.isBlank()) {
            canonicalName = connectionName;
        }
        healthWarnings = healthWarnings != null ? healthWarnings : List.of();
    }

    /** 该名字是否只是别名，即它与另一个名字共用同一个物理 Hikari 池。 */
    public boolean isAlias() {
        return !connectionName.equals(canonicalName);
    }

    /**
     * Returns a serializable Map representation suitable for MCP responses.
     */
    public Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("connectionName", connectionName);
        // 暴露物理池身份，运维才能看出「6 条记录其实是 2 个池」
        m.put("canonicalName", canonicalName);
        m.put("isAlias", isAlias());
        m.put("dialect", dialect);
        m.put("jdbcUrlMasked", jdbcUrlMasked);
        m.put("totalConnections", totalConnections);
        m.put("activeConnections", activeConnections);
        m.put("idleConnections", idleConnections);
        m.put("pendingThreads", pendingThreads);
        m.put("maxPoolSize", maxPoolSize);
        m.put("minIdle", minIdle);
        m.put("connectionTimeoutMs", connectionTimeoutMs);
        m.put("idleTimeoutMs", idleTimeoutMs);
        m.put("maxLifetimeMs", maxLifetimeMs);
        m.put("leakDetectionThresholdMs", leakDetectionThresholdMs);
        m.put("isPoolHealthy", isPoolHealthy);
        m.put("utilizationRatio", maxPoolSize > 0 ? (double) activeConnections / maxPoolSize : 0);
        m.put("healthWarnings", healthWarnings);
        return m;
    }
}
