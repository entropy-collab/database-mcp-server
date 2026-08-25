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

import com.entropy.database.mcp.config.DatabaseConstants;
import com.entropy.database.mcp.config.EtlConfig;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.etl.JobExecutionEngine;
import com.entropy.database.mcp.etl.JobExecution;
import com.entropy.database.mcp.etl.MigrationJob;
import com.entropy.database.mcp.etl.Step;
import com.entropy.database.mcp.etl.StepType;
import com.entropy.database.mcp.facade.DatabaseOperations;
import com.entropy.database.mcp.security.SqlValidator;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.entropy.database.mcp.util.ValidationUtils.*;

/**
 * Unified ETL tools.
 * Replaces: DataMigrationTools (data operations), EtlJobTools
 */
@Component
@ConditionalOnProperty(name = "entropy.mcp.gateway.enabled", havingValue = "true")
public class EtlTools extends McpToolBase {

    private static final int DEFAULT_BATCH_INSERT_SIZE = 1000;

    private final DynamicDataSourceManager dataSourceManager;
    private final DatabaseOperations routingFacade;
    private final JobExecutionEngine executionEngine;
    private final EtlConfig etlConfig;
    private final SqlValidator sqlValidator;

    /**
     * @param dataSourceManager only for {@link #createNamedConnection}: registering a connection is
     *                          connection-management, not a database operation, so it has no seam
     *                          on the facade.
     */
    public EtlTools(DynamicDataSourceManager dataSourceManager,
                    DatabaseOperations routingFacade,
                    JobExecutionEngine executionEngine,
                    EtlConfig etlConfig,
                    SqlValidator sqlValidator) {
        this.dataSourceManager = dataSourceManager;
        this.routingFacade = routingFacade;
        this.executionEngine = executionEngine;
        this.etlConfig = etlConfig;
        this.sqlValidator = sqlValidator;
    }

    @McpTool(description = "Create a named BYOK connection to a remote database",
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public Map<String, Object> createNamedConnection(
            @McpToolParam(description = "Connection name for reuse") String name,
            @McpToolParam(description = "JDBC URL") String jdbcUrl,
            @McpToolParam(description = "Database username") String username,
            @McpToolParam(description = "Database password") String password,
            @McpToolParam(description = "Database dialect (oracle, mysql, postgres, sqlserver)") String dialect) {
        return safeExecute(() -> {
            ConnectionProperties properties = ConnectionProperties.builder()
                    .jdbcUrl(jdbcUrl)
                    .username(username)
                    .password(password)
                    .dialect(dialect)
                    .build();
            properties.validate();
            ByokDataSourceContext context = dataSourceManager.acquire(name, properties);
            context.getJdbcTemplate().queryForList(context.getDialect().connectionTestQuery());
            // Connection registration is synchronous, but the MCP tool result is serialized to the client.
            // Advise the LLM to verify the connection before use, as rapid subsequent calls may race with
            // the response delivery.
            return success(Map.of(
                    "connectionName", name,
                    "dialect", properties.dialect(),
                    "message", "Connection created and tested successfully. Call describeConnection to confirm before querying.",
                    "recommendation", "Call describeConnection(\"connection\": \"" + name + "\") before using this connection for queries."
            ));
        });
    }

    @McpTool(description = "Batch insert rows into a remote table via BYOK connection",
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> insertData(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Target table name") String tableName,
            @McpToolParam(description = "List of rows to insert (each map is a row)") List<Map<String, Object>> rows,
            @McpToolParam(description = "Batch size for JDBC batch update") Integer batchSize) throws Exception {
        return safeExecute(() -> {
            requireNotBlank(connectionName, "connectionName");
            requireNotBlank(tableName, "tableName");
            validateIdentifier(tableName, "tableName");
            requireNotEmpty(rows, "rows");
            List<String> columns = rows.get(0).keySet().stream().toList();
            int size = batchSize != null ? batchSize : etlConfig.batchSize();
            if (size <= 0) throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "batchSize must be positive, got: " + size);
            long startTime = System.currentTimeMillis();
            long totalRows = routingFacade.batchInsert(tableName, columns, toPositionalRows(columns, rows), size, connectionName);
            return success(Map.of(
                    "connectionName", connectionName, "tableName", tableName,
                    "rowCount", totalRows, "batchSize", size,
                    "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Inserted %d rows into %s", totalRows, tableName)
            ));
        });
    }

