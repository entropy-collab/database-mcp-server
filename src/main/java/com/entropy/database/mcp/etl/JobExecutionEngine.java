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
package com.entropy.database.mcp.etl;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.config.EtlConfig;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Execution engine for ETL MigrationJob.
 * <p>
 * Features:
 * - Dependency-aware step execution (topological sort)
 * - Idempotency via checkpoint/resume
 * - Cross-connection data movement
 * - Detailed execution tracking
 * </p>
 */
public class JobExecutionEngine implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionEngine.class);

    private final DynamicDataSourceManager dataSourceManager;

    // Active job executions (in-memory for demo; in production use a persistent store)
    private final Map<String, JobExecution> executions = new ConcurrentHashMap<>();

    private final TaskExecutor taskExecutor;
    private final EtlConfig etlConfig;
    private final McpMetricsCollector metricsCollector;

    public JobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                              McpMetricsCollector metricsCollector) {
        this(dataSourceManager, metricsCollector, new EtlConfig(4, 1000), null);
    }

    public JobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                              McpMetricsCollector metricsCollector,
                              EtlConfig etlConfig,
                              TaskExecutor taskExecutor) {
        this.dataSourceManager = dataSourceManager;
        this.etlConfig = etlConfig;
        this.metricsCollector = metricsCollector;
        this.taskExecutor = taskExecutor;
    }

    public JobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                              ExecutorService executor,
                              McpMetricsCollector metricsCollector) {
        this(dataSourceManager, metricsCollector, null, executor::submit);
    }

    // ─── Job Lifecycle ───────────────────────────────────────────────────────

    /**
     * Submit a new job for execution.
     *
     * @param job the job definition
     * @return the created JobExecution
     */
    public JobExecution submit(MigrationJob job) {
        job.validate();

        JobExecution execution = JobExecution.create(job.id(), job.name(), job.steps());
        executions.put(job.id(), execution);

        log.info("Submitted job: {} ({} steps)", job.id(), job.steps().size());
        if (metricsCollector != null) {
            metricsCollector.recordEtlJobSubmitted();
        }

        // Execute asynchronously using thread pool
        taskExecutor.execute(() -> execute(job, execution));

        return execution;
    }

    @Override
    public void destroy() {
        // Thread pool lifecycle managed by Spring's ThreadPoolTaskExecutor
        log.info("JobExecutionEngine shutting down");
    }

    /**
     * Get the status of a job execution.
     */
    public Optional<JobExecution> getExecution(String jobId) {
        return Optional.ofNullable(executions.get(jobId));
    }

    /**
     * List all job executions.
     */
    public Collection<JobExecution> listExecutions() {
        return Collections.unmodifiableCollection(executions.values());
    }

    // ─── Execution Engine ───────────────────────────────────────────────────

    /**
     * Execute a job with dependency resolution.
     */
    private void execute(MigrationJob job, JobExecution execution) {
        boolean success = false;
        try {
            execution = new JobExecution(
                    execution.jobId(),
                    execution.jobName(),
                    JobStatus.RUNNING,
                    execution.startedAt(),
                    null,
                    new ConcurrentHashMap<>(execution.stepStates())
            );
            executions.put(job.id(), execution);

            // Topological sort of steps
            List<Step> sortedSteps = topologicalSort(job.steps());

            // Execute steps in order
            for (Step step : sortedSteps) {
                // Check if any dependency failed
                List<String> failedDeps = getFailedDependencies(step, execution);
                if (!failedDeps.isEmpty()) {
                    log.warn("Skipping step {} due to failed dependencies: {}", step.id(), failedDeps);
                    updateStepState(execution, step.id(),
                            StepExecutionState.skipped(step.id()));
                    continue;
                }

                // Check idempotency: skip already completed steps
                StepExecutionState existingState = execution.stepStates().get(step.id());
                if (existingState != null && existingState.status() == StepStatus.COMPLETED) {
                    log.info("Skipping already completed step: {}", step.id());
                    continue;
                }

                // Execute step
                executeStep(job, step, execution);
            }

            // Determine final status
            JobStatus finalStatus = execution.getFailedStepIds().isEmpty()
                    ? JobStatus.COMPLETED
                    : JobStatus.FAILED;

            execution = new JobExecution(
                    execution.jobId(),
                    execution.jobName(),
                    finalStatus,
                    execution.startedAt(),
                    Instant.now(),
                    new ConcurrentHashMap<>(execution.stepStates())
            );
            executions.put(job.id(), execution);

            success = finalStatus == JobStatus.COMPLETED;
            log.info("Job {} completed with status: {}", job.id(), finalStatus);

        } catch (Exception e) {
            success = false;
            log.error("Job execution failed: {}", job.id(), e);
            execution = new JobExecution(
                    execution.jobId(),
                    execution.jobName(),
                    JobStatus.FAILED,
                    execution.startedAt(),
                    Instant.now(),
                    new ConcurrentHashMap<>(execution.stepStates())
            );
            executions.put(job.id(), execution);
        } finally {
            if (metricsCollector != null) {
                metricsCollector.recordEtlJobCompleted(success);
            }
        }
    }

    /**
     * Execute a single step using the appropriate StepHandler.
     */
    private void executeStep(MigrationJob job, Step step, JobExecution execution) {
        updateStepState(execution, step.id(), StepExecutionState.running(step.id()));

        log.info("Executing step: {} (type: {})", step.id(), step.type());

        try {
            ByokDataSourceContext sourceContext = resolveConnection(step.connection());
            ByokDataSourceContext targetContext = resolveConnection(
                    step.targetConnection() != null ? step.targetConnection() : step.connection()
            );

            StepHandler stepHandler = findHandler(step.type());
            long rowsAffected = stepHandler.execute(sourceContext, targetContext, step, this);

            updateStepState(execution, step.id(), StepExecutionState.completed(step.id(), rowsAffected));
            log.info("Step {} completed: {} rows affected", step.id(), rowsAffected);

        } catch (Exception e) {
            log.error("Step {} failed", step.id(), e);
            updateStepState(execution, step.id(), StepExecutionState.failed(step.id(), e.getMessage()));
        }
    }

    private StepHandler findHandler(StepType type) {
        return switch (type) {
            case QUERY_TO_TABLE -> new QueryToTableStepHandler();
            case TRANSFORM -> new TransformStepHandler();
            case DDL -> new DdlStepHandler();
            case UPSERT -> new UpsertStepHandler();
            case QUERY_TO_JSON -> new QueryToJsonStepHandler();
            case READ -> new ReadStepHandler();
            case EXPORT -> new ExportStepHandler();
        };
    }

    // ─── Step Executors (exposed as protected for StepHandler reuse) ─────────

    protected long executeQueryToTable(ByokDataSourceContext source, ByokDataSourceContext target,
                                       Step step) {
        JdbcTemplate sourceJdbc = source.getJdbcTemplate();
        JdbcTemplate targetJdbc = target.getJdbcTemplate();
        DatabaseDialect dialect = target.getDialect();

        String targetTable = dialect.normalizeTableName(step.targetTable());
        List<Map<String, Object>> rows = sourceJdbc.queryForList(step.sourceSql());
        if (rows.isEmpty()) return 0;

        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        String columnList = columns.stream().map(dialect::quote).collect(Collectors.joining(", "));
        String placeholderList = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String insertSql = "INSERT INTO " + targetTable + " (" + columnList + ") VALUES (" + placeholderList + ")";

        int batchSize = getIntParam(step, "batchSize", etlConfig != null ? etlConfig.batchSize() : 1000);
        int[][] updateCounts = targetJdbc.batchUpdate(insertSql, rows, batchSize, (ps, row) -> {
            for (int i = 0; i < columns.size(); i++) {
                ps.setObject(i + 1, row.get(columns.get(i)));
            }
        });

        return Arrays.stream(updateCounts).flatMapToInt(Arrays::stream).sum();
    }

    protected long executeTransform(ByokDataSourceContext source, ByokDataSourceContext target,
                                    Step step) {
        List<String> columnMapping = getListParam(step, "columnMapping", List.of());
        String whereClause = getStringParam(step, "whereClause", null);

        JdbcTemplate jdbcTemplate = source.getJdbcTemplate();
        DatabaseDialect dialect = source.getDialect();

        List<String> sourceColumns = new ArrayList<>();
        List<String> targetColumns = new ArrayList<>();
        List<String> transforms = new ArrayList<>();

        for (String mapping : columnMapping) {
            String[] parts = mapping.split(":");
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
            selectExprs.add(expr + " AS " + dialect.quote(targetColumns.get(i)));
        }
        selectSql.append(String.join(", ", selectExprs));
        selectSql.append(" FROM ").append(dialect.normalizeTableName(step.sourceSql()));
        if (whereClause != null && !whereClause.isBlank()) {
            selectSql.append(" WHERE ").append(whereClause);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql.toString());
        if (rows.isEmpty()) return 0;

        String targetTable = dialect.normalizeTableName(step.targetTable());
        String columnList = targetColumns.stream().map(dialect::quote).collect(Collectors.joining(", "));
        String placeholderList = targetColumns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String insertSql = "INSERT INTO " + targetTable + " (" + columnList + ") VALUES (" + placeholderList + ")";

        int batchSize = getIntParam(step, "batchSize", 1000);
        int[][] updateCounts = jdbcTemplate.batchUpdate(insertSql, rows, batchSize, (ps, row) -> {
            for (int i = 0; i < targetColumns.size(); i++) {
                ps.setObject(i + 1, row.get(targetColumns.get(i)));
            }
        });

        return Arrays.stream(updateCounts).flatMapToInt(Arrays::stream).sum();
    }

    protected long executeDdl(ByokDataSourceContext context, Step step) {
        JdbcTemplate jdbcTemplate = context.getJdbcTemplate();
        List<String> statements = getListParam(step, "statements", List.of());

        int totalAffected = 0;
        for (String ddl : statements) {
            totalAffected += jdbcTemplate.update(ddl);
        }
        return totalAffected;
    }

    protected long executeUpsert(ByokDataSourceContext context, ByokDataSourceContext target,
                                 Step step) {
        // Delegates to dialect-specific buildUpsertSql via UpsertStepHandler
        return executeQueryToTable(context, target, step);
    }

    protected long executeQueryToJson(ByokDataSourceContext context, Step step) {
        List<Map<String, Object>> rows = context.getJdbcTemplate().queryForList(step.sourceSql());
        log.info("Query to JSON: {} rows", rows.size());
        return rows.size();
    }

    protected long executeRead(ByokDataSourceContext context, Step step) {
        List<Map<String, Object>> rows = context.getJdbcTemplate().queryForList(step.sourceSql());
        log.info("Read step: {} rows", rows.size());
        return rows.size();
    }

    protected long executeExport(ByokDataSourceContext context, Step step) {
        log.info("Export step: {}", step.id());
        return 0;
    }

    // ─── Helper Methods ─────────────────────────────────────────────────────

    private ByokDataSourceContext resolveConnection(String connectionName) {
        if (connectionName == null || connectionName.isBlank()) {
            throw new IllegalArgumentException("Connection name is required");
        }
        return dataSourceManager.acquire(connectionName);
    }

    private List<Step> topologicalSort(List<Step> steps) {
        Map<String, Step> stepMap = steps.stream()
                .collect(Collectors.toMap(Step::id, s -> s));

        List<Step> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (Step step : steps) {
            if (!visited.contains(step.id())) {
                visit(step, stepMap, visited, visiting, result);
            }
        }

        return result;
    }

    private void visit(Step step, Map<String, Step> stepMap, Set<String> visited,
                       Set<String> visiting, List<Step> result) {
        if (visiting.contains(step.id())) {
            throw new IllegalStateException("Circular dependency detected at step: " + step.id());
        }
        if (visited.contains(step.id())) return;

        visiting.add(step.id());

        for (String dep : step.dependsOn()) {
            Step depStep = stepMap.get(dep);
            if (depStep != null) {
                visit(depStep, stepMap, visited, visiting, result);
            }
        }

        visiting.remove(step.id());
        visited.add(step.id());
        result.add(step);
    }

    private List<String> getFailedDependencies(Step step, JobExecution execution) {
        return step.dependsOn().stream()
                .filter(depId -> {
                    StepExecutionState depState = execution.stepStates().get(depId);
                    return depState != null && depState.status() == StepStatus.FAILED;
                })
                .toList();
    }

    private void updateStepState(JobExecution execution, String stepId, StepExecutionState newState) {
        Map<String, StepExecutionState> newStates = new ConcurrentHashMap<>(execution.stepStates());
        newStates.put(stepId, newState);
        execution = new JobExecution(
                execution.jobId(),
                execution.jobName(),
                execution.status(),
                execution.startedAt(),
                execution.completedAt(),
                newStates
        );
        executions.put(execution.jobId(), execution);
    }

    protected int getIntParam(Step step, String key, int defaultValue) {
        Object value = step.params().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        return defaultValue;
    }

    protected List<String> getListParam(Step step, String key, List<String> defaultValue) {
        Object value = step.params().get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (value instanceof String s) {
            return List.of(s.split(","));
        }
        return defaultValue;
    }

    protected String getStringParam(Step step, String key, String defaultValue) {
        Object value = step.params().get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
