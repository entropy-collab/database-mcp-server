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

import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.entropy.database.mcp.tools.McpToolUtils.errorResponse;
import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;

/**
 * Template method base class for query tools that share a common execution pattern.
 *
 * <p>The template defines the skeleton of the query execution algorithm:
 * <ol>
 *   <li>Execute the query via the routing facade (subclasses provide the SQL/logic)</li>
 *   <li>Post-process the result (e.g., wrap in response format, serialize to CSV/JSON)</li>
 *   <li>Handle errors uniformly</li>
 * </ol>
 *
 * <p>Subclasses override {@link #doExecute(String, Integer, String, String)} to provide
 * the query-specific logic, and optionally {@link #buildSuccessContext(Map)} to add
 * result metadata. This follows the Template Method pattern as used in
 * {@code org.springframework.jdbc.core.JdbcTemplate} and {@code JdbcOperations}.
 *
 * @param <R> the raw query result type (typically {@link PaginatedQueryResult})
 */
public abstract class BaseQueryTool<R> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final RoutingDatabaseFacade routingFacade;
    protected final int defaultMaxRows;

    protected BaseQueryTool(RoutingDatabaseFacade routingFacade, int defaultMaxRows) {
        this.routingFacade = routingFacade;
        this.defaultMaxRows = defaultMaxRows;
    }

    /**
     * Execute the core query logic. Subclasses implement this to perform the actual
     * database operation.
     *
     * @param sql the SQL query to execute
     * @param maxRows maximum rows to return (null = use default)
     * @param continuationToken pagination token (null for first page)
     * @param connection BYOK connection name
     * @return the raw query result
     */
    protected abstract R doExecute(String sql, Integer maxRows, String continuationToken, String connection);

    /**
     * Build the context map for a successful response.
     *
     * @param result the raw query result
     * @param sql the original SQL
     * @param connection the connection name (may be null)
     * @return context map to be wrapped in successResponse
     */
    protected abstract Map<String, Object> buildSuccessContext(R result, String sql, String connection);

    /**
     * Check if the result contains rows (used for empty-result short-circuit).
     */
    protected boolean hasRows(R result) {
        return true;
    }

    /**
     * Get an empty success response when there are no rows.
     */
    protected Map<String, Object> emptyResultContext(String sql, String connection) {
        return Map.of("message", "No rows returned", "rowCount", 0);
    }

    /**
     * Template method: executes the query and wraps the result in a standard response.
     *
     * <p>Error handling, connection name injection, and response formatting are all
     * handled here, so subclasses only need to implement the query-specific logic.
     */
    public final Map<String, Object> execute(String sql, Integer maxRows,
                                              String continuationToken, String connection) {
        try {
            int limit = maxRows != null ? maxRows : defaultMaxRows;
            R result = doExecute(sql, limit, continuationToken, connection);

            if (!hasRows(result)) {
                return successResponse(emptyResultContext(sql, connection));
            }

            Map<String, Object> context = buildSuccessContext(result, sql, connection);
            return successResponse(context);
        } catch (Exception e) {
            log.warn("{} failed: {}", getClass().getSimpleName(), e.getMessage(), e);
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("sql", sql);
            ctx.put("connection", connection);
            return errorResponse(ctx, e.getMessage(), e.getClass().getSimpleName());
        }
    }
}
