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
    void getCacheKeyCombinesJdbcUrlUsernameDialectCredentialAndReadonly() {
        ConnectionProperties props = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost/test", "user", "pass", null, null, null
        );

        assertThat(props.getCacheKey())
                .startsWith("jdbc:oracle:thin:@//localhost/test|user|oracle|")
                .endsWith("|false")
                .doesNotContain("pass");
    }

    @Test
    void getCacheKeyDiffersWhenOnlyPasswordDiffers() {
        ConnectionProperties correct = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost/test", "user", "right", null, null, null
        );
        ConnectionProperties wrong = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost/test", "user", "wrong", null, null, null
        );

        assertThat(correct.getCacheKey()).isNotEqualTo(wrong.getCacheKey());
    }

    @Test
    void getCacheKeyDiffersWhenOnlyReadonlyDiffers() {
        ConnectionProperties writable = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost/test", "user", "pass", null, null, false
        );
        ConnectionProperties readonly = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost/test", "user", "pass", null, null, true
        );

        assertThat(writable.getCacheKey()).isNotEqualTo(readonly.getCacheKey());
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

    // ─── 指纹口径：只消除无意义差异，绝不合并语义不同的 URL ────────────────────

    /**
     * 旧实现把 {@code ?} 之后整段丢弃，于是两个不同 schema 的连接指纹相同，会被判成别名共用一个池——
     * 调用方以为在读 a，实际读到 b。
     */
    @Test
    void fingerprintKeepsDifferentQueryParameterValuesApart() {
        ConnectionProperties schemaA = new ConnectionProperties(
                "jdbc:postgresql://host:5432/db?currentSchema=a", "user", "pass", null, null, null
        );
        ConnectionProperties schemaB = new ConnectionProperties(
                "jdbc:postgresql://host:5432/db?currentSchema=b", "user", "pass", null, null, null
        );

        assertThat(schemaA.getCacheKey()).isNotEqualTo(schemaB.getCacheKey());
    }

    /**
     * 分号风格的 {@code databaseName} 是语义参数：任何「支持分号就把分号后面截掉」的实现都会把两个
     * 不同的库合成一个池，这是数据串库，必须挡住。
     */
    @Test
    void fingerprintKeepsDifferentSemicolonDatabaseNamesApart() {
        ConnectionProperties dbA = new ConnectionProperties(
                "jdbc:sqlserver://host:1433;databaseName=a;encrypt=true", "user", "pass", null, null, null
        );
        ConnectionProperties dbB = new ConnectionProperties(
                "jdbc:sqlserver://host:1433;databaseName=b;encrypt=true", "user", "pass", null, null, null
        );

        assertThat(dbA.getCacheKey()).isNotEqualTo(dbB.getCacheKey());
    }

    /** 参数顺序不同不是语义差异，旧实现对分号风格原样保留字符串，会少去重一次。 */
    @Test
    void fingerprintIgnoresSemicolonParameterOrder() {
        ConnectionProperties first = new ConnectionProperties(
                "jdbc:sqlserver://host:1433;databaseName=db;encrypt=true", "user", "pass", null, null, null
        );
        ConnectionProperties second = new ConnectionProperties(
                "jdbc:sqlserver://host:1433;encrypt=true;databaseName=db", "user", "pass", null, null, null
        );

        assertThat(first.getCacheKey()).isEqualTo(second.getCacheKey());
    }

    /** 键的大小写与多余空白同样不是语义差异。 */
    @Test
    void fingerprintIgnoresParameterKeyCaseAndSurroundingWhitespace() {
        ConnectionProperties first = new ConnectionProperties(
                "jdbc:sqlserver://host:1433;databaseName=db;encrypt=true", "user", "pass", null, null, null
        );
        ConnectionProperties second = new ConnectionProperties(
                "jdbc:sqlserver://host:1433; DATABASENAME = db ;encrypt=true;", "user", "pass", null, null, null
        );

        assertThat(first.getCacheKey()).isEqualTo(second.getCacheKey());
    }

    /** 查询串风格的顺序、大小写归一同样成立，但值必须原样参与比较。 */
    @Test
    void fingerprintIgnoresQueryParameterOrderButNotValues() {
        ConnectionProperties first = new ConnectionProperties(
                "jdbc:mysql://host:3306/db?useSSL=true&serverTimezone=UTC", "user", "pass", null, null, null
        );
        ConnectionProperties reordered = new ConnectionProperties(
                "jdbc:mysql://host:3306/db?serverTimezone=UTC&usessl=true", "user", "pass", null, null, null
        );
        ConnectionProperties otherTimezone = new ConnectionProperties(
                "jdbc:mysql://host:3306/db?useSSL=true&serverTimezone=Asia/Shanghai", "user", "pass", null, null, null
        );

        assertThat(first.getCacheKey()).isEqualTo(reordered.getCacheKey());
        assertThat(first.getCacheKey()).isNotEqualTo(otherTimezone.getCacheKey());
    }

    /**
     * 同一个键重复出现时多数驱动是「后者覆盖前者」，排序会把生效值不同的两个 URL 抹成同一个指纹，
     * 所以这种情况必须保持原有顺序。
     */
    @Test
    void fingerprintDoesNotReorderRepeatedParameterKeys() {
        ConnectionProperties lastWinsB = new ConnectionProperties(
                "jdbc:postgresql://host:5432/db?currentSchema=a&currentSchema=b", "user", "pass", null, null, null
        );
        ConnectionProperties lastWinsA = new ConnectionProperties(
                "jdbc:postgresql://host:5432/db?currentSchema=b&currentSchema=a", "user", "pass", null, null, null
        );

        assertThat(lastWinsB.getCacheKey()).isNotEqualTo(lastWinsA.getCacheKey());
    }

    /** 分号风格的 base 自带 {@code //host:port}，不能被当成参数拆走。 */
    @Test
    void normalizeKeepsTheHostInTheBaseForSemicolonStyleUrls() {
        assertThat(ConnectionProperties.normalizeJdbcUrl(
                "jdbc:sqlserver://host:1433;encrypt=true;databaseName=db"))
                .isEqualTo("jdbc:sqlserver://host:1433;databasename=db;encrypt=true");
        assertThat(ConnectionProperties.normalizeJdbcUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"))
                .isEqualTo("jdbc:h2:mem:testdb;db_close_delay=-1");
    }

    /** 无参数的 URL 原样返回，避免归一化本身引入新的差异。 */
    @Test
    void normalizeLeavesParameterlessUrlsUntouched() {
        assertThat(ConnectionProperties.normalizeJdbcUrl("jdbc:oracle:thin:@//localhost:1521/test"))
                .isEqualTo("jdbc:oracle:thin:@//localhost:1521/test");
        assertThat(ConnectionProperties.normalizeJdbcUrl(
                "jdbc:oracle:thin:@(description=(address=(protocol=tcp)(host=h)(port=1521)))"))
                .isEqualTo("jdbc:oracle:thin:@(description=(address=(protocol=tcp)(host=h)(port=1521)))");
    }
}
