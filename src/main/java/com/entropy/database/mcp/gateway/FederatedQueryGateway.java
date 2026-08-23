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
import com.entropy.database.mcp.config.QueryConfig;
import com.entropy.database.mcp.tools.McpToolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;

/**
 * Federated query gateway - enables cross-database queries.
 * Supports dynamic client registration, remote JNDI lookup, and multi-database execution.
 */
@Component
public class FederatedQueryGateway implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FederatedQueryGateway.class);
    private static final int MAX_CONCURRENT_QUERY_THREADS = 10;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final DialectResolver dialectResolver;
    private final SqlValidator sqlValidator;
    private final QueryConfig queryConfig;

    private final Map<String, JdbcTemplate> databaseClients = new ConcurrentHashMap<>();
    private final Map<String, DataSource> dataSourceMap = new ConcurrentHashMap<>();
    private final ExecutorService executorService;

    public FederatedQueryGateway(DialectResolver dialectResolver, SqlValidator sqlValidator, QueryConfig queryConfig) {
        this.dialectResolver = dialectResolver;
        this.sqlValidator = sqlValidator;
        this.queryConfig = queryConfig;
        this.executorService = Executors.newFixedThreadPool(Math.min(10, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Register a database client manually.
     * Both maps are updated atomically via a local lock to prevent
     * a state where one map has the entry but the other does not.
     */
    private final Object regLock = new Object();

    public void registerClient(String clientId, DataSource dataSource) {
        if (clientId == null || clientId.isBlank()) {
            throw new McpFederatedException(ErrorCode.CONNECTION_FAILED, "Client ID cannot be null or blank");
        }
        synchronized (regLock) {
            databaseClients.put(clientId, new JdbcTemplate(dataSource));
            dataSourceMap.put(clientId, dataSource);
        }
        log.info("Registered federated client: {}", clientId);
    }

    /**
     * Unregister a database client.
     */
    public void unregisterClient(String clientId) {
        synchronized (regLock) {
            databaseClients.remove(clientId);
            dataSourceMap.remove(clientId);
        }
        log.info("Unregistered federated client: {}", clientId);
    }

    /**
     * Execute a query against a specific database.
     */
    public List<Map<String, Object>> executeQuery(String databaseId, String sql,
                                                   Integer maxRows,
                                                   Map<String, Object> params) {
        JdbcTemplate template = databaseClients.get(databaseId);
        if (template == null) {
            throw new McpFederatedException(ErrorCode.REMOTE_DATABASE_NOT_FOUND, "Unknown database: " + databaseId);
        }

        // Validate SQL
        try {
            sqlValidator.validateSelect(sql);
        } catch (Exception e) {
            throw new McpValidationException(ErrorCode.SQL_VALIDATION_FAILED, "SQL validation failed", e);
        }

        // Apply dialect-specific SQL adaptation
        DatabaseDialect dialect = dialectResolver.resolve(detectDialectName(template.getDataSource()), template.getDataSource());
        int limit = maxRows != null ? maxRows : queryConfig.maxRows();
        String adaptedSql = dialect.applyLimit(sql, limit, 0);

        log.debug("Executing query on {}: {}", databaseId, adaptedSql);

        if (params == null || params.isEmpty()) {
            return template.queryForList(adaptedSql);
        }

        Object[] paramArray = params.values().toArray();
        return template.queryForList(adaptedSql, paramArray);
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
            .map(dbId -> CompletableFuture.runAsync(() -> {
                try {
                    List<Map<String, Object>> rows = executeQuery(dbId, query, maxRows, null);
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
            }))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return Map.of(
            "databases", databases,
            "results", results,
            "executionTimeMs", System.currentTimeMillis() - startTime,
            "successCount", results.entrySet().stream()
                .filter(e -> "success".equals(((Map<?, ?>) e.getValue()).get("status")))
                .count()
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
        JdbcTemplate template = databaseClients.get(databaseId);
        if (template == null) {
            return Map.of("id", databaseId, "status", "not_found");
        }

        try (var conn = template.getDataSource().getConnection()) {
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
     */
    public Map<String, Object> getQueryStats() {
        return Map.of(
            "registeredClients", databaseClients.size(),
            "availableDatabases", databaseClients.keySet().size(),
            "executorPoolSize", executorService instanceof ThreadPoolExecutor pool ? String.valueOf(pool.getCorePoolSize()) : "unknown"
        );
    }

    /**
     * Check if a specific database is available.
     */
    public boolean isDatabaseAvailable(String databaseId) {
        JdbcTemplate template = databaseClients.get(databaseId);
        if (template == null) return false;

        try (var conn = template.getDataSource().getConnection()) {
            return conn.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get dialect name from datasource.
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
            return "GENERIC";
        }
    }

    /**
     * Execute different queries on different databases in parallel.
     */
    public Map<String, Object> executeSelectiveQuery(Map<String, String> databaseQueries) {
        Map<String, Object> results = new ConcurrentHashMap<>();
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = databaseQueries.entrySet().stream()
            .map(entry -> CompletableFuture.runAsync(() -> {
                String dbId = entry.getKey();
                String sql = entry.getValue();
                try {
                    List<Map<String, Object>> rows = executeQuery(dbId, sql, queryConfig.maxRows(), null);
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
            }))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return Map.of(
            "queries", databaseQueries,
            "results", results,
            "executionTimeMs", System.currentTimeMillis() - startTime,
            "successCount", results.entrySet().stream()
                .filter(e -> "success".equals(((Map<?, ?>) e.getValue()).get("status")))
                .count()
        );
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
        dataSourceMap.clear();
        log.info("FederatedQueryGateway shut down");
    }

    @Override
    public void destroy() {
        shutdown();
    }
}
