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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.lineage.*;
import com.entropy.database.mcp.properties.LineageProperties;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Data lineage analysis MCP tools.
 * Provides table-level and column-level lineage tracking via foreign-key constraints.
 */
@Component
public class LineageTools extends McpToolBase {

    private final LineageAnalyzerService analyzer;
    private final LineageProperties props;

    public LineageTools(LineageAnalyzerService analyzer, LineageProperties props) {
        this.analyzer = analyzer;
        this.props = props;
    }

    // ─── Upstream / Downstream ────────────────────────────────────────────────

    @McpTool(description = "Get direct upstream edges (tables that this table references via foreign keys)")
    public Map<String, Object> getUpstream(
            @McpToolParam(description = "Table name to analyze") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Output format: json, text, mermaid, or dot", required = false) String format) {
        return safeExecute(() -> formatEdgeResult(analyzer.getUpstream(tableName, connectionName),
                tableName, connectionName, format, "UPSTREAM"));
    }

    @McpTool(description = "Get direct downstream edges (tables that reference this table via foreign keys)")
    public Map<String, Object> getDownstream(
            @McpToolParam(description = "Table name to analyze") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Output format: json, text, mermaid, or dot", required = false) String format) {
        return safeExecute(() -> formatEdgeResult(analyzer.getDownstream(tableName, connectionName),
                tableName, connectionName, format, "DOWNSTREAM"));
    }

    // ─── Full Analysis ────────────────────────────────────────────────────────

    @McpTool(description = "Full lineage analysis with BFS traversal — returns upstream/downstream tables and anomalies")
    public Map<String, Object> analyzeLineage(
            @McpToolParam(description = "Table name to analyze") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Max BFS traversal depth (1-10)", required = false) Integer maxDepth,
            @McpToolParam(description = "Output format: json, text, mermaid, or dot", required = false) String format) {
        return safeExecute(() -> {
            int depth = maxDepth != null ? Math.max(1, Math.min(maxDepth, 10)) : 5;

            if ("mermaid".equalsIgnoreCase(format)) {
                return success(Map.of("format", "mermaid", "graph",
                        analyzer.exportMermaid(tableName, connectionName, depth)));
            }
            if ("dot".equalsIgnoreCase(format)) {
                return success(Map.of("format", "dot", "graph",
                        analyzer.exportDot(tableName, connectionName, depth)));
            }

            LineageAnalysis analysis = analyzer.analyze(tableName, connectionName, depth);
            Map<String, Object> result = context("table", tableName, "connection", connectionName, "maxDepth", depth);
            result.put("directUpstream", edgesToMaps(analysis.directUpstream()));
            result.put("directDownstream", edgesToMaps(analysis.directDownstream()));
            result.put("allUpstream", analysis.allUpstream());
            result.put("allDownstream", analysis.allDownstream());
            result.put("edgeCount", analysis.edgeCount());
            result.put("hasUpstream", analysis.hasUpstream());
            result.put("hasDownstream", analysis.hasDownstream());
            if (analysis.hasAnomalies()) {
                result.put("anomalies", analysis.anomalies().stream()
                        .map(a -> Map.<String, Object>of("type", a.type(), "description", a.description(),
                                "affectedTables", a.affectedTables()))
                        .collect(Collectors.toList()));
            }
            return success(result);
        });
    }

    // ─── Impact Analysis ──────────────────────────────────────────────────────

    @McpTool(description = "Impact analysis: find all downstream tables that would be affected by a change to the source table")
    public Map<String, Object> getImpactAnalysis(
            @McpToolParam(description = "Source table name") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Max traversal depth", required = false) Integer maxDepth) {
        return safeExecute(() -> {
            int depth = maxDepth != null ? Math.max(1, Math.min(maxDepth, 10)) : 5;
            List<String> impacted = analyzer.getImpactTables(tableName, connectionName, depth);
            LineageAnalysis analysis = analyzer.analyze(tableName, connectionName, depth);

            Map<Integer, List<String>> byDepth = new TreeMap<>();
            for (String table : impacted) {
                int d = findDepth(analysis.allDownstream(), table, depth);
                byDepth.computeIfAbsent(d, k -> new ArrayList<>()).add(table);
            }

            Map<String, Object> result = context("sourceTable", tableName, "connection", connectionName,
                    "maxDepth", depth, "totalImpacted", impacted.size(), "impactedTables", impacted, "byDepth", byDepth);
            if (analysis.hasAnomalies()) {
                result.put("anomalies", analysis.anomalies());
            }
            return success(result);
        });
    }

    // ─── Export ────────────────────────────────────────────────────────────────

    @McpTool(description = "Export lineage graph as Mermaid flowchart syntax")
    public Map<String, Object> exportMermaid(
            @McpToolParam(description = "Table name to export") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Max traversal depth", required = false) Integer maxDepth) {
        int depth = maxDepth != null ? Math.max(1, Math.min(maxDepth, 10)) : 5;
        return success(Map.of("table", tableName, "connection", connectionName,
                "format", "mermaid", "graph", analyzer.exportMermaid(tableName, connectionName, depth)));
    }

