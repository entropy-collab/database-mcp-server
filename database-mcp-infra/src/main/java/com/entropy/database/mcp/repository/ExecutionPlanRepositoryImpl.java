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
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.domain.PlanProperty;
import com.entropy.database.mcp.domain.PlanWarning;
import com.entropy.database.mcp.domain.StandardizedPlan;
import com.entropy.database.mcp.security.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Execution plan analysis repository.
 * Provides standardized EXPLAIN PLAN functionality across dialects.
 */
public class ExecutionPlanRepositoryImpl implements ExecutionPlanRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPlanRepositoryImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;
    private final SqlValidator sqlValidator;

    public ExecutionPlanRepositoryImpl(JdbcTemplate jdbcTemplate,
                                   DatabaseDialect dialect,
                                   SqlValidator sqlValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        this.sqlValidator = sqlValidator;
    }

    /**
     * Get standardized execution plan for a SQL query.
     *
     * <p>Oracle 分支按方言能力（{@link DatabaseDialect#explainWritesToPlanTable()}）判定，不再按类名：
     * 类名判断对子类和 CGLIB 代理都是静默失效。剩下两个分支还按 {@link DatabaseDialect#getDialectName()}
     * 分派，那只决定「计划结果怎么解析」（Postgres 是 JSON 树、MySQL 是表格行），把它也提成方言契约需要
     * 每个方言都表态、默认值一错就让 Postgres/MySQL 退化成"无计划"，本次不扩大战场；但至少不再从
     * {@code getClass().getSimpleName()} 取名字，方言自己报的名字改名时是一处可查的常量。
     */
    @Override
    public StandardizedPlan getExecutionPlan(String sql) {
        if (dialect.explainWritesToPlanTable()) {
            return getOracleExecutionPlan(sql);
        }
        String dialectName = dialect.getDialectName() == null
                ? "" : dialect.getDialectName().toLowerCase(Locale.ROOT);
        return switch (dialectName) {
            case "postgres", "postgresql" -> getPostgresExecutionPlan(sql);
            case "mysql", "mariadb" -> getMysqlExecutionPlan(sql);
            default -> createDefaultPlan(sql);
        };
    }

    /**
     * Get execution plan with performance warnings.
     */
    @Override
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
            List<Map<String, Object>> rows = explainPlanRows(sql);
            if (!rows.isEmpty()) {
                // Find the root node (id=0) which contains the total cost/cardinality
                Map<String, Object> rootRow = rows.stream()
                    .filter(row -> "0".equals(String.valueOf(row.getOrDefault("ID", "-1"))))
                    .findFirst()
                    .orElse(rows.get(0));

                Map<String, String> planRow = convertToMap(rootRow);
                return StandardizedPlan.fromOracleExplain(planRow, rows);
            }

            return createDefaultPlan(sql);

        } catch (Exception e) {
            log.warn("Failed to get Oracle execution plan, using fallback", e);
            return createDefaultPlan(sql);
        }
    }

    @Override
    public List<Map<String, Object>> explainPlanRows(String sql) {
        // EXPLAIN 会把入参原文当语句执行，所以先按"只能是 SELECT"复校一遍。
        sqlValidator.validateSelect(sql);

        if (dialect.explainWritesToPlanTable()) {
            // 「EXPLAIN 要不要分两步」是方言能力，不是调用方按类名猜出来的：子类、CGLIB 代理、将来的
            // Oracle23Dialect 都会让类名判断静默走单步分支，而单步在 Oracle 上恒返回空计划。
            return planTableExplainRows(sql);
        }

        String explainSql = dialect.getExplainPlanSql(sql);
        if (explainSql == null) {
            return List.of();
        }
        if (!dialect.explainPlanReturnsRows()) {
            // SQL Server 的 SET SHOWPLAN_TEXT：计划走会话输出，批处理本身没有可读结果集。
            jdbcTemplate.execute(explainSql);
            return List.of();
        }
        return jdbcTemplate.queryForList(explainSql);
    }

    /**
     * 两步 EXPLAIN，必须在同一条物理连接上完成（Oracle 是目前唯一这样的方言）。
     *
     * <p>{@code EXPLAIN PLAN FOR} 不返回结果集，而是往 {@code SYS.PLAN_TABLE$} 写行；那张表是
     * 会话级临时表（实测 {@code TEMPORARY=Y / DURATION=SYS$SESSION}），换一条池连接去查就是空的。
     * 所以这里用 {@link ConnectionCallback} 把"写计划 → 读计划 → 清理"三步锁在同一个 {@link Connection} 上，
     * 并用 {@code STATEMENT_ID} 把本次结果与同一会话里的历史计划隔开。
     *
     * <p>三条语句都由方言给出：品种判断不再出现在这里，语句拼装也不在这里——{@code STATEMENT_ID} 只能
     * 以字面量入 SQL，校验该字面量是方言的职责。
     */
    private List<Map<String, Object>> planTableExplainRows(String sql) {
        String statementId = "mcp_query_" + System.nanoTime();
        String explainSql = dialect.explainPlanStatement(statementId, sql);
        String fetchSql = dialect.planTableFetchSql();
        if (explainSql == null || fetchSql == null) {
            // 方言声称走计划表却不给语句：宁可空手回，也不要把裸 EXPLAIN 当查询执行——那在 Oracle 上
            // 恒返回空结果集，和"这条 SQL 没有计划"无法区分。
            log.warn("Dialect {} declares a plan table but provides no EXPLAIN/fetch statement",
                    dialect.getDialectName());
            return List.of();
        }
        String cleanupSql = dialect.planTableCleanupSql();

        return jdbcTemplate.execute((ConnectionCallback<List<Map<String, Object>>>) con -> {
            try (Statement statement = con.createStatement()) {
                statement.execute(explainSql);
            }
            // 清理必须在 finally 里：EXPLAIN 已经把行写进临时表了，读取阶段抛异常时若跳过清理，这些行会
            // 一直留在会话里，而连接会被池复用到 max-lifetime——正是下面注释担心的堆积。
            try {
                List<Map<String, Object>> rows = new ArrayList<>();
                try (PreparedStatement fetch = con.prepareStatement(fetchSql)) {
                    fetch.setString(1, statementId);
                    try (ResultSet rs = fetch.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= meta.getColumnCount(); i++) {
                                row.put(meta.getColumnLabel(i), rs.getObject(i));
                            }
                            rows.add(row);
                        }
                    }
                }
                return rows;
            } finally {
                cleanupPlanTable(con, cleanupSql, statementId);
            }
        });
    }

    /**
     * 删掉本次写进计划表的行。
     *
     * <p>临时表按会话保留行，而连接会被池复用最长 max-lifetime，不清理就会一直堆积。清理自身的失败只记
     * debug：它发生在 finally 里，抛出去会盖掉真正的读取异常，而"没清干净"最多是多留几行计划。
     */
    private void cleanupPlanTable(Connection con, String cleanupSql, String statementId) {
        if (cleanupSql == null) {
            return;
        }
        try (PreparedStatement cleanup = con.prepareStatement(cleanupSql)) {
            cleanup.setString(1, statementId);
            cleanup.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            log.debug("plan_table cleanup skipped for {}: {}", statementId, e.getMessage());
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
            log.warn("Failed to get PostgreSQL execution plan, using fallback", e);
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
            log.warn("Failed to get MySQL execution plan, using fallback", e);
            return createDefaultPlan(sql);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

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
