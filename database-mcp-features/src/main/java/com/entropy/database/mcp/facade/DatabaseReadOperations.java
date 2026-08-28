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
package com.entropy.database.mcp.facade;

import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;

import java.util.List;
import java.util.Map;

/**
 * Read-only query execution and plan analysis.
 */
public interface DatabaseReadOperations {

    PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken, String connection);

    List<Map<String, Object>> executeNamedQuery(String sql, Map<String, Object> params, String connection);

    /**
     * Execute a dialect-specific read query with positional parameters and return all rows.
     *
     * <p>This is the seam for the many dialect-flavoured lookups that tools previously ran by
     * taking a {@code JdbcTemplate} out of the connection context directly: health checks,
     * data-dictionary probes, cross-database link metadata, aggregate counts. Routing them here
     * means the timing, audit and read-only advice apply to them too.
     *
     * <p>Unlike {@link #executeQuery}, no pagination or result caching is applied: callers are
     * expected to have written a query that is already bounded.
     */
    List<Map<String, Object>> queryRows(String sql, String connection, Object... args);

    PlanAnalysis explainPlan(String sql, String connection);
}
