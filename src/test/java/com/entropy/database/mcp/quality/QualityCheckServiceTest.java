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
package com.entropy.database.mcp.quality;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.GenericDialect;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import com.entropy.database.mcp.security.SqlValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link QualityCheckService}: column names reaching SQL by interpolation, the aliased
 * derived table in the duplicate check, and the CUSTOM_SQL gate.
 */
class QualityCheckServiceTest {

    private static final DatabaseDialect DIALECT = new GenericDialect();
    private static final String TABLE = "ORDERS";

    /** Nine rows: NOTE is null in seven of them and one row is a verbatim duplicate. */
    private static final String H2_TABLE = "Q_ORDERS";

    /** Two columns, no nulls, no duplicates. */
    private static final String CLEAN_TABLE = "Q_CLEAN";

    private static JdbcTemplate jdbcTemplate;

    private RecordingReadOperations db;

    @BeforeAll
    static void createSchema() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:qualitysvc;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);

        jdbcTemplate.execute("DROP TABLE IF EXISTS Q_ORDERS");
        jdbcTemplate.execute("DROP TABLE IF EXISTS Q_CLEAN");
        jdbcTemplate.execute("CREATE TABLE Q_ORDERS (ID INT, STATUS VARCHAR(10), NOTE VARCHAR(50))");
        jdbcTemplate.update("INSERT INTO Q_ORDERS VALUES (1, 'A', NULL)");
        jdbcTemplate.update("INSERT INTO Q_ORDERS VALUES (1, 'A', NULL)");
        for (int id = 2; id <= 6; id++) {
            jdbcTemplate.update("INSERT INTO Q_ORDERS VALUES (?, 'A', NULL)", id);
        }
        jdbcTemplate.update("INSERT INTO Q_ORDERS VALUES (7, 'A', 'note-7')");
        jdbcTemplate.update("INSERT INTO Q_ORDERS VALUES (8, 'A', 'note-8')");

        jdbcTemplate.execute("CREATE TABLE Q_CLEAN (ID INT, LABEL VARCHAR(20))");
        jdbcTemplate.update("INSERT INTO Q_CLEAN VALUES (1, 'one')");
        jdbcTemplate.update("INSERT INTO Q_CLEAN VALUES (2, 'two')");
    }

    @BeforeEach
    void setUp() {
        db = new RecordingReadOperations();
    }

    private static QualityCheckService service(boolean customSqlEnabled) {
        return new QualityCheckService(new QualityRuleRegistry(), null, null, customSqlEnabled);
    }

    private static QualityCheckService h2Service() {
        return service(false);
    }

    private static DatabaseReadOperations h2Db() {
        return new H2ReadOperations(jdbcTemplate);
    }

    @Test
    void duplicateCheckAliasesItsDerivedTable() {
        service(false).check("primary", TABLE, null, List.of(), DIALECT, db);

        String duplicateSql = db.captured.stream()
                .filter(sql -> sql.contains("GROUP BY"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no duplicate-group query was issued: " + db.captured));

        // MySQL and PostgreSQL reject an unaliased derived table ("Every derived table must have
        // its own alias"), which made this check answer 0 on both instead of failing loudly.
        assertThat(duplicateSql).endsWith("HAVING COUNT(*) > 1) t");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ID) FROM DUAL--",
            "ID; DROP TABLE ORDERS",
            "(SELECT PASSWORD FROM SYS_USERS)",
            "ID\" OR \"1\"=\"1"
    })
    void rejectsMaliciousRuleColumn(String column) {
        QualityRule rule = new QualityRule("r1", "Null rate", QualityRule.RuleType.NULL_RATE,
                column, Map.of(), 0.05, QualityRule.Severity.WARNING, true);

        assertThatThrownBy(() -> service(false).check("primary", TABLE, null, List.of(rule), DIALECT, db))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("Invalid column name");
    }

    @Test
    void rejectsMaliciousColumnInADuplicatesRule() {
        QualityRule rule = QualityRule.duplicates("dup", List.of("ID", "NAME) FROM DUAL--"),
                0.01, QualityRule.Severity.ERROR);

        assertThatThrownBy(() -> service(false).check("primary", TABLE, null, List.of(rule), DIALECT, db))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("Invalid column name");
    }

    @Test
    void acceptsAPlainRuleColumnAndQuotesIt() {
        QualityRule rule = QualityRule.enumValues("enum1", "STATUS", List.of("A", "B"),
                QualityRule.Severity.WARNING);

        assertThatCode(() -> service(false).check("primary", TABLE, null, List.of(rule), DIALECT, db))
                .doesNotThrowAnyException();

        String enumSql = db.captured.stream()
                .filter(sql -> sql.contains("NOT IN"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no enum query was issued: " + db.captured));
        assertThat(enumSql).contains(DIALECT.quote("STATUS"));
        assertThat(enumSql).contains("NOT IN (?, ?)");
    }

    @Test
    void customSqlRuleIsRefusedWhileDisabled() {
        QualityRule rule = QualityRule.customSql("cs", "SELECT COUNT(*) FROM SYS_USERS", 0.0,
                QualityRule.Severity.WARNING);

        assertThatThrownBy(() -> service(false).check("primary", TABLE, null, List.of(rule), DIALECT, db))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("custom-sql-enabled");

        assertThat(db.captured).noneMatch(sql -> sql.contains("SYS_USERS"));
    }

    @Test
    void customSqlRuleStillNeedsATableWhitelistWhenEnabled() {
        QualityRule rule = QualityRule.customSql("cs", "SELECT COUNT(*) FROM SYS_USERS", 0.0,
                QualityRule.Severity.WARNING);

        // Enabled, but no allowed-tables whitelist is configured in these properties.
        assertThatThrownBy(() -> service(true).check("primary", TABLE, null, List.of(rule), DIALECT, db))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("allowed-tables");
    }

    // ─── Against a real database ──────────────────────────────────────────

    /**
     * The checks that only exist if the column list does. {@code queryColumns} bound nothing to a
     * {@code columnsQuery} that always names the table, so on every dialect it threw, the failure was
     * absorbed, and the report carried no rules at all - which the score formula turns into a
     * flawless 100 for any table whatsoever.
     */
    @Nested
    @DisplayName("column discovery drives the per-column and duplicate checks")
    class AgainstH2 {

        @Test
        @DisplayName("every column of the table is discovered")
        void columnsAreDiscovered() {
            QualityReport report = h2Service().check("primary", H2_TABLE, null, List.of(),
                    new H2Dialect(), h2Db());

            assertThat(report.rules()).extracting(QualityRule::column)
                    .contains("ID", "STATUS", "NOTE");
        }

        @Test
        @DisplayName("the per-column null-rate check runs and reports the column above the threshold")
        void nullRateCheckRuns() {
            QualityReport report = h2Service().check("primary", H2_TABLE, null, List.of(),
                    new H2Dialect(), h2Db());

            assertThat(report.issues()).extracting(QualityIssue::ruleId).contains("null-NOTE");
            assertThat(report.issues()).extracting(QualityIssue::ruleId)
                    .doesNotContain("null-ID", "null-STATUS");
        }

        @Test
        @DisplayName("the duplicate-row check runs and finds the duplicated row")
        void duplicateCheckRuns() {
            QualityReport report = h2Service().check("primary", H2_TABLE, null, List.of(),
                    new H2Dialect(), h2Db());

            QualityIssue duplicates = report.issues().stream()
                    .filter(issue -> "dup-all".equals(issue.ruleId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no duplicate issue: " + report.issues()));
            assertThat(duplicates.issueCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the score is no longer a constant 100")
        void scoreReflectsTheIssuesFound() {
            QualityReport report = h2Service().check("primary", H2_TABLE, null, List.of(),
                    new H2Dialect(), h2Db());

            // Three columns plus the duplicate rule; two of them report an issue.
            assertThat(report.rulesChecked()).isEqualTo(4);
            assertThat(report.issuesFound()).isEqualTo(2);
            assertThat(report.overallScore()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("a clean table still scores 100, so the score is not merely always low")
        void aCleanTableScoresPerfectly() {
            QualityReport report = h2Service().check("primary", CLEAN_TABLE, null, List.of(),
                    new H2Dialect(), h2Db());

            assertThat(report.rulesChecked()).isEqualTo(2);
            assertThat(report.issuesFound()).isZero();
            assertThat(report.overallScore()).isEqualTo(100.0);
        }
    }

    /**
     * Minimal read facade that records every statement and answers the probes with fixed counts:
     * 10 rows in the table, no nulls and no duplicate groups.
     */
    private static final class RecordingReadOperations implements DatabaseReadOperations {

        private final List<String> captured = new ArrayList<>();

        @Override
        public List<Map<String, Object>> queryRows(String sql, String connection, Object... args) {
            captured.add(sql);
            if (!sql.startsWith("SELECT COUNT(*)")) {
                return List.of(Map.of("column_name", "ID"), Map.of("column_name", "NAME"));
            }
            if (sql.contains("IS NULL") || sql.contains("GROUP BY") || sql.contains("NOT IN")) {
                return List.of(Map.of("CNT", 0L));
            }
            return List.of(Map.of("CNT", 10L));
        }

        @Override
        public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken, String connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Map<String, Object>> executeNamedQuery(String sql, Map<String, Object> params, String connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlanAnalysis explainPlan(String sql, String connection) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Read facade over the real H2 database: unlike the recording double it passes the arguments on,
     * so a statement whose placeholder count does not match the argument list fails here exactly as
     * it would in production.
     */
    private record H2ReadOperations(JdbcTemplate jdbc) implements DatabaseReadOperations {

        @Override
        public List<Map<String, Object>> queryRows(String sql, String connection, Object... args) {
            return jdbc.queryForList(sql, args);
        }

        @Override
        public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken, String connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Map<String, Object>> executeNamedQuery(String sql, Map<String, Object> params, String connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlanAnalysis explainPlan(String sql, String connection) {
            throw new UnsupportedOperationException();
        }
    }
}
