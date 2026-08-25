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
package com.entropy.database.mcp.security;

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpSqlValidationException;
import com.entropy.database.mcp.properties.DatabaseProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards {@link SqlValidatorImpl}, the single gate every SQL statement must pass before it
 * reaches a database: it decides which statement types are executable, which tables may be
 * read, and how structurally expensive a query is allowed to be. A hole here is a direct
 * path to arbitrary SQL execution or unauthorised data access, so this class pins down the
 * full rule surface (operation whitelist, table whitelist, JOIN/subquery/row limits, the
 * separate {@code validateDdl} path, malformed and injection-shaped input).
 *
 * <p>The whitelist and limit rules are asserted against the <em>intended</em> behaviour, including
 * the bypasses this suite originally uncovered: aliases, schema qualification, derived tables,
 * subqueries, CTE shadowing, parenthesised statements, MySQL executable comments, and the
 * {@code LIMIT}/{@code FETCH FIRST} row cap.</p>
 */
class SqlValidatorTest {

    // ─── Fixtures ─────────────────────────────────────────────────────────

    private static DatabaseProperties properties(List<String> allowedTables,
                                                 int maxRows,
                                                 int maxJoins,
                                                 int maxSubqueryDepth) {
        return new DatabaseProperties(
            true, "h2",
            new DatabaseProperties.QueryProperties(maxRows, 30, true, 10000, 500, 100),
            null, null, null,
            new DatabaseProperties.SecurityProperties(maxJoins, maxSubqueryDepth, allowedTables),
            null, null, null, null, null, null, null, null, null, null);
    }

    /** maxRows=100, maxJoins=10, maxSubqueryDepth=5, table whitelist disabled. */
    private static SqlValidatorImpl validator() {
        return new SqlValidatorImpl(properties(List.of(), 100, 10, 5));
    }

    private static SqlValidatorImpl validatorWithTables(String... tables) {
        return new SqlValidatorImpl(properties(List.of(tables), 100, 10, 5));
    }

    private static SqlValidatorImpl validatorWith(int maxRows, int maxJoins, int maxSubqueryDepth) {
        return new SqlValidatorImpl(properties(List.of(), maxRows, maxJoins, maxSubqueryDepth));
    }

