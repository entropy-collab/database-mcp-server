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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the audit-log secret masking in {@link QueryAuditLoggerImpl}.
 *
 * <p>The previous pattern required {@code 'password'= '...'} — a field name wrapped in single
 * quotes followed by {@code =} — which is not valid SQL, so nothing was ever masked and Oracle's
 * {@code IDENTIFIED BY} password from {@code createDbLink} was written verbatim to the audit log,
 * the in-memory buffer and the {@code audit_log} table.
 */
class QueryAuditLoggerMaskingTest {

    @Test
    void masksOracleDbLinkPassword() {
        String sql = "CREATE DATABASE LINK REMOTE_LNK CONNECT TO SCOTT IDENTIFIED BY \"s3cr3t\" "
                + "USING '(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db.example.com)(PORT=1521))"
                + "(CONNECT_DATA=(SERVICE_NAME=ORCL)))'";

        String masked = QueryAuditLoggerImpl.maskSensitiveValues(sql);

        assertThat(masked).doesNotContain("s3cr3t");
        // Structure survives, so the audit trail is still readable
        assertThat(masked).contains("CREATE DATABASE LINK REMOTE_LNK CONNECT TO SCOTT IDENTIFIED BY '***'");
        assertThat(masked).contains("SERVICE_NAME=ORCL");
    }

    @Test
    void masksUnquotedIdentifiedBy() {
        String masked = QueryAuditLoggerImpl.maskSensitiveValues("ALTER USER scott IDENTIFIED BY tiger123");

        assertThat(masked).doesNotContain("tiger123");
        assertThat(masked).contains("IDENTIFIED BY '***'");
    }

    @Test
    void masksIdentifiedByValuesHash() {
        String masked = QueryAuditLoggerImpl.maskSensitiveValues(
                "CREATE USER u IDENTIFIED BY VALUES 'S:AB12CDEF'");

        assertThat(masked).doesNotContain("S:AB12CDEF");
    }

    @Test
    void masksPasswordAssignment() {
        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("UPDATE users SET password = 'p@ss' WHERE id = 1"))
                .doesNotContain("p@ss")
                .contains("SET password = '***'")
                .contains("WHERE id = 1");

        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("UPDATE users SET password='p@ss'"))
                .doesNotContain("p@ss");

        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("connect PWD=topsecret"))
                .doesNotContain("topsecret");
    }

    @Test
    void masksSetPasswordForUser() {
        String masked = QueryAuditLoggerImpl.maskSensitiveValues(
                "SET PASSWORD FOR 'app'@'localhost' = 'hunter2'");

        assertThat(masked).doesNotContain("hunter2");
        assertThat(masked).contains("SET PASSWORD FOR 'app'@'localhost'");
    }

    @Test
    void masksJdbcUrlCredentials() {
        String masked = QueryAuditLoggerImpl.maskSensitiveValues("jdbc:mysql://u:pw@h/db");

        assertThat(masked).doesNotContain(":pw@");
        assertThat(masked).contains("jdbc:mysql://u:***@h/db");
    }

    @Test
    void masksSecretAndTokenFields() {
        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("INSERT INTO t (api_key) VALUES ('ak-123')"))
                .isNotNull();
        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("SET secret = 'shh', token := 'tk-9'"))
                .doesNotContain("shh")
                .doesNotContain("tk-9");
    }

    @Test
    void masksPasswordThatFollowsTheKeywordDirectly() {
        // PostgreSQL/H2 在关键字后直接给值，没有 = 也没有 :，靠 "field = value" 的规则抓不到
        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("CREATE USER app WITH PASSWORD 'sup3rs3cret'"))
                .doesNotContain("sup3rs3cret")
                .contains("CREATE USER app WITH PASSWORD '***'");

        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("ALTER ROLE app WITH LOGIN PASSWORD 'sup3rs3cret'"))
                .doesNotContain("sup3rs3cret")
                .contains("ALTER ROLE app WITH LOGIN PASSWORD '***'");

        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("CREATE USER h2u PASSWORD 'sup3rs3cret'"))
                .doesNotContain("sup3rs3cret");
    }

    @Test
    void masksMysqlPasswordFunctionArgument() {
        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("SELECT PASSWORD('sup3rs3cret')"))
                .doesNotContain("sup3rs3cret")
                .contains("PASSWORD('***')");
    }

    @Test
    void masksOracleReplacedOldPassword() {
        String masked = QueryAuditLoggerImpl.maskSensitiveValues(
                "ALTER USER scott IDENTIFIED BY newpw REPLACE oldpw123");

        assertThat(masked).doesNotContain("newpw").doesNotContain("oldpw123");
        assertThat(masked).contains("IDENTIFIED BY '***' REPLACE '***'");
    }

    @Test
    void leavesTheReplaceStringFunctionAlone() {
        String sql = "UPDATE t SET name = REPLACE(name, 'a', 'b') WHERE id = 1";

        assertThat(QueryAuditLoggerImpl.maskSensitiveValues(sql)).isEqualTo(sql);
    }

    @Test
    void masksInsertValueOfASecretColumn() {
        String masked = QueryAuditLoggerImpl.maskSensitiveValues(
                "INSERT INTO users (name, password) VALUES ('bob', 'sup3rs3cret')");

        assertThat(masked).doesNotContain("sup3rs3cret");
        // 只有口令列被换掉，其余值保留，审计仍然能看出这条语句做了什么
        assertThat(masked).contains("'bob'").contains("'***'");
    }

    @Test
    void masksEveryRowOfAMultiRowInsert() {
        String masked = QueryAuditLoggerImpl.maskSensitiveValues(
                "INSERT INTO users (name, pwd) VALUES ('bob', 'first'), ('eve', 'second')");

        assertThat(masked).doesNotContain("first").doesNotContain("second");
        assertThat(masked).contains("'bob'").contains("'eve'");
    }

    @Test
    void insertWithoutSecretColumnsIsUntouched() {
        String sql = "INSERT INTO t (a, b) VALUES (1, 'x')";

        assertThat(QueryAuditLoggerImpl.maskSensitiveValues(sql)).isEqualTo(sql);
    }

    @Test
    void unparseableInsertFallsBackToTheRegexResult() {
        // 审计在热路径上：解析失败只能退回正则结果，不能把异常抛给调用方
        String sql = "INSERT INTO users (name, password) VALUES ('bob',";

        assertThat(QueryAuditLoggerImpl.maskSensitiveValues(sql)).isEqualTo(sql);
    }

    @Test
    void insertFromSelectIsHandledWithoutThrowing() {
        String sql = "INSERT INTO users (name, password) SELECT n, p FROM staging";

        assertThat(QueryAuditLoggerImpl.maskSensitiveValues(sql)).isEqualTo(sql);
    }

    @Test
    void leavesOrdinarySqlAlone() {
        String sql = "SELECT id, name FROM users WHERE status = 'A' AND amount > 100";

        assertThat(QueryAuditLoggerImpl.maskSensitiveValues(sql)).isEqualTo(sql);
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(QueryAuditLoggerImpl.maskSensitiveValues(null)).isNull();
        assertThat(QueryAuditLoggerImpl.maskSensitiveValues("")).isEmpty();
    }
}
