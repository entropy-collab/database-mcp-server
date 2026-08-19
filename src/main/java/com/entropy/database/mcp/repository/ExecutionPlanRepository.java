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
package com.entropy.database.mcp.repository;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.*;
import com.entropy.database.mcp.security.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution plan analysis repository.
 * Provides standardized EXPLAIN PLAN functionality across dialects.
 */
@Repository
public class ExecutionPlanRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPlanRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;
    private final SqlValidator sqlValidator;

    public ExecutionPlanRepository(@Qualifier("primaryJdbcTemplate") JdbcTemplate jdbcTemplate,
                                   DatabaseDialect dialect,
                                   SqlValidator sqlValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        this.sqlValidator = sqlValidator;
    }

    /**
     * Get standardized execution plan for a SQL query.
     */
    public StandardizedPlan getExecutionPlan(String sql) {
        sqlValidator.validateSelect(sql);
        
        String dialectName = detectDialect();
        
        return switch (dialectName) {
            case "oracle" -> getOracleExecutionPlan(sql);
            case "postgres" -> getPostgresExecutionPlan(sql);
            case "mysql" -> getMysqlExecutionPlan(sql);
            default -> getGenericExecutionPlan(sql);
        };
    }

    /**
     * Get execution plan with performance warnings.
     */
    public PlanAnalysis analyzeExecutionPlan(String sql) {
        StandardizedPlan plan = getExecutionPlan(sql);
        
        List<PlanWarning> warnings = new ArrayList<>();
        
        // Check for full table scans
        if (plan.isFullTableScan()) {
            warnings.add(new PlanWarning(
                "FULL_TABLE_SCAN",
                "Potential full table scan detected. Consider adding an index.",
                PlanWarning.Severity.HIGH
            ));
        }
        
        // Check for large estimated row counts
        long estimatedRows = plan.getEstimatedRows();
        if (estimatedRows > 1000000) {
            warnings.add(new PlanWarning(
                "LARGE_RESULT_SET",
                "Estimated " + estimatedRows + " rows. Consider pagination or filtering.",
                PlanWarning.Severity.MEDIUM
            ));
        }
        
        // Check for missing predicates
        if (plan.accessPredicates() == null && plan.filterPredicates() == null) {
            warnings.add(new PlanWarning(
                "NO_PREDICATES",
                "No access or filter predicates found. Query may return all rows.",
                PlanWarning.Severity.HIGH
            ));
        }
        
        return new PlanAnalysis(plan, warnings);
    }

    // ─── Dialect-specific implementations ────────────────────────────────

    private StandardizedPlan getOracleExecutionPlan(String sql) {
        try {
            // Execute EXPLAIN PLAN
            String explainSql = "EXPLAIN PLAN SET STATEMENT_ID = 'mcp_query_" + System.nanoTime() + "' FOR " + sql;
            jdbcTemplate.execute(explainSql);

            // Query the plan with hierarchical structure
            String querySql = """
                SELECT LPAD(' ', 2*(LEVEL-1)) || operation AS operation,
                       options,
                       object_name,
                       object_owner,
                       cost,
                       cardinality,
                       bytes,
                       access_predicates,
                       filter_predicates,
                       projection,
                       id
                FROM plan_table
                START WITH id = 0
                CONNECT BY PRIOR id = parent_id
                AND statement_id = 'mcp_query_' || extractValue(xmltype(
                    dbms_xplan.display('TABLE','mcp_query_', 'BASIC')
                ), '/row/id')
                ORDER SIBLINGS BY id
                """;

            // Simplified query for compatibility
            querySql = "SELECT id, parent_id, operation, options, object_name, " +
                       "cost, cardinality, bytes, access_predicates, filter_predicates " +
                       "FROM plan_table WHERE statement_id LIKE 'mcp_query_%' " +
                       "ORDER BY id";

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(querySql);

            // Convert to standardized format
            if (!rows.isEmpty()) {
                Map<String, String> firstRow = convertToMap(rows.get(0));
                return StandardizedPlan.fromOracleExplain(firstRow);
            }

            return createDefaultPlan(sql);

        } catch (Exception e) {
            log.warn("Failed to get Oracle execution plan, using fallback: {}", e.getMessage());
            return createDefaultPlan(sql);
        }
    }

    private StandardizedPlan getPostgresExecutionPlan(String sql) {
        try {
            // Use EXPLAIN (ANALYZE, FORMAT JSON) for detailed output
            String explainSql = "EXPLAIN (FORMAT JSON) " + sql;
            List<Map<String, Object>> result = jdbcTemplate.queryForList(explainSql);

            if (!result.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = result.get(0);
                // PostgreSQL returns array as first element
                Object planObj = row.values().iterator().next();
                if (planObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> planList = (List<Map<String, Object>>) planObj;
                    if (!planList.isEmpty()) {
                        Map<String, Object> plan = planList.get(0);
                        return StandardizedPlan.fromPostgresExplain(plan);
                    }
                }
            }

            return createDefaultPlan(sql);

        } catch (Exception e) {
            log.warn("Failed to get PostgreSQL execution plan, using fallback: {}", e.getMessage());
            return createDefaultPlan(sql);
        }
    }

    private StandardizedPlan getMysqlExecutionPlan(String sql) {
        try {
            // MySQL EXPLAIN output
            String explainSql = "EXPLAIN " + sql;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(explainSql);

            if (!rows.isEmpty()) {
                Map<String, Object> firstRow = rows.get(0);

                String operation = String.valueOf(firstRow.getOrDefault("type", "UNKNOWN"));
                String object = String.valueOf(firstRow.getOrDefault("table", ""));
                String possibleKeys = String.valueOf(firstRow.getOrDefault("possible_keys", ""));
                String key = String.valueOf(firstRow.getOrDefault("key", ""));
                long estimatedRows = ((Number) firstRow.getOrDefault("rows", 0)).longValue();
                Double cost = firstRow.get("cost") != null ?
                    ((Number) firstRow.get("cost")).doubleValue() : null;

                return new StandardizedPlan(
                    "0",
                    "",
                    mapMySqlOperation(operation),
                    object,
                    key.isBlank() ? null : key,
                    List.of(
                        new PlanProperty("estimated_rows", estimatedRows),
                        new PlanProperty("type", operation),
                        new PlanProperty("possible_keys", possibleKeys.isBlank() ? null : possibleKeys),
                        new PlanProperty("key", key.isBlank() ? null : key),
                        new PlanProperty("cost", cost)
                    ),
                    null,
                    null,
                    null
                );
            }

            return createDefaultPlan(sql);

        } catch (Exception e) {
            log.warn("Failed to get MySQL execution plan, using fallback: {}", e.getMessage());
            return createDefaultPlan(sql);
        }
    }

    private StandardizedPlan getGenericExecutionPlan(String sql) {
        return createDefaultPlan(sql);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private String detectDialect() {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            String url = conn.getMetaData().getURL();
            if (url.contains("oracle")) return "oracle";
            if (url.contains("postgres")) return "postgres";
            if (url.contains("mysql")) return "mysql";
        } catch (Exception e) {
            // Fallback
        }
        return "generic";
    }

    private StandardizedPlan createDefaultPlan(String sql) {
        return new StandardizedPlan(
            "0",
            "",
            "ANALYSIS_NOT_AVAILABLE",
            "",
            null,
            List.of(new PlanProperty("sql", sql)),
            null,
            null,
            null
        );
    }

    private Map<String, String> convertToMap(Map<String, Object> row) {
        Map<String, String> result = new HashMap<>();
        row.forEach((k, v) -> result.put(k, v != null ? String.valueOf(v) : ""));
        return result;
    }

    private String mapMySqlOperation(String type) {
        return switch (type.toUpperCase()) {
            case "ALL" -> "FULL_SCAN";
            case "INDEX" -> "INDEX_SCAN";
            case "RANGE" -> "RANGE_SCAN";
            case "REF" -> "INDEX_REF";
            case "EQ_REF" -> "EQ_REF";
            case "CONST" -> "CONSTANT";
            case "SYSTEM" -> "SYSTEM_TABLE";
            case "NULL" -> "NO_ROWS";
            default -> type.toUpperCase();
        };
    }
}
