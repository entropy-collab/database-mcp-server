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

import java.util.Map;

/**
 * JMX MBean interface for real-time connection pool statistics.
 * Exposed at {@code com.entropy.database.mcp:type=PoolStats}
 */
public interface PoolStatsMBeanMxBean {

    /**
     * Get real-time HikariCP pool stats for all BYOK connections.
     * Keyed by connection name.
     */
    Map<String, Object> getPoolStats();

    /**
     * Get MCP server-wide metrics snapshot (tool execution stats, cache hit rate, etc.).
     */
    Map<String, Object> getMetrics();

    /**
     * Returns the JMX object name this bean is registered under.
     */
    String getRegisteredObjectName();
}
