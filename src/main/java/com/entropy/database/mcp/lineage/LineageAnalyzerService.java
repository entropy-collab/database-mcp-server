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
package com.entropy.database.mcp.lineage;

import java.util.List;
import java.util.Map;

/**
 * Service for analyzing data lineage between database tables.
 */
public interface LineageAnalyzerService {

    /**
     * Get direct upstream (foreign key dependents) for a table.
     */
    List<LineageEdge> getUpstream(String tableName, String connection);

    /**
     * Get direct downstream (tables that reference this table via FK).
     */
    List<LineageEdge> getDownstream(String tableName, String connection);

    /**
     * Full lineage analysis for a table with BFS traversal.
     */
    LineageAnalysis analyze(String tableName, String connection, int maxDepth);

    /**
     * Get all edges (both directions) for a table.
     */
    List<LineageEdge> getAllEdges(String tableName, String connection);

    /**
     * Find tables that would be impacted if the given table's schema changes.
     */
    List<String> getImpactTables(String tableName, String connection, int maxDepth);

    /**
     * Export lineage graph as Mermaid flowchart syntax.
     */
    String exportMermaid(String tableName, String connection, int maxDepth);

    /**
     * Export lineage graph as DOT (Graphviz) syntax.
     */
    String exportDot(String tableName, String connection, int maxDepth);

    /**
     * List all foreign-key-based lineage edges across all tables.
     */
    List<LineageEdge> listAllEdges(String connection);
}
