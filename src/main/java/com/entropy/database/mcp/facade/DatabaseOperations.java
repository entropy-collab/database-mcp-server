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

import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.stream.SseStreamManager;

import java.util.List;
import java.util.Map;

/**
 * Common interface for database facade operations.
 * Implemented by both primary and BYOK facade implementations.
 */
public interface DatabaseOperations {

    // ─── Read Operations ──────────────────────────────────────────────────

    List<Map<String, Object>> listTables(String schema);

    List<Map<String, Object>> searchTables(String keyword);

    List<String> listSchemas();

    Map<String, Object> describeTable(String table, String schema);

    List<Map<String, Object>> listIndexes(String table, String schema);

    List<Map<String, Object>> listViews(String schema);

    List<Map<String, Object>> listSequences(String schema);

    PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken);

    PaginatedQueryResult executeQueryWithSse(String sql, int maxRows, String continuationToken,
                                             SseStreamManager.QueryExecutor<PaginatedQueryResult> executor);

    Map<String, Object> getDatabaseInfo();

    // ─── Execution Plan ────────────────────────────────────────────────────

    PlanAnalysis explainPlan(String sql);

    // ─── Write Operations ──────────────────────────────────────────────────

    Map<String, Object> executeDdl(String sql);

    // ─── Metadata Operations ───────────────────────────────────────────────

    Map<String, Object> backupSchema(String tableName);

    Map<String, Object> backupData(String tableName, int maxRows);

    Map<String, Object> diffSchema(String sourceTable, String targetTable);

    // ─── Cache Operations ──────────────────────────────────────────────────

    void clearCache();

    // ─── Statistics ────────────────────────────────────────────────────────

    Map<String, Object> getStatistics();
}
