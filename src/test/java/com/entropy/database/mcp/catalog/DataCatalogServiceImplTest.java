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
package com.entropy.database.mcp.catalog;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.ByokInfrastructure;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.H2Dialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the failure modes that silently disabled the catalog, against a real H2 database.
 *
 * <p>The column-comment query carries a {@code table_name = ?} placeholder and used to be executed
 * with no arguments: every call threw, the exception was swallowed, and the resulting empty column
 * list made each table look non-sensitive. And {@code scanSchema} filled one shared
 * {@code ArrayList} from a {@code parallelStream()}, which loses entries under load.
 *
 * <p>The dialect under test is the production {@link H2Dialect}: it now implements the comment and
 * row-count queries itself, so the test no longer has to supply them from a subclass - which means
 * these tests exercise the SQL that actually ships.
 */
class DataCatalogServiceImplTest {

    private static final String CONNECTION = "h2-catalog";

    private static JdbcTemplate jdbcTemplate;

    /** Number of tables used by the concurrency check; comfortably above the parallelism cap. */
    private static final int WIDE_TABLE_COUNT = 30;

    @BeforeAll
    static void createSchema() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:catalogsvc;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);

        jdbcTemplate.execute("DROP TABLE IF EXISTS CUSTOMER");
        jdbcTemplate.execute("DROP TABLE IF EXISTS SHIPMENT");
        jdbcTemplate.execute("""
                CREATE TABLE CUSTOMER (
                    ID INT PRIMARY KEY,
                    EMAIL VARCHAR(120),
                    PASSWORD VARCHAR(120),
                    SALARY DECIMAL(12,2),
                    REGION VARCHAR(40)
                )
                """);
        jdbcTemplate.execute("CREATE TABLE SHIPMENT (ID INT PRIMARY KEY, SHIPMENT_NO VARCHAR(40))");
        jdbcTemplate.execute("COMMENT ON TABLE CUSTOMER IS '客户主表'");
        jdbcTemplate.execute("COMMENT ON COLUMN CUSTOMER.EMAIL IS '联系邮箱'");
        jdbcTemplate.execute("COMMENT ON COLUMN CUSTOMER.PASSWORD IS '登录口令'");
        jdbcTemplate.execute("COMMENT ON COLUMN CUSTOMER.SALARY IS '年薪'");

        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS WIDE");
        for (int i = 0; i < WIDE_TABLE_COUNT; i++) {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS WIDE.T" + i
                    + " (ID INT PRIMARY KEY, EMAIL VARCHAR(50))");
        }
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────

    private DataCatalogServiceImpl service() {
        return service(new H2Dialect());
    }

    private DataCatalogServiceImpl service(H2Dialect dialect) {
        ByokDataSourceContext ctx = new ByokDataSourceContext(CONNECTION,
                jdbcTemplate.getDataSource(), dialect, jdbcTemplate,
                new ByokInfrastructure(null, null, null, null, null, null));
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire(anyString())).thenReturn(ctx);
        return new DataCatalogServiceImpl(manager);
    }

    // ─── Column comments ──────────────────────────────────────────────────

    @Test
    @DisplayName("the table name is bound, so column comments are actually returned")
    void columnCommentsAreRead() {
        DataCatalogEntry entry = service().generateCatalog("CUSTOMER", CONNECTION);

        assertThat(entry.tableName()).isEqualTo("CUSTOMER");
        assertThat(entry.tableComment()).isEqualTo("客户主表");
        assertThat(entry.columns()).extracting(DataElement::columnName)
                .containsExactly("ID", "EMAIL", "PASSWORD", "SALARY", "REGION");
        assertThat(entry.columns()).extracting(DataElement::columnComment)
                .contains("联系邮箱", "登录口令", "年薪");
        assertThat(entry.description()).doesNotContain("查询失败");
    }

    @Test
    @DisplayName("the bound placeholder scopes the query to one table")
    void columnCommentsAreScopedToTheRequestedTable() {
        DataCatalogEntry entry = service().generateCatalog("SHIPMENT", CONNECTION);

        assertThat(entry.columns()).extracting(DataElement::columnName)
                .containsExactly("ID", "SHIPMENT_NO");
    }

    @Test
    @DisplayName("nullability survives the YES/NO spelling of information_schema")
    void nullabilityIsParsed() {
        DataCatalogEntry entry = service().generateCatalog("CUSTOMER", CONNECTION);

        DataElement id = entry.columns().get(0);
        DataElement email = entry.columns().get(1);
        assertThat(id.nullable()).isZero();
        assertThat(email.nullable()).isEqualTo(1);
    }

    @Test
    @DisplayName("sensitivity is inferred from the comments that are now available")
    void sensitivityIsInferred() {
        DataCatalogEntry entry = service().generateCatalog("CUSTOMER", CONNECTION);

        assertThat(entry.maxSensitivity().getLevel()).isGreaterThanOrEqualTo(2);
        assertThat(entry.hasSensitiveColumns()).isTrue();
    }

    @Test
    @DisplayName("getSensitiveColumns returns real columns instead of an empty list")
    void sensitiveColumnsAreReported() {
        List<DataElement> sensitive = service().getSensitiveColumns("PUBLIC", CONNECTION);

        assertThat(sensitive).isNotEmpty();
        assertThat(sensitive).extracting(DataElement::columnName)
                .contains("EMAIL", "PASSWORD", "SALARY");
        assertThat(sensitive).allSatisfy(col ->
                assertThat(col.sensitivityLevel().getLevel()).isGreaterThanOrEqualTo(2));
    }

    @Test
    @DisplayName("a failing column query is reported, not disguised as a table without columns")
    void columnQueryFailureIsSurfaced() {
        DataCatalogEntry entry = service(new BrokenColumnCommentsDialect())
                .generateCatalog("CUSTOMER", CONNECTION);

        assertThat(entry.columns()).isEmpty();
        assertThat(entry.description()).contains("列元数据查询失败");
    }

    @Test
    @DisplayName("a dialect without comment support yields columns-free entries without an error note")
    void unsupportedDialectIsNotAnError() {
        DataCatalogEntry entry = service(new NoCommentSupportDialect())
                .generateCatalog("CUSTOMER", CONNECTION);

        assertThat(entry.columns()).isEmpty();
        assertThat(entry.description()).doesNotContain("查询失败");
    }

    // ─── Row count ────────────────────────────────────────────────────────

    @Test
    @DisplayName("rowCount is the real number of rows, not the -1 every dialect used to report")
    void rowCountIsRead() {
        jdbcTemplate.execute("DELETE FROM SHIPMENT");
        jdbcTemplate.update("INSERT INTO SHIPMENT (ID, SHIPMENT_NO) VALUES (1, 'S-1')");
        jdbcTemplate.update("INSERT INTO SHIPMENT (ID, SHIPMENT_NO) VALUES (2, 'S-2')");

        assertThat(service().generateCatalog("SHIPMENT", CONNECTION).rowCount()).isEqualTo(2);
    }

    // ─── scanSchema ───────────────────────────────────────────────────────

    @RepeatedTest(value = 5, name = "concurrent scanSchema keeps every entry [{currentRepetition}/5]")
    void scanSchemaLosesNoEntries() {
        List<DataCatalogEntry> entries = service().scanSchema("WIDE", CONNECTION);

        assertThat(entries).hasSize(WIDE_TABLE_COUNT);
        assertThat(entries).doesNotContainNull();
        assertThat(entries).extracting(DataCatalogEntry::tableName)
                .doesNotHaveDuplicates()
                .allSatisfy(name -> assertThat(name).startsWith("T"));
        assertThat(entries).allSatisfy(entry ->
                assertThat(entry.columns()).extracting(DataElement::columnName)
                        .containsExactly("ID", "EMAIL"));
    }

    @Test
    @DisplayName("table names come back resolved, not the string \"null\"")
    void scanSchemaResolvesTableNames() {
        List<DataCatalogEntry> entries = service().scanSchema("WIDE", CONNECTION);

        assertThat(entries).extracting(DataCatalogEntry::tableName).doesNotContain("null");
    }

    @Test
    @DisplayName("an unknown schema scans nothing rather than failing")
    void unknownSchemaYieldsEmptyResult() {
        assertThat(service().scanSchema("NO_SUCH_SCHEMA", CONNECTION)).isEmpty();
    }

    // ─── Test dialects ────────────────────────────────────────────────────

    /** Stands in for a dialect whose comment query cannot run against the live schema. */
    private static final class BrokenColumnCommentsDialect extends H2Dialect {
        @Override
        public String columnCommentsQuery(String schema, String tableName) {
            return "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.NO_SUCH_VIEW WHERE TABLE_NAME = ?";
        }
    }

    /** Stands in for a dialect that stores no comments at all, which is not an error. */
    private static final class NoCommentSupportDialect extends H2Dialect {
        @Override
        public String columnCommentsQuery(String schema, String tableName) {
            return null;
        }
    }
}
