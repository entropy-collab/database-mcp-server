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
import com.entropy.database.mcp.exception.DatabaseMcpException;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.security.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Federated query gateway for cross-database queries.
 * Supports querying multiple databases with different dialects.
 */
@Component
@ConditionalOnProperty(name = "entropy.mcp.gateway.enabled", havingValue = "true")
public class FederatedQueryGateway {

    private static final Logger log = LoggerFactory.getLogger(FederatedQueryGateway.class);

    private final Map<String, JdbcTemplate> databaseClients = new ConcurrentHashMap<>();
    private final Map<String, DataSource> dataSourceMap = new ConcurrentHashMap<>();
    private final DialectResolver dialectResolver;
    private final SqlValidator sqlValidator;

    public FederatedQueryGateway(List<DataSource> dataSources,
                                  DialectResolver dialectResolver,
                                  SqlValidator sqlValidator) {
        this.dialectResolver = dialectResolver;
        this.sqlValidator = sqlValidator;
        // Initialize with configured data sources
        if (dataSources != null) {
            dataSources.forEach(ds -> {
                String clientId = getClientId(ds);
                databaseClients.put(clientId, new JdbcTemplate(ds));
                dataSourceMap.put(clientId, ds);
                log.info("Registered federated client: {}", clientId);
            });
        }
    }

    /**
     * Register a database client manually.
     */
    public void registerClient(String clientId, DataSource dataSource) {
        if (clientId == null || clientId.isBlank()) {
            throw new DatabaseMcpException(ErrorCode.DB_CONNECTION_FAILED, "Client ID cannot be null or blank");
        }
        databaseClients.put(clientId, new JdbcTemplate(dataSource));
        dataSourceMap.put(clientId, dataSource);
        log.info("Registered federated client: {}", clientId);
    }

    /**
     * Unregister a database client.
     */
    public void unregisterClient(String clientId) {
        databaseClients.remove(clientId);
        dataSourceMap.remove(clientId);
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
            throw new DatabaseMcpException(ErrorCode.DB_NOT_FOUND, "Unknown database: " + databaseId);
        }

        // Validate SQL
        try {
            sqlValidator.validateSelect(sql);
        } catch (Exception e) {
            throw new DatabaseMcpException(ErrorCode.SQL_VALIDATION_FAILED, e.getMessage());
        }

        // Apply dialect-specific SQL adaptation
        DatabaseDialect dialect = dialectResolver.resolve(detectDialectName(template.getDataSource()), template.getDataSource());
        int limit = maxRows != null ? maxRows : 100;
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
                        "error", e.getMessage()
                    ));
                }
            }))
            .collect(Collectors.toList());

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
            .collect(Collectors.toList());
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
            Map<String, Object> info = new HashMap<>();
            info.put("id", databaseId);
            info.put("productName", meta.getDatabaseProductName());
            info.put("productVersion", meta.getDatabaseProductVersion());
            info.put("url", meta.getURL());
            info.put("user", meta.getUserName());
            info.put("jdbcUrl", meta.getURL());
            info.put("status", "connected");
            return info;
        } catch (Exception e) {
            log.warn("Failed to get info for database {}: {}", databaseId, e.getMessage(), e);
            return Map.of("id", databaseId, "status", "error", "error", e.getMessage());
        }
    }

    /**
     * Execute different queries on different databases.
     */
    public Map<String, Object> executeSelectiveQuery(Map<String, String> databaseQueries) {
        Map<String, Object> results = new ConcurrentHashMap<>();
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = databaseQueries.entrySet().stream()
            .map(entry -> CompletableFuture.runAsync(() -> {
                String dbId = entry.getKey();
                String sql = entry.getValue();
                try {
                    List<Map<String, Object>> rows = executeQuery(dbId, sql, null, null);
                    results.put(dbId, Map.of(
                        "status", "success",
                        "rowCount", rows.size(),
                        "data", rows
                    ));
                } catch (Exception e) {
                    log.warn("Failed to query database {}", dbId, e);
                    results.put(dbId, Map.of(
                        "status", "error",
                        "error", e.getMessage()
                    ));
                }
            }))
            .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        results.put("executionTimeMs", System.currentTimeMillis() - startTime);
        return results;
    }

    /**
     * Get client count.
     */
    public int getClientCount() {
        return databaseClients.size();
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private String getClientId(DataSource ds) {
        try (var conn = ds.getConnection()) {
            var meta = conn.getMetaData();
            String url = meta.getURL();
            String user = meta.getUserName();

            // Extract meaningful ID from URL
            if (url.contains("@")) {
                String hostPart = url.substring(url.lastIndexOf("@") + 1);
                String dbPart = "";
                if (hostPart.contains("/")) {
                    dbPart = hostPart.substring(hostPart.indexOf("/") + 1);
                    hostPart = hostPart.substring(0, hostPart.indexOf("/"));
                }
                if (hostPart.contains(":")) {
                    hostPart = hostPart.substring(0, hostPart.indexOf(":"));
                }
                // Include username to make ID unique for different users on same host
                if (user != null && !user.isBlank()) {
                    return hostPart + "_" + user.toLowerCase();
                }
                return hostPart + (dbPart.isBlank() ? "" : "_" + dbPart);
            }
            return meta.getDatabaseProductName().toLowerCase() + "_" + (user != null ? user.toLowerCase() : "unknown");
        } catch (Exception e) {
            return "unknown-" + System.identityHashCode(ds);
        }
    }

    private String detectDialectName(DataSource ds) {
        try (var conn = ds.getConnection()) {
            var url = conn.getMetaData().getURL();
            if (url.contains("oracle")) return "oracle";
            if (url.contains("postgres")) return "postgres";
            if (url.contains("mysql")) return "mysql";
            return "generic";
        } catch (Exception e) {
            log.warn("Failed to detect dialect for datasource, defaulting to generic", e);
            return "generic";
        }
    }
}
