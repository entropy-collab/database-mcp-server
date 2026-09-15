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

    /**
     * 拿执行计划的原始行，一行一个计划步骤，列名随方言。
     *
     * <p>调用方只给 SELECT，不要自己拼 EXPLAIN 语句：Oracle 的 {@code EXPLAIN PLAN FOR} 不返回结果集，
     * 而是往会话级临时表写行，必须在同一条物理连接上查回来。这套差异只在
     * {@code ExecutionPlanRepository#explainPlanRows} 里有一份实现。
     *
     * <p>与 {@link #explainPlan} 的区别：这里给的是逐行明细（工具要展示的东西），
     * 那里给的是压扁到根节点的 {@link PlanAnalysis}（要判定全表扫描之类的结论）。
     */
    List<Map<String, Object>> explainPlanRows(String sql, String connection);
}
