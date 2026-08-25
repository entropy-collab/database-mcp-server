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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;

/**
 * Federated query gateway - enables cross-database queries.
 * Supports dynamic client registration, remote JNDI lookup, and multi-database execution.
 *
 * <p>Every parallel fan-out runs on this gateway's own {@link #executorService}. Handing the work
 * to {@code CompletableFuture.runAsync} without an executor would put blocking JDBC calls on
 * {@link java.util.concurrent.ForkJoinPool#commonPool()}, which is sized for CPU-bound work and
 * shared with every parallel stream in the process — a slow remote database would then stall
 * unrelated work such as the catalog package's {@code parallelStream()} usage.
 */
@Component
public class FederatedQueryGateway implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FederatedQueryGateway.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final int MAX_QUERY_THREADS = 10;

    private final DialectResolver dialectResolver;
    private final SqlValidator sqlValidator;
    private final QueryConfig queryConfig;

    private final Map<String, RegisteredClient> databaseClients = new ConcurrentHashMap<>();
    /**
     * Dialect per registered client. Detection costs a physical connection
     * ({@link #detectDialectName} calls {@code DataSource#getConnection}), so it must not happen
     * per query. Populated on first successful detection only: a failed probe returns a GENERIC
     * fallback that is deliberately not cached, so a database that was merely unreachable is
     * re-detected later instead of being pinned to the wrong dialect for the process lifetime.
     */
    private final Map<String, DatabaseDialect> dialectCache = new ConcurrentHashMap<>();
    private final ExecutorService executorService;

    public FederatedQueryGateway(DialectResolver dialectResolver, SqlValidator sqlValidator, QueryConfig queryConfig) {
        this.dialectResolver = dialectResolver;
        this.sqlValidator = sqlValidator;
        this.queryConfig = queryConfig;
        this.executorService = Executors.newFixedThreadPool(
                Math.min(MAX_QUERY_THREADS, Runtime.getRuntime().availableProcessors()));
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
        databaseClients.put(clientId, RegisteredClient.of(dataSource));
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
     */
    public Map<String, Object> executeFederatedQuery(String query,
                                                      List<String> databases,
                                                      Integer maxRows) {
        Map<String, Object> results = new ConcurrentHashMap<>();
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = databases.stream()
            .map(dbId -> CompletableFuture.runAsync(
                    () -> collectResult(results, dbId, query, maxRows), executorService))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

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
     * only became true once they stopped defaulting to the common ForkJoinPool.
     */
    public Map<String, Object> getQueryStats() {
        return Map.of(
            "registeredClients", databaseClients.size(),
            "availableDatabases", databaseClients.keySet().size(),
            "executorPoolSize", executorService instanceof ThreadPoolExecutor pool
                    ? String.valueOf(pool.getCorePoolSize()) : "unknown"
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
     */
    public Map<String, Object> executeSelectiveQuery(Map<String, String> databaseQueries) {
        Map<String, Object> results = new ConcurrentHashMap<>();
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = databaseQueries.entrySet().stream()
            .map(entry -> CompletableFuture.runAsync(
                    () -> collectResult(results, entry.getKey(), entry.getValue(), queryConfig.maxRows()),
                    executorService))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return Map.of(
            "queries", databaseQueries,
            "results", results,
            "executionTimeMs", System.currentTimeMillis() - startTime,
            "successCount", countSuccesses(results)
        );
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

        static RegisteredClient of(DataSource dataSource) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            return new RegisteredClient(dataSource, jdbc, new NamedParameterJdbcTemplate(jdbc));
        }
    }
}
