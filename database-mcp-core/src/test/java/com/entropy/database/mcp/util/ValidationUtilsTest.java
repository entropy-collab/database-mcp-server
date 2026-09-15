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
package com.entropy.database.mcp.util;

import com.entropy.database.mcp.exception.McpValidationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ValidationUtils}, focused on the WHERE-clause validator.
 *
 * <p>The validator used to be a character blacklist whose keyword check only ran for patterns
 * containing a space, so {@code ;}, {@code --}, {@code /*} and parentheses were never inspected and
 * {@code 1=1 OR 1=1 --} passed. It now parses the clause and whitelists the expression tree.
 */
class ValidationUtilsTest {

    @Nested
    class WhereClauseRejects {

        @ParameterizedTest
        @ValueSource(strings = {
                "1=1 OR 1=1 --",                       // tautology plus a comment that hides the rest
                "1=1 /* comment */",                   // block comment
                "x = 1; DROP TABLE t",                 // stacked statement
                "id IN (SELECT id FROM sys_users)",     // subquery in an IN list
                "(SELECT 1)",                          // scalar subquery
                "id = (SELECT MAX(id) FROM t)",        // scalar subquery on the right-hand side
                "EXISTS (SELECT 1 FROM sys_users)",    // EXISTS subquery
                "UPPER(name) = 'A'",                   // function call
                "1=1 UNION SELECT password FROM sys_users", // set operation appended to the clause
                "1=1 ORDER BY 1",                      // trailing clause appended to the wrapper
                "1=1 GROUP BY 1",
                "a = 1 CONNECT BY LEVEL < 1000000",    // Oracle hierarchical query: unbounded row generator
                "a = 1 START WITH b IN (SELECT pwd FROM sys_users) CONNECT BY PRIOR id = pid",
                "a = 1 FOR UPDATE",                    // locks every matched row for the session
                "a = 1 WINDOW w AS (PARTITION BY (SELECT 1))", // subquery smuggled into a window definition
                "status = 'A"                          // unterminated literal: not parseable
        })
        void maliciousClauses(String clause) {
            assertThatThrownBy(() -> ValidationUtils.validateWhereClause(clause, "whereClause"))
                    .isInstanceOf(McpValidationException.class)
                    .hasMessageContaining("whereClause");
        }

        @Test
        void semicolonIsNamedExplicitly() {
            assertThatThrownBy(() -> ValidationUtils.validateWhereClause("x = 1; DROP TABLE t", "whereClause"))
                    .hasMessageContaining("semicolon");
        }

        @Test
        void commentIsNamedExplicitly() {
            assertThatThrownBy(() -> ValidationUtils.validateWhereClause("1=1 OR 1=1 --", "whereClause"))
                    .hasMessageContaining("comment");
        }
    }

    @Nested
    class WhereClauseAccepts {

        @ParameterizedTest
        @ValueSource(strings = {
                "status = 'A' AND amount > 100",
                "created_at BETWEEN '2026-01-01' AND '2026-02-01'",
                "name LIKE 'A%'",
                "status IN ('A', 'B', 'C')",
                "amount >= 10 AND amount <= 20",
                "deleted_at IS NULL",
                "NOT (status = 'X')",
                "t.status <> 'X' OR t.amount = -1",
                "status = 'A' AND (amount > 100 OR amount < 10)"
        })
        void legitimatePredicates(String clause) {
            assertThatCode(() -> ValidationUtils.validateWhereClause(clause, "whereClause"))
                    .doesNotThrowAnyException();
        }

        @Test
        void blankClauseIsANoOp() {
            assertThatCode(() -> ValidationUtils.validateWhereClause(null, "whereClause"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> ValidationUtils.validateWhereClause("   ", "whereClause"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class IsoDate {

        @Test
        void acceptsARealDate() {
            assertThatCode(() -> ValidationUtils.validateIsoDate("2026-02-28", "startDate"))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "2026-2-8",                    // wrong shape
                "2026-02-30",                  // not a calendar date
                "2026-13-01",
                "2026-01-01' OR '1'='1",       // injection attempt
                "20260101"
        })
        void rejectsAnythingElse(String value) {
            assertThatThrownBy(() -> ValidationUtils.validateIsoDate(value, "startDate"))
                    .isInstanceOf(McpValidationException.class)
                    .hasMessageContaining("startDate");
        }

        @Test
        void rejectsBlank() {
            assertThatThrownBy(() -> ValidationUtils.validateIsoDate(null, "endDate"))
                    .isInstanceOf(McpValidationException.class);
        }
    }

    /**
     * 标识符规则自本版起由 {@code SqlIdentifiers} 单点持有（contract 模块），本组用例只验证
     * {@code validateIdentifier} 这个委派入口的对外行为：方法名、异常类型与接受集。
     */
    @Nested
    class Identifiers {

        @ParameterizedTest
        @ValueSource(strings = {
                "TBL_STL_TXN",
                "_tmp_stage",          // 统一前被这里拒掉（旧正则要求首字符是字母），现在放行
                "V$SESSION",           // Oracle 数据字典视图，$# 是合法字符
                "SYS#"
        })
        void acceptsAPlainIdentifier(String name) {
            assertThatCode(() -> ValidationUtils.validateIdentifier(name, "tableName"))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "t; DROP TABLE x",
                "t' OR '1'='1",
                "\"quoted\"",
                "my table",
                "t--comment",
                "t/*comment",
                "订单表",
                "1st_table"
        })
        void rejectsInjection(String name) {
            assertThatThrownBy(() -> ValidationUtils.validateIdentifier(name, "tableName"))
                    .isInstanceOf(McpValidationException.class)
                    .hasMessageContaining("tableName");
        }

        @Test
        void rejectsAnOverlongIdentifier() {
            assertThatThrownBy(() -> ValidationUtils.validateIdentifier("a".repeat(129), "tableName"))
                    .isInstanceOf(McpValidationException.class)
                    .hasMessageContaining("tableName");
        }

        @Test
        void rejectsBlank() {
            assertThatThrownBy(() -> ValidationUtils.validateIdentifier(null, "tableName"))
                    .isInstanceOf(McpValidationException.class)
                    .hasMessageContaining("cannot be blank");
        }
    }
}
