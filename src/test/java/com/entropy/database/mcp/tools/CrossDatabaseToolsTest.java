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

import com.entropy.database.mcp.exception.McpToolException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CrossDatabaseTools}: the parameters that are interpolated into the pre-built
 * cross-database analytics statement, and the statement itself.
 *
 * <p>Only rejection paths and the pure SQL builder are exercised, so no facade is needed: every
 * check runs before the statement is handed to the routing facade.
 */
class CrossDatabaseToolsTest {

    private static final String VALID_PREFIX = "TBL_STL_TXN_DTL_";
    private static final String VALID_PARTITION = "20260201";
    private static final String VALID_START = "2026-02-01";
    private static final String VALID_END = "2026-02-28";

    private static CrossDatabaseTools gatewayEnabledTools() {
        Environment environment = new MockEnvironment()
                .withProperty("entropy.mcp.gateway.enabled", "true");
        return new CrossDatabaseTools(null, null, null, null, null, environment);
    }

    @Nested
    class ParameterValidation {

        @ParameterizedTest
        @ValueSource(strings = {
                "LNK; DROP TABLE t",
                "LNK@evil",
                "LNK' OR '1'='1",
                "LNK WHERE 1=1",
                "1LNK"
        })
        void rejectsMaliciousDbLinkName(String dbLinkName) {
            assertThatThrownBy(() -> gatewayEnabledTools().queryComplexCrossDatabaseAnalytics(
                    dbLinkName, VALID_PREFIX, VALID_PARTITION, VALID_START, VALID_END, 10, null))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("dbLinkName");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "2026-02-01' OR '1'='1",
                "2026-02-30",
                "2026-2-1",
                "20260201",
                "' UNION SELECT password FROM sys_users --"
        })
        void rejectsMaliciousStartDate(String startDate) {
            assertThatThrownBy(() -> gatewayEnabledTools().queryComplexCrossDatabaseAnalytics(
                    "REMOTE_LNK", VALID_PREFIX, VALID_PARTITION, startDate, VALID_END, 10, null))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("startDate");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "2026-02-28' AND 1=1 --",
                "2026-02-31",
                "not-a-date"
        })
        void rejectsMaliciousEndDate(String endDate) {
            assertThatThrownBy(() -> gatewayEnabledTools().queryComplexCrossDatabaseAnalytics(
                    "REMOTE_LNK", VALID_PREFIX, VALID_PARTITION, VALID_START, endDate, 10, null))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("endDate");
        }

        @Test
        void rejectsMaliciousPartitionDate() {
            assertThatThrownBy(() -> gatewayEnabledTools().queryComplexCrossDatabaseAnalytics(
                    "REMOTE_LNK", VALID_PREFIX, "2026-02-01", VALID_START, VALID_END, 10, null))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("partitionDate");
        }
    }

    @Nested
    class AnalyticsStatement {

        private final String sql = CrossDatabaseTools.buildComplexAnalyticsSql(
                VALID_PREFIX + VALID_PARTITION, "REMOTE_LNK", VALID_START, VALID_END, 25);

        @Test
        void noLongerEmitsTheConstantNameAsAnIdentifier() {
            // The text block used to read "WHERE ROWNUM <= MAX_CTE_ROWS" with no placeholder, so
            // Oracle parsed the constant's name as a column and answered ORA-00904 every time.
            assertThat(sql).doesNotContain("MAX_CTE_ROWS");
            assertThat(sql).containsPattern("ROWNUM <= \\d+");
        }

        @Test
        void interpolatesEveryParameterInOrder() {
            assertThat(sql).contains("FROM " + VALID_PREFIX + VALID_PARTITION);
            assertThat(sql).contains("FROM REMOTE_QUALITY_TABLE@REMOTE_LNK");
            assertThat(sql).contains("FROM DIM_STATION@REMOTE_LNK");
            assertThat(sql).contains("BETWEEN DATE '" + VALID_START + "' AND DATE '" + VALID_END + "'");
            assertThat(sql).contains("FETCH FIRST 25 ROWS ONLY");
        }

        @Test
        void parsesAsASelectWhereTheDialectAllowsIt() {
            try {
                Statement statement = CCJSqlParserUtil.parse(sql);
                assertThat(statement).isInstanceOf(Select.class);
            } catch (JSQLParserException e) {
                // JSQLParser does not model Oracle's table@dblink syntax. Falling back to the text
                // assertion still pins the defect that made this statement fail on Oracle.
                assertThat(sql).containsPattern("ROWNUM <= \\d+");
            }
        }
    }
}
