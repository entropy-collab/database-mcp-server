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
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SPI contract for dialects: discovery, aliases, URL detection, third-party override.
 *
 * <p>These assertions are what make the {@code switch}-free {@link DialectResolver} trustworthy. If
 * the {@code META-INF/services} entry for a built-in goes missing, {@code resolve("oracle", …)}
 * silently degrades to {@link GenericDialect} — every metadata query still answers, just with the
 * wrong SQL — so the failure would otherwise surface as subtly wrong results rather than an error.
 */
class DialectProviderSpiTest {

    private final DialectResolver resolver = new DialectResolver();

    @Test
    void allEightBuiltInDialectsAreDiscoverableViaServiceLoader() {
        List<String> names = ServiceLoader.load(DialectProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(DialectProvider::getName)
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "oracle", "mysql", "postgres", "sqlserver", "sqlite", "db2", "h2", "generic");
    }

    @Test
    void everyBuiltInProviderReportsItselfAsBuiltIn() {
        assertThat(ServiceLoader.load(DialectProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> !p.isBuiltIn())
                .map(p -> p.getClass().getName()))
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "oracle,OracleDialect",
            "mysql,MySqlDialect",
            "mariadb,MySqlDialect",
            "postgres,PostgresDialect",
            "postgresql,PostgresDialect",
            "sqlserver,SqlServerDialect",
            "mssql,SqlServerDialect",
            "sqlite,SqliteDialect",
            "db2,Db2Dialect",
            "h2,H2Dialect",
            "generic,GenericDialect",
            // 大小写与空白都归一化，因为这些值来自 yml，手写空格很常见
            "  PostgreSQL  ,PostgresDialect",
            "ORACLE,OracleDialect",
    })
    void resolvesNamesAndAliases(String configured, String expectedClass) {
        assertThat(resolver.resolve(configured, null).getClass().getSimpleName())
                .isEqualTo(expectedClass);
    }

    @Test
    void unknownDialectFallsBackToGeneric() {
        assertThat(resolver.resolve("no-such-db", null)).isInstanceOf(GenericDialect.class);
        assertThat(resolver.resolve(null, null)).isInstanceOf(GenericDialect.class);
        assertThat(resolver.resolve("  ", null)).isInstanceOf(GenericDialect.class);
    }

    @ParameterizedTest
    @CsvSource({
            "jdbc:oracle:thin:@//host:1521/orcl,OracleDialect",
            "jdbc:mysql://host:3306/app,MySqlDialect",
            "jdbc:mariadb://host:3306/app,MySqlDialect",
            "jdbc:postgresql://host:5432/app,PostgresDialect",
            "jdbc:sqlserver://host:1433;databaseName=app,SqlServerDialect",
            "jdbc:sqlite:/tmp/app.db,SqliteDialect",
            "jdbc:db2://host:50000/app,Db2Dialect",
            "jdbc:h2:mem:app,H2Dialect",
            "jdbc:unknown://host/app,GenericDialect",
    })
    void detectsDialectFromJdbcUrl(String url, String expectedClass) {
        assertThat(resolver.forJdbcUrl(url).getClass().getSimpleName()).isEqualTo(expectedClass);
    }

    /**
     * The URL prefixes now live on the providers, while {@link DialectUtils#inferDialect} keeps its
     * own copy for the BYOK naming path ({@code ConnectionProperties}). Two copies drift, so pin
     * them together: any prefix a provider claims must map to the same dialect name in DialectUtils.
     */
    @Test
    void providerUrlPrefixesAgreeWithDialectUtils() {
        ServiceLoader.load(DialectProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .forEach(provider -> provider.getJdbcUrlPrefixes().forEach(prefix ->
                        assertThat(DialectUtils.inferDialect(prefix + "//host/db"))
                                .as("DialectUtils.inferDialect disagrees with provider %s on prefix %s",
                                        provider.getClass().getSimpleName(), prefix)
                                .isEqualTo(provider.getName())));
    }

    @Test
    void thirdPartyProviderOverridesABuiltInName() {
        var providers = new java.util.ArrayList<DialectProvider>();
        ServiceLoader.load(DialectProvider.class).forEach(providers::add);
        providers.add(new FakeOracleProvider());

        var overridden = new DialectResolver(providers);

        assertThat(overridden.resolve("oracle", null)).isInstanceOf(FakeOracleDialect.class);
        // 对照组：其他方言不受影响，证明覆盖是按名字精确生效而不是整表被替换
        assertThat(overridden.resolve("mysql", null)).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void thirdPartyProviderCanRegisterANewNameAndUrlPrefix() {
        var providers = new java.util.ArrayList<DialectProvider>();
        ServiceLoader.load(DialectProvider.class).forEach(providers::add);
        providers.add(new DamengProvider());

        var extended = new DialectResolver(providers);

        assertThat(extended.resolve("dameng", null)).isInstanceOf(FakeOracleDialect.class);
        assertThat(extended.resolve("dm", null)).isInstanceOf(FakeOracleDialect.class);
        assertThat(extended.forJdbcUrl("jdbc:dm://host:5236/app")).isInstanceOf(FakeOracleDialect.class);
        assertThat(extended.registeredNames()).contains("dameng", "dm");
    }

    @Test
    void resolvedDialectsAreSharedInstances() {
        assertThat(resolver.resolve("h2", null)).isSameAs(resolver.resolve("h2", null));
    }

    // ─── Fakes ─────────────────────────────────────────────────────────────

    private static final class FakeOracleDialect extends GenericDialect {
    }

    private static final class FakeOracleProvider implements DialectProvider {
        private final FakeOracleDialect dialect = new FakeOracleDialect();

        @Override
        public String getName() {
            return "oracle";
        }

        @Override
        public DatabaseDialect getDialect() {
            return dialect;
        }
    }

    private static final class DamengProvider implements DialectProvider {
        private final FakeOracleDialect dialect = new FakeOracleDialect();

        @Override
        public String getName() {
            return "dameng";
        }

        @Override
        public DatabaseDialect getDialect() {
            return dialect;
        }

        @Override
        public List<String> getAliases() {
            return List.of("dm");
        }

        @Override
        public List<String> getJdbcUrlPrefixes() {
            return List.of("jdbc:dm:");
        }
    }
}