    private static McpSqlValidationException rejectedSelect(SqlValidatorImpl validator, String sql) {
        McpSqlValidationException ex = assertThrows(McpSqlValidationException.class,
            () -> validator.validateSelect(sql));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_VALIDATION_FAILED);
        assertThat(ex.getSql()).isEqualTo(sql);
        return ex;
    }

    private static String selectWithJoins(int joinCount) {
        StringBuilder sb = new StringBuilder("SELECT * FROM t0");
        for (int i = 1; i <= joinCount; i++) {
            sb.append(" JOIN t").append(i).append(" ON t0.id = t").append(i).append(".id");
        }
        return sb.toString();
    }

    private static String nestedFromSubqueries(int levels) {
        String sql = "SELECT id FROM base";
        for (int i = 0; i < levels; i++) {
            sql = "SELECT id FROM (" + sql + ") lvl" + i;
        }
        return sql;
    }

    private static String nestedInSubqueries(int levels) {
        String sql = "SELECT id FROM t" + levels;
        for (int i = levels - 1; i >= 0; i--) {
            sql = "SELECT id FROM t" + i + " WHERE id IN (" + sql + ")";
        }
        return sql;
    }

    // ─── Operation whitelist ──────────────────────────────────────────────

    @Nested
    @DisplayName("statement type whitelist")
    class OperationWhitelist {

        @ParameterizedTest(name = "allows [{0}]")
        @ValueSource(strings = {
            "SELECT * FROM users",
            "SELECT ID, NAME FROM users WHERE ID = 1",
            "select 1",
            "sElEcT iD fRoM users",
            "   SELECT 1   ",
            "\n\tSELECT 1\n",
            "SELECT 1;",
            "SELECT /* inline */ 1",
            "SELECT 1 -- DROP TABLE x",
            "SELECT 1 FROM users -- ; DELETE FROM users",
            "WITH recent AS (SELECT id FROM users) SELECT * FROM recent",
            "SELECT * FROM users ORDER BY id DESC",
            "SELECT COUNT(*) FROM users GROUP BY dept HAVING COUNT(*) > 1",
            "DESCRIBE users",
            "DESC users",
            "SHOW TABLES",
            "SHOW COLUMNS FROM users",
            "EXPLAIN SELECT * FROM users"
        })
        void allowsReadOnlyStatements(String sql) {
            assertThatCode(() -> validator().validateSelect(sql)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "rejects [{0}]")
        @ValueSource(strings = {
            "INSERT INTO users VALUES (1)",
            "INSERT INTO users SELECT * FROM staging",
            "UPDATE users SET name = 'x'",
            "DELETE FROM users",
            "DROP TABLE users",
            "ALTER TABLE users ADD age INT",
            "TRUNCATE TABLE users",
            "CREATE TABLE t (a INT)",
            "CREATE VIEW v AS SELECT * FROM users",
            "GRANT SELECT ON users TO bob",
            "MERGE INTO t USING s ON (t.a = s.a) WHEN MATCHED THEN UPDATE SET t.b = s.b",
            "CALL some_proc()",
            "drop table users",
            "  DELETE FROM users  "
        })
        void rejectsWriteAndAdminStatements(String sql) {
            McpSqlValidationException ex = rejectedSelect(validator(), sql);
            assertThat(ex.getMessage()).startsWith("Operation not allowed:");
        }

        @Test
        @DisplayName("allowed operation set is exactly SELECT/DESCRIBE/SHOW/EXPLAIN and immutable")
        void allowedOperationsAreFixed() {
            SqlValidatorImpl v = validator();
            assertThat(v.getAllowedOperations())
                .containsExactlyInAnyOrder("SELECT", "DESCRIBE", "SHOW", "EXPLAIN");
            assertThatCode(() -> v.setAllowedOperations(Set.of("DELETE"))).doesNotThrowAnyException();
            assertThat(v.getAllowedOperations())
                .containsExactlyInAnyOrder("SELECT", "DESCRIBE", "SHOW", "EXPLAIN");
        }

        @Test
        @DisplayName("set operations (UNION/INTERSECT/EXCEPT) are not recognised as SELECT and are refused")
        void setOperationsAreRefused() {
            // JSQLParser models these as SetOperationList, which extractOp() maps to UNKNOWN.
            // Fail-closed, but it also blocks legitimate UNION queries — see report.
            for (String sql : List.of(
                    "SELECT id FROM users UNION SELECT id FROM admin",
                    "SELECT id FROM users UNION ALL SELECT id FROM admin",
                    "SELECT id FROM users INTERSECT SELECT id FROM admin",
                    "SELECT id FROM users EXCEPT SELECT id FROM admin")) {
                assertThat(rejectedSelect(validator(), sql).getMessage())
                    .startsWith("Operation not allowed:");
            }
        }
    }

    // ─── Empty / malformed input ──────────────────────────────────────────

    @Nested
    @DisplayName("empty and malformed input")
    class MalformedInput {

        @ParameterizedTest(name = "blank input [{0}] is refused as empty")
        @NullSource
        @EmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n", " \t\n "})
        void refusesBlankSql(String sql) {
            McpSqlValidationException ex = assertThrows(McpSqlValidationException.class,
                () -> validator().validateSelect(sql));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_VALIDATION_FAILED);
            assertThat(ex.getMessage()).isEqualTo("SQL is empty");
            assertThat(ex.getSql()).isEqualTo(sql);
            assertThat(ex.getCause()).isNull();
        }

        @ParameterizedTest(name = "blank input [{0}] is refused as empty on the DDL path too")
        @NullSource
        @EmptySource
        @ValueSource(strings = {" ", "\n"})
        void refusesBlankSqlOnDdlPath(String sql) {
            McpSqlValidationException ex = assertThrows(McpSqlValidationException.class,
                () -> validator().validateDdl(sql));
            assertThat(ex.getMessage()).isEqualTo("SQL is empty");
        }

        @ParameterizedTest(name = "unparseable [{0}] is refused with the parser error attached")
        @ValueSource(strings = {
            "-- only a comment",
            "/* only a comment */",
            "SELECT FROM users",
            "SELECT * FROM",
            "SELECT * FROM users WHERE",
            "SELCT 1",
            "EXPLAIN PLAN FOR SELECT * FROM users",
            "not sql at all"
        })
        void refusesUnparseableSql(String sql) {
            McpSqlValidationException ex = rejectedSelect(validator(), sql);
            assertThat(ex.getMessage()).isEqualTo("SQL validation error");
            assertThat(ex.getCause()).isNotNull();
        }

        @ParameterizedTest(name = "stacked statements [{0}] are refused")
        @ValueSource(strings = {
            "SELECT 1; DROP TABLE users",
            "SELECT * FROM users; DELETE FROM users",
            "SELECT 1;SELECT 2",
            "SELECT 1; -- DROP TABLE users\nDROP TABLE users"
        })
        void refusesStackedStatements(String sql) {
            // Multi-statement input never reaches the operation whitelist: the parser rejects it.
            McpSqlValidationException ex = rejectedSelect(validator(), sql);
            assertThat(ex.getMessage()).isEqualTo("SQL validation error");
        }

        @Test
        @DisplayName("trailing line comment cannot smuggle a second statement")
        void commentedOutKeywordsStayInert() {
            assertThatCode(() -> validator().validateSelect("SELECT 1 -- DROP TABLE users"))
                .doesNotThrowAnyException();
            assertThatCode(() -> validator().validateSelect("SELECT 1 /* DELETE FROM users */"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MySQL executable comments are refused: the parser strips them, the server runs them")
        void executableCommentsMustBeRefused() {
            rejectedSelect(validator(), "SELECT 1 /*! UNION SELECT password FROM admin */");
            rejectedSelect(validator(), "SELECT 1 /*!32302 UNION SELECT 2 */");
        }
    }

    // ─── Table whitelist ──────────────────────────────────────────────────

    @Nested
    @DisplayName("table whitelist (security.allowed-tables)")
    class TableWhitelist {

        @ParameterizedTest(name = "empty whitelist allows [{0}]")
        @ValueSource(strings = {
            "SELECT * FROM users",
            "SELECT * FROM admin",
            "SELECT * FROM secret.credentials",
            "SELECT * FROM a JOIN b ON a.id = b.id"
        })
        void emptyWhitelistDisablesTableChecking(String sql) {
            assertThatCode(() -> validator().validateSelect(sql)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("whitelisted table is allowed, case-insensitively on the SQL side")
        void allowsWhitelistedTable() {
            SqlValidatorImpl v = validatorWithTables("USERS");
            assertThatCode(() -> v.validateSelect("SELECT * FROM USERS")).doesNotThrowAnyException();
            assertThatCode(() -> v.validateSelect("select * from users")).doesNotThrowAnyException();
            assertThatCode(() -> v.validateSelect("SELECT * FROM UsErS")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("non-whitelisted FROM table is refused and named in the message")
        void refusesUnlistedFromTable() {
            McpSqlValidationException ex = rejectedSelect(validatorWithTables("USERS"),
                "SELECT * FROM admin");
            assertThat(ex.getMessage()).startsWith("Tables not allowed:").contains("ADMIN");
        }

        @Test
        @DisplayName("JOIN targets are checked against the whitelist")
        void checksJoinTables() {
            SqlValidatorImpl v = validatorWithTables("USERS", "ORDERS");
            assertThatCode(() -> v.validateSelect(
                "SELECT * FROM users JOIN orders ON users.id = orders.user_id"))
                .doesNotThrowAnyException();

            McpSqlValidationException ex = rejectedSelect(v,
                "SELECT * FROM users JOIN admin ON users.id = admin.user_id");
            assertThat(ex.getMessage()).startsWith("Tables not allowed:").contains("ADMIN");
        }

        @Test
        @DisplayName("whitelist can be swapped at runtime and is exposed read-only")
        void whitelistIsRuntimeConfigurable() {
            SqlValidatorImpl v = validator();
            assertThat(v.getAllowedTables()).isEmpty();

            v.setAllowedTables(Set.of("USERS"));
            assertThat(v.getAllowedTables()).containsExactly("USERS");
            assertThatCode(() -> v.validateSelect("SELECT * FROM users")).doesNotThrowAnyException();
            rejectedSelect(v, "SELECT * FROM admin");

            assertThrows(UnsupportedOperationException.class,
                () -> v.getAllowedTables().add("ADMIN"));

            v.setAllowedTables(Set.of());
            assertThatCode(() -> v.validateSelect("SELECT * FROM admin")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("whitelist is only applied to SELECT-shaped statements")
        void whitelistSkippedForNonSelectStatements() {
            // On the DDL path a DROP is not a select, so the table whitelist never sees it.
            assertThatCode(() -> validatorWithTables("USERS").validateDdl("DROP TABLE admin"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an alias does not hide a whitelisted table")
        void aliasedTableMustBeAcceptedWhenWhitelisted() {
            SqlValidatorImpl v = validatorWithTables("USERS");
            assertThatCode(() -> v.validateSelect("SELECT * FROM users u")).doesNotThrowAnyException();
            assertThatCode(() -> v.validateSelect("SELECT * FROM users AS u")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a schema-qualified name is matched on the table, not the schema")
        void schemaQualifiedTableMustBeMatchedOnTableName() {
            SqlValidatorImpl v = validatorWithTables("USERS");
            assertThatCode(() -> v.validateSelect("SELECT * FROM app.users")).doesNotThrowAnyException();
            // and the schema name alone must not open up unrelated tables
            rejectedSelect(validatorWithTables("APP"), "SELECT * FROM app.admin");
        }

        @Test
        @DisplayName("whitelist entries may be configured in any case")
        void whitelistEntriesMustBeCaseInsensitive() {
            SqlValidatorImpl v = validatorWithTables("users");
            assertThatCode(() -> v.validateSelect("SELECT * FROM users")).doesNotThrowAnyException();
            assertThatCode(() -> v.validateSelect("SELECT * FROM USERS")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a derived table is resolved to the tables it reads")
        void derivedTableMustBeResolvedToItsInnerTables() {
            SqlValidatorImpl v = validatorWithTables("USERS");
            assertThatCode(() -> v.validateSelect("SELECT * FROM (SELECT * FROM users) t"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tables referenced only inside a subquery are checked too")
        void subqueryTablesMustBeCheckedAgainstWhitelist() {
            SqlValidatorImpl v = validatorWithTables("USERS");
            rejectedSelect(v, "SELECT * FROM users WHERE id IN (SELECT id FROM admin)");
            rejectedSelect(v, "SELECT (SELECT MAX(password) FROM admin) AS p FROM users");
            rejectedSelect(v, "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM admin)");
        }

        @Test
        @DisplayName("a CTE cannot shadow a whitelisted name to read another table")
        void cteShadowingMustNotBypassWhitelist() {
            rejectedSelect(validatorWithTables("USERS"),
                "WITH users AS (SELECT * FROM admin) SELECT * FROM users");
        }

        @Test
        @DisplayName("wrapping the statement in parentheses does not skip the whitelist")
        void parenthesisedSelectMustNotBypassWhitelist() {
            rejectedSelect(validatorWithTables("USERS"), "(SELECT * FROM admin)");
        }
    }

    // ─── Structural limits ────────────────────────────────────────────────

    @Nested
    @DisplayName("structural limits")
    class StructuralLimits {

        @Test
        @DisplayName("JOIN count at the configured limit passes, one over is refused")
        void enforcesMaxJoins() {
            SqlValidatorImpl v = validator(); // maxJoins = 10
            assertThat(v.getMaxJoins()).isEqualTo(10);
            assertThatCode(() -> v.validateSelect(selectWithJoins(10))).doesNotThrowAnyException();

            McpSqlValidationException ex = rejectedSelect(v, selectWithJoins(11));
            assertThat(ex.getMessage()).isEqualTo("Exceeds max joins: 11 > 10");
        }

        @ParameterizedTest(name = "maxJoins={0}: {1} joins refused")
        @ValueSource(ints = {1, 2, 3})
        void maxJoinsIsConfigurationDriven(int maxJoins) {
            SqlValidatorImpl v = validatorWith(100, maxJoins, 5);
            assertThat(v.getMaxJoins()).isEqualTo(maxJoins);
            assertThatCode(() -> v.validateSelect(selectWithJoins(maxJoins)))
                .doesNotThrowAnyException();
            assertThat(rejectedSelect(v, selectWithJoins(maxJoins + 1)).getMessage())
                .isEqualTo("Exceeds max joins: " + (maxJoins + 1) + " > " + maxJoins);
        }

        @Test
        @DisplayName("JOIN limit also applies on the DDL path")
        void joinLimitAppliesToDdlPath() {
            SqlValidatorImpl v = validator();
            McpSqlValidationException ex = assertThrows(McpSqlValidationException.class,
                () -> v.validateDdl(selectWithJoins(11)));
            assertThat(ex.getMessage()).isEqualTo("Exceeds max joins: 11 > 10");
        }

        @ParameterizedTest(name = "{0} nesting levels are within the configured depth of 5")
        @ValueSource(ints = {1, 2, 3})
        void allowsSubqueryNestingWithinLimit(int levels) {
            SqlValidatorImpl v = validator(); // maxSubqueryDepth = 5
            assertThat(v.getMaxSubqueryDepth()).isEqualTo(5);
            assertThatCode(() -> v.validateSelect(nestedFromSubqueries(levels)))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nested FROM subqueries past the depth limit are refused")
        void enforcesMaxSubqueryDepthForNestedFrom() {
            SqlValidatorImpl v = validator();
            assertThat(rejectedSelect(v, nestedFromSubqueries(6)).getMessage())
                .startsWith("Exceeds max subquery depth:");
            rejectedSelect(v, nestedFromSubqueries(8));
        }

        @Test
        @DisplayName("subqueries reached through a WHERE clause count towards the depth limit")
        void enforcesMaxSubqueryDepthForWhereSubqueries() {
            SqlValidatorImpl v = validator();
            assertThat(rejectedSelect(v, nestedInSubqueries(6)).getMessage())
                .startsWith("Exceeds max subquery depth:");
        }
    }

    // ─── Row limit ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("row limit")
    class RowLimit {

        @Test
        @DisplayName("maxRows is read from configuration and the setter is a no-op")
        void maxRowsComesFromConfiguration() {
            SqlValidatorImpl v = validatorWith(250, 10, 5);
            assertThat(v.getMaxRows()).isEqualTo(250);
            v.setMaxRows(1);
            assertThat(v.getMaxRows()).isEqualTo(250);
        }

        @ParameterizedTest(name = "explicit LIMIT within maxRows=100 is allowed: [{0}]")
        @ValueSource(strings = {
            "SELECT * FROM users LIMIT 1",
            "SELECT * FROM users LIMIT 99",
            "SELECT * FROM users LIMIT 100"
        })
        void allowsLimitWithinMaxRows(String sql) {
            assertThatCode(() -> validator().validateSelect(sql)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a statement without LIMIT is not rejected by the row rule")
        void allowsStatementWithoutLimit() {
            assertThatCode(() -> validator().validateSelect("SELECT * FROM users"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an explicit LIMIT above maxRows is refused")
        void refusesLimitAboveMaxRows() {
            SqlValidatorImpl v = validator();
            assertThat(rejectedSelect(v, "SELECT * FROM users LIMIT 999999").getMessage())
                .startsWith("Exceeds max rows:");
            rejectedSelect(validatorWith(10, 10, 5), "SELECT * FROM users LIMIT 500");
        }

        @Test
        @DisplayName("FETCH FIRST n ROWS ONLY above maxRows is refused (Oracle has no LIMIT)")
        void refusesFetchFirstAboveMaxRows() {
            rejectedSelect(validator(), "SELECT * FROM users FETCH FIRST 999999 ROWS ONLY");
        }
    }

    // ─── validateDdl path ─────────────────────────────────────────────────

    @Nested
    @DisplayName("validateDdl path")
    class DdlPath {

        @ParameterizedTest(name = "validateDdl accepts [{0}] which validateSelect refuses")
        @ValueSource(strings = {
            "INSERT INTO users VALUES (1)",
            "UPDATE users SET name = 'x'",
            "DELETE FROM users",
            "DROP TABLE users",
            "ALTER TABLE users ADD age INT",
            "TRUNCATE TABLE users",
            "CREATE TABLE t (a INT)",
            "GRANT SELECT ON users TO bob",
            "CALL some_proc()"
        })
        void ddlPathSkipsTheOperationWhitelist(String sql) {
            assertThatCode(() -> validator().validateDdl(sql)).doesNotThrowAnyException();
            rejectedSelect(validator(), sql);
        }

        @ParameterizedTest(name = "validateDdl still refuses unparseable [{0}]")
        @ValueSource(strings = {
            "DROP TABLE users; DROP TABLE admin",
            "DRP TABLE users",
            "-- only a comment"
        })
        void ddlPathStillRequiresParseableSingleStatement(String sql) {
            McpSqlValidationException ex = assertThrows(McpSqlValidationException.class,
                () -> validator().validateDdl(sql));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_VALIDATION_FAILED);
            assertThat(ex.getMessage()).isEqualTo("SQL validation error");
        }

        @Test
        @DisplayName("validateDdl still enforces the table whitelist for SELECT statements")
        void ddlPathStillEnforcesTableWhitelistOnSelects() {
            McpSqlValidationException ex = assertThrows(McpSqlValidationException.class,
                () -> validatorWithTables("USERS").validateDdl("SELECT * FROM admin"));
            assertThat(ex.getMessage()).startsWith("Tables not allowed:");
        }
    }

    // ─── Configuration surface ────────────────────────────────────────────

    @Nested
    @DisplayName("configuration surface")
    class ConfigurationSurface {

        @Test
        @DisplayName("limits are read from properties, setters are no-ops")
        void limitSettersAreNoOps() {
            SqlValidatorImpl v = validatorWith(42, 3, 2);
            assertThat(v.getMaxRows()).isEqualTo(42);
            assertThat(v.getMaxJoins()).isEqualTo(3);
            assertThat(v.getMaxSubqueryDepth()).isEqualTo(2);

            v.setMaxRows(1);
            v.setMaxJoins(1);
            v.setMaxSubqueryDepth(1);

            assertThat(v.getMaxRows()).isEqualTo(42);
            assertThat(v.getMaxJoins()).isEqualTo(3);
            assertThat(v.getMaxSubqueryDepth()).isEqualTo(2);
        }

        @Test
        @DisplayName("non-positive limits fall back to the documented defaults")
        void nonPositiveLimitsFallBackToDefaults() {
            SqlValidatorImpl v = validatorWith(0, 0, 0);
            assertThat(v.getMaxRows()).isEqualTo(100);
            assertThat(v.getMaxJoins()).isEqualTo(10);
            assertThat(v.getMaxSubqueryDepth()).isEqualTo(5);
        }

        @Test
        @DisplayName("allowed tables are seeded from properties")
        void allowedTablesSeededFromProperties() {
            assertThat(validatorWithTables("USERS", "ORDERS").getAllowedTables())
                .containsExactlyInAnyOrder("USERS", "ORDERS");
        }

        @Test
        @DisplayName("mask columns are copied in and out")
        void maskColumnsAreDefensivelyCopied() {
            SqlValidatorImpl v = validator();
            assertThat(v.getMaskColumns()).isEmpty();

            List<String> input = new ArrayList<>(List.of("EMAIL", "SSN"));
            v.setMaskColumns(input);
            input.add("PHONE");

            assertThat(v.getMaskColumns()).containsExactly("EMAIL", "SSN");
            assertThrows(UnsupportedOperationException.class, () -> v.getMaskColumns().add("X"));
        }
    }
}
