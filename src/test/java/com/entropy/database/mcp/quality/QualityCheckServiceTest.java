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
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import com.entropy.database.mcp.stream.SseStreamManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    private RecordingReadOperations db;

    @BeforeEach
    void setUp() {
        db = new RecordingReadOperations();
    }

    private static QualityCheckService service(boolean customSqlEnabled) {
        return new QualityCheckService(new QualityRuleRegistry(), null, null, customSqlEnabled);
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
        public PaginatedQueryResult executeQueryWithSse(String sql, int maxRows, String continuationToken,
                                                        SseStreamManager.QueryExecutor<PaginatedQueryResult> executor,
                                                        String connection) {
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