    @McpTool(description = "Export lineage graph as DOT (Graphviz) syntax")
    public Map<String, Object> exportDot(
            @McpToolParam(description = "Table name to export") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Max traversal depth", required = false) Integer maxDepth) {
        int depth = maxDepth != null ? Math.max(1, Math.min(maxDepth, 10)) : 5;
        return success(Map.of("table", tableName, "connection", connectionName,
                "format", "dot", "graph", analyzer.exportDot(tableName, connectionName, depth)));
    }

    // ─── Global ────────────────────────────────────────────────────────────────

    @McpTool(description = "List all foreign-key-based lineage edges across all tables in the database")
    public Map<String, Object> listAllEdges(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName) {
        return safeExecute(() -> {
            List<LineageEdge> edges = analyzer.listAllEdges(connectionName);
            List<Map<String, Object>> items = edges.stream().map(e -> context(
                    "sourceTable", e.sourceTable(), "targetTable", e.targetTable(),
                    "sourceColumn", e.sourceColumn(), "targetColumn", e.targetColumn(),
                    "type", e.type().name()
            )).collect(Collectors.toList());
            return success(Map.of("connection", connectionName, "totalEdges", items.size(), "edges", items));
        });
    }

    @McpTool(description = "Show current lineage configuration")
    public Map<String, Object> getLineageConfig() {
        return success(Map.of(
                "enabled", props.enabled(),
                "foreignKeyEnabled", props.foreignKeyEnabled(),
                "viewDependencyEnabled", props.viewDependencyEnabled(),
                "maxTraversalDepth", props.maxTraversalDepth(),
                "autoAnalyze", props.autoAnalyze(),
                "maxTablesPerGraph", props.maxTablesPerGraph()
        ));
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> formatEdgeResult(List<LineageEdge> edges, String table,
                                                  String connection, String format, String direction) {
        if ("mermaid".equalsIgnoreCase(format)) {
            return success(Map.of("format", "mermaid", "direction", direction,
                    "graph", buildMermaidForEdges(edges, table, direction)));
        }
        if ("dot".equalsIgnoreCase(format)) {
            return success(Map.of("format", "dot", "direction", direction,
                    "graph", buildDotForEdges(edges, table, direction)));
        }
        if ("text".equalsIgnoreCase(format)) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(direction).append(" edges for ").append(table).append(" ===\n");
            for (LineageEdge e : edges) {
                sb.append(e.sourceTable()).append(".").append(e.sourceColumn())
                        .append(" -> ").append(e.targetTable()).append(".").append(e.targetColumn())
                        .append(" [").append(e.type()).append("]\n");
            }
            return success(Map.of("direction", direction, "edgeCount", edges.size(), "text", sb.toString()));
        }
        List<Map<String, Object>> items = edges.stream().map(e -> context(
                "sourceTable", e.sourceTable(), "targetTable", e.targetTable(),
                "sourceColumn", e.sourceColumn(), "targetColumn", e.targetColumn(),
                "type", e.type().name()
        )).collect(Collectors.toList());
        return success(Map.of("direction", direction, "table", table, "connection", connection,
                "edgeCount", edges.size(), "edges", items));
    }

    private List<Map<String, Object>> edgesToMaps(List<LineageEdge> edges) {
        return edges.stream().map(e -> context(
                "sourceTable", e.sourceTable(), "targetTable", e.targetTable(),
                "sourceColumn", e.sourceColumn(), "targetColumn", e.targetColumn(),
                "type", e.type().name()
        )).collect(Collectors.toList());
    }

    private String buildMermaidForEdges(List<LineageEdge> edges, String table, String direction) {
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        sb.append("    T[\"").append(escapeM(table)).append("\"]\n");
        String arrow = "DOWNSTREAM".equals(direction) ? "T -->|" + direction + "| " : "T <--|" + direction + "| ";
        for (LineageEdge e : edges) {
            String other = "DOWNSTREAM".equals(direction) ? escapeM(e.targetTable()) : escapeM(e.sourceTable());
            sb.append("    ").append(other).append("[\"").append(other).append("\"]\n");
            if ("DOWNSTREAM".equals(direction)) {
                sb.append("    T -->|FK| ").append(other).append("\n");
            } else {
                sb.append("    ").append(other).append(" -->|FK| T\n");
            }
        }
        return sb.toString();
    }

    private String buildDotForEdges(List<LineageEdge> edges, String table, String direction) {
        StringBuilder sb = new StringBuilder("digraph lineage {\n  rankdir=LR;\n  node [shape=box];\n");
        sb.append("  \"").append(table).append("\" [style=filled fillcolor=lightblue];\n");
        for (LineageEdge e : edges) {
            String src = "DOWNSTREAM".equals(direction) ? table : e.sourceTable();
            String tgt = "DOWNSTREAM".equals(direction) ? e.targetTable() : table;
            sb.append("  \"").append(src).append("\" -> \"").append(tgt)
              .append("\" [label=\"").append(direction).append("\"];\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String escapeM(String s) {
        return s.replaceAll("[\"\\[\\]{}]", "_");
    }

    /** Find the BFS depth of a table in a flat downstream list (approximate). */
    private int findDepth(List<String> list, String target, int maxDepth) {
        if (list.isEmpty()) return maxDepth;
        int idx = list.indexOf(target.toUpperCase());
        if (idx < 0) return maxDepth;
        int perLevel = Math.max(1, list.size() / maxDepth);
        return Math.min(maxDepth, (idx / perLevel) + 1);
    }
}
