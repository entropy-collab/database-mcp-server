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

    @McpTool(description = """
            【查询直接上游】列出本表通过外键引用的父表，只看一层直接依赖。
            前置条件：先调用 createNamedConnection 注册数据库连接；外键血缘依赖配置 entropy.mcp.database.lineage.foreign-key-enabled，关闭时恒返回空边集。
            使用场景：写入本表前确认哪些父表必须先有数据、排查外键约束报错的来源。
            返回字段：direction（固定 UPSTREAM）、table、connection、edgeCount、edges（数组，每项含 sourceTable 上游父表、targetTable 本表、sourceColumn、targetColumn、type，type 恒为 FOREIGN_KEY）；format=text 时改为返回 direction、edgeCount、text；format=mermaid 或 dot 时改为返回 format、direction、graph。
            不要用于：查引用本表的子表（用 getDownstream）；多层双向全链路（用 analyzeLineage）；评估改动波及范围（用 getImpactAnalysis）；导出全库边（用 listAllEdges）。
            标签：[read, lineage, upstream, graph]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getUpstream(
            @McpToolParam(description = "要分析的表名，大小写不敏感（内部按方言归一化）") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "输出格式，取值：json（默认，结构化边数组）、text（箭头文本清单）、mermaid（Mermaid flowchart 文本）、dot（Graphviz DOT 文本）；省略即 json", required = false) String format) {
        return safeExecute(() -> formatEdgeResult(analyzer.getUpstream(tableName, connectionName),
                tableName, connectionName, format, "UPSTREAM"));
    }

    @McpTool(description = """
            【查询直接下游】列出通过外键引用本表的子表，只看一层直接依赖。
            前置条件：先调用 createNamedConnection 注册数据库连接；外键血缘依赖配置 entropy.mcp.database.lineage.foreign-key-enabled，关闭时恒返回空边集。
            使用场景：删除或修改本表主键前确认哪些子表会受外键牵连。
            返回字段：direction（固定 DOWNSTREAM）、table、connection、edgeCount、edges（数组，每项含 sourceTable 本表、targetTable 下游子表、sourceColumn、targetColumn、type，type 恒为 FOREIGN_KEY）；format=text 时改为返回 direction、edgeCount、text；format=mermaid 或 dot 时改为返回 format、direction、graph。
            不要用于：查本表引用的父表（用 getUpstream）；多层双向全链路（用 analyzeLineage）；需要按深度分层的影响面清单（用 getImpactAnalysis，它会递归到多层）；导出全库边（用 listAllEdges）。
            标签：[read, lineage, downstream, graph]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getDownstream(
            @McpToolParam(description = "要分析的表名，大小写不敏感（内部按方言归一化）") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "输出格式，取值：json（默认，结构化边数组）、text（箭头文本清单）、mermaid（Mermaid flowchart 文本）、dot（Graphviz DOT 文本）；省略即 json", required = false) String format) {
        return safeExecute(() -> formatEdgeResult(analyzer.getDownstream(tableName, connectionName),
                tableName, connectionName, format, "DOWNSTREAM"));
    }

    // ─── Full Analysis ────────────────────────────────────────────────────────

    @McpTool(description = """
            【全链路血缘分析】以指定表为根做双向广度遍历，一次拿到多层上游与下游，并检测血缘异常。
            前置条件：先调用 createNamedConnection 注册数据库连接；血缘基于外键约束，无外键的库返回空链路。
            使用场景：理解一张陌生表在数据链路中的位置、排查环形依赖与悬空引用、需要上下游一起看时。
            返回字段：table、connection、maxDepth（实际生效深度）、directUpstream 与 directDownstream（数组，每项含 sourceTable、targetTable、sourceColumn、targetColumn、type）、allUpstream 与 allDownstream（多层表名数组，按广度顺序，不含根表）、edgeCount（直接边总数）、hasUpstream、hasDownstream，检测到异常时附带 anomalies（数组，每项含 type=CYCLE/ORPHAN/TRUNCATED、description、affectedTables）；format=mermaid 或 dot 时只返回 format 与 graph 两个字段。
            不要用于：只要一层边（用 getUpstream 或 getDownstream，开销更小）；只关心下游影响面且需按深度分层（用 getImpactAnalysis）；导出全库边（用 listAllEdges）。
            标签：[read, lineage, graph, bfs]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> analyzeLineage(
            @McpToolParam(description = "要分析的表名，大小写不敏感（内部按方言归一化）") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "广度遍历最大深度，取值 1-10，超出范围会被截断到区间内；省略时默认 5。实际深度还会被配置 entropy.mcp.database.lineage.max-traversal-depth 上限约束", required = false) Integer maxDepth,
            @McpToolParam(description = "输出格式，取值：mermaid（Mermaid flowchart 文本）、dot（Graphviz DOT 文本）；其余取值或省略均返回结构化 JSON（不支持 text）", required = false) String format) {
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
                        .toList());
            }
            return success(result);
        });
    }

    // ─── Impact Analysis ──────────────────────────────────────────────────────

    @McpTool(description = """
            【变更影响分析】沿外键向下游递归，列出修改源表会波及的全部表，并按传播深度分层。
            前置条件：先调用 createNamedConnection 注册数据库连接；影响面基于外键约束推导。
            使用场景：改表结构、清理数据、下线表之前评估波及范围与回归测试范围。
            返回字段：sourceTable、connection、maxDepth、totalImpacted、impactedTables（受影响表名数组）、byDepth（深度 → 该层表名数组），检测到异常时附带 anomalies（每项含 type、description、affectedTables）。
            不要用于：只要一层子表（用 getDownstream）；要连上游一起看（用 analyzeLineage）；需要可视化图（用 exportMermaid 或 exportDot）。
            标签：[read, lineage, impact, downstream]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getImpactAnalysis(
            @McpToolParam(description = "变更的源表名，大小写不敏感") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "向下游递归的最大深度，取值 1-10，超出范围会被截断到区间内；省略时默认 5", required = false) Integer maxDepth) {
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

    @McpTool(description = """
            【导出 Mermaid 血缘图】把指定表的直接上下游导出为 Mermaid flowchart TD 文本。
            前置条件：先调用 createNamedConnection 注册数据库连接，并显式传入连接名。
            使用场景：需要把血缘图贴进 Markdown、飞书文档、GitHub Issue 等支持 Mermaid 渲染的地方。
            返回字段：table、connection、format（固定 mermaid）、graph（Mermaid flowchart TD 源码文本，节点为表名，边标注 FK；自引用外键会被忽略）。
            不要用于：需要 Graphviz 渲染或用 dot 命令排版（用 exportDot）；需要结构化数据做程序处理（用 analyzeLineage 的 JSON 输出）。
            标签：[read, lineage, export, mermaid]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> exportMermaid(
            @McpToolParam(description = "要导出的表名，大小写不敏感") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "遍历深度，取值 1-10，超出范围会被截断到区间内；省略时默认 5（仅影响底层分析范围，图中只画直接上下游）", required = false) Integer maxDepth) {
        int depth = maxDepth != null ? Math.max(1, Math.min(maxDepth, 10)) : 5;
        return success(context("table", tableName, "connection", connectionName,
                "format", "mermaid", "graph", analyzer.exportMermaid(tableName, connectionName, depth)));
    }

    @McpTool(description = """
            【导出 DOT 血缘图】把指定表的直接上下游导出为 Graphviz DOT 文本。
            前置条件：先调用 createNamedConnection 注册数据库连接，并显式传入连接名。
            使用场景：需要用 Graphviz（dot / neato）渲染成 PNG、SVG 或接入既有图形流水线。
            返回字段：table、connection、format（固定 dot）、graph（digraph 源码文本，rankdir=LR，根表高亮，边标注外键列名；自引用外键会被忽略）。
            不要用于：贴进 Markdown 直接渲染（用 exportMermaid）；需要结构化数据做程序处理（用 analyzeLineage 的 JSON 输出）。
            标签：[read, lineage, export, dot, graphviz]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> exportDot(
            @McpToolParam(description = "要导出的表名，大小写不敏感") String tableName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "遍历深度，取值 1-10，超出范围会被截断到区间内；省略时默认 5（仅影响底层分析范围，图中只画直接上下游）", required = false) Integer maxDepth) {
        int depth = maxDepth != null ? Math.max(1, Math.min(maxDepth, 10)) : 5;
        return success(context("table", tableName, "connection", connectionName,
                "format", "dot", "graph", analyzer.exportDot(tableName, connectionName, depth)));
    }

    // ─── Global ────────────────────────────────────────────────────────────────

    @McpTool(description = """
            【导出全库血缘边】遍历当前库的所有基表，导出全部外键血缘边并去重。
            前置条件：先调用 createNamedConnection 注册数据库连接，并显式传入连接名；每张表一次外键查询，大库开销较高。
            使用场景：构建全库依赖图、批量导入外部血缘系统、盘点缺失外键。
            返回字段：connection、totalEdges、edges（数组，每项含 sourceTable 上游表、targetTable 下游表、sourceColumn、targetColumn、type，type 恒为 FOREIGN_KEY）。
            注意：检查的表数受配置 entropy.mcp.database.lineage.max-tables-per-graph 限制（默认 200），超限时只取前 N 张表，结果不完整。
            不要用于：只关心单表血缘（用 getUpstream / getDownstream / analyzeLineage，开销小得多）；需要可视化图（用 exportMermaid 或 exportDot）。
            标签：[read, lineage, edges, global]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listAllEdges(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName) {
        return safeExecute(() -> {
            List<LineageEdge> edges = analyzer.listAllEdges(connectionName);
            List<Map<String, Object>> items = edges.stream().map(e -> context(
                    "sourceTable", e.sourceTable(), "targetTable", e.targetTable(),
                    "sourceColumn", e.sourceColumn(), "targetColumn", e.targetColumn(),
                    "type", e.type().name()
            )).toList();
            return success(context("connection", connectionName, "totalEdges", items.size(), "edges", items));
        });
    }

    @McpTool(description = """
            【查看血缘配置】读取血缘模块当前生效的配置开关与阈值，无需任何参数。
            使用场景：血缘查询返回空结果或链路被截断时，先看配置是否关闭或深度、表数上限过小。
            返回字段：enabled（血缘模块总开关）、foreignKeyEnabled（外键血缘开关，关闭时所有边查询返回空）、viewDependencyEnabled（视图依赖血缘开关）、maxTraversalDepth（遍历深度上限，默认 10）、autoAnalyze（自动分析开关）、maxTablesPerGraph（单图与 listAllEdges 的表数上限，默认 200）。
            标签：[read, lineage, config]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
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
        )).toList();
        return success(context("direction", direction, "table", table, "connection", connection,
                "edgeCount", edges.size(), "edges", items));
    }

    private List<Map<String, Object>> edgesToMaps(List<LineageEdge> edges) {
        return edges.stream().map(e -> context(
                "sourceTable", e.sourceTable(), "targetTable", e.targetTable(),
                "sourceColumn", e.sourceColumn(), "targetColumn", e.targetColumn(),
                "type", e.type().name()
        )).toList();
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
