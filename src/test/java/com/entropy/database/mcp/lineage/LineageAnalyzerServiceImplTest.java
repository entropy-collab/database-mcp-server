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
import com.entropy.database.mcp.byok.ByokInfrastructure;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.byok.StatementTemplates;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.properties.LineageProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the edge-direction contract against a real foreign key on a real H2 database.
 *
 * <p>The dialects now own the orientation: {@code source_*} is the referenced (parent) side,
 * {@code target_*} the referencing (child) side, the upstream query filters the child side and the
 * downstream query filters the parent side. These tests use the production {@link H2Dialect} so the
 * SQL actually runs, and assert the direction end to end rather than trusting a hand-written stub.
 *
 * <p>The last case covers what the service still has to judge on its own: a dialect that filtered
 * the wrong side returns rows belonging to some other table, and that must not be reshaped into a
 * plausible-looking edge.
 */
class LineageAnalyzerServiceImplTest {

    private static final String CONNECTION = "h2-lineage";

    private static JdbcTemplate jdbcTemplate;

    /**
     * Cyclic fixtures live in their own database: a self-reference and a mutual foreign key would
     * otherwise show up in the whole-schema edge list the acyclic tests assert on exactly.
     */
    private static JdbcTemplate cyclicJdbcTemplate;

    @BeforeAll
    static void createSchema() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:lineagesvc;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);

        jdbcTemplate.execute("DROP TABLE IF EXISTS GRANDCHILD");
        jdbcTemplate.execute("DROP TABLE IF EXISTS CHILD");
        jdbcTemplate.execute("DROP TABLE IF EXISTS PARENT");
        jdbcTemplate.execute("CREATE TABLE PARENT (ID INT PRIMARY KEY, NAME VARCHAR(40))");
        jdbcTemplate.execute("""
                CREATE TABLE CHILD (
                    ID INT PRIMARY KEY,
                    PARENT_ID INT,
                    CONSTRAINT FK_CHILD_PARENT FOREIGN KEY (PARENT_ID) REFERENCES PARENT(ID)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE GRANDCHILD (
                    ID INT PRIMARY KEY,
                    CHILD_ID INT,
                    CONSTRAINT FK_GC_CHILD FOREIGN KEY (CHILD_ID) REFERENCES CHILD(ID)
                )
                """);

        org.h2.jdbcx.JdbcDataSource cyclicDs = new org.h2.jdbcx.JdbcDataSource();
        cyclicDs.setURL("jdbc:h2:mem:lineagecycle;DB_CLOSE_DELAY=-1");
        cyclicDs.setUser("sa");
        cyclicDs.setPassword("");
        cyclicJdbcTemplate = new JdbcTemplate(cyclicDs);

        cyclicJdbcTemplate.execute("DROP TABLE IF EXISTS LOOP_A");
        cyclicJdbcTemplate.execute("DROP TABLE IF EXISTS LOOP_B");
        cyclicJdbcTemplate.execute("DROP TABLE IF EXISTS SELF_REF");
        cyclicJdbcTemplate.execute("""
                CREATE TABLE SELF_REF (
                    ID INT PRIMARY KEY,
                    PARENT_ID INT,
                    CONSTRAINT FK_SELF FOREIGN KEY (PARENT_ID) REFERENCES SELF_REF(ID)
                )
                """);
        cyclicJdbcTemplate.execute("CREATE TABLE LOOP_A (ID INT PRIMARY KEY, B_ID INT)");
        cyclicJdbcTemplate.execute("""
                CREATE TABLE LOOP_B (
                    ID INT PRIMARY KEY,
                    A_ID INT,
                    CONSTRAINT FK_B_A FOREIGN KEY (A_ID) REFERENCES LOOP_A(ID)
                )
                """);
        cyclicJdbcTemplate.execute(
                "ALTER TABLE LOOP_A ADD CONSTRAINT FK_A_B FOREIGN KEY (B_ID) REFERENCES LOOP_B(ID)");
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────

    private LineageAnalyzerServiceImpl service() {
        return service(new H2Dialect(), new LineageProperties());
    }

    private LineageAnalyzerServiceImpl service(H2Dialect dialect, LineageProperties properties) {
        return service(dialect, properties, jdbcTemplate);
    }

    private LineageAnalyzerServiceImpl cyclicService() {
        return service(new H2Dialect(), new LineageProperties(), cyclicJdbcTemplate);
    }

    private LineageAnalyzerServiceImpl service(H2Dialect dialect, LineageProperties properties,
                                               JdbcTemplate jdbc) {
        ByokDataSourceContext ctx = new ByokDataSourceContext(CONNECTION,
                jdbc.getDataSource(), dialect,
                StatementTemplates.over(jdbc.getDataSource(), jdbc, null),
                new ByokInfrastructure(null, null, null, null, null, null));
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire(anyString())).thenReturn(ctx);
        return new LineageAnalyzerServiceImpl(manager, properties);
    }

    // ─── Direction ────────────────────────────────────────────────────────

    @Test
    @DisplayName("upstream of the child is the parent, oriented parent -> child")
    void upstreamPointsFromParentToChild() {
        List<LineageEdge> upstream = service().getUpstream("CHILD", CONNECTION);

        assertThat(upstream).hasSize(1);
        LineageEdge edge = upstream.get(0);
        assertThat(edge.sourceTable()).isEqualTo("PARENT");
        assertThat(edge.targetTable()).isEqualTo("CHILD");
        assertThat(edge.sourceColumn()).isEqualTo("ID");
        assertThat(edge.targetColumn()).isEqualTo("PARENT_ID");
        assertThat(edge.type()).isEqualTo(LineageType.FOREIGN_KEY);
    }

    @Test
    @DisplayName("downstream of the parent is the child, oriented parent -> child")
    void downstreamPointsFromParentToChild() {
        List<LineageEdge> downstream = service().getDownstream("PARENT", CONNECTION);

        assertThat(downstream).hasSize(1);
        LineageEdge edge = downstream.get(0);
        assertThat(edge.sourceTable()).isEqualTo("PARENT");
        assertThat(edge.targetTable()).isEqualTo("CHILD");
    }

    @Test
    @DisplayName("a row belonging to neither end is passed through, not reshaped")
    void unattributableRowKeepsDialectOrientation() {
        // A dialect that filters the wrong side answers a lookup for GRANDCHILD with the
        // PARENT -> CHILD row. The service must hand that row back as the dialect produced it
        // instead of rewriting one of its ends to the queried table, which would invent an edge.
        List<LineageEdge> edges = service(new WrongSideH2Dialect(), new LineageProperties())
                .getUpstream("GRANDCHILD", CONNECTION);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).sourceTable()).isEqualTo("PARENT");
        assertThat(edges.get(0).targetTable()).isEqualTo("CHILD");
    }

    // ─── Traversal ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the parent is reported as transitive upstream of the grandchild")
    void transitiveUpstreamIsCollected() {
        LineageAnalysis analysis = service().analyze("GRANDCHILD", CONNECTION, 5);

        assertThat(analysis.allUpstream()).containsExactly("CHILD", "PARENT");
        assertThat(analysis.hasUpstream()).isTrue();
        assertThat(analysis.directUpstream()).extracting(LineageEdge::sourceTable)
                .containsExactly("CHILD");
    }

    @Test
    @DisplayName("the grandchild is reported as transitive downstream of the parent")
    void transitiveDownstreamIsCollected() {
        LineageAnalysis analysis = service().analyze("PARENT", CONNECTION, 5);

        assertThat(analysis.allDownstream()).containsExactly("CHILD", "GRANDCHILD");
        assertThat(analysis.anomalies()).extracting(LineageAnomaly::type).doesNotContain("CYCLE");
    }

    @Test
    @DisplayName("getImpactTables is no longer always empty")
    void impactTablesAreReported() {
        assertThat(service().getImpactTables("PARENT", CONNECTION, 5))
                .containsExactly("CHILD", "GRANDCHILD");
    }

    @Test
    @DisplayName("a table with no foreign keys has an empty, anomaly-free graph")
    void isolatedTableHasNoEdges() {
        LineageAnalysis analysis = service().analyze("PARENT", CONNECTION, 1);

        assertThat(analysis.directUpstream()).isEmpty();
        assertThat(analysis.allUpstream()).isEmpty();
    }

    @Test
    @DisplayName("the node cap truncates the walk and says so")
    void nodeCapIsReported() {
        LineageProperties capped = new LineageProperties(true, true, true, 10, true, 1);
        LineageAnalysis analysis = service(new H2Dialect(), capped)
                .analyze("PARENT", CONNECTION, 5);

        assertThat(analysis.allDownstream()).hasSize(1);
        assertThat(analysis.anomalies()).extracting(LineageAnomaly::type).contains("TRUNCATED");
    }

    @Test
    @DisplayName("repeated analysis of the same table is served from the short-lived cache")
    void analysisIsCached() {
        LineageAnalyzerServiceImpl service = service();

        LineageAnalysis first = service.analyze("PARENT", CONNECTION, 5);
        assertSame(first, service.analyze("PARENT", CONNECTION, 5));
    }

    // ─── Cycles ───────────────────────────────────────────────────────────

    /**
     * The CYCLE anomaly used to be looked for in {@code allDownstream}, which the traversal builds
     * with the root excluded by construction - so the condition could never hold and the assertion
     * that no cycle was reported was true of every schema, cyclic or not.
     */
    @Test
    @DisplayName("a self-referencing foreign key is reported as a cycle")
    void selfReferenceIsACycle() {
        LineageAnalysis analysis = cyclicService().analyze("SELF_REF", CONNECTION, 5);

        assertThat(analysis.anomalies()).extracting(LineageAnomaly::type).contains("CYCLE");
        // The edge itself is still reported; only the traversal excludes the root.
        assertThat(analysis.directUpstream()).extracting(LineageEdge::sourceTable)
                .containsExactly("SELF_REF");
        assertThat(analysis.allUpstream()).isEmpty();
    }

    @Test
    @DisplayName("a mutual foreign key A -> B -> A is reported as a cycle")
    void mutualForeignKeysAreACycle() {
        LineageAnalysis analysis = cyclicService().analyze("LOOP_A", CONNECTION, 5);

        assertThat(analysis.anomalies()).extracting(LineageAnomaly::type).contains("CYCLE");
        assertThat(analysis.allDownstream()).containsExactly("LOOP_B");
    }

    @Test
    @DisplayName("an acyclic graph reports no cycle, so the anomaly is not simply always raised")
    void acyclicGraphHasNoCycle() {
        assertThat(service().analyze("GRANDCHILD", CONNECTION, 5).anomalies())
                .extracting(LineageAnomaly::type).doesNotContain("CYCLE");
    }

    // ─── Export ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mermaid export draws the neighbour, never a self-loop")
    void mermaidHasNoSelfLoop() {
        String mermaid = service().exportMermaid("PARENT", CONNECTION, 5);

        assertThat(mermaid).contains("T -->|FK| CHILD");
        assertThat(mermaid).doesNotContain("T -->|FK| T");
        assertThat(mermaid).doesNotContain("T-->|FK| T");
        assertThat(mermaid).doesNotContain("PARENT-->|FK| T");
    }

    @Test
    @DisplayName("DOT export orients the arrow from parent to child")
    void dotIsOriented() {
        String dot = service().exportDot("CHILD", CONNECTION, 5);

        assertThat(dot).contains("\"PARENT\" -> \"CHILD\"");
        assertThat(dot).doesNotContain("\"CHILD\" -> \"CHILD\"");
    }

    @Test
    @DisplayName("listAllEdges reports each foreign key once, parent first")
    void allEdgesAreDeduplicated() {
        List<LineageEdge> edges = service().listAllEdges(CONNECTION);

        assertThat(edges).extracting(e -> e.sourceTable() + "->" + e.targetTable())
                .containsExactlyInAnyOrder("PARENT->CHILD", "CHILD->GRANDCHILD");
    }

    // ─── Test dialects ────────────────────────────────────────────────────

    /**
     * A dialect that breaks the contract by filtering the parent side in the upstream query - the
     * MySQL defect, reproduced deliberately. The parent is pinned to PARENT so a lookup for
     * GRANDCHILD is answered with the PARENT/CHILD row, whose ends have nothing to do with the
     * queried table; the bound value is still consumed, so the one-placeholder contract holds and
     * the service passes the same single argument it would pass to a correct dialect.
     */
    private static final class WrongSideH2Dialect extends H2Dialect {
        @Override
        public String foreignKeyUpstreamQuery(String tableName) {
            return super.foreignKeyDownstreamQuery(tableName)
                    .replace("AND parent.TABLE_NAME = ?",
                            "AND parent.TABLE_NAME = 'PARENT' AND CAST(? AS VARCHAR) IS NOT NULL");
        }
    }
}
