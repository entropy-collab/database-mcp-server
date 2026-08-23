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

/**
 * Results of a lineage analysis for a given table.
 *
 * @param table            analyzed table name
 * @param connection       BYOK connection name
 * @param directUpstream   direct upstream edges
 * @param directDownstream direct downstream edges
 * @param allUpstream      all transitive upstream tables (BFS)
 * @param allDownstream    all transitive downstream tables (BFS)
 * @param maxDepth         max depth explored
 * @param anomalies        any detected anomalies
 * @param edgeCount        total edges in result
 */
public record LineageAnalysis(
    String table,
    String connection,
    List<LineageEdge> directUpstream,
    List<LineageEdge> directDownstream,
    List<String> allUpstream,
    List<String> allDownstream,
    int maxDepth,
    List<LineageAnomaly> anomalies,
    int edgeCount
) {
    public LineageAnalysis {
        if (directUpstream == null) directUpstream = List.of();
        if (directDownstream == null) directDownstream = List.of();
        if (allUpstream == null) allUpstream = List.of();
        if (allDownstream == null) allDownstream = List.of();
        if (anomalies == null) anomalies = List.of();
    }

    public boolean hasDownstream() { return !allDownstream.isEmpty(); }
    public boolean hasUpstream()   { return !allUpstream.isEmpty(); }
    public boolean hasAnomalies()  { return !anomalies.isEmpty(); }
}
