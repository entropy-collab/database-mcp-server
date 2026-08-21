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
import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import com.entropy.database.mcp.repository.DatabaseWriteRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepository;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Facade implementation for BYOK datasources.
 * Wraps a ByokDataSourceContext to provide the same API as DatabaseFacade.
 */
class ByokDatabaseFacade implements DatabaseOperations {

    private static final Logger log = LoggerFactory.getLogger(ByokDatabaseFacade.class);

    private final ByokDataSourceContext context;

    public ByokDatabaseFacade(ByokDataSourceContext context) {
        this.context = context;
    }

    // ─── Read Operations ──────────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> listTables(String schema) {
        return context.getReadRepository().listTables(schema);
    }

    @Override
    public List<Map<String, Object>> searchTables(String keyword) {
        return context.getReadRepository().searchTables(keyword);
    }

    @Override
    public List<String> listSchemas() {
        return context.getReadRepository().listSchemas();
    }

    @Override
    public Map<String, Object> describeTable(String table, String schema) {
        return context.getReadRepository().describeTable(table, schema);
    }

    @Override
    public List<Map<String, Object>> listIndexes(String table, String schema) {
        return context.getReadRepository().listIndexes(table, schema);
    }

    @Override
    public List<Map<String, Object>> listViews(String schema) {
        return context.getReadRepository().listViews(schema);
    }

    @Override
    public List<Map<String, Object>> listSequences(String schema) {
        return context.getReadRepository().listSequences(schema);
    }

    @Override
    public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken) {
        return context.getReadRepository().executeQuery(sql, maxRows, continuationToken);
    }

    @Override
    public PaginatedQueryResult executeQueryWithSse(String sql, int maxRows, String continuationToken,
                                                    com.entropy.database.mcp.stream.SseStreamManager.QueryExecutor<PaginatedQueryResult> executor) {
        return context.getReadRepository().executeQueryWithSse(sql, maxRows, continuationToken, executor);
    }

    @Override
    public Map<String, Object> getDatabaseInfo() {
        return context.getReadRepository().getDatabaseInfo();
    }

    // ─── Execution Plan ────────────────────────────────────────────────────

    @Override
    public PlanAnalysis explainPlan(String sql) {
        return context.getExecutionPlanRepository().analyzeExecutionPlan(sql);
    }

    // ─── Write Operations ──────────────────────────────────────────────────

    @Override
    public Map<String, Object> executeDdl(String sql) {
        return context.getWriteRepository().executeDdl(sql);
    }

    // ─── Metadata Operations ───────────────────────────────────────────────

    @Override
    public Map<String, Object> backupSchema(String tableName) {
        throw new UnsupportedOperationException("backupSchema is not supported for BYOK connections");
    }

    @Override
    public Map<String, Object> backupData(String tableName, int maxRows) {
        throw new UnsupportedOperationException("backupData is not supported for BYOK connections");
    }

    @Override
    public Map<String, Object> diffSchema(String sourceTable, String targetTable) {
        throw new UnsupportedOperationException("diffSchema is not supported for BYOK connections");
    }

    // ─── Cache Operations ──────────────────────────────────────────────────

    @Override
    public void clearCache() {
        context.getCache().invalidateAll();
    }

    // ─── Statistics ────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> getStatistics() {
        return Map.of(
                "queryStats", context.getHealthMonitor().getQueryStats().toSummary(),
                "cacheStats", context.getCache().getStatistics()
        );
    }
}
