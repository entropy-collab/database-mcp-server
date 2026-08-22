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
 * All operations require an explicit connection name; there is no default connection.
 */
public interface DatabaseOperations {

    // ─── Read Operations ──────────────────────────────────────────────────

    List<Map<String, Object>> listTables(String schema, String connection);

    List<Map<String, Object>> searchTables(String keyword, String connection);

    List<String> listSchemas(String connection);

    Map<String, Object> describeTable(String table, String schema, String connection);

    List<Map<String, Object>> listIndexes(String table, String schema, String connection);

    List<Map<String, Object>> listViews(String schema, String connection);

    List<Map<String, Object>> listSequences(String schema, String connection);

    PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken, String connection);

    PaginatedQueryResult executeQueryWithSse(String sql, int maxRows, String continuationToken,
                                             SseStreamManager.QueryExecutor<PaginatedQueryResult> executor, String connection);

    List<Map<String, Object>> executeNamedQuery(String sql, Map<String, Object> params, String connection);

    Map<String, Object> getDatabaseInfo(String connection);

    // ─── Execution Plan ────────────────────────────────────────────────────

    PlanAnalysis explainPlan(String sql, String connection);

    // ─── Write Operations ──────────────────────────────────────────────────

    Map<String, Object> executeDdl(String sql, String connection);

    // ─── Metadata Operations ────────────────────────────────────────────────

    Map<String, Object> backupSchema(String tableName, String connection);

    Map<String, Object> backupData(String tableName, int maxRows, String connection);

    Map<String, Object> diffSchema(String sourceTable, String targetTable, String connection);

    // ─── Cache Operations ──────────────────────────────────────────────────

    void clearCache(String connection);

    // ─── Statistics ────────────────────────────────────────────────────────

    Map<String, Object> getStatistics(String connection);
}
