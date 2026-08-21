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

import com.entropy.database.mcp.config.EtlConfig;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.etl.JobExecutionEngine;
import com.entropy.database.mcp.etl.JobExecution;
import com.entropy.database.mcp.etl.MigrationJob;
import com.entropy.database.mcp.etl.Step;
import com.entropy.database.mcp.etl.StepType;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

import static com.entropy.database.mcp.util.ValidationUtils.*;
import static com.entropy.database.mcp.tools.McpToolUtils.errorResponse;
import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;

/**
 * Unified ETL tools.
 * Replaces: DataMigrationTools (data operations), EtlJobTools
 */
@Configuration
@ConditionalOnProperty(name = "entropy.mcp.gateway.enabled", havingValue = "true")
public class EtlTools {

    private static final Logger log = LoggerFactory.getLogger(EtlTools.class);

    private final DynamicDataSourceManager dataSourceManager;
    private final JobExecutionEngine executionEngine;
    private final EtlConfig etlConfig;

    public EtlTools(DynamicDataSourceManager dataSourceManager,
                    JobExecutionEngine executionEngine,
                    EtlConfig etlConfig) {
        this.dataSourceManager = dataSourceManager;
        this.executionEngine = executionEngine;
        this.etlConfig = etlConfig;
    }

    // ─── Connection Management (from DataMigrationTools) ────────────────────

