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
package com.entropy.database.mcp.byok;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionPropertiesTest {

    @Test
    void rejectsBlankJdbcUrl() {
        assertThatThrownBy(() -> new ConnectionProperties("", "user", "pass", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbcUrl is required");
    }

    @Test
    void rejectsBlankUsername() {
        assertThatThrownBy(() -> new ConnectionProperties("jdbc:oracle:thin:@//localhost/test", "", "pass", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username is required");
    }

    @Test
    void defaultsPasswordToEmptyString() {
        ConnectionProperties props = new ConnectionProperties("jdbc:oracle:thin:@//localhost/test", "user", null, null, null, null);

        assertThat(props.password()).isEmpty();
    }

    @Test
    void infersOracleDialectAndDriver() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost:1521/test", "user", "pass", null, null, null
        );

        assertThat(props.dialect()).isEqualTo("oracle");
        assertThat(props.driverClassName()).isEqualTo("oracle.jdbc.OracleDriver");
    }

    @Test
    void infersMySqlDialectAndDriver() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:mysql://localhost:3306/test", "user", "pass", null, null, null
        );

        assertThat(props.dialect()).isEqualTo("mysql");
        assertThat(props.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    void infersPostgresDialectAndDriver() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:postgresql://localhost:5432/test", "user", "pass", null, null, null
        );

        assertThat(props.dialect()).isEqualTo("postgres");
        assertThat(props.driverClassName()).isEqualTo("org.postgresql.Driver");
    }

    @Test
    void infersSqlServerDialectAndDriver() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:sqlserver://localhost:1433/test", "user", "pass", null, null, null
        );

        assertThat(props.dialect()).isEqualTo("sqlserver");
        assertThat(props.driverClassName()).isEqualTo("com.microsoft.sqlserver.jdbc.SQLServerDriver");
    }

    @Test
    void infersSqliteDialectAndDriver() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:sqlite:///test.db", "user", "pass", null, null, null
        );

        assertThat(props.dialect()).isEqualTo("sqlite");
        assertThat(props.driverClassName()).isEqualTo("org.sqlite.JDBC");
    }

    @Test
    void infersDb2DialectAndDriver() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:db2://localhost:50000/test", "user", "pass", null, null, null
        );

        assertThat(props.dialect()).isEqualTo("db2");
        assertThat(props.driverClassName()).isEqualTo("com.ibm.db2.jdbc.DB2Driver");
    }

    @Test
    void infersGenericDialectForUnknownJdbcUrl() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:unknown://localhost/test", "user", "pass", null, null, null
        );

        assertThat(props.dialect()).isEqualTo("generic");
        assertThat(props.driverClassName()).isNull();
    }

    @Test
    void defaultsReadonlyToFalse() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost/test", "user", "pass", null, null, null
        );

        assertThat(props.readonly()).isFalse();
    }

    @Test
    void getCacheKeyCombinesJdbcUrlUsernameAndDialect() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost/test", "user", "pass", null, null, null
        );

        assertThat(props.getCacheKey()).isEqualTo("jdbc:oracle:thin:@//localhost/test|user|oracle");
    }

    @ParameterizedTest
    @ValueSource(strings = {"oracle", "mysql", "postgres", "sqlserver", "sqlite", "db2"})
    void preservesExplicitDialect(String dialect) {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:unknown://localhost/test", "user", "pass", dialect, "com.example.Driver", null
        );

        assertThat(props.dialect()).isEqualTo(dialect);
        assertThat(props.driverClassName()).isEqualTo("com.example.Driver");
    }
}
