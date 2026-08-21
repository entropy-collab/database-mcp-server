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
import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Routing facade that delegates to either the primary datasource or a BYOK datasource
 * based on the provided connection properties.
 *
 * <p>When no connection is provided, operations are routed to the primary datasource.
 * When connection is provided, a BYOK datasource is acquired and used for the operation.
 */
@Service
public class RoutingDatabaseFacade {

    private final DatabaseOperations primaryFacade;
    private final DynamicDataSourceManager dynamicDataSourceManager;

    public RoutingDatabaseFacade(DatabaseOperations primaryFacade,
                                 DynamicDataSourceManager dynamicDataSourceManager) {
        this.primaryFacade = primaryFacade;
        this.dynamicDataSourceManager = dynamicDataSourceManager;
    }

    // ─── Helper ────────────────────────────────────────────────────────────

    private DatabaseOperations resolveFacade(ConnectionProperties connection) {
        if (connection == null) {
            return primaryFacade;
        }
        ByokDataSourceContext context = dynamicDataSourceManager.acquire(
                connection.getCacheKey(), connection);
        return new ByokDatabaseFacade(context);
    }

    // ─── Read Operations ───────────────────────────────────────────────────

    public List<Map<String, Object>> listTables(String schema, ConnectionProperties connection) {
        return resolveFacade(connection).listTables(schema);
    }

    public List<Map<String, Object>> searchTables(String keyword, ConnectionProperties connection) {
        return resolveFacade(connection).searchTables(keyword);
    }

    public List<String> listSchemas(ConnectionProperties connection) {
        return resolveFacade(connection).listSchemas();
    }

    public Map<String, Object> describeTable(String table, String schema, ConnectionProperties connection) {
        return resolveFacade(connection).describeTable(table, schema);
    }

    public List<Map<String, Object>> listIndexes(String table, String schema, ConnectionProperties connection) {
        return resolveFacade(connection).listIndexes(table, schema);
    }

    public List<Map<String, Object>> listViews(String schema, ConnectionProperties connection) {
        return resolveFacade(connection).listViews(schema);
    }

    public List<Map<String, Object>> listSequences(String schema, ConnectionProperties connection) {
        return resolveFacade(connection).listSequences(schema);
    }

    public com.entropy.database.mcp.domain.PaginatedQueryResult executeQuery(
            String sql, int maxRows, String continuationToken, ConnectionProperties connection) {
        return resolveFacade(connection).executeQuery(sql, maxRows, continuationToken);
    }

    public Map<String, Object> getDatabaseInfo(ConnectionProperties connection) {
        return resolveFacade(connection).getDatabaseInfo();
    }

    // ─── Execution Plan ────────────────────────────────────────────────────

    public com.entropy.database.mcp.domain.PlanAnalysis explainPlan(String sql, ConnectionProperties connection) {
        return resolveFacade(connection).explainPlan(sql);
    }

    // ─── Write Operations ──────────────────────────────────────────────────

    public Map<String, Object> executeDdl(String sql, ConnectionProperties connection) {
        return resolveFacade(connection).executeDdl(sql);
    }

    // ─── Metadata Operations ───────────────────────────────────────────────

    public Map<String, Object> backupSchema(String tableName, ConnectionProperties connection) {
        return resolveFacade(connection).backupSchema(tableName);
    }

    public Map<String, Object> backupData(String tableName, int maxRows, ConnectionProperties connection) {
        return resolveFacade(connection).backupData(tableName, maxRows);
    }

    public Map<String, Object> diffSchema(String sourceTable, String targetTable, ConnectionProperties connection) {
        return resolveFacade(connection).diffSchema(sourceTable, targetTable);
    }

    // ─── Statistics ────────────────────────────────────────────────────────

    public Map<String, Object> getStatistics(ConnectionProperties connection) {
        return resolveFacade(connection).getStatistics();
    }
}
