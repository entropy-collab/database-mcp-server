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
 * Read-only source of connection pool statistics.
 *
 * <p>Declared here, on the consumer side, so that reporting components such as
 * {@link PoolStatsMBean} do not have to reach back into the connection registry. The registry
 * implements this port, which keeps the dependency pointing one way: registry to monitoring.</p>
 */
public interface PoolStatsSource {

    /**
     * Real-time HikariCP pool statistics for every registered connection.
     *
     * @return map of connection name to pool stats, empty if no HikariDataSource is available
     */
    Map<String, HikariPoolStats> getPoolStats();
}
