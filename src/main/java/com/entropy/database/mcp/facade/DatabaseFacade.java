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

import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.repository.DatabaseMetadataRepository;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import com.entropy.database.mcp.repository.DatabaseWriteRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepository;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Application facade that orchestrates repository operations.
 * Provides a clean API for tool publishers.
 */
@Service
public class DatabaseFacade {

    private static final Logger log = LoggerFactory.getLogger(DatabaseFacade.class);

    private final DatabaseReadRepository readRepo;
    private final DatabaseWriteRepository writeRepo;
    private final DatabaseMetadataRepository metadataRepo;
    private final ExecutionPlanRepository executionPlanRepo;
    private final DatabaseCache cache;
    private final DatabaseHealthMonitor healthMonitor;
    private final QueryAuditLogger auditLogger;
    private final McpMetricsCollector metricsCollector;

    public DatabaseFacade(DatabaseReadRepository readRepo,
                          DatabaseWriteRepository writeRepo,
                          DatabaseMetadataRepository metadataRepo,
                          ExecutionPlanRepository executionPlanRepo,
                          DatabaseCache cache,
                          DatabaseHealthMonitor healthMonitor,
                          QueryAuditLogger auditLogger,
                          McpMetricsCollector metricsCollector) {
        this.readRepo = readRepo;
        this.writeRepo = writeRepo;
        this.metadataRepo = metadataRepo;
        this.executionPlanRepo = executionPlanRepo;
        this.cache = cache;
        this.healthMonitor = healthMonitor;
        this.auditLogger = auditLogger;
        this.metricsCollector = metricsCollector;
    }

    // ─── Read Operations ──────────────────────────────────────────────────

    public List<Map<String, Object>> listTables(String schema) {
        return readRepo.listTables(schema);
    }

    /**
     * Search tables across all schemas with optional keyword filter.
     */
    public List<Map<String, Object>> searchTables(String keyword) {
        return readRepo.searchTables(keyword);
    }

    public List<String> listSchemas() {
        return readRepo.listSchemas();
    }

    public Map<String, Object> describeTable(String table, String schema) {
        return readRepo.describeTable(table, schema);
    }

    public List<Map<String, Object>> listIndexes(String table, String schema) {
        return readRepo.listIndexes(table, schema);
    }

    public List<Map<String, Object>> listViews(String schema) {
        return readRepo.listViews(schema);
    }

    public List<Map<String, Object>> listSequences(String schema) {
        return readRepo.listSequences(schema);
    }

    public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken) {
        return executeQueryWithSse(sql, maxRows, continuationToken, null);
    }

    public PaginatedQueryResult executeQueryWithSse(String sql, int maxRows, String continuationToken,
                                                    com.entropy.database.mcp.stream.SseStreamManager.QueryExecutor<PaginatedQueryResult> executor) {
        long start = System.currentTimeMillis();
        try {
            PaginatedQueryResult result = readRepo.executeQueryWithSse(sql, maxRows, continuationToken, executor);
            long duration = System.currentTimeMillis() - start;
            healthMonitor.recordQuery(duration, result.rows().size(), true);
            auditLogger.log("executeQuery", sql, result.rows().size(), duration, true);
            metricsCollector.recordToolExecution("executeQuery", duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            healthMonitor.recordQuery(duration, 0, false);
            auditLogger.log("executeQuery", sql, 0, duration, false, e.getMessage());
            metricsCollector.recordToolExecution("executeQuery", duration);
            throw e;
        }
    }

    public Map<String, Object> getDatabaseInfo() {
        return readRepo.getDatabaseInfo();
    }

    // ─── Execution Plan ────────────────────────────────────────────────────

    public PlanAnalysis explainPlan(String sql) {
        return executionPlanRepo.analyzeExecutionPlan(sql);
    }

    // ─── Write Operations ─────────────────────────────────────────────────

    public Map<String, Object> executeDdl(String sql) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> result = writeRepo.executeDdl(sql);
            long duration = System.currentTimeMillis() - start;
            int affected = (int) result.getOrDefault("affectedRows", 0);
            healthMonitor.recordQuery(duration, affected, true);
            auditLogger.log("executeDdl", sql, affected, duration, true);
            metricsCollector.recordToolExecution("executeDdl", duration);
            cache.invalidateAll(); // Evict cache on DDL
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            healthMonitor.recordQuery(duration, 0, false);
            auditLogger.log("executeDdl", sql, 0, duration, false, e.getMessage());
            metricsCollector.recordToolExecution("executeDdl", duration);
            throw e;
        }
    }

    // ─── Metadata Operations ──────────────────────────────────────────────

    public Map<String, Object> backupSchema(String tableName) {
        return metadataRepo.backupSchema(tableName);
    }

    public Map<String, Object> backupData(String tableName, int maxRows) {
        return metadataRepo.backupData(tableName, maxRows);
    }

    public Map<String, Object> diffSchema(String sourceTable, String targetTable) {
        return metadataRepo.diffSchema(sourceTable, targetTable);
    }

    // ─── Cache Operations ─────────────────────────────────────────────────

    public void clearCache() {
        cache.invalidateAll();
    }

    // ─── Statistics ───────────────────────────────────────────────────────

    public Map<String, Object> getStatistics() {
        return Map.of(
            "queryStats", healthMonitor.getQueryStats().toSummary(),
            "cacheStats", cache.getStatistics()
        );
    }
}
