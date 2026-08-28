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

import com.entropy.database.mcp.backup.DatabaseBackupService;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Routing facade that delegates to BYOK datasources only.
 * All connections are equal; there is no default connection.
 */
@Service
public class RoutingDatabaseFacade implements DatabaseOperations {

    private static final Logger log = LoggerFactory.getLogger(RoutingDatabaseFacade.class);

    private final DynamicDataSourceManager dynamicDataSourceManager;
    private final DatabaseBackupService backupService;

    /**
     * Per-connection facades, keyed by the resolved connection key.
     *
     * <p>A facade is a thin, immutable wrapper around a context, but it used to be re-allocated on
     * every single delegated call. Entries are replaced as soon as {@code acquire} hands back a
     * different context object, so a rebuilt pool is never served by a facade pointing at the old
     * one, and the map never grows beyond the number of registered connection names. Pool lifetime
     * is unaffected: pools are closed by the manager's lease-eviction listener, not by
     * reachability from here.
     */
    private final ConcurrentHashMap<String, ByokDatabaseFacade> facades = new ConcurrentHashMap<>();

    /**
     * @param backupService injected lazily because it resolves connections through the same
     *                      {@link DynamicDataSourceManager} this facade uses; the proxy keeps the
     *                      two service beans from constraining each other's initialisation order.
     */
    public RoutingDatabaseFacade(DynamicDataSourceManager dynamicDataSourceManager,
                                 @Lazy DatabaseBackupService backupService) {
        this.dynamicDataSourceManager = dynamicDataSourceManager;
        this.backupService = backupService;
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
        ByokDataSourceContext context = resolveContext(connection);
        return facades.compute(context.getKey(), (key, cached) ->
                cached != null && cached.wraps(context) ? cached : new ByokDatabaseFacade(context));
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
    public List<Map<String, Object>> executeNamedQuery(
            String sql, Map<String, Object> params, String connection) {
        return resolveFacade(connection).executeNamedQuery(sql, params, connection);
    }

    @Override
    public List<Map<String, Object>> queryRows(String sql, String connection, Object... args) {
        return resolveFacade(connection).queryRows(sql, connection, args);
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

    @Override
    public int executeUpdate(String sql, String connection, Object... args) {
        return resolveFacade(connection).executeUpdate(sql, connection, args);
    }

    @Override
    public long batchInsert(String table, List<String> columns, List<List<Object>> rows,
                            int batchSize, String connection) {
        return resolveFacade(connection).batchInsert(table, columns, rows, batchSize, connection);
    }

    @Override
    public long batchUpsert(String table, List<String> keyColumns, List<String> columns,
                            List<List<Object>> rows, int batchSize, String connection) {
        return resolveFacade(connection).batchUpsert(table, keyColumns, columns, rows, batchSize, connection);
    }

    @Override
    public <T> T inTransaction(String connection, TransactionalWork<T> work) {
        return resolveFacade(connection).inTransaction(connection, work);
    }

    // ─── Backup Operations ─────────────────────────────────────────────────

    @Override
    public Map<String, Object> backupSchema(String tableName, String connection) {
        return backupService.backupSchema(tableName, connection);
    }

    @Override
    public Map<String, Object> backupData(String tableName, int maxRows, String connection) {
        return backupService.backupData(tableName, maxRows, connection);
    }

    @Override
    public Map<String, Object> diffSchema(String sourceTable, String targetTable, String connection) {
        return backupService.diffSchema(sourceTable, targetTable, connection);
    }

    // ─── Cross-connection Operations ───────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>The source result is read in full into memory before the first row is written: the read
     * side goes through {@link #queryRows}, which applies no pagination. {@code batchSize} therefore
     * only controls the write side. Callers moving large tables must bound {@code sourceSql}
     * themselves.
     */
    @Override
    public long copyRows(String sourceSql, String sourceConnection,
                         String targetTable, List<String> targetColumns,
                         int batchSize, String targetConnection) {
        List<Map<String, Object>> sourceRows = queryRows(sourceSql, sourceConnection);
        if (sourceRows.isEmpty()) {
            return 0L;
        }
        List<String> columns = targetColumns != null && !targetColumns.isEmpty()
                ? targetColumns
                : List.copyOf(sourceRows.get(0).keySet());
        List<List<Object>> rows = new ArrayList<>(sourceRows.size());
        for (Map<String, Object> sourceRow : sourceRows) {
            List<Object> values = new ArrayList<>(columns.size());
            for (String column : columns) {
                values.add(sourceRow.get(column));
            }
            rows.add(values);
        }
        return batchInsert(targetTable, columns, rows, batchSize, targetConnection);
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

    @Override
    public DatabaseDialect getDialect(String connection) {
        return resolveFacade(connection).getDialect(connection);
    }

    @Override
    public <T> T withMetaData(String connection, MetaDataCallback<T> callback) {
        return resolveFacade(connection).withMetaData(connection, callback);
    }
}
