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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code quote()} must escape the delimiter it wraps with. Without doubling, a single delimiter
 * character inside the name closes the identifier and everything after it is parsed as SQL, which
 * turns every "already quoted, therefore safe" call site in the codebase into an injection point.
 */
class DialectQuotingTest {

    /** Every dialect implementation, so a newly added one cannot skip the escaping contract. */
    private static Stream<DatabaseDialect> allDialects() {
        return Stream.of(new OracleDialect(), new MySqlDialect(), new PostgresDialect(),
                new SqlServerDialect(), new SqliteDialect(), new Db2Dialect(),
                new H2Dialect(), new GenericDialect());
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> doublingCases() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(new OracleDialect(), "A\"B", "\"A\"\"B\""),
                org.junit.jupiter.params.provider.Arguments.of(new PostgresDialect(), "a\"b", "\"a\"\"b\""),
                org.junit.jupiter.params.provider.Arguments.of(new SqliteDialect(), "a\"b", "\"a\"\"b\""),
                org.junit.jupiter.params.provider.Arguments.of(new Db2Dialect(), "a\"b", "\"a\"\"b\""),
                org.junit.jupiter.params.provider.Arguments.of(new H2Dialect(), "a\"b", "\"a\"\"b\""),
                org.junit.jupiter.params.provider.Arguments.of(new MySqlDialect(), "a`b", "`a``b`"),
                org.junit.jupiter.params.provider.Arguments.of(new GenericDialect(), "a`b", "`a``b`"),
                org.junit.jupiter.params.provider.Arguments.of(new SqlServerDialect(), "a]b", "[a]]b]"));
    }

    @ParameterizedTest
    @MethodSource("doublingCases")
    void quoteDoublesTheEmbeddedDelimiter(DatabaseDialect dialect, String name, String expected) {
        assertThat(dialect.quote(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("allDialects")
    void quoteLeavesNoUnescapedDelimiterInsideTheIdentifier(DatabaseDialect dialect) {
        // A payload carrying every delimiter character at once: whichever delimiter this dialect
        // uses must appear only in doubled form inside the identifier body.
        String quoted = dialect.quote("t\"`]x");
        String closing = String.valueOf(quoted.charAt(quoted.length() - 1));
        String body = quoted.substring(1, quoted.length() - 1);

        assertThat(body.replace(closing + closing, ""))
                .as("dialect %s left an unescaped %s in %s", dialect.getDialectName(), closing, quoted)
                .doesNotContain(closing);
    }

    @ParameterizedTest
    @MethodSource("allDialects")
    void quoteIsUnchangedForPlainIdentifiers(DatabaseDialect dialect) {
        assertThat(dialect.quote("EMPLOYEES")).isIn("\"EMPLOYEES\"", "`EMPLOYEES`", "[EMPLOYEES]");
    }

    @Test
    void oracleStillUpperCasesWhileEscaping() {
        OracleDialect oracle = new OracleDialect();

        assertThat(oracle.quote("employees")).isEqualTo("\"EMPLOYEES\"");
        assertThat(oracle.quote("emp\"loyees")).isEqualTo("\"EMP\"\"LOYEES\"");
    }

    @Test
    void quotingNeutralizesAnIdentifierBreakoutAttempt() {
        // `t" ; DROP TABLE users --` must stay one identifier instead of closing it and starting a
        // second statement.
        String payload = "t\" ; DROP TABLE users --";

        assertThat(new PostgresDialect().quote(payload))
                .isEqualTo("\"t\"\" ; DROP TABLE users --\"");
        assertThat(new MySqlDialect().quote("t` ; DROP TABLE users --"))
                .isEqualTo("`t`` ; DROP TABLE users --`");
        assertThat(new SqlServerDialect().quote("t] ; DROP TABLE users --"))
                .isEqualTo("[t]] ; DROP TABLE users --]");
    }
}
