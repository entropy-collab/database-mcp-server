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

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Implements data lineage analysis using foreign-key constraints and view dependencies.
 */
@Service
public class LineageAnalyzerServiceImpl implements LineageAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(LineageAnalyzerServiceImpl.class);

    private final DynamicDataSourceManager dataSourceManager;

    public LineageAnalyzerServiceImpl(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    // ─── Direct Edges ────────────────────────────────────────────────────────

    @Override
    public List<LineageEdge> getUpstream(String tableName, String connection) {
        return fetchEdges(tableName, connection, true);
    }

    @Override
    public List<LineageEdge> getDownstream(String tableName, String connection) {
        return fetchEdges(tableName, connection, false);
    }

    @Override
    public List<LineageEdge> getAllEdges(String tableName, String connection) {
        List<LineageEdge> upstream = getUpstream(tableName, connection);
        List<LineageEdge> downstream = getDownstream(tableName, connection);
        List<LineageEdge> all = new ArrayList<>(upstream);
        all.addAll(downstream);
        return all;
    }

    // ─── BFS Traversal ───────────────────────────────────────────────────────

    @Override
    public LineageAnalysis analyze(String tableName, String connection, int maxDepth) {
        int depth = Math.max(1, Math.min(maxDepth, 10));
        Set<String> visitedUp = new HashSet<>();
        Set<String> visitedDown = new HashSet<>();
        List<LineageEdge> directUpstream = new ArrayList<>();
        List<LineageEdge> directDownstream = new ArrayList<>();

        // First-level edges
        directUpstream = getUpstream(tableName, connection);
        directDownstream = getDownstream(tableName, connection);

        // BFS upstream
        Queue<String> queue = new LinkedList<>();
        Set<String> allUp = new LinkedHashSet<>();
        Map<String, Integer> upDepth = new HashMap<>();
        for (LineageEdge e : directUpstream) {
            String src = normalize(e.sourceTable());
            if (!src.equals(normalize(tableName)) && visitedUp.add(src)) {
                queue.add(src);
                allUp.add(src);
                upDepth.put(src, 1);
            }
        }
        int currentDepth = 0;
        while (!queue.isEmpty() && currentDepth < depth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                List<LineageEdge> edges = getUpstream(current, connection);
                for (LineageEdge e : edges) {
                    String src = normalize(e.sourceTable());
                    if (visitedUp.add(src)) {
                        queue.add(src);
                        allUp.add(src);
                    }
                }
            }
            currentDepth++;
        }

        // BFS downstream
        Queue<String> downQueue = new LinkedList<>();
        Set<String> allDown = new LinkedHashSet<>();
        for (LineageEdge e : directDownstream) {
            String tgt = normalize(e.targetTable());
            if (!tgt.equals(normalize(tableName)) && visitedDown.add(tgt)) {
                downQueue.add(tgt);
                allDown.add(tgt);
            }
        }
        currentDepth = 0;
        while (!downQueue.isEmpty() && currentDepth < depth) {
            int levelSize = downQueue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = downQueue.poll();
                List<LineageEdge> edges = getDownstream(current, connection);
                for (LineageEdge e : edges) {
                    String tgt = normalize(e.targetTable());
                    if (visitedDown.add(tgt)) {
                        downQueue.add(tgt);
                        allDown.add(tgt);
                    }
                }
            }
            currentDepth++;
        }

        List<LineageAnomaly> anomalies = detectAnomalies(tableName, connection, allUp, allDown);

        return new LineageAnalysis(
                tableName, connection,
                directUpstream, directDownstream,
                new ArrayList<>(allUp), new ArrayList<>(allDown),
                currentDepth, anomalies,
                directUpstream.size() + directDownstream.size()
        );
    }

    @Override
    public List<String> getImpactTables(String tableName, String connection, int maxDepth) {
        LineageAnalysis analysis = analyze(tableName, connection, maxDepth);
        return analysis.allDownstream();
    }

    // ─── Format Export ───────────────────────────────────────────────────────

    @Override
    public String exportMermaid(String tableName, String connection, int maxDepth) {
        LineageAnalysis analysis = analyze(tableName, connection, maxDepth);
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");
        sb.append("    subgraph lineage['Lineage: ").append(tableName).append("']\n");
        sb.append("        T[\"").append(tableName).append("\"]\n");

        for (LineageEdge e : analysis.directUpstream()) {
            String safeSrc = escapeMermaid(e.sourceTable());
            String safeTgt = escapeMermaid(e.targetTable());
            sb.append("        ").append(safeSrc).append("[\"").append(safeSrc).append("\"]\n");
            sb.append("        ").append(safeSrc).append("-->|FK| T\n");
        }
        for (LineageEdge e : analysis.directDownstream()) {
            String safeSrc = escapeMermaid(e.sourceTable());
            String safeTgt = escapeMermaid(e.targetTable());
            sb.append("        ").append(safeTgt).append("[\"").append(safeTgt).append("\"]\n");
            sb.append("        T -->|FK| ").append(safeTgt).append("\n");
        }
        sb.append("    end\n");
        return sb.toString();
    }

    @Override
    public String exportDot(String tableName, String connection, int maxDepth) {
        LineageAnalysis analysis = analyze(tableName, connection, maxDepth);
        StringBuilder sb = new StringBuilder();
        sb.append("digraph lineage {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  node [shape=box];\n\n");
        sb.append("  \"").append(tableName).append("\" [style=filled fillcolor=lightblue];\n");

        for (LineageEdge e : analysis.directUpstream()) {
            sb.append("  \"").append(e.sourceTable()).append("\" -> \"").append(tableName)
              .append("\" [label=\"").append(e.sourceColumn()).append("\"];\n");
        }
        for (LineageEdge e : analysis.directDownstream()) {
            sb.append("  \"").append(tableName).append("\" -> \"").append(e.targetTable())
              .append("\" [label=\"").append(e.targetColumn()).append("\"];\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    @Override
    public List<LineageEdge> listAllEdges(String connection) {
        // Collect all base tables first
        List<Map<String, Object>> tables = listBaseTables(connection);
        if (tables.isEmpty()) return List.of();

        List<LineageEdge> allEdges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> t : tables) {
            String tName = (String) t.get("table_name");
            String schema = (String) t.get("table_schema");
            try {
                List<LineageEdge> edges = fetchEdges(tName, connection, true);
                for (LineageEdge e : edges) {
                    String key = e.sourceTable() + "->" + e.targetTable() + ":" + e.sourceColumn();
                    if (seen.add(key)) allEdges.add(e);
                }
            } catch (Exception ex) {
                log.debug("Could not fetch upstream for {}: {}", tName, ex.getMessage());
            }
        }
        return allEdges;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private List<LineageEdge> fetchEdges(String tableName, String connection, boolean upstream) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            String sql = upstream
                    ? dialect.foreignKeyUpstreamQuery(tableName)
                    : dialect.foreignKeyDownstreamQuery(tableName);
            if (sql == null) return List.of();

            List<Map<String, Object>> rows = jdbc.queryForList(sql,
                    dialect.normalizeTableName(tableName));
            List<LineageEdge> edges = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String srcTable = (String) row.get("source_table");
                String tgtTable = (String) row.get("target_table");
                String srcCol = (String) row.get("source_column");
                String tgtCol = (String) row.get("target_column");
                if (srcTable == null || tgtTable == null) continue;
                if (upstream) {
                    edges.add(new LineageEdge(srcTable, tgtTable, srcCol, tgtCol,
                            LineageType.FOREIGN_KEY, connection, null));
                } else {
                    edges.add(new LineageEdge(tgtTable, srcTable, tgtCol, srcCol,
                            LineageType.FOREIGN_KEY, connection, null));
                }
            }
            return edges;
        } catch (Exception e) {
            log.warn("Failed to fetch edges for {}: {}", tableName, e.getMessage(), e);
            return List.of();
        } finally {
            ctx.close();
        }
    }

    private List<Map<String, Object>> listBaseTables(String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            String sql = dialect.tablesQuery(null);
            return jdbc.queryForList(sql);
        } catch (Exception e) {
            log.warn("Failed to list tables: {}", e.getMessage(), e);
            return List.of();
        } finally {
            ctx.close();
        }
    }

    private List<LineageAnomaly> detectAnomalies(String tableName, String connection,
                                                   Collection<String> allUpstream,
                                                   Collection<String> allDownstream) {
        List<LineageAnomaly> anomalies = new ArrayList<>();
        // Check for cycle: if the analyzed table appears in its own downstream
        if (allDownstream.contains(tableName)) {
            anomalies.add(new LineageAnomaly("CYCLE",
                    "Table " + tableName + " is both ancestor and descendant of itself",
                    List.of(tableName)));
        }
        // Check for orphan tables (referenced but not in source table list)
        Set<String> knownTables = new HashSet<>();
        try {
            for (Map<String, Object> t : listBaseTables(connection)) {
                knownTables.add(normalize((String) t.get("table_name")));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch base tables for lineage analysis on connection {}: {}", connection, e.getClass().getSimpleName());
        }
        Set<String> unknownUp = new HashSet<>();
        for (String u : allUpstream) {
            if (!knownTables.contains(normalize(u))) unknownUp.add(u);
        }
        if (!unknownUp.isEmpty()) {
            anomalies.add(new LineageAnomaly("ORPHAN",
                    "Upstream references point to tables not found in schema: " + unknownUp,
                    new ArrayList<>(unknownUp)));
        }
        return anomalies;
    }

    private String normalize(String name) {
        if (name == null) return "";
        return name.toUpperCase().trim();
    }

    private String escapeMermaid(String s) {
        return s.replaceAll("[\"\\[\\]{}]", "_");
    }
}