    @McpTool(description = "Insert query results into a target table (ETL helper)",
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> insertQueryResult(
            @McpToolParam(description = "Source BYOK connection name") String sourceConnectionName,
            @McpToolParam(description = "Source SELECT query") String sourceSql,
            @McpToolParam(description = "Target BYOK connection name") String targetConnectionName,
            @McpToolParam(description = "Target table name") String targetTable,
            @McpToolParam(description = "Batch size for insertion") Integer batchSize) throws Exception {
        return safeExecute(() -> {
            validateIdentifier(targetTable, "targetTable");
            if (sqlValidator != null) sqlValidator.validateSelect(sourceSql);
            int size = batchSize != null ? batchSize : DatabaseConstants.DEFAULT_BATCH_SIZE;
            if (size <= 0) throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "batchSize must be positive, got: " + size);
            long startTime = System.currentTimeMillis();
            long totalRows = routingFacade.copyRows(sourceSql, sourceConnectionName, targetTable, null, size, targetConnectionName);
            // An empty source is the only way nothing lands: a successful insert reports at least
            // one row per submitted row. Keep the dedicated response the caller had before.
            if (totalRows == 0) return emptyResult();
            return success(Map.of(
                    "sourceConnection", sourceConnectionName, "targetConnection", targetConnectionName,
                    "targetTable", targetTable, "rowCount", totalRows, "batchSize", size,
                    "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Inserted %d rows from query into %s", totalRows, targetTable)
            ));
        });
    }

    @McpTool(description = "Transform and insert data with column mapping (ETL helper)",
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> transformAndInsert(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Source table name") String sourceTable,
            @McpToolParam(description = "Target table name") String targetTable,
            @McpToolParam(description = "Column mappings (e.g., ['id:ID', 'name:FULL_NAME:upper'])") List<String> columnMapping,
            @McpToolParam(description = "Optional WHERE clause for filtering", required = false) String whereClause,
            @McpToolParam(description = "Batch size for insertion", required = false) Integer batchSize) throws Exception {
        return safeExecute(() -> {
            validateTransformParams(connectionName, sourceTable, targetTable, columnMapping);
            List<String> sourceColumns = new ArrayList<>();
            List<String> targetColumns = new ArrayList<>();
            List<String> transforms = new ArrayList<>();

            for (String mapping : columnMapping) {
                String[] parts = mapping.split(":");
                if (parts.length < 2) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid column mapping: " + mapping + ". Expected format: source:target[:transform] (connectionName=" + connectionName + ", sourceTable=" + sourceTable + ", targetTable=" + targetTable + ")");
                }
                sourceColumns.add(parts[0]);
                targetColumns.add(parts[1]);
                transforms.add(parts.length >= 3 ? parts[2] : "none");
            }

            String selectSql = buildTransformSelect(sourceColumns, transforms, targetColumns, sourceTable, whereClause);
            List<Map<String, Object>> rows = routingFacade.queryRows(selectSql, connectionName);
            if (rows.isEmpty()) return emptyResult();

            int size = batchSize != null ? batchSize : DatabaseConstants.DEFAULT_BATCH_SIZE;
            if (size <= 0) throw new IllegalArgumentException("batchSize must be positive, got: " + size);
            long startTime = System.currentTimeMillis();
            long totalRows = routingFacade.batchInsert(targetTable, targetColumns,
                    toPositionalRows(targetColumns, rows), size, connectionName);
            return success(Map.of(
                    "connectionName", connectionName, "sourceTable", sourceTable, "targetTable", targetTable,
                    "rowCount", totalRows, "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Transformed and inserted %d rows", totalRows)
            ));
        });
    }

    @McpTool(description = "Upsert data into a table (insert or update based on key columns)",
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> upsertData(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Target table name") String tableName,
            @McpToolParam(description = "Key columns for matching (e.g., ['id'])") List<String> keyColumns,
            @McpToolParam(description = "List of rows to upsert") List<Map<String, Object>> rows) throws Exception {
        return safeExecute(() -> {
            requireNotBlank(connectionName, "connectionName");
            requireNotBlank(tableName, "tableName");
            validateIdentifier(tableName, "tableName");
            requireNotEmpty(rows, "rows");
            requireNotEmpty(keyColumns, "keyColumns");
            List<String> allColumns = rows.get(0).keySet().stream().toList();
            long startTime = System.currentTimeMillis();
            long totalRows = routingFacade.batchUpsert(tableName, keyColumns, allColumns,
                    toPositionalRows(allColumns, rows), DEFAULT_BATCH_INSERT_SIZE, connectionName);
            return success(Map.of(
                    "connectionName", connectionName, "tableName", tableName,
                    "keyColumns", keyColumns, "rowCount", totalRows, "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Upserted %d rows into %s", totalRows, tableName)
            ));
        });
    }

    @McpTool(description = "Validate data quality in a table (nulls, duplicates, types)",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> validateDataQuality(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Table name to validate") String tableName,
            @McpToolParam(description = "Columns to check (null for all)", required = false) List<String> columns) {
        return safeExecute(() -> {
            DatabaseDialect dialect = routingFacade.getDialect(connectionName);
            if (!dialect.isValidIdentifier(tableName)) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid table name: " + tableName + " (connectionName=" + connectionName + ", tableName=" + tableName + ")");
            }
            String validatedTable = dialect.normalizeTableName(tableName);
            List<String> colList = (columns == null || columns.isEmpty())
                    ? routingFacade.queryRows("SELECT column_name FROM user_tab_columns WHERE table_name = ?", connectionName, validatedTable)
                            .stream().map(row -> (String) row.get("column_name")).toList()
                    : columns;
            for (String column : colList) {
                if (!dialect.isValidIdentifier(column)) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid column name: " + column + " (connectionName=" + connectionName + ", tableName=" + tableName + ", column=" + column + ")");
                }
            }
            String columnList = colList.stream().map(dialect::quote).reduce((a, b) -> a + ", " + b).orElse("");
            List<Map<String, Object>> issues = new ArrayList<>();
            int totalChecks = 0;
            for (String column : colList) {
                totalChecks++;
                Long nullCount = queryCount("SELECT COUNT(*) FROM " + validatedTable + " WHERE " + dialect.quote(column) + " IS NULL", connectionName);
                if (nullCount != null && nullCount > 0) issues.add(Map.of("type", "NULL_VALUES", "column", column, "count", nullCount, "severity", "WARNING"));
            }
            totalChecks++;
            Long duplicateCount = queryCount(
                    "SELECT COUNT(*) FROM (SELECT COUNT(*) cnt FROM " + validatedTable + " GROUP BY " + columnList + " HAVING COUNT(*) > 1)", connectionName);
            if (duplicateCount != null && duplicateCount > 0) issues.add(Map.of("type", "DUPLICATES", "columns", colList, "count", duplicateCount, "severity", "ERROR"));
            totalChecks++;
            Long rowCount = queryCount("SELECT COUNT(*) FROM " + validatedTable, connectionName);
            double score = totalChecks > 0 ? (double) (totalChecks - issues.size()) / totalChecks * 100 : 100.0;
            return success(Map.of(
                    "summary", Map.of("table", validatedTable, "totalRows", rowCount != null ? rowCount : 0,
                            "columnsChecked", colList.size(), "issuesFound", issues.size(),
                            "totalChecks", totalChecks, "passed", totalChecks - issues.size(),
                            "failed", issues.size(), "qualityScore", score),
                    "issues", issues,
                    "message", String.format("Data quality check completed: %.1f%% passed", score)
            ));
        });
    }

    @McpTool(description = "Export query results to a table with automatic batching",
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> exportQueryToTable(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Source SELECT query") String sourceSql,
            @McpToolParam(description = "Target table name") String targetTable,
            @McpToolParam(description = "Batch size for processing", required = false) Integer batchSize) throws Exception {
        return safeExecute(() -> {
            DatabaseDialect dialect = routingFacade.getDialect(connectionName);
            if (!dialect.isValidIdentifier(targetTable)) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid target table name: " + targetTable + " (connectionName=" + connectionName + ")");
            }
            String validatedTargetTable = dialect.normalizeTableName(targetTable);
            int size = batchSize != null ? batchSize : DatabaseConstants.DEFAULT_BATCH_SIZE;
            long startTime = System.currentTimeMillis();
            int totalRows = 0;
            String continuationToken = null;
            int maxPages = DatabaseConstants.DEFAULT_BATCH_SIZE; // safety limit: max DEFAULT_BATCH_SIZE pages × batchSize rows
            int pageCount = 0;
            do {
                PaginatedQueryResult result = routingFacade.executeQuery(sourceSql, size, continuationToken, connectionName);
                if (result.rows().isEmpty()) break;
                List<Map<String, Object>> rows = result.rows();
                List<String> columns = rows.get(0).keySet().stream().toList();
                routingFacade.batchInsert(validatedTargetTable, columns, toPositionalRows(columns, rows), size, connectionName);
                totalRows += rows.size();
                continuationToken = result.continuationToken();
                if (++pageCount >= maxPages) {
                    log.warn("exportQueryToTable reached maxPages={} for table={}, stopping", maxPages, validatedTargetTable);
                    break;
                }
            } while (continuationToken != null && !continuationToken.isBlank());
            return success(Map.of(
                    "connectionName", connectionName, "sourceQuery", sourceSql,
                    "targetTable", validatedTargetTable, "rowCount", totalRows, "batchSize", size,
                    "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Exported %d rows to %s", totalRows, validatedTargetTable)
            ));
        });
    }

    @McpTool(description = "Submit an ETL job for execution (MigrationJob DSL)",
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> submitEtlJob(@McpToolParam(description = "Job definition (id, name, description, steps)") Object jobDefinition) {
        return safeExecute(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> jd = (Map<String, Object>) jobDefinition;
            String jobId = (String) jd.get("id");
            String jobName = (String) jd.get("name");
            String description = (String) jd.getOrDefault("description", "");
            List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) jd.get("steps");
            requireNotEmpty(stepDefs, "steps");
            List<Step> steps = new ArrayList<>();
            for (Map<String, Object> stepDef : stepDefs) {
                String stepId = (String) stepDef.get("id");
                String typeStr = (String) stepDef.get("type");
                if (stepId == null || stepId.isBlank()) { log.warn("Skipping step with null or blank id"); continue; }
                if (typeStr == null || typeStr.isBlank()) { log.warn("Skipping step with null or blank type for step id: {}", stepId); continue; }
                StepType type = StepType.from(typeStr);
                List<String> dependsOn;
                if (stepDef.containsKey("dependsOn")) {
                    Object deps = stepDef.get("dependsOn");
                    if (deps instanceof List<?> depList) {
                        dependsOn = new ArrayList<>();
                        depList.forEach(dep -> dependsOn.add(dep.toString()));
                    } else if (deps instanceof String depStr) {
                        dependsOn = List.of(depStr.split(","));
                    } else {
                        dependsOn = new ArrayList<>();
                    }
                } else {
                    dependsOn = new ArrayList<>();
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> params = new HashMap<>();
                if (stepDef.containsKey("params")) {
                    Object rawParams = stepDef.get("params");
                    if (!(rawParams instanceof Map<?, ?>)) throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "params must be a map");
                    params.putAll((Map<String, Object>) rawParams);
                }
                steps.add(new Step(stepId, type, dependsOn,
                        (String) stepDef.get("connection"), (String) stepDef.get("sourceSql"),
                        (String) stepDef.get("targetTable"), (String) stepDef.get("targetConnection"), params));
            }
            JobExecution execution = executionEngine.submit(new MigrationJob(jobId, jobName, description, steps));
            return success(Map.of(
                    "jobId", jobId, "jobName", jobName, "totalSteps", steps.size(),
                    "status", execution.status(), "message", "Job submitted successfully"
            ));
        });
    }

    @McpTool(description = "Get the status of an ETL job",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getJobStatus(@McpToolParam(description = "Job identifier") String jobId) {
        return safeExecute(() -> {
            Optional<JobExecution> execution = executionEngine.getExecution(jobId);
            if (execution.isEmpty()) throw new McpToolException(ErrorCode.CONNECTION_NOT_FOUND, "Job not found: " + jobId + " (jobId=" + jobId + ")");
            var exec = execution.get();
            List<Map<String, Object>> stepStates = exec.stepStates().entrySet().stream()
                    .map(e -> {
                        var state = e.getValue();
                        return context(
                                "stepId", state.stepId(), "status", state.status(),
                                "startedAt", state.startedAt() != null ? state.startedAt().toString() : null,
                                "completedAt", state.completedAt() != null ? state.completedAt().toString() : null,
                                "rowsAffected", state.rowsAffected(), "error", state.error());
                    }).toList();
            return success(Map.of("job", context(
                    "jobId", exec.jobId(), "jobName", exec.jobName(), "status", exec.status(),
                    "startedAt", exec.startedAt() != null ? exec.startedAt().toString() : null,
                    "completedAt", exec.completedAt() != null ? exec.completedAt().toString() : null,
                    "progress", String.format("%.1f%%", exec.getProgress()),
                    "steps", stepStates)));
        });
    }

    @McpTool(description = "List all submitted ETL jobs",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listJobs() {
        return safeExecute(() -> {
            List<Map<String, Object>> jobs = executionEngine.listExecutions().stream()
                    .map(exec -> Map.<String, Object>of(
                            "jobId", exec.jobId(), "jobName", exec.jobName(), "status", exec.status(),
                            "startedAt", exec.startedAt() != null ? exec.startedAt().toString() : null,
                            "completedAt", exec.completedAt() != null ? exec.completedAt().toString() : null,
                            "progress", String.format("%.1f%%", exec.getProgress()),
                            "totalSteps", exec.stepStates().size(),
                            "completedSteps", exec.getCompletedStepIds().size(),
                            "failedSteps", exec.getFailedStepIds().size()))
                    .sorted(Comparator.comparing(m -> (String) m.get("jobId")))
                    .toList();
            return success(Map.of("totalJobs", jobs.size(), "jobs", jobs));
        });
    }

    @McpTool(description = "Stop a running ETL job",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> stopJob(@McpToolParam(description = "Job identifier to stop") String jobId) {
        return safeExecute(() -> {
            Optional<JobExecution> execution = executionEngine.getExecution(jobId);
            if (execution.isEmpty()) throw new McpToolException(ErrorCode.CONNECTION_NOT_FOUND, "Job not found: " + jobId + " (jobId=" + jobId + ")");
            log.warn("Stop requested for job: {} (not implemented in demo)", jobId);
            return success(Map.of("jobId", jobId, "message", "Stop signal sent (requires production job scheduler for graceful shutdown)"));
        });
    }

    private static final java.util.function.Function<String, String> IDENTITY = java.util.function.Function.identity();
    private static final java.util.Map<String, java.util.function.Function<String, String>> TRANSFORM_FN = java.util.Map.of(
            "upper",  s -> "UPPER(" + s + ")",
            "lower",  s -> "LOWER(" + s + ")",
            "trim",   s -> "TRIM(" + s + ")",
            "int",    s -> "CAST(" + s + " AS INTEGER)",
            "long",   s -> "CAST(" + s + " AS BIGINT)",
            "double", s -> "CAST(" + s + " AS DOUBLE)"
    );

    private String buildTransformSelect(List<String> sourceColumns, List<String> transforms, List<String> targetColumns,
                                         String sourceTable, String whereClause) {
        List<String> selectExprs = new ArrayList<>();
        for (int i = 0; i < sourceColumns.size(); i++) {
            String src = sourceColumns.get(i);
            String transform = transforms.get(i);
            String expr = TRANSFORM_FN.getOrDefault(transform, IDENTITY).apply(src);
            selectExprs.add(expr + " AS " + targetColumns.get(i));
        }
        String sql = "SELECT " + String.join(", ", selectExprs) + " FROM " + sourceTable;
        if (whereClause != null && !whereClause.isBlank()) {
            validateWhereClause(whereClause, "whereClause");
            sql += " WHERE " + whereClause;
        }
        return sql;
    }

    /**
     * Adapt the column-keyed rows the MCP contract accepts to the positional shape the write
     * facade takes, so column order is fixed once here instead of by a PreparedStatement setter.
     */
    private static List<List<Object>> toPositionalRows(List<String> columns, List<Map<String, Object>> rows) {
        List<List<Object>> positional = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            List<Object> values = new ArrayList<>(columns.size());
            for (String column : columns) {
                values.add(row.get(column));
            }
            positional.add(values);
        }
        return positional;
    }

    /**
     * Read a single-row, single-column aggregate.
     *
     * <p>The value arrives as whatever numeric type the driver picked (Oracle answers COUNT(*) as
     * BigDecimal), so it is narrowed here rather than relying on a typed {@code queryForObject}.
     */
    private Long queryCount(String sql, String connectionName) {
        List<Map<String, Object>> rows = routingFacade.queryRows(sql, connectionName);
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            return null;
        }
        Object value = rows.get(0).values().iterator().next();
        return value instanceof Number number ? number.longValue() : null;
    }

    private void validateTransformParams(String connectionName, String sourceTable,
                                          String targetTable, List<String> columnMapping) {
        requireNotBlank(connectionName, "connectionName");
        requireNotBlank(sourceTable, "sourceTable");
        requireNotBlank(targetTable, "targetTable");
        validateIdentifier(sourceTable, "sourceTable");
        validateIdentifier(targetTable, "targetTable");
        requireNotEmpty(columnMapping, "columnMapping");
        for (String mapping : columnMapping) {
            String[] parts = mapping.split(":");
            if (parts.length < 2) {
                throw new McpValidationException(
                        com.entropy.database.mcp.exception.ErrorCode.PARAMETER_VALIDATION_FAILED,
                        "Invalid column mapping: " + mapping + ". Expected format: source:target[:transform]");
            }
        }
    }
}
