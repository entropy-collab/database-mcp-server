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
package com.entropy.database.mcp.facade;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.stream.SseStreamManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Routing facade that delegates to BYOK datasources only.
 * All connections are equal; there is no default connection.
 */
@Service
public class RoutingDatabaseFacade implements DatabaseOperations {

    private static final Logger log = LoggerFactory.getLogger(RoutingDatabaseFacade.class);

    private final DynamicDataSourceManager dynamicDataSourceManager;

    public RoutingDatabaseFacade(DynamicDataSourceManager dynamicDataSourceManager) {
        this.dynamicDataSourceManager = dynamicDataSourceManager;
    }

    // ─── Helper ────────────────────────────────────────────────────────────

    private ByokDataSourceContext resolveContext(String connection) {
        if (connection == null || connection.isBlank()) {
            Collection<String> keys = dynamicDataSourceManager.listConnectionKeys();
            if (keys.size() == 1) {
                connection = keys.iterator().next();
            } else if (keys.isEmpty()) {
                throw new IllegalArgumentException(buildConnectionRequiredMessage());
            } else {
                throw new IllegalArgumentException(buildConnectionRequiredMessage());
            }
        }
        try {
            return dynamicDataSourceManager.acquire(connection);
        } catch (IllegalArgumentException e) {
            // Preserve the original error message and append available connections
            String originalMsg = e.getMessage();
            Collection<String> registered = dynamicDataSourceManager.listConnectionKeys();
            String tip;
            if (registered.isEmpty()) {
                tip = "No connections registered. Call createNamedConnection first.";
            } else {
                String connectionList = registered.stream()
                        .map(name -> "  - " + name)
                        .collect(Collectors.joining("\n"));
                tip = String.format("\nAvailable connections:\n%s\nUse one of these names.", connectionList);
            }
            throw new IllegalArgumentException(originalMsg + tip, e);
        }
    }

    private String buildConnectionRequiredMessage() {
        Collection<String> registered = dynamicDataSourceManager.listConnectionKeys();
        if (registered.isEmpty()) {
            return """
                    Connection is required but not provided.
                    No connections are registered yet.
                    To get started:
                      1. Call createNamedConnection with: name, jdbcUrl, username, password, dialect
                      2. Then pass the connection name to this tool.
                    For help, call prompt("database-quick-start").""";
        }
        // Format connections as a clear list for the LLM
        String connectionList = registered.stream()
                .map(name -> "  - " + name)
                .collect(Collectors.joining("\n"));
        return """
                Connection is required but not provided.
                Available connections:
                %s
                You MUST pass one of these connection names to the tool.
                Example: pass connection="fcs_analyst_v2" to use the connection above.
                """.formatted(connectionList);
    }

    private String buildConnectionNotFoundMessage(String connection) {
        Collection<String> registered = dynamicDataSourceManager.listConnectionKeys();
        String tip;
        if (registered.isEmpty()) {
            tip = "No connections registered. Call createNamedConnection first.";
        } else {
            String connectionList = registered.stream()
                    .map(name -> "  - " + name)
                    .collect(Collectors.joining("\n"));
            tip = String.format("Available connections:\n%s\nUse one of these names instead.", connectionList);
        }
        return "Connection not found: " + connection + ". " + tip;
    }

    private ByokDatabaseFacade resolveFacade(String connection) {
        return new ByokDatabaseFacade(resolveContext(connection));
    }

    // ─── Read Operations ───────────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> listTables(String schema, String connection) {
        return resolveFacade(connection).listTables(schema, connection);
    }

    @Override
    public List<Map<String, Object>> searchTables(String keyword, String connection) {
        return resolveFacade(connection).searchTables(keyword, connection);
    }

    @Override
    public List<String> listSchemas(String connection) {
        return resolveFacade(connection).listSchemas(connection);
    }

    @Override
    public Map<String, Object> describeTable(String table, String schema, String connection) {
        return resolveFacade(connection).describeTable(table, schema, connection);
    }

    @Override
    public List<Map<String, Object>> listIndexes(String table, String schema, String connection) {
        return resolveFacade(connection).listIndexes(table, schema, connection);
    }

    @Override
    public List<Map<String, Object>> listViews(String schema, String connection) {
        return resolveFacade(connection).listViews(schema, connection);
    }

    @Override
    public List<Map<String, Object>> listSequences(String schema, String connection) {
        return resolveFacade(connection).listSequences(schema, connection);
    }

    @Override
    public PaginatedQueryResult executeQuery(
            String sql, int maxRows, String continuationToken, String connection) {
        return resolveFacade(connection).executeQuery(sql, maxRows, continuationToken, connection);
    }

    @Override
    public PaginatedQueryResult executeQueryWithSse(String sql, int maxRows, String continuationToken,
                                                    SseStreamManager.QueryExecutor<PaginatedQueryResult> executor, String connection) {
        return resolveFacade(connection).executeQueryWithSse(sql, maxRows, continuationToken, executor, connection);
    }

    @Override
    public List<Map<String, Object>> executeNamedQuery(
            String sql, Map<String, Object> params, String connection) {
        return resolveFacade(connection).executeNamedQuery(sql, params, connection);
    }

    @Override
    public Map<String, Object> getDatabaseInfo(String connection) {
        return resolveFacade(connection).getDatabaseInfo(connection);
    }

    // ─── Execution Plan ────────────────────────────────────────────────────

    @Override
    public com.entropy.database.mcp.domain.PlanAnalysis explainPlan(String sql, String connection) {
        return resolveFacade(connection).explainPlan(sql, connection);
    }

    // ─── Write Operations ──────────────────────────────────────────────────

    @Override
    public Map<String, Object> executeDdl(String sql, String connection) {
        return resolveFacade(connection).executeDdl(sql, connection);
    }

    // ─── Metadata Operations ────────────────────────────────────────────────

    @Override
    public Map<String, Object> backupSchema(String tableName, String connection) {
        return resolveFacade(connection).backupSchema(tableName, connection);
    }

    @Override
    public Map<String, Object> backupData(String tableName, int maxRows, String connection) {
        return resolveFacade(connection).backupData(tableName, maxRows, connection);
    }

    @Override
    public Map<String, Object> diffSchema(String sourceTable, String targetTable, String connection) {
        return resolveFacade(connection).diffSchema(sourceTable, targetTable, connection);
    }

    @Override
    public void clearCache(String connection) {
        resolveFacade(connection).clearCache(connection);
    }

    // ─── Statistics ────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> getStatistics(String connection) {
        return resolveFacade(connection).getStatistics(connection);
    }
}
