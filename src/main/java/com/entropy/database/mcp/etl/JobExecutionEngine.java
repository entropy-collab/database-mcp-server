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
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.properties.EtlConfig;
import com.entropy.database.mcp.security.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
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
    private static final int DEFAULT_ETL_THREADS = 4;
    private static final int DEFAULT_BATCH_SIZE = 1000;

    /**
     * Ceiling on how many source rows one step may read.
     *
     * <p>ETL is the highest-volume path in this server and {@code SqlValidator.validateSelect}
     * only checks syntax, so without a ceiling a single {@code SELECT * FROM big_table} decides
     * the heap budget. Overridable per step via the {@code maxSourceRows} param, or process-wide
     * through {@link #JobExecutionEngine(DynamicDataSourceManager, McpMetricsCollector, EtlConfig,
     * TaskExecutor, SqlValidator, int)}.
     */
    public static final int DEFAULT_MAX_SOURCE_ROWS = 1_000_000;

    /** Single-quoted literals in a driver message: the part most likely to carry row data. */
    private static final Pattern SQL_LITERAL = Pattern.compile("'[^']*'");

    /** Upper bound on the error text stored per step and handed to the LLM. */
    private static final int MAX_STEP_ERROR_LENGTH = 500;

    private final DynamicDataSourceManager dataSourceManager;
    private final SqlValidator sqlValidator;

    // Job executions live in process memory only — they are lost on restart. Bounded so a long
    // running server cannot accumulate executions (each one retains its full step state) until
    // it runs out of heap.
    private static final int MAX_TRACKED_EXECUTIONS = 500;
    private static final Duration EXECUTION_RETENTION = Duration.ofHours(24);
    private final Map<String, JobExecution> executions = com.github.benmanes.caffeine.cache.Caffeine
            .newBuilder()
            .maximumSize(MAX_TRACKED_EXECUTIONS)
            .expireAfterWrite(EXECUTION_RETENTION)
            .<String, JobExecution>build()
            .asMap();

    private final TaskExecutor taskExecutor;
    private final EtlConfig etlConfig;
    private final McpMetricsCollector metricsCollector;
    private final int maxSourceRows;

    /**
     * One handler instance per step type, built once.
     *
     * <p>{@link StepHandler} implementations are stateless and are not Spring beans, so they are
     * instantiated here rather than injected — but only once per engine instead of once per step,
     * which is what the previous {@code switch}-with-{@code new} did.
     */
    private final Map<StepType, StepHandler> handlers;

    public JobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                              McpMetricsCollector metricsCollector) {
        this(dataSourceManager, metricsCollector, new EtlConfig(DEFAULT_ETL_THREADS, DEFAULT_BATCH_SIZE), null);
    }

    public JobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                              McpMetricsCollector metricsCollector,
                              EtlConfig etlConfig,
                              TaskExecutor taskExecutor) {
        this(dataSourceManager, metricsCollector, etlConfig, taskExecutor, null);
    }

    public JobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                              McpMetricsCollector metricsCollector,
                              EtlConfig etlConfig,
                              TaskExecutor taskExecutor,
                              SqlValidator sqlValidator) {
        this(dataSourceManager, metricsCollector, etlConfig, taskExecutor, sqlValidator,
                DEFAULT_MAX_SOURCE_ROWS);
    }

    public JobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                              McpMetricsCollector metricsCollector,
                              EtlConfig etlConfig,
                              TaskExecutor taskExecutor,
                              SqlValidator sqlValidator,
                              int maxSourceRows) {
        this.dataSourceManager = dataSourceManager;
        this.sqlValidator = sqlValidator;
        this.etlConfig = etlConfig;
        this.metricsCollector = metricsCollector;
        this.taskExecutor = taskExecutor;
        this.maxSourceRows = maxSourceRows > 0 ? maxSourceRows : DEFAULT_MAX_SOURCE_ROWS;
        this.handlers = buildHandlers();
    }

    public JobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                              ExecutorService executor,
                              McpMetricsCollector metricsCollector) {
        this(dataSourceManager, metricsCollector, null, executor::submit);
    }

    private static Map<StepType, StepHandler> buildHandlers() {
        List<StepHandler> all = List.of(
                new QueryToTableStepHandler(),
                new TransformStepHandler(),
                new DdlStepHandler(),
                new UpsertStepHandler(),
                new QueryToJsonStepHandler(),
                new ReadStepHandler(),
                new ExportStepHandler());
        Map<StepType, StepHandler> byType = new EnumMap<>(StepType.class);
        for (StepHandler handler : all) {
            StepHandler previous = byType.put(handler.supports(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two handlers claim step type " + handler.supports()
                        + ": " + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        return Collections.unmodifiableMap(byType);
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
                // A dependency that did not complete means this step's input does not exist
                List<String> blockedDeps = blockingDependencies(step, execution);
                if (!blockedDeps.isEmpty()) {
                    log.warn("Skipping step {}: dependencies did not complete: {}", step.id(), blockedDeps);
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
            // Keep the real reason: getJobStatus hands state.error() straight to the LLM, and a
            // fixed "执行失败" left the caller with nothing to act on. Redacted and truncated,
            // because driver messages quote the failing statement and its literals.
            updateStepState(execution, step.id(),
                    StepExecutionState.failed(step.id(), describeFailure(e, step)));
        }
    }

    /**
     * Render a step failure as a short, redacted, actionable string.
     *
     * <p>Uses the root cause — the wrapper is usually {@code DataAccessException}, the cause is
     * what actually went wrong — and strips single-quoted literals, which is where row data shows
     * up in driver messages, plus the step's own SQL if the message echoes it.
     */
    private String describeFailure(Throwable failure, Step step) {
        Throwable root = rootCause(failure);
        String message = root.getMessage() == null ? "" : root.getMessage();
        message = redact(message, step.sourceSql());
        String description = root.getClass().getSimpleName() + (message.isBlank() ? "" : ": " + message);
        description = description.replaceAll("\\s+", " ").trim();
        return description.length() > MAX_STEP_ERROR_LENGTH
                ? description.substring(0, MAX_STEP_ERROR_LENGTH) + "...(truncated)"
                : description;
    }

    private String redact(String message, String sourceSql) {
        String redacted = message;
        if (sourceSql != null && !sourceSql.isBlank()) {
            redacted = redacted.replace(sourceSql, "[sql omitted]");
        }
        return SQL_LITERAL.matcher(redacted).replaceAll("'?'");
    }

    private Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private StepHandler findHandler(StepType type) {
        StepHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("No StepHandler registered for step type " + type);
        }
        return handler;
    }

    // ─── Step execution support (used by StepHandler implementations) ─────────

    /**
     * Validate a step's source SQL when a validator is configured.
     *
     * <p>Handlers call this before reading: they run the SQL a caller supplied, and the engine owns
     * the validator. A null validator means validation is disabled for this engine instance.
     */
    public void validateSourceSql(String sql) {
        if (sqlValidator != null && sql != null && !sql.isBlank()) {
            sqlValidator.validateSelect(sql);
        }
    }

    /**
     * Rows a step may read from its source, {@code maxSourceRows} param overriding the engine-wide
     * ceiling.
     */
    public int maxSourceRows(Step step) {
        return getIntParam(step, "maxSourceRows", maxSourceRows);
    }

    /**
     * Rows per batch when writing to a target, {@code batchSize} param overriding the configured
     * default.
     */
    public int batchSize(Step step) {
        return getIntParam(step, "batchSize", etlConfig != null ? etlConfig.batchSize() : DEFAULT_BATCH_SIZE);
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

    /**
     * Dependencies of {@code step} that did not complete, and therefore block it.
     *
     * <p>Only {@link StepStatus#COMPLETED} clears a dependency. Looking at {@code FAILED} alone
     * let a failure escape one hop: A fails, B (depending on A) is marked {@code SKIPPED}, and C
     * (depending on B) saw no {@code FAILED} dependency and ran anyway — a {@code QUERY_TO_TABLE}
     * step then wrote whatever the never-produced source yielded into the target table, and
     * {@code getFailedStepIds()} showed nothing about C. {@code PENDING}/{@code RUNNING} block for
     * the same reason: the input is not there yet.
     *
     * <p>A dependency id with no recorded state is not treated as blocking: job validation rejects
     * unknown dependency ids, so this can only be a step outside this execution.
     */
    private List<String> blockingDependencies(Step step, JobExecution execution) {
        return step.dependsOn().stream()
                .filter(depId -> {
                    StepExecutionState depState = execution.stepStates().get(depId);
                    return depState != null && depState.status() != StepStatus.COMPLETED;
                })
                .toList();
    }

    /**
     * Record a step's new state on the live execution.
     *
     * <p>Mutates {@code execution.stepStates()} in place rather than rebuilding the
     * {@link JobExecution}. The previous copy-on-write version reassigned its own parameter, so
     * the caller's {@code execution} reference kept the stale map — {@code getFailedStepIds()} in
     * {@link #execute} then saw no failures and reported a job with failed steps as COMPLETED.
     */
    private void updateStepState(JobExecution execution, String stepId, StepExecutionState newState) {
        execution.stepStates().put(stepId, newState);
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