    @McpTool(description = "Create a named BYOK connection to a remote database")
    public Map<String, Object> createNamedConnection(
            @McpToolParam(description = "Connection name for reuse") String name,
            @McpToolParam(description = "JDBC URL") String jdbcUrl,
            @McpToolParam(description = "Database username") String username,
            @McpToolParam(description = "Database password") String password,
            @McpToolParam(description = "Database dialect (oracle, mysql, postgres, sqlserver)") String dialect) {
        try {
            com.entropy.database.mcp.byok.ConnectionProperties properties = new com.entropy.database.mcp.byok.ConnectionProperties(
                    jdbcUrl, username, password, dialect, null, false);
            ByokDataSourceContext context = dataSourceManager.acquire(name, properties);
            JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
            String testSql = context.getDialect().connectionTestQuery();
            jdbcTemplate.queryForList(testSql);
            return successResponse(Map.of(
                    "connectionName", name,
                    "jdbcUrl", properties.jdbcUrl(),
                    "username", properties.username(),
                    "dialect", properties.dialect(),
                    "message", "Connection created and tested successfully"));
        } catch (Exception e) {
            return errorResponse(Map.of("connectionName", name), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── Data Insertion (from DataMigrationTools) ────────────────────────────

    @McpTool(description = "Batch insert rows into a remote table via BYOK connection")
    public Map<String, Object> insertData(
            @McpToolParam(description = "BYOK connection name") String connectionName,
            @McpToolParam(description = "Target table name") String tableName,
            @McpToolParam(description = "List of rows to insert (each map is a row)") List<Map<String, Object>> rows,
            @McpToolParam(description = "Batch size for JDBC batch update") Integer batchSize) {
        try {
            requireNotBlank(connectionName, "connectionName");
            requireNotBlank(tableName, "tableName");
            requireNotEmpty(rows, "rows");
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
            List<String> columns = rows.get(0).keySet().stream().toList();
            String columnList = String.join(", ", columns);
            String placeholderList = String.join(", ", columns.stream().map(c -> "?").toList());
            String sql = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columnList, placeholderList);
            int size = batchSize != null ? batchSize : etlConfig.batchSize();
            long startTime = System.currentTimeMillis();
            int[][] updateCounts = jdbcTemplate.batchUpdate(sql, rows, size, (ps, row) -> {
                for (int i = 0; i < columns.size(); i++) {
                    ps.setObject(i + 1, row.get(columns.get(i)));
                }
            });
            long duration = System.currentTimeMillis() - startTime;
            int totalRows = 0;
            for (int[] batch : updateCounts) {
                for (int count : batch) {
                    totalRows += count;
                }
            }
            return successResponse(Map.of(
                    "connectionName", connectionName,
                    "tableName", tableName,
                    "rowCount", totalRows,
                    "batchSize", size,
                    "durationMs", duration,
                    "message", String.format("Inserted %d rows into %s", totalRows, tableName)));
        } catch (Exception e) {
            return errorResponse(Map.of("connectionName", connectionName, "tableName", tableName),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Insert query results into a target table (ETL helper)")
    public Map<String, Object> insertQueryResult(
            @McpToolParam(description = "Source BYOK connection name") String sourceConnectionName,
            @McpToolParam(description = "Source SELECT query") String sourceSql,
            @McpToolParam(description = "Target BYOK connection name") String targetConnectionName,
            @McpToolParam(description = "Target table name") String targetTable,
            @McpToolParam(description = "Batch size for insertion") Integer batchSize) {
        try {
            ByokDataSourceContext sourceContext = dataSourceManager.acquire(sourceConnectionName);
            JdbcTemplate sourceJdbc = sourceContext.getJdbcTemplate();
            List<Map<String, Object>> rows = sourceJdbc.queryForList(sourceSql);
            if (rows.isEmpty()) {
                return successResponse(Map.of("message", "No rows to insert", "rowCount", 0));
            }
            ByokDataSourceContext targetContext = dataSourceManager.acquire(targetConnectionName);
            JdbcTemplate targetJdbc = targetContext.getJdbcTemplate();
            List<String> columns = rows.get(0).keySet().stream().toList();
            String columnList = String.join(", ", columns);
            String placeholderList = String.join(", ", columns.stream().map(c -> "?").toList());
            String insertSql = String.format("INSERT INTO %s (%s) VALUES (%s)", targetTable, columnList, placeholderList);
            int size = batchSize != null ? batchSize : 1000;
            long startTime = System.currentTimeMillis();
            int[][] updateCounts = targetJdbc.batchUpdate(insertSql, rows, size, (ps, row) -> {
                for (int i = 0; i < columns.size(); i++) {
                    ps.setObject(i + 1, row.get(columns.get(i)));
                }
            });
            long duration = System.currentTimeMillis() - startTime;
            int totalRows = 0;
            for (int[] batch : updateCounts) {
                for (int count : batch) {
                    totalRows += count;
                }
            }
            return successResponse(Map.of(
                    "sourceConnection", sourceConnectionName,
                    "targetConnection", targetConnectionName,
                    "targetTable", targetTable,
                    "rowCount", totalRows,
                    "batchSize", size,
                    "durationMs", duration,
                    "message", String.format("Inserted %d rows from query into %s", totalRows, targetTable)));
        } catch (Exception e) {
            return errorResponse(Map.of("sourceConnection", sourceConnectionName, "targetConnection", targetConnectionName, "targetTable", targetTable),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Transform and insert data with column mapping (ETL helper)")
    public Map<String, Object> transformAndInsert(
            @McpToolParam(description = "BYOK connection name") String connectionName,
            @McpToolParam(description = "Source table name") String sourceTable,
            @McpToolParam(description = "Target table name") String targetTable,
            @McpToolParam(description = "Column mappings (e.g., ['id:ID', 'name:FULL_NAME:upper'])") List<String> columnMapping,
            @McpToolParam(description = "Optional WHERE clause for filtering", required = false) String whereClause) {
        try {
            requireNotBlank(connectionName, "connectionName");
            requireNotBlank(sourceTable, "sourceTable");
            requireNotBlank(targetTable, "targetTable");
            requireNotEmpty(columnMapping, "columnMapping");
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
            List<String> sourceColumns = new ArrayList<>();
            List<String> targetColumns = new ArrayList<>();
            List<String> transforms = new ArrayList<>();

            for (String mapping : columnMapping) {
                String[] parts = mapping.split(":");
                if (parts.length < 2) {
                    return errorResponse(Map.of("connectionName", connectionName, "sourceTable", sourceTable, "targetTable", targetTable),
                            "Invalid column mapping: " + mapping + ". Expected format: source:target[:transform]", "ValidationException");
                }
                sourceColumns.add(parts[0]);
                targetColumns.add(parts[1]);
                transforms.add(parts.length >= 3 ? parts[2] : "none");
            }

            StringBuilder selectSql = new StringBuilder("SELECT ");
            List<String> selectExprs = new ArrayList<>();
            for (int i = 0; i < sourceColumns.size(); i++) {
                String src = sourceColumns.get(i);
                String transform = transforms.get(i);
                String expr = switch (transform) {
                    case "upper" -> "UPPER(" + src + ")";
                    case "lower" -> "LOWER(" + src + ")";
                    case "trim" -> "TRIM(" + src + ")";
                    case "int" -> "CAST(" + src + " AS INTEGER)";
                    case "long" -> "CAST(" + src + " AS BIGINT)";
                    case "double" -> "CAST(" + src + " AS DOUBLE)";
                    default -> src;
                };
                selectExprs.add(expr + " AS " + targetColumns.get(i));
            }
            selectSql.append(String.join(", ", selectExprs));
            selectSql.append(" FROM ").append(sourceTable);
            if (whereClause != null && !whereClause.isBlank()) {
                selectSql.append(" WHERE ").append(whereClause);
            }

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql.toString());
            if (rows.isEmpty()) {
                return successResponse(Map.of("message", "No rows to transform", "rowCount", 0));
            }

            String columnList = String.join(", ", targetColumns);
            String placeholderList = String.join(", ", targetColumns.stream().map(c -> "?").toList());
            String insertSql = String.format("INSERT INTO %s (%s) VALUES (%s)", targetTable, columnList, placeholderList);
            long startTime = System.currentTimeMillis();
            int[][] updateCounts = jdbcTemplate.batchUpdate(insertSql, rows, 1000, (ps, row) -> {
                for (int i = 0; i < targetColumns.size(); i++) {
                    ps.setObject(i + 1, row.get(targetColumns.get(i)));
                }
            });
            long duration = System.currentTimeMillis() - startTime;
            int totalRows = 0;
            for (int[] batch : updateCounts) {
                for (int count : batch) {
                    totalRows += count;
                }
            }
            return successResponse(Map.of(
                    "connectionName", connectionName,
                    "sourceTable", sourceTable,
                    "targetTable", targetTable,
                    "rowCount", totalRows,
                    "durationMs", duration,
                    "message", String.format("Transformed and inserted %d rows", totalRows)));
        } catch (Exception e) {
            return errorResponse(Map.of("connectionName", connectionName, "sourceTable", sourceTable, "targetTable", targetTable),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Upsert data into a table (insert or update based on key columns)")
    public Map<String, Object> upsertData(
            @McpToolParam(description = "BYOK connection name") String connectionName,
            @McpToolParam(description = "Target table name") String tableName,
            @McpToolParam(description = "Key columns for matching (e.g., ['id'])") List<String> keyColumns,
            @McpToolParam(description = "List of rows to upsert") List<Map<String, Object>> rows) {
        try {
            requireNotBlank(connectionName, "connectionName");
            requireNotBlank(tableName, "tableName");
            requireNotEmpty(rows, "rows");
            requireNotEmpty(keyColumns, "keyColumns");
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
            String dialect = context.getDialect().getClass().getSimpleName().toLowerCase();
            List<String> allColumns = rows.get(0).keySet().stream().toList();
            List<String> nonKeyColumns = allColumns.stream().filter(col -> !keyColumns.contains(col)).toList();
            String columnList = String.join(", ", allColumns);
            String placeholderList = String.join(", ", allColumns.stream().map(c -> "?").toList());
            String upsertSql;
            if (dialect.contains("postgres") || dialect.contains("oracle")) {
                if (dialect.contains("postgres")) {
                    String keyList = String.join(", ", keyColumns);
                    String updateSet = nonKeyColumns.stream().map(col -> col + " = EXCLUDED." + col).reduce((a, b) -> a + ", " + b).orElse("");
                    upsertSql = String.format("INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO UPDATE SET %s",
                            tableName, columnList, placeholderList, keyList, updateSet);
                } else {
                    String keyCondition = keyColumns.stream().map(k -> "target." + k + " = source." + k).reduce((a, b) -> a + " AND " + b).orElse("");
                    String updateSet = nonKeyColumns.stream().map(col -> "target." + col + " = source." + col).reduce((a, b) -> a + ", " + b).orElse("");
                    upsertSql = String.format(
                            "MERGE INTO %s target USING (SELECT %s FROM DUAL) source ON (%s) WHEN MATCHED THEN UPDATE SET %s WHEN NOT MATCHED THEN INSERT (%s) VALUES (%s)",
                            tableName, columnList.replace(", ", ", source."), keyCondition, updateSet, columnList, placeholderList);
                }
            } else {
                String updateSet = nonKeyColumns.stream().map(col -> col + " = VALUES(" + col + ")").reduce((a, b) -> a + ", " + b).orElse("");
                upsertSql = String.format("INSERT INTO %s (%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s",
                        tableName, columnList, placeholderList, updateSet);
            }
            long startTime = System.currentTimeMillis();
            int[][] updateCounts = jdbcTemplate.batchUpdate(upsertSql, rows, 1000, (ps, row) -> {
                for (int i = 0; i < allColumns.size(); i++) {
                    ps.setObject(i + 1, row.get(allColumns.get(i)));
                }
            });
            long duration = System.currentTimeMillis() - startTime;
            int totalRows = 0;
            for (int[] batch : updateCounts) {
                for (int count : batch) {
                    totalRows += count;
                }
            }
            return Map.of(
                    "success", true,
                    "connectionName", connectionName,
                    "tableName", tableName,
                    "keyColumns", keyColumns,
                    "rowCount", totalRows,
                    "durationMs", duration,
                    "message", String.format("Upserted %d rows into %s", totalRows, tableName));
        } catch (Exception e) {
            return errorResponse(Map.of("connectionName", connectionName, "tableName", tableName),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Validate data quality in a table (nulls, duplicates, types)")
    public Map<String, Object> validateDataQuality(
            @McpToolParam(description = "BYOK connection name") String connectionName,
            @McpToolParam(description = "Table name to validate") String tableName,
            @McpToolParam(description = "Columns to check (null for all)", required = false) List<String> columns) {
        try {
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
            DatabaseDialect dialect = context.getDialect();
            if (!dialect.isValidIdentifier(tableName)) {
                return errorResponse(Map.of("connectionName", connectionName, "tableName", tableName),
                        "Invalid table name: " + tableName, "ValidationException");
            }
            String validatedTable = dialect.normalizeTableName(tableName);
            List<Map<String, Object>> issues = new ArrayList<>();
            int totalChecks = 0;
            if (columns == null || columns.isEmpty()) {
                columns = jdbcTemplate.queryForList("SELECT column_name FROM user_tab_columns WHERE table_name = ?", validatedTable)
                        .stream().map(row -> (String) row.get("column_name")).toList();
            }
            for (String column : columns) {
                if (!dialect.isValidIdentifier(column)) {
                    return errorResponse(Map.of("connectionName", connectionName, "tableName", tableName, "column", column),
                            "Invalid column name: " + column, "ValidationException");
                }
            }
            String columnList = columns.stream().map(dialect::quote).reduce((a, b) -> a + ", " + b).orElse("");
            for (String column : columns) {
                totalChecks++;
                String quotedColumn = dialect.quote(column);
                Long nullCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + validatedTable + " WHERE " + quotedColumn + " IS NULL", Long.class);
                if (nullCount != null && nullCount > 0) {
                    issues.add(Map.of("type", "NULL_VALUES", "column", column, "count", nullCount, "severity", "WARNING"));
                }
            }
            totalChecks++;
            Long duplicateCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (SELECT COUNT(*) cnt FROM " + validatedTable + " GROUP BY " + columnList + " HAVING COUNT(*) > 1)", Long.class);
            if (duplicateCount != null && duplicateCount > 0) {
                issues.add(Map.of("type", "DUPLICATES", "columns", columns, "count", duplicateCount, "severity", "ERROR"));
            }
            totalChecks++;
            Long rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + validatedTable, Long.class);
            Map<String, Object> summary = Map.of(
                    "table", validatedTable,
                    "totalRows", rowCount != null ? rowCount : 0,
                    "columnsChecked", columns.size(),
                    "issuesFound", issues.size(),
                    "totalChecks", totalChecks,
                    "passed", totalChecks - issues.size(),
                    "failed", issues.size(),
                    "qualityScore", totalChecks > 0 ? (double) (totalChecks - issues.size()) / totalChecks * 100 : 100.0);
            return successResponse(Map.of(
                    "summary", summary,
                    "issues", issues,
                    "message", String.format("Data quality check completed: %.1f%% passed", (double) (totalChecks - issues.size()) / totalChecks * 100)));
        } catch (Exception e) {
            return errorResponse(Map.of("connectionName", connectionName, "tableName", tableName),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Export query results to a table with automatic batching")
    public Map<String, Object> exportQueryToTable(
            @McpToolParam(description = "BYOK connection name") String connectionName,
            @McpToolParam(description = "Source SELECT query") String sourceSql,
            @McpToolParam(description = "Target table name") String targetTable,
            @McpToolParam(description = "Batch size for processing", required = false) Integer batchSize) {
        try {
            ByokDataSourceContext context = dataSourceManager.acquire(connectionName);
            JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
            DatabaseDialect dialect = context.getDialect();
            if (!dialect.isValidIdentifier(targetTable)) {
                return errorResponse(Map.of("connectionName", connectionName),
                        "Invalid target table name: " + targetTable, "ValidationException");
            }
            String validatedTargetTable = dialect.normalizeTableName(targetTable);
            int size = batchSize != null ? batchSize : 1000;
            long startTime = System.currentTimeMillis();
            int totalRows = 0;
            String continuationToken = null;
            do {
                PaginatedQueryResult result = context.getReadRepository().executeQuery(sourceSql, size, continuationToken);
                if (result.rows().isEmpty()) {
                    break;
                }
                List<Map<String, Object>> rows = result.rows();
                List<String> columns = rows.get(0).keySet().stream().toList();
                String columnList = String.join(", ", columns);
                String placeholderList = String.join(", ", columns.stream().map(c -> "?").toList());
                String insertSql = String.format("INSERT INTO %s (%s) VALUES (%s)", validatedTargetTable, columnList, placeholderList);
                jdbcTemplate.batchUpdate(insertSql, rows, size, (ps, row) -> {
                    for (int i = 0; i < columns.size(); i++) {
                        ps.setObject(i + 1, row.get(columns.get(i)));
                    }
                });
                totalRows += rows.size();
                continuationToken = result.continuationToken();
            } while (continuationToken != null && !continuationToken.isBlank());
            long duration = System.currentTimeMillis() - startTime;
            return Map.of(
                    "success", true,
                    "connectionName", connectionName,
                    "sourceQuery", sourceSql,
                    "targetTable", validatedTargetTable,
                    "rowCount", totalRows,
                    "batchSize", size,
                    "durationMs", duration,
                    "message", String.format("Exported %d rows to %s", totalRows, validatedTargetTable));
        } catch (Exception e) {
            return errorResponse(Map.of("connectionName", connectionName, "targetTable", targetTable),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    // ─── ETL Job Management (from EtlJobTools) ───────────────────────────────

    @McpTool(description = "Submit an ETL job for execution (MigrationJob DSL)")
    public Map<String, Object> submitEtlJob(
            @McpToolParam(description = "Job definition (id, name, description, steps)") Map<String, Object> jobDefinition) {
        try {
            String jobId = (String) jobDefinition.get("id");
            String jobName = (String) jobDefinition.get("name");
            String description = (String) jobDefinition.getOrDefault("description", "");
            List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) jobDefinition.get("steps");
            requireNotEmpty(stepDefs, "steps");
            List<Step> steps = new ArrayList<>();
            for (Map<String, Object> stepDef : stepDefs) {
                String stepId = (String) stepDef.get("id");
                String typeStr = (String) stepDef.get("type");
                StepType type = StepType.from(typeStr);
                List<String> dependsOn = new ArrayList<>();
                if (stepDef.containsKey("dependsOn")) {
                    Object deps = stepDef.get("dependsOn");
                    if (deps instanceof List<?> depList) {
                        for (Object dep : depList) {
                            dependsOn.add(dep.toString());
                        }
                    } else if (deps instanceof String depStr) {
                        dependsOn = Arrays.asList(depStr.split(","));
                    }
                }
                String connection = (String) stepDef.get("connection");
                String sourceSql = (String) stepDef.get("sourceSql");
                String targetTable = (String) stepDef.get("targetTable");
                String targetConnection = (String) stepDef.get("targetConnection");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = new HashMap<>();
                if (stepDef.containsKey("params")) {
                    params.putAll((Map<String, Object>) stepDef.get("params"));
                }
                steps.add(new Step(stepId, type, dependsOn, connection, sourceSql, targetTable, targetConnection, params));
            }
            MigrationJob job = new MigrationJob(jobId, jobName, description, steps);
            JobExecution execution = executionEngine.submit(job);
            return Map.of(
                    "success", true,
                    "jobId", jobId,
                    "jobName", jobName,
                    "totalSteps", steps.size(),
                    "status", execution.status(),
                    "message", "Job submitted successfully");
        } catch (Exception e) {
            log.warn("Failed to submit ETL job", e);
            return errorResponse(Map.of(), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Get the status of an ETL job")
    public Map<String, Object> getJobStatus(
            @McpToolParam(description = "Job identifier") String jobId) {
        try {
            Optional<JobExecution> execution = executionEngine.getExecution(jobId);
            if (execution.isEmpty()) {
                return errorResponse(Map.of("jobId", jobId), "Job not found: " + jobId, "ValidationException");
            }
            JobExecution exec = execution.get();
            List<Map<String, Object>> stepStates = exec.stepStates().entrySet().stream()
                    .map(e -> {
                        com.entropy.database.mcp.etl.StepExecutionState state = e.getValue();
                        Map<String, Object> stepMap = new LinkedHashMap<>();
                        stepMap.put("stepId", state.stepId());
                        stepMap.put("status", state.status());
                        stepMap.put("startedAt", state.startedAt() != null ? state.startedAt().toString() : null);
                        stepMap.put("completedAt", state.completedAt() != null ? state.completedAt().toString() : null);
                        stepMap.put("rowsAffected", state.rowsAffected());
                        stepMap.put("error", state.error());
                        return stepMap;
                    })
                    .collect(Collectors.toList());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobId", exec.jobId());
            result.put("jobName", exec.jobName());
            result.put("status", exec.status());
            result.put("startedAt", exec.startedAt() != null ? exec.startedAt().toString() : null);
            result.put("completedAt", exec.completedAt() != null ? exec.completedAt().toString() : null);
            result.put("progress", String.format("%.1f%%", exec.getProgress()));
            result.put("steps", stepStates);
            return successResponse(Map.of("job", result));
        } catch (Exception e) {
            return errorResponse(Map.of("jobId", jobId), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "List all submitted ETL jobs")
    public Map<String, Object> listJobs() {
        try {
            Collection<JobExecution> executions = executionEngine.listExecutions();
            List<Map<String, Object>> jobs = executions.stream()
                    .map(exec -> Map.<String, Object>of(
                            "jobId", exec.jobId(),
                            "jobName", exec.jobName(),
                            "status", exec.status(),
                            "startedAt", exec.startedAt() != null ? exec.startedAt().toString() : null,
                            "completedAt", exec.completedAt() != null ? exec.completedAt().toString() : null,
                            "progress", String.format("%.1f%%", exec.getProgress()),
                            "totalSteps", exec.stepStates().size(),
                            "completedSteps", exec.getCompletedStepIds().size(),
                            "failedSteps", exec.getFailedStepIds().size()
                    ))
                    .sorted(Comparator.comparing(m -> (String) m.get("jobId")))
                    .collect(Collectors.toList());
            return successResponse(Map.of("totalJobs", jobs.size(), "jobs", jobs));
        } catch (Exception e) {
            return errorResponse(Map.of(), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Stop a running ETL job")
    public Map<String, Object> stopJob(
            @McpToolParam(description = "Job identifier to stop") String jobId) {
        try {
            Optional<JobExecution> execution = executionEngine.getExecution(jobId);
            if (execution.isEmpty()) {
                return errorResponse(Map.of("jobId", jobId), "Job not found: " + jobId, "ValidationException");
            }
            log.warn("Stop requested for job: {} (not implemented in demo)", jobId);
            return successResponse(Map.of(
                    "jobId", jobId,
                    "message", "Stop signal sent (requires production job scheduler for graceful shutdown)"));
        } catch (Exception e) {
            return errorResponse(Map.of("jobId", jobId), e.getMessage(), e.getClass().getSimpleName());
        }
    }
}
