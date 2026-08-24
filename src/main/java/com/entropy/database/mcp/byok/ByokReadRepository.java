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
package com.entropy.database.mcp.byok;

import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.security.QueryAuditLogger;
import com.entropy.database.mcp.security.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * BYOK read operations repository.
 * Simplified version of DatabaseReadRepository for BYOK datasources.
 */
public class ByokReadRepository {
    private static final Logger log = LoggerFactory.getLogger(ByokReadRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;
    private final SqlValidator sqlValidator;
    private final DatabaseCache cache;
    private final DatabaseHealthMonitor healthMonitor;
    private final QueryAuditLogger auditLogger;
    private final int maxRows;
    private final int maxResultRows;
    private final int queryTimeoutSeconds;

    public ByokReadRepository(JdbcTemplate jdbcTemplate,
                              DatabaseDialect dialect,
                              SqlValidator sqlValidator,
                              DatabaseCache cache,
                              DatabaseHealthMonitor healthMonitor,
                              QueryAuditLogger auditLogger,
                              int maxRows,
                              int maxResultRows,
                              int queryTimeoutSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        this.sqlValidator = sqlValidator;
        this.cache = cache;
        this.healthMonitor = healthMonitor;
        this.auditLogger = auditLogger;
        this.maxRows = maxRows;
        this.maxResultRows = maxResultRows;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken) {
        long start = System.currentTimeMillis();
        try {
            sqlValidator.validateSelect(sql);

            // Rewrite LIMIT/FETCH clauses for dialects that don't support them natively (e.g. Oracle)
            String rewrittenSql = dialect.rewriteLimitInSql(sql);

            int limit = maxRows > 0 ? Math.min(maxRows, this.maxRows) : this.maxRows;
            int offset = 0;

            String limitedSql = dialect.supportsLimit()
                    ? dialect.applyLimit(rewrittenSql, limit, offset)
                    : rewrittenSql;
            
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(limitedSql);
            long duration = System.currentTimeMillis() - start;
            
            // Record metrics
            healthMonitor.recordQuery(duration, rows.size(), true);
            auditLogger.log("executeQuery", sql, rows.size(), duration, true, (String) null, (String) null);
            
            return PaginatedQueryResult.from(rows, null, false);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            healthMonitor.recordQuery(duration, 0, false);
            auditLogger.log("executeQuery", sql, 0, duration, false, null, (String) null);
            throw new com.entropy.database.mcp.exception.McpQueryException(
                com.entropy.database.mcp.exception.ErrorCode.QUERY_EXECUTION_FAILED,
                "Query failed", e);
        }
    }
}
