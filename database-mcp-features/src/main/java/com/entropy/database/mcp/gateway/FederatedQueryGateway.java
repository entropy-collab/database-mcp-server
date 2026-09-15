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
package com.entropy.database.mcp.gateway;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.exception.McpFederatedException;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.properties.QueryConfig;
import com.entropy.database.mcp.properties.ThreadPoolProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Federated query gateway - enables cross-database queries.
 * Supports dynamic client registration, remote JNDI lookup, and multi-database execution.
 *
 * <p>Every parallel fan-out runs on this gateway's own {@link #executorService}. Handing the work
 * to {@code CompletableFuture.runAsync} without an executor would put blocking JDBC calls on
 * {@link java.util.concurrent.ForkJoinPool#commonPool()}, which is sized for CPU-bound work and
 * shared with every parallel stream in the process — a slow remote database would then stall
 * unrelated work such as the catalog package's {@code parallelStream()} usage.
 *
 * <p><b>有界队列 + 有超时的等待</b>：这两点是一起的。之前的池是
 * {@code Executors.newFixedThreadPool}，自带无界 {@code LinkedBlockingQueue}，而 fan-out 用
 * {@code join()} 无限等——于是过载时任务只排队不拒绝，MCP 请求线程挂在 {@code join()} 上直到最慢的库
 * 返回。{@code RegisteredClient} 的 statement 级 {@code queryTimeout} 管不到这里：驱动可能在建连、
 * 取结果集阶段卡住，那不是 statement 超时能覆盖的。现在队列显式有界 + {@code AbortPolicy}（过载立即
 * 失败，把背压交给调用方，而不是攒一队迟早都会超时的任务），等待带超时（超时的库按<b>单库失败</b>
 * 记进结果，与 {@link #collectResult} 的语义一致，而不是整个 fan-out 抛错）。
 *
 * <p><b>注册表有界</b>：{@code databaseClients} 的键全部来自调用方（{@code clientId}），每个 entry
 * 持一个 {@link DataSource} 和两个 {@link JdbcTemplate}，此前只有显式 {@code unregisterClient} 才移除。
 * 换成有上限 + {@code expireAfterAccess} 的 Caffeine，与 {@code BackupMetadataRepository} /
 * {@code JobExecutionEngine} 一致。这里能静默驱逐是因为注册本身是<b>缓存语义</b>而不是业务注册：被驱逐
 * 的库下次查询报 {@code REMOTE_DATABASE_NOT_FOUND}，重新注册即可恢复；驱逐不关闭 {@link DataSource}，
 * 因为它是调用方传进来的、生命周期不在本类。
 */
@Component
public class FederatedQueryGateway implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FederatedQueryGateway.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    /**
     * 注册表上限与空闲保留期。键来自调用方，每个 entry 持一个 DataSource + 两个 JdbcTemplate，
     * 所以必须有界；2 小时未被访问的注册视为调用方已经不用了。
     */
    private static final int MAX_REGISTERED_CLIENTS = 200;
    private static final Duration CLIENT_RETENTION = Duration.ofHours(2);

    /**
     * 队列深度 = 线程数 × 此系数。队列存在的意义只是吸收瞬时抖动：fan-out 是同步请求的一部分，
     * 排太久的任务等到被执行时调用方早已超时，所以宁可浅队列 + 立即拒绝。
     */
    private static final int QUEUE_DEPTH_PER_WORKER = 4;

    /**
     * fan-out 等待超时相对 statement 超时的宽限秒数：statement 超时只约束「SQL 执行」，建连、
     * 取结果集、驱动内部重试都在它之外，所以整体等待必须比它宽一点，否则会把本来能返回的库判成超时。
     */
    private static final int FAN_OUT_GRACE_SECONDS = 5;

    /** {@code queryTimeoutSeconds} 未配置（<= 0）时的兜底等待上限。 */
    private static final int DEFAULT_FAN_OUT_TIMEOUT_SECONDS = 35;

    private final DialectResolver dialectResolver;
    private final SqlValidator sqlValidator;
    private final QueryConfig queryConfig;
    private final int fanOutTimeoutSeconds;

    private final ConcurrentMap<String, RegisteredClient> databaseClients = Caffeine.newBuilder()
            .maximumSize(MAX_REGISTERED_CLIENTS)
            .expireAfterAccess(CLIENT_RETENTION)
            .<String, RegisteredClient>build()
            .asMap();
    /**
     * Dialect per registered client. Detection costs a physical connection
     * ({@link #detectDialectName} calls {@code DataSource#getConnection}), so it must not happen
     * per query. Populated on first successful detection only: a failed probe returns a GENERIC
     * fallback that is deliberately not cached, so a database that was merely unreachable is
     * re-detected later instead of being pinned to the wrong dialect for the process lifetime.
     *
     * <p>与 {@link #databaseClients} 同样有界：键同源，留着一份无界索引等于把上面的上限白加。
     */
    private final ConcurrentMap<String, DatabaseDialect> dialectCache = Caffeine.newBuilder()
            .maximumSize(MAX_REGISTERED_CLIENTS)
            .expireAfterAccess(CLIENT_RETENTION)
            .<String, DatabaseDialect>build()
            .asMap();
    private final ExecutorService executorService;

    // 两个构造器并存时 Spring 无法自行挑选（报 "No default constructor found"），
    // 必须显式指出注入用的是哪一个。
    @org.springframework.beans.factory.annotation.Autowired
    public FederatedQueryGateway(DialectResolver dialectResolver, SqlValidator sqlValidator, QueryConfig queryConfig,
                                 ThreadPoolProperties threadPoolProperties) {
        this(dialectResolver, sqlValidator, queryConfig, threadPoolProperties,
                queryConfig.queryTimeoutSeconds() > 0
                        ? queryConfig.queryTimeoutSeconds() + FAN_OUT_GRACE_SECONDS
                        : DEFAULT_FAN_OUT_TIMEOUT_SECONDS);
    }

    /**
     * 测试接缝：超时值本来由 {@link QueryConfig#queryTimeoutSeconds()} 推导（没有为 gateway 单独加配置项，
     * 因为 statement 超时和整体等待本就该联动，两个各自可调的值只会让它们互相矛盾），但「超时后返回部分
     * 结果」这条行为不该靠让测试真等几十秒来验证。
     */
    FederatedQueryGateway(DialectResolver dialectResolver, SqlValidator sqlValidator, QueryConfig queryConfig,
                          ThreadPoolProperties threadPoolProperties, int fanOutTimeoutSeconds) {
        this.dialectResolver = dialectResolver;
        this.sqlValidator = sqlValidator;
        this.queryConfig = queryConfig;
        this.fanOutTimeoutSeconds = fanOutTimeoutSeconds;
        // Deliberately not min(_, availableProcessors()): these workers block on remote JDBC, so
        // CPU count is the wrong bound. On a 2-vCPU container the old formula turned an N-database
        // fan-out into two-at-a-time, which is the opposite of what the fan-out is for.
        int size = (threadPoolProperties != null ? threadPoolProperties : ThreadPoolProperties.defaults())
                .federatedQuerySize();
        int queueCapacity = size * QUEUE_DEPTH_PER_WORKER;
        this.executorService = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "mcp-federated");
                    thread.setDaemon(true);
                    return thread;
                },
                // 过载时立即抛 RejectedExecutionException，由 executeFederatedQuery 翻成
                // FEDERATED_GATEWAY_UNAVAILABLE。CallerRunsPolicy 被否决：它会让 MCP 请求线程自己去跑
                // 阻塞 JDBC，等于把「池满」变成「请求线程也满」，正是这次要消掉的那种挂死。
                new ThreadPoolExecutor.AbortPolicy());
        log.info("Federated query pool: size={}, queueCapacity={}, fanOutTimeoutSeconds={}",
                size, queueCapacity, fanOutTimeoutSeconds);
    }

    /**
     * Register a database client manually.
     */
    public void registerClient(String clientId, DataSource dataSource) {
        if (clientId == null || clientId.isBlank()) {
            throw new McpFederatedException(ErrorCode.CONNECTION_FAILED, "Client ID cannot be null or blank");
        }
        if (dataSource == null) {
            throw new McpFederatedException(ErrorCode.CONNECTION_FAILED,
                    "DataSource cannot be null (clientId=" + clientId + ")");
        }
        databaseClients.put(clientId, RegisteredClient.of(dataSource, queryConfig.queryTimeoutSeconds()));
        dialectCache.remove(clientId);
        log.info("Registered federated client: {}", clientId);
    }

    /**
     * Unregister a database client.
     */
    public void unregisterClient(String clientId) {
        databaseClients.remove(clientId);
        dialectCache.remove(clientId);
        log.info("Unregistered federated client: {}", clientId);
    }

    /**
     * Execute a query against a specific database.
     *
     * <p>{@code params} is a <em>named</em> parameter map: the SQL must use {@code :name}
     * placeholders and binding goes through {@link NamedParameterJdbcTemplate}. It used to be
     * flattened with {@code params.values().toArray()} and bound positionally, which silently
     * mis-bound every query whose map iteration order did not happen to match the order of the
     * {@code ?} placeholders — for a {@code HashMap} that order is a hash artefact. A {@code ?}
     * style statement now fails loudly on the unset placeholder instead of reading the wrong rows.
     */
    public List<Map<String, Object>> executeQuery(String databaseId, String sql,
                                                   Integer maxRows,
                                                   Map<String, Object> params) {
        RegisteredClient client = databaseClients.get(databaseId);
        if (client == null) {
            throw new McpFederatedException(ErrorCode.REMOTE_DATABASE_NOT_FOUND, "Unknown database: " + databaseId);
        }

        // Validate SQL
        try {
            sqlValidator.validateSelect(sql);
        } catch (Exception e) {
            throw new McpValidationException(ErrorCode.SQL_VALIDATION_FAILED, "SQL validation failed", e);
        }

        // Apply dialect-specific SQL adaptation
        DatabaseDialect dialect = dialectFor(databaseId, client);
        int limit = maxRows != null ? maxRows : queryConfig.maxRows();
        String adaptedSql = dialect.applyLimit(sql, limit, 0);

        log.debug("Executing query on {}: {}", databaseId, adaptedSql);

        if (params == null || params.isEmpty()) {
            return client.jdbc().queryForList(adaptedSql);
        }
        return client.named().queryForList(adaptedSql, params);
    }

    /**
     * Execute federated query across multiple databases.
     *
     * <p>返回的 {@code results} 可能是<b>部分</b>结果：超时或过载的库以 {@code status=error} 出现，
     * {@code successCount} 只数成功的那些。
     */
    public Map<String, Object> executeFederatedQuery(String query,
                                                      List<String> databases,
                                                      Integer maxRows) {
        Map<String, Object> results = new ConcurrentHashMap<>();
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures =
                submitAll(databases, dbId -> collectResult(results, dbId, query, maxRows));
        awaitFanOut(futures, results, databases);

        return Map.of(
            "databases", databases,
            "results", results,
            "executionTimeMs", System.currentTimeMillis() - startTime,
            "successCount", countSuccesses(results)
        );
    }

    /**
     * List all registered databases.
     */
    public List<Map<String, Object>> listDatabases() {
        return databaseClients.keySet().stream()
            .map(this::getDatabaseInfo)
            .toList();
    }

    /**
     * Get database connection info.
     */
    public Map<String, Object> getDatabaseInfo(String databaseId) {
        RegisteredClient client = databaseClients.get(databaseId);
        if (client == null) {
            return Map.of("id", databaseId, "status", "not_found");
        }

        try (var conn = client.dataSource().getConnection()) {
            var meta = conn.getMetaData();
            return Map.of(
                "id", databaseId,
                "status", "connected",
                "databaseProductName", meta.getDatabaseProductName(),
                "databaseProductVersion", meta.getDatabaseProductVersion(),
                "driverName", meta.getDriverName(),
                "url", meta.getURL()
            );
        } catch (Exception e) {
            return Map.of("id", databaseId, "status", "error", "error", "Connection test failed");
        }
    }

    /**
     * Get query statistics.
     *
     * <p>{@code executorPoolSize} describes the pool the fan-out methods actually submit to, which
     * only became true once they stopped defaulting to the common ForkJoinPool. {@code executorQueued}
     * 与 {@code fanOutTimeoutSeconds} 一起报出来，才能在过载被拒时看出是「队列满了」还是「等超时了」。
     */
    public Map<String, Object> getQueryStats() {
        return Map.of(
            "registeredClients", databaseClients.size(),
            "availableDatabases", databaseClients.keySet().size(),
            "executorPoolSize", executorService instanceof ThreadPoolExecutor pool
                    ? String.valueOf(pool.getCorePoolSize()) : "unknown",
            "executorQueued", executorService instanceof ThreadPoolExecutor pool
                    ? String.valueOf(pool.getQueue().size()) : "unknown",
            "fanOutTimeoutSeconds", fanOutTimeoutSeconds
        );
    }

    /**
     * Check if a specific database is available.
     */
    public boolean isDatabaseAvailable(String databaseId) {
        RegisteredClient client = databaseClients.get(databaseId);
        if (client == null) return false;

        try (var conn = client.dataSource().getConnection()) {
            return conn.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolve the dialect for a client, detecting it at most once per registration.
     */
    private DatabaseDialect dialectFor(String databaseId, RegisteredClient client) {
        DatabaseDialect cached = dialectCache.get(databaseId);
        if (cached != null) {
            return cached;
        }
        String dialectName = detectDialectName(client.dataSource());
        DatabaseDialect dialect = dialectResolver.resolve(
                dialectName != null ? dialectName : "GENERIC", client.dataSource());
        if (dialectName != null) {
            dialectCache.put(databaseId, dialect);
        }
        return dialect;
    }

    /**
     * Get dialect name from datasource, or {@code null} when the probe failed and the answer is
     * therefore not worth caching.
     */
    private String detectDialectName(DataSource dataSource) {
        try (var conn = dataSource.getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            if (productName.contains("oracle")) return "ORACLE";
            if (productName.contains("mysql")) return "MYSQL";
            if (productName.contains("postgresql")) return "POSTGRES";
            if (productName.contains("sql server")) return "SQLSERVER";
            if (productName.contains("h2")) return "H2";
            return "GENERIC";
        } catch (Exception e) {
            log.warn("Dialect detection failed, falling back to GENERIC: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Execute different queries on different databases in parallel.
     *
     * <p>与 {@link #executeFederatedQuery} 一样可能返回部分结果。
     */
    public Map<String, Object> executeSelectiveQuery(Map<String, String> databaseQueries) {
        Map<String, Object> results = new ConcurrentHashMap<>();
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = submitAll(databaseQueries.keySet(),
                dbId -> collectResult(results, dbId, databaseQueries.get(dbId), queryConfig.maxRows()));
        awaitFanOut(futures, results, databaseQueries.keySet());

        return Map.of(
            "queries", databaseQueries,
            "results", results,
            "executionTimeMs", System.currentTimeMillis() - startTime,
            "successCount", countSuccesses(results)
        );
    }

    /**
     * 把每个目标库的任务提交到 {@link #executorService}。
     *
     * <p>队列满时 {@code AbortPolicy} 会在提交处就抛 {@link RejectedExecutionException}，这里翻成
     * {@code FEDERATED_GATEWAY_UNAVAILABLE} 并取消已提交的任务：过载时立刻告诉调用方「现在别问」，
     * 比让它等一个必然超时的答案有用。已经在跑的任务取消不了（见 {@link #awaitFanOut}），但它们写入的
     * {@code results} 已经没人读，仅浪费一次远端查询。
     */
    private List<CompletableFuture<Void>> submitAll(Collection<String> targets, Consumer<String> task) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(targets.size());
        for (String dbId : targets) {
            try {
                futures.add(CompletableFuture.runAsync(() -> task.accept(dbId), executorService));
            } catch (RejectedExecutionException e) {
                futures.forEach(future -> future.cancel(false));
                throw new McpFederatedException(ErrorCode.FEDERATED_GATEWAY_UNAVAILABLE,
                        "Federated query pool is saturated, refusing to queue more work "
                                + "(pending=" + futures.size() + ", target=" + dbId + ")", e);
            }
        }
        return futures;
    }

    /**
     * 等所有分支跑完，最多等 {@link #fanOutTimeoutSeconds} 秒。
     *
     * <p>超时不抛错：fan-out 的语义本就是「能拿到几个库的结果就返回几个」（见 {@link #collectResult}），
     * 让整次调用失败会把已经拿到的结果一起丢掉，对 LLM 调用方尤其浪费。没回来的库补一条
     * {@code status=error} 记录，形状与单库失败完全一致，调用方不需要为超时单开一条分支。
     *
     * <p>{@code cancel(true)} 不会中断已在执行的 JDBC 调用（{@code CompletableFuture} 只是让 future 进入
     * 取消态），真正的止损靠 {@code RegisteredClient} 的 statement 超时；这里取消的意义是让还在队列里
     * 没起跑的任务不必再跑。
     */
    private void awaitFanOut(List<CompletableFuture<Void>> futures, Map<String, Object> results,
                             Collection<String> targets) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(fanOutTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            futures.forEach(future -> future.cancel(true));
            markMissingAsTimedOut(results, targets);
            log.warn("Federated fan-out timed out after {}s; returning partial results for {} of {} databases",
                    fanOutTimeoutSeconds, results.size(), targets.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures.forEach(future -> future.cancel(true));
            markMissingAsTimedOut(results, targets);
        } catch (ExecutionException e) {
            // collectResult 自己吞掉每个库的异常，所以走到这里说明是记录结果本身出了问题（例如 OOM）。
            // 仍然返回已收集到的部分，理由同上。
            log.warn("Federated fan-out failed while collecting results", e);
        }
    }

    private void markMissingAsTimedOut(Map<String, Object> results, Collection<String> targets) {
        for (String dbId : targets) {
            results.putIfAbsent(dbId, Map.of(
                    "status", "error",
                    "error", "Query timed out on database " + dbId
                            + " after " + fanOutTimeoutSeconds + "s"
            ));
        }
    }

    /**
     * Run one query and record its outcome. A failure is reported per database rather than
     * aborting the whole fan-out; the detail stays in the log because the message may echo SQL.
     */
    private void collectResult(Map<String, Object> results, String dbId, String sql, Integer maxRows) {
        try {
            List<Map<String, Object>> rows = executeQuery(dbId, sql, maxRows, null);
            results.put(dbId, Map.of(
                "status", "success",
                "rowCount", rows.size(),
                "data", rows
            ));
        } catch (Exception e) {
            log.warn("Failed to query database {}", dbId, e);
            results.put(dbId, Map.of(
                "status", "error",
                "error", "Query failed on database " + dbId
            ));
        }
    }

    private long countSuccesses(Map<String, Object> results) {
        return results.values().stream()
                .filter(value -> value instanceof Map<?, ?> m && "success".equals(m.get("status")))
                .count();
    }

    /**
     * Get the number of registered database clients.
     */
    public int getClientCount() {
        return databaseClients.size();
    }

    /**
     * Shutdown the gateway and release resources.
     */
    public void shutdown() {
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("ExecutorService did not terminate gracefully within 30 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for executorService shutdown");
        }
        databaseClients.clear();
        dialectCache.clear();
        log.info("FederatedQueryGateway shut down");
    }

    @Override
    public void destroy() {
        shutdown();
    }

    /**
     * One registered federated database: the positional and named templates over a single
     * {@link DataSource}, created once at registration.
     *
     * <p>Replaces the previous pair of parallel maps (a {@code JdbcTemplate} map plus a
     * write-only {@code DataSource} map that nothing ever read), which had to be kept in sync
     * under a lock to avoid a half-registered client.
     */
    private record RegisteredClient(DataSource dataSource, JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {

        /**
         * @param queryTimeoutSeconds statement ceiling for this client. A federated fan-out waits on
         *                            every database it targets, so an unbounded remote statement
         *                            holds a gateway thread — and the request thread joining on it —
         *                            until the driver gives up.
         */
        static RegisteredClient of(DataSource dataSource, int queryTimeoutSeconds) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            if (queryTimeoutSeconds > 0) {
                jdbc.setQueryTimeout(queryTimeoutSeconds);
            }
            return new RegisteredClient(dataSource, jdbc, new NamedParameterJdbcTemplate(jdbc));
        }
    }
}
