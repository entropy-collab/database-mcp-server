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
package com.entropy.database.mcp.dialect;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the bind-parameter and direction contracts documented on {@link DatabaseDialect}.
 *
 * <p>Before this contract existed, each caller derived its argument list by counting {@code ?} in the
 * returned SQL, because the dialects named the table one, two or three times. Counting cannot tell
 * "the table name twice" from "the table name and then the schema", so as soon as a second parameter
 * appeared the caller bound the wrong value: the schema predicate silently never matched and the
 * metric came back as -1. The counts asserted here are therefore part of the API, not an
 * implementation detail.
 *
 * <p>The direction contract is asserted two ways. On H2 it is verified end to end against real
 * foreign keys. For Oracle, MySQL and PostgreSQL it is verified on the SQL text, because their
 * catalogs ({@code all_constraints}, {@code key_column_usage.referenced_table_name},
 * {@code constraint_column_usage}) exist on no embeddable database - so without a live server the
 * only checkable fact is which side of the join the placeholder constrains, which is precisely the
 * defect that made MySQL swap upstream and downstream.
 */
class DialectMetadataContractTest {

    private static final String TABLE = "CUSTOMER";
    private static final String SCHEMA = "HR";

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void createH2Schema() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dialectcontract;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("DROP TABLE IF EXISTS ORDER_LINE");
        jdbc.execute("DROP TABLE IF EXISTS CUSTOMER_ORDER");
        jdbc.execute("CREATE TABLE CUSTOMER_ORDER (ID INT PRIMARY KEY, PLACED_ON DATE)");
        jdbc.execute("""
                CREATE TABLE ORDER_LINE (
                    ID INT PRIMARY KEY,
                    ORDER_ID INT,
                    CONSTRAINT FK_LINE_ORDER FOREIGN KEY (ORDER_ID)
                        REFERENCES CUSTOMER_ORDER(ID)
                )
                """);
        jdbc.execute("COMMENT ON COLUMN ORDER_LINE.ORDER_ID IS '所属订单'");
        jdbc.update("INSERT INTO CUSTOMER_ORDER (ID) VALUES (1)");
        jdbc.update("INSERT INTO CUSTOMER_ORDER (ID) VALUES (2)");
        jdbc.update("INSERT INTO CUSTOMER_ORDER (ID) VALUES (3)");
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────

    private static DatabaseDialect oracle() {
        return new OracleDialect();
    }

    private static DatabaseDialect mysql() {
        return new MySqlDialect();
    }

    private static DatabaseDialect postgres() {
        return new PostgresDialect();
    }

    private static DatabaseDialect h2() {
        return new H2Dialect();
    }

    /** Every dialect, so a newly added one cannot quietly skip the contract. */
    private static Stream<DatabaseDialect> allDialects() {
        return Stream.of(oracle(), mysql(), postgres(), h2(),
                new SqlServerDialect(), new SqliteDialect(), new Db2Dialect(), new GenericDialect());
    }

    /** The four dialects that implement the comment and lineage metadata queries. */
    private static Stream<DatabaseDialect> metadataDialects() {
        return Stream.of(oracle(), mysql(), postgres(), h2());
    }

    /** The three dialects with a real table-size source and index statistics. */
    private static Stream<DatabaseDialect> statisticsAwareDialects() {
        return Stream.of(oracle(), mysql(), postgres());
    }

    private static int placeholders(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }

