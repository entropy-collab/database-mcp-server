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
import com.entropy.database.mcp.properties.LineageProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Implements data lineage analysis using foreign-key constraints.
 *
 * <h2>Edge direction contract</h2>
 * Every {@link LineageEdge} this service produces obeys one orientation:
 * {@code sourceTable} is the <em>upstream</em> side (the referenced parent table) and
 * {@code targetTable} is the <em>downstream</em> side (the referencing child table);
 * {@code sourceColumn} belongs to {@code sourceTable} and {@code targetColumn} to
 * {@code targetTable}. Callers can therefore read an edge as "source feeds target" without
 * knowing which dialect produced it.
 *
 * <p>That orientation is now the dialect's own contract (see
 * {@link DatabaseDialect#foreignKeyUpstreamQuery(String)}): {@code source_table} / {@code source_column}
 * are always the parent and {@code target_table} / {@code target_column} always the child, and the
 * upstream query constrains the child side while the downstream query constrains the parent side.
 * This service therefore no longer re-derives the direction from the row - re-deriving could not fix
 * a dialect whose {@code WHERE} clause filtered the wrong side, which is what made MySQL answer every
 * upstream lookup with the downstream graph.
 *
 * <p>What remains is a check, not a correction: a row in which <em>neither</em> end is the queried
 * table cannot be attributed to it, so it is logged and passed through with the dialect's own
 * orientation instead of being silently reshaped.
 */
@Service
public class LineageAnalyzerServiceImpl implements LineageAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(LineageAnalyzerServiceImpl.class);

    /**
     * Time-to-live of a cached {@link #analyze} result.
     *
     * <p>Short on purpose: {@code exportMermaid}, {@code exportDot} and {@code getImpactTables} each
     * run a full traversal, and clients typically call all three back to back for the same table.
     * The window is small enough that a DDL change is picked up on the next interaction.
     */
    private static final Duration ANALYSIS_TTL = Duration.ofSeconds(30);

    private static final int ANALYSIS_CACHE_SIZE = 200;

    private final DynamicDataSourceManager dataSourceManager;
    private final LineageProperties properties;

    /**
     * Own Caffeine cache rather than {@code ByokDataSourceContext#getCache()}: that one is the
     * shared query/metadata cache whose TTL is global configuration, and lineage graphs need a
     * much shorter, independently tunable window.
     */
    private final Cache<String, LineageAnalysis> analysisCache = Caffeine.newBuilder()
            .maximumSize(ANALYSIS_CACHE_SIZE)
            .expireAfterWrite(ANALYSIS_TTL)
            .build();

    public LineageAnalyzerServiceImpl(DynamicDataSourceManager dataSourceManager,
                                      LineageProperties properties) {
        this.dataSourceManager = dataSourceManager;
        this.properties = properties;
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
        List<LineageEdge> all = new ArrayList<>(getUpstream(tableName, connection));
        all.addAll(getDownstream(tableName, connection));
        return all;
    }

    // ─── BFS Traversal ───────────────────────────────────────────────────────

    @Override
    public LineageAnalysis analyze(String tableName, String connection, int maxDepth) {
        int depth = Math.max(1, Math.min(maxDepth, properties.maxTraversalDepth()));
        String key = connection + "\u0000" + normalize(tableName) + "\u0000" + depth;
        return analysisCache.get(key, k -> computeAnalysis(tableName, connection, depth));
    }

    private LineageAnalysis computeAnalysis(String tableName, String connection, int depth) {
        List<LineageEdge> directUpstream = getUpstream(tableName, connection);
        List<LineageEdge> directDownstream = getDownstream(tableName, connection);

        int nodeCap = properties.maxTablesPerGraph();
        Traversal up = traverse(connection, tableName, directUpstream, true, depth, nodeCap);
        Traversal down = traverse(connection, tableName, directDownstream, false, depth, nodeCap);

        List<LineageAnomaly> anomalies = new ArrayList<>(
                detectAnomalies(tableName, connection, up.tables(), down.tables()));
        if (up.truncated()) {
            anomalies.add(truncationAnomaly(tableName, "upstream", nodeCap));
        }
        if (down.truncated()) {
            anomalies.add(truncationAnomaly(tableName, "downstream", nodeCap));
        }

        return new LineageAnalysis(
                tableName, connection,
                directUpstream, directDownstream,
                up.tables(), down.tables(),
                Math.max(up.depthReached(), down.depthReached()), anomalies,
                directUpstream.size() + directDownstream.size()
        );
    }

    /**
     * Breadth-first walk in one direction.
     *
     * <p>Bounded by both depth and total node count: without the node cap a hub table in a wide
     * schema fans out to a query per discovered table per level, which is the fastest way to
     * exhaust the connection pool. Hitting the cap is reported as a {@code TRUNCATED} anomaly
     * rather than silently returning a partial graph.
     */
    private Traversal traverse(String connection, String rootTable, List<LineageEdge> seedEdges,
                               boolean upstream, int maxDepth, int nodeCap) {
        String root = normalize(rootTable);
        Set<String> visited = new HashSet<>();
        visited.add(root);

        Set<String> collected = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        boolean truncated = false;
        int depthReached = 0;

        for (LineageEdge edge : seedEdges) {
            String neighbour = normalize(neighbourOf(edge, upstream));
            if (neighbour.isEmpty() || !visited.add(neighbour)) {
                continue;
            }
            if (collected.size() >= nodeCap) {
                truncated = true;
                break;
            }
            collected.add(neighbour);
            queue.add(neighbour);
            depthReached = 1;
        }

        int currentDepth = 1;
        while (!queue.isEmpty() && currentDepth < maxDepth && !truncated) {
            int levelSize = queue.size();
            boolean levelProducedNodes = false;
            for (int i = 0; i < levelSize && !truncated; i++) {
                String current = queue.poll();
                List<LineageEdge> edges = upstream
                        ? getUpstream(current, connection)
                        : getDownstream(current, connection);
                for (LineageEdge edge : edges) {
                    String neighbour = normalize(neighbourOf(edge, upstream));
                    if (neighbour.isEmpty() || !visited.add(neighbour)) {
                        continue;
                    }
                    if (collected.size() >= nodeCap) {
                        truncated = true;
                        break;
                    }
                    collected.add(neighbour);
                    queue.add(neighbour);
                    levelProducedNodes = true;
                }
            }
            currentDepth++;
            if (levelProducedNodes) {
                depthReached = currentDepth;
            }
        }
        return new Traversal(new ArrayList<>(collected), depthReached, truncated);
    }

    /** With the orientation contract in place the neighbour is simply the far end of the edge. */
    private static String neighbourOf(LineageEdge edge, boolean upstream) {
        return upstream ? edge.sourceTable() : edge.targetTable();
    }

    private LineageAnomaly truncationAnomaly(String tableName, String direction, int nodeCap) {
        return new LineageAnomaly("TRUNCATED",
                "The " + direction + " graph of " + tableName + " was truncated at "
                        + nodeCap + " tables (entropy.mcp.database.lineage.max-tables-per-graph); "
                        + "the result is incomplete",
                List.of(tableName));
    }

    @Override
    public List<String> getImpactTables(String tableName, String connection, int maxDepth) {
        return analyze(tableName, connection, maxDepth).allDownstream();
    }

    // ─── Format Export ───────────────────────────────────────────────────────

    @Override
    public String exportMermaid(String tableName, String connection, int maxDepth) {
        LineageAnalysis analysis = analyze(tableName, connection, maxDepth);
        String root = normalize(tableName);
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");
        sb.append("    subgraph lineage['Lineage: ").append(tableName).append("']\n");
        sb.append("        T[\"").append(tableName).append("\"]\n");

        for (LineageEdge e : analysis.directUpstream()) {
            String neighbour = e.sourceTable();
            if (normalize(neighbour).equals(root)) {
                continue;   // self-referencing FK: an arrow from T to T carries no information
            }
            String safe = escapeMermaid(neighbour);
            sb.append("        ").append(safe).append("[\"").append(safe).append("\"]\n");
            sb.append("        ").append(safe).append("-->|FK| T\n");
        }
        for (LineageEdge e : analysis.directDownstream()) {
            String neighbour = e.targetTable();
            if (normalize(neighbour).equals(root)) {
                continue;
            }
            String safe = escapeMermaid(neighbour);
            sb.append("        ").append(safe).append("[\"").append(safe).append("\"]\n");
            sb.append("        T -->|FK| ").append(safe).append("\n");
        }
        sb.append("    end\n");
        return sb.toString();
    }

    @Override
    public String exportDot(String tableName, String connection, int maxDepth) {
        LineageAnalysis analysis = analyze(tableName, connection, maxDepth);
        String root = normalize(tableName);
        StringBuilder sb = new StringBuilder();
        sb.append("digraph lineage {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  node [shape=box];\n\n");
        sb.append("  \"").append(tableName).append("\" [style=filled fillcolor=lightblue];\n");

        for (LineageEdge e : analysis.directUpstream()) {
            if (normalize(e.sourceTable()).equals(root)) {
                continue;
            }
            sb.append("  \"").append(e.sourceTable()).append("\" -> \"").append(tableName)
              .append("\" [label=\"").append(e.sourceColumn()).append("\"];\n");
        }
        for (LineageEdge e : analysis.directDownstream()) {
            if (normalize(e.targetTable()).equals(root)) {
                continue;
            }
            sb.append("  \"").append(tableName).append("\" -> \"").append(e.targetTable())
              .append("\" [label=\"").append(e.targetColumn()).append("\"];\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Every foreign key of the connection's default schema.
     *
     * <p>Prefers the dialect's whole-schema query: the per-table fallback below costs one round trip
     * per table, so a thousand-table schema cost a thousand queries and was capped at
     * {@code max-tables-per-graph}, silently returning a partial graph. Dialects that cannot report
     * all edges at once return {@code null} and the per-table walk is used unchanged.
     */
    @Override
    public List<LineageEdge> listAllEdges(String connection) {
        if (!properties.foreignKeyEnabled()) {
            return List.of();
        }
        List<LineageEdge> schemaWide = fetchAllEdges(connection);
        if (schemaWide != null) {
            return dedupe(schemaWide);
        }
        return listAllEdgesPerTable(connection);
    }

    /**
     * @return all edges of the schema, or {@code null} when the dialect has no whole-schema query -
     *         which is different from "the schema has no foreign keys" and must not be conflated
     *         with an empty list
     */
    private List<LineageEdge> fetchAllEdges(String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            String sql = dialect.foreignKeyAllEdgesQuery(null);
            if (sql == null) {
                return null;
            }
            return toEdges(ctx.getJdbcTemplate().queryForList(sql), null, connection);
        } catch (Exception e) {
            log.warn("Whole-schema foreign-key read failed, falling back to one query per table: {}",
                    e.getMessage(), e);
            return null;
        }
    }

    private List<LineageEdge> listAllEdgesPerTable(String connection) {
        List<Map<String, Object>> tables = listBaseTables(connection);
        if (tables.isEmpty()) return List.of();

        int tableCap = properties.maxTablesPerGraph();
        if (tables.size() > tableCap) {
            log.warn("listAllEdges: {} tables found, only the first {} are inspected "
                            + "(one foreign-key query per table); raise "
                            + "entropy.mcp.database.lineage.max-tables-per-graph to widen",
                    tables.size(), tableCap);
            tables = tables.subList(0, tableCap);
        }

        List<LineageEdge> allEdges = new ArrayList<>();
        for (Map<String, Object> t : tables) {
            String tName = rowString(t, "table_name");
            if (tName == null || tName.isBlank()) {
                continue;
            }
            try {
                allEdges.addAll(fetchEdges(tName, connection, true));
            } catch (Exception ex) {
                log.debug("Could not fetch upstream for {}: {}", tName, ex.getMessage());
            }
        }
        return dedupe(allEdges);
    }

    /** One row per (parent, child, parent column, child column); dialects repeat an edge per table end. */
    private static List<LineageEdge> dedupe(List<LineageEdge> edges) {
        List<LineageEdge> unique = new ArrayList<>(edges.size());
        Set<String> seen = new HashSet<>();
        for (LineageEdge e : edges) {
            String key = normalize(e.sourceTable()) + "->" + normalize(e.targetTable())
                    + ":" + e.sourceColumn() + ":" + e.targetColumn();
            if (seen.add(key)) {
                unique.add(e);
            }
        }
        return unique;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Reads foreign-key edges for one table.
     *
     * <p>The dialect already orients the row (parent in {@code source_*}, child in {@code target_*})
     * and already filters the correct side, so the row is taken as it is. The only judgement left is
     * the sanity check in {@link #attribute}.
     */
    private List<LineageEdge> fetchEdges(String tableName, String connection, boolean upstream) {
        if (!properties.foreignKeyEnabled()) {
            return List.of();
        }
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            String sql = upstream
                    ? dialect.foreignKeyUpstreamQuery(tableName)
                    : dialect.foreignKeyDownstreamQuery(tableName);
            if (sql == null) return List.of();

            String queried = dialect.normalizeTableName(tableName);
            return toEdges(jdbc.queryForList(sql, queried), queried, connection);
        } catch (Exception e) {
            log.warn("Failed to fetch edges for {}: {}", tableName, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Converts dialect rows into edges.
     *
     * @param queried the table the rows were requested for, or {@code null} for a whole-schema read
     *                where no single table is expected to appear in every row
     */
    private List<LineageEdge> toEdges(List<Map<String, Object>> rows, String queried, String connection) {
        List<LineageEdge> edges = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String srcTable = rowString(row, "source_table");
            String tgtTable = rowString(row, "target_table");
            if (srcTable == null || tgtTable == null) continue;

            if (queried != null) {
                attribute(queried, srcTable, tgtTable);
            }
            edges.add(new LineageEdge(srcTable, tgtTable,
                    rowString(row, "source_column"), rowString(row, "target_column"),
                    LineageType.FOREIGN_KEY, connection, null));
        }
        return edges;
    }

    /**
     * Flags a row that cannot be attributed to the queried table.
     *
     * <p>Both ends being foreign to the queried table means the dialect's {@code WHERE} clause did not
     * filter what the contract says it filters. The row is still returned - dropping it would hide
     * real foreign keys - but it must not pass unnoticed, because it is the signature of the exact
     * defect this contract was introduced to prevent.
     */
    private void attribute(String queried, String srcTable, String tgtTable) {
        String self = normalize(queried);
        if (!normalize(srcTable).equals(self) && !normalize(tgtTable).equals(self)) {
            log.warn("Lineage row for {} mentions neither end ({} -> {}); the dialect filtered the "
                            + "wrong side of the foreign key",
                    queried, srcTable, tgtTable);
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
        }
    }

    private List<LineageAnomaly> detectAnomalies(String tableName, String connection,
                                                   Collection<String> allUpstream,
                                                   Collection<String> allDownstream) {
        List<LineageAnomaly> anomalies = new ArrayList<>();
        String self = normalize(tableName);
        // Check for cycle: if the analyzed table appears in its own downstream
        if (allDownstream.stream().anyMatch(t -> normalize(t).equals(self))) {
            anomalies.add(new LineageAnomaly("CYCLE",
                    "Table " + tableName + " is both ancestor and descendant of itself",
                    List.of(tableName)));
        }
        // Check for orphan tables (referenced but not in source table list)
        Set<String> knownTables = new HashSet<>();
        try {
            for (Map<String, Object> t : listBaseTables(connection)) {
                knownTables.add(normalize(rowString(t, "table_name")));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch base tables for lineage analysis on connection {}: {}",
                    connection, e.getClass().getSimpleName());
        }
        if (knownTables.isEmpty()) {
            return anomalies;   // no table list means no basis for an orphan verdict
        }
        Set<String> unknownUp = new LinkedHashSet<>();
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

    private static String normalize(String name) {
        if (name == null) return "";
        return name.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Case-insensitive column lookup: Oracle and H2 label result columns upper case while MySQL and
     * PostgreSQL keep them lower case.
     */
    private static String rowString(Map<String, Object> row, String column) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase(Locale.ROOT));
        }
        if (value == null) {
            value = row.get(column.toLowerCase(Locale.ROOT));
        }
        if (value == null) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        return value != null ? String.valueOf(value) : null;
    }

    private String escapeMermaid(String s) {
        return s.replaceAll("[\"\\[\\]{}]", "_");
    }

    /**
     * Outcome of one directional traversal.
     *
     * @param tables       discovered tables, breadth-first order, root excluded
     * @param depthReached depth of the deepest discovered table
     * @param truncated    whether the node cap stopped the walk early
     */
    private record Traversal(List<String> tables, int depthReached, boolean truncated) {
    }
}