    /** Collapses runs of whitespace so column-alignment inside the SQL is not part of the assertion. */
    private static String flat(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    // ─── One placeholder: the table name ──────────────────────────────────

    @ParameterizedTest
    @MethodSource("metadataDialects")
    @DisplayName("columnCommentsQuery declares exactly one placeholder")
    void columnCommentsQueryHasOnePlaceholder(DatabaseDialect dialect) {
        assertThat(placeholders(dialect.columnCommentsQuery(TABLE)))
                .as("%s.columnCommentsQuery", dialect.getDialectName())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("metadataDialects")
    @DisplayName("tableCommentQuery declares exactly one placeholder")
    void tableCommentQueryHasOnePlaceholder(DatabaseDialect dialect) {
        assertThat(placeholders(dialect.tableCommentQuery(null, TABLE)))
                .as("%s.tableCommentQuery", dialect.getDialectName())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("statisticsAwareDialects")
    @DisplayName("estimateTableSizeSql declares exactly one placeholder")
    void estimateTableSizeSqlHasOnePlaceholder(DatabaseDialect dialect) {
        assertThat(placeholders(dialect.estimateTableSizeSql(TABLE, null)))
                .as("%s.estimateTableSizeSql", dialect.getDialectName())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("statisticsAwareDialects")
    @DisplayName("candidateColumnsForIndexSql declares exactly one placeholder")
    void candidateColumnsForIndexSqlHasOnePlaceholder(DatabaseDialect dialect) {
        assertThat(placeholders(dialect.candidateColumnsForIndexSql(TABLE)))
                .as("%s.candidateColumnsForIndexSql", dialect.getDialectName())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("statisticsAwareDialects")
    @DisplayName("listTableIndexesSql declares exactly one placeholder")
    void listTableIndexesSqlHasOnePlaceholder(DatabaseDialect dialect) {
        assertThat(placeholders(dialect.listTableIndexesSql(TABLE)))
                .as("%s.listTableIndexesSql", dialect.getDialectName())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("statisticsAwareDialects")
    @DisplayName("getTableRowCountEstimateSql declares exactly one placeholder")
    void rowCountEstimateSqlHasOnePlaceholder(DatabaseDialect dialect) {
        assertThat(placeholders(dialect.getTableRowCountEstimateSql(TABLE)))
                .as("%s.getTableRowCountEstimateSql", dialect.getDialectName())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("metadataDialects")
    @DisplayName("both foreign-key queries declare exactly one placeholder")
    void foreignKeyQueriesHaveOnePlaceholder(DatabaseDialect dialect) {
        assertThat(placeholders(dialect.foreignKeyUpstreamQuery(TABLE)))
                .as("%s.foreignKeyUpstreamQuery", dialect.getDialectName())
                .isEqualTo(1);
        assertThat(placeholders(dialect.foreignKeyDownstreamQuery(TABLE)))
                .as("%s.foreignKeyDownstreamQuery", dialect.getDialectName())
                .isEqualTo(1);
    }

    /**
     * The generic dialect is the one documented deviation: it has no size source, so the row is a
     * constant and there is nothing to filter.
     */
    @Test
    @DisplayName("the generic dialect's size stub declares no placeholder and cannot be injected")
    void genericSizeStubIsConstant() {
        GenericDialect generic = new GenericDialect();

        assertThat(placeholders(generic.estimateTableSizeSql(TABLE, null))).isZero();
        assertThat(generic.estimateTableSizeSql("x' UNION SELECT 1 --", null))
                .doesNotContain("UNION");
    }

    // ─── No placeholder: the identifier is quoted into the SQL ─────────────

    @ParameterizedTest
    @MethodSource("allDialects")
    @DisplayName("getTableRowCountSql declares no placeholder and quotes the table name")
    void rowCountSqlHasNoPlaceholder(DatabaseDialect dialect) {
        String sql = dialect.getTableRowCountSql(TABLE);

        assertThat(sql).as("%s must be able to count rows", dialect.getDialectName()).isNotNull();
        assertThat(placeholders(sql))
                .as("%s.getTableRowCountSql", dialect.getDialectName())
                .isZero();
        assertThat(sql).contains(dialect.quote(TABLE));
    }

    @ParameterizedTest
    @MethodSource("allDialects")
    @DisplayName("getTableRowCountSql schema-qualifies the table when a schema is given")
    void rowCountSqlQualifiesTheSchema(DatabaseDialect dialect) {
        String sql = dialect.getTableRowCountSql(SCHEMA, TABLE);

        assertThat(placeholders(sql)).isZero();
        if (dialect.supportsSchema()) {
            assertThat(sql).contains(dialect.quote(SCHEMA) + "." + dialect.quote(TABLE));
        }
    }

    @ParameterizedTest
    @MethodSource("metadataDialects")
    @DisplayName("the whole-schema comment and lineage queries declare no placeholder")
    void wholeSchemaQueriesHaveNoPlaceholder(DatabaseDialect dialect) {
        assertThat(placeholders(dialect.tableCommentsQuery()))
                .as("%s.tableCommentsQuery", dialect.getDialectName())
                .isZero();
        assertThat(placeholders(dialect.foreignKeyAllEdgesQuery(null)))
                .as("%s.foreignKeyAllEdgesQuery", dialect.getDialectName())
                .isZero();
    }

    // ─── The schema never becomes a placeholder ────────────────────────────

    private static Stream<Arguments> schemaAwareQueries() {
        return metadataDialects().flatMap(d -> Stream.of(
                Arguments.of(d.getDialectName() + ".columnCommentsQuery",
                        d.columnCommentsQuery(null, TABLE), d.columnCommentsQuery(SCHEMA, TABLE)),
                Arguments.of(d.getDialectName() + ".tableCommentQuery",
                        d.tableCommentQuery(null, TABLE), d.tableCommentQuery(SCHEMA, TABLE)),
                Arguments.of(d.getDialectName() + ".tableCommentsQuery",
                        d.tableCommentsQuery(null), d.tableCommentsQuery(SCHEMA)),
                Arguments.of(d.getDialectName() + ".foreignKeyAllEdgesQuery",
                        d.foreignKeyAllEdgesQuery(null), d.foreignKeyAllEdgesQuery(SCHEMA)),
                Arguments.of(d.getDialectName() + ".getTableRowCountEstimateSql",
                        d.getTableRowCountEstimateSql(null, TABLE),
                        d.getTableRowCountEstimateSql(SCHEMA, TABLE)),
                Arguments.of(d.getDialectName() + ".estimateTableSizeSql",
                        d.estimateTableSizeSql(TABLE, null), d.estimateTableSizeSql(TABLE, SCHEMA))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaAwareQueries")
    @DisplayName("supplying a schema neither adds a bind parameter nor yields IS NULL")
    void schemaIsNeverAPlaceholder(String label, String withoutSchema, String withSchema) {
        if (withoutSchema == null) {
            assertThat(withSchema)
                    .as("%s: both variants must be supported or neither", label)
                    .isNull();
            return;
        }
        assertThat(placeholders(withSchema))
                .as("%s: the schema must not add a bind parameter", label)
                .isEqualTo(placeholders(withoutSchema));
        // A `<schema column> IS NULL` predicate matches no row in any information schema, so a missing
        // schema has to degrade to the current one instead.
        assertThat(withoutSchema.toUpperCase())
                .as("%s must not filter on a null schema", label)
                .doesNotContain("IS NULL AND");
        assertThat(withSchema).as("%s must honour the requested schema", label).contains(SCHEMA);
    }

    // ─── Direction, asserted on the SQL text ──────────────────────────────

    @Test
    @DisplayName("Oracle filters the child upstream and the parent downstream")
    void oracleFiltersTheContractedSide() {
        assertThat(oracle().foreignKeyUpstreamQuery(TABLE))
                .contains("uc_fk.table_name = ?")
                .doesNotContain("uc_pk.table_name = ?");
        assertThat(oracle().foreignKeyDownstreamQuery(TABLE))
                .contains("uc_pk.table_name = ?")
                .doesNotContain("uc_fk.table_name = ?");
        // source_* is the parent side in both directions.
        assertThat(flat(oracle().foreignKeyUpstreamQuery(TABLE)))
                .contains("uc_pk.table_name AS source_table")
                .contains("uc_fk.table_name AS target_table");
    }

    @Test
    @DisplayName("MySQL filters the child upstream and the parent downstream")
    void mysqlFiltersTheContractedSide() {
        // The defect this pins: the upstream query used to constrain the referenced side, so it
        // returned the tables pointing at the queried one - its downstream, not its upstream.
        assertThat(mysql().foreignKeyUpstreamQuery(TABLE))
                .contains("kcu.table_name = ?")
                .doesNotContain("kcu.referenced_table_name = ?");
        assertThat(mysql().foreignKeyDownstreamQuery(TABLE))
                .contains("kcu.referenced_table_name = ?");
        assertThat(flat(mysql().foreignKeyUpstreamQuery(TABLE)))
                .contains("kcu.referenced_table_name AS source_table")
                .contains("kcu.table_name AS target_table");
    }

    @Test
    @DisplayName("PostgreSQL filters the child upstream and the parent downstream")
    void postgresFiltersTheContractedSide() {
        assertThat(postgres().foreignKeyUpstreamQuery(TABLE))
                .contains("tc.table_name = ?")
                .doesNotContain("ccu.table_name = ?");
        assertThat(postgres().foreignKeyDownstreamQuery(TABLE))
                .contains("ccu.table_name = ?");
        assertThat(flat(postgres().foreignKeyUpstreamQuery(TABLE)))
                .contains("ccu.table_name AS source_table")
                .contains("tc.table_name AS target_table");
    }

    @Test
    @DisplayName("PostgreSQL no longer equates the referencing and the referenced table")
    void postgresDoesNotSelfJoinTheConstraintViews() {
        // `ccu.table_name = tc.table_name` holds only for a self-reference: in a foreign key
        // tc.table_name is the child and ccu.table_name the parent, so both queries returned nothing.
        assertThat(flat(postgres().foreignKeyUpstreamQuery(TABLE)))
                .doesNotContain("ccu.table_name = tc.table_name");
        assertThat(flat(postgres().foreignKeyDownstreamQuery(TABLE)))
                .doesNotContain("ccu.table_name = tc.table_name");
    }

    // ─── Direction and metadata, executed against H2 ──────────────────────

    @Test
    @DisplayName("H2: upstream of the child returns the parent, oriented parent -> child")
    void h2UpstreamReturnsTheParent() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                h2().foreignKeyUpstreamQuery("ORDER_LINE"), "ORDER_LINE");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("SOURCE_TABLE", "CUSTOMER_ORDER")
                .containsEntry("TARGET_TABLE", "ORDER_LINE")
                .containsEntry("SOURCE_COLUMN", "ID")
                .containsEntry("TARGET_COLUMN", "ORDER_ID");
    }

    @Test
    @DisplayName("H2: downstream of the parent returns the child, oriented parent -> child")
    void h2DownstreamReturnsTheChild() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                h2().foreignKeyDownstreamQuery("CUSTOMER_ORDER"), "CUSTOMER_ORDER");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("SOURCE_TABLE", "CUSTOMER_ORDER")
                .containsEntry("TARGET_TABLE", "ORDER_LINE");
    }

    @Test
    @DisplayName("H2: the whole-schema query returns the same edge in one round trip")
    void h2AllEdgesMatchThePerTableQueries() {
        List<Map<String, Object>> rows = jdbc.queryForList(h2().foreignKeyAllEdgesQuery(null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("SOURCE_TABLE", "CUSTOMER_ORDER")
                .containsEntry("TARGET_TABLE", "ORDER_LINE");
    }

    @Test
    @DisplayName("H2: columnsQuery with a null schema finds the columns instead of nothing")
    void h2ColumnsQueryWithoutSchemaResolvesColumns() {
        String sql = h2().columnsQuery("ORDER_LINE", null);

        assertThat(sql).doesNotContain("IS NULL");
        assertThat(jdbc.queryForList(sql, "ORDER_LINE"))
                .extracting(row -> row.get("COLUMN_NAME"))
                .containsExactly("ID", "ORDER_ID");
    }

    @Test
    @DisplayName("H2: getTableRowCountSql returns the real row count")
    void h2RowCountIsExact() {
        assertThat(jdbc.queryForObject(h2().getTableRowCountSql("CUSTOMER_ORDER"), Long.class))
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("H2: columnCommentsQuery reads REMARKS, so classification works on H2")
    void h2ColumnCommentsAreRead() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                h2().columnCommentsQuery("ORDER_LINE"), "ORDER_LINE");

        assertThat(rows).extracting(row -> row.get("COLUMN_NAME"))
                .containsExactly("ID", "ORDER_ID");
        assertThat(rows.get(1)).containsEntry("COLUMN_COMMENT", "所属订单");
    }
}
