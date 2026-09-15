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
package com.entropy.database.mcp.init;

import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.credential.CredentialCipher;
import com.entropy.database.mcp.credential.SealedCredential;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.properties.ConfiguredConnectionProperties;
import com.entropy.database.mcp.properties.ConfiguredConnectionProperties.Definition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Startup behaviour of {@code entropy.mcp.database.connections}.
 *
 * <p>The interesting part is the failure policy: {@code required: true} must abort startup, everything
 * else must degrade to a WARN so that one unreachable database does not take the whole server down.
 * Both directions are asserted, because getting either wrong is silent — a swallowed required failure
 * leaves clients hitting a connection that does not exist, and an over-eager abort turns a single flaky
 * database into a failed deploy.
 */
class ConfiguredConnectionRegistrarTest {

    private static KeyPair keyPair;

    private final DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);

    @BeforeAll
    static void generateKeyPair() throws Exception {
        // 2048 位就够跑测试，且比 3072 快一截；容量边界另有断言（见 CredentialCipherTest）。
        KeyPairGenerator generator = KeyPairGenerator.getInstance(CredentialCipher.KEY_ALGORITHM);
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private static Definition definition(String url, boolean required) {
        return new Definition(url, "mcp_reader", "secret", null, null, true, required);
    }

    private ConfiguredConnectionRegistrar registrar(Map<String, Definition> connections) {
        return registrar(connections, null);
    }

    private ConfiguredConnectionRegistrar registrar(Map<String, Definition> connections,
                                                    CredentialCipher cipher) {
        @SuppressWarnings("unchecked")
        ObjectProvider<CredentialCipher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cipher);
        return new ConfiguredConnectionRegistrar(
                new ConfiguredConnectionProperties(connections), manager, provider);
    }


    @Test
    void registersEveryDeclaredConnectionAsPinned() {
        Map<String, Definition> declared = new LinkedHashMap<>();
        declared.put("oracle-prod", definition("jdbc:oracle:thin:@//host:1521/ORCL", true));
        declared.put("pg-reporting", definition("jdbc:postgresql://host:5432/reporting", false));

        registrar(declared).run(null);

        var captor = ArgumentCaptor.forClass(ConnectionProperties.class);
        verify(manager).registerPinned(eq("oracle-prod"), captor.capture());
        verify(manager).registerPinned(eq("pg-reporting"), any());

        // dialect 留空时由 JDBC URL 推断，配置里不必重复写一遍
        assertThat(captor.getValue().dialect()).isEqualTo("oracle");
        assertThat(captor.getValue().readonly()).isTrue();
    }

    @Test
    void requiredConnectionThatCannotBeEstablishedAbortsStartup() {
        doThrow(new IllegalStateException("ORA-12541: TNS:no listener"))
                .when(manager).registerPinned(eq("oracle-prod"), any());

        var registrar = registrar(Map.of(
                "oracle-prod", definition("jdbc:oracle:thin:@//host:1521/ORCL", true)));

        assertThatThrownBy(() -> registrar.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oracle-prod")
                .hasMessageContaining("ORA-12541");
    }

    @Test
    void optionalConnectionThatCannotBeEstablishedIsSkipped() {
        doThrow(new IllegalStateException("connection refused"))
                .when(manager).registerPinned(eq("pg-reporting"), any());

        Map<String, Definition> declared = new LinkedHashMap<>();
        declared.put("pg-reporting", definition("jdbc:postgresql://host:5432/reporting", false));
        declared.put("oracle-prod", definition("jdbc:oracle:thin:@//host:1521/ORCL", false));

        // 不抛，并且后面的连接照常注册——失败的库不能挡住其他库
        registrar(declared).run(null);

        verify(manager).registerPinned(eq("oracle-prod"), any());
    }

    @Test
    void malformedDefinitionIsFatalRegardlessOfRequired() {
        var registrar = registrar(Map.of(
                "broken", new Definition("postgresql://missing-jdbc-prefix", "u", "p",
                        null, null, false, false)));

        assertThatThrownBy(() -> registrar.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entropy.mcp.database.connections.broken.jdbc-url");

        verify(manager, never()).registerPinned(any(), any());
    }

    @Test
    void noDeclaredConnectionsIsNotAnError() {
        registrar(Map.of()).run(null);
        verify(manager, never()).registerPinned(any(), any());
    }

    @Test
    void autoDialectIsTreatedAsUnsetSoTheUrlDecides() {
        var definition = new Definition("jdbc:mysql://host:3306/app", "u", "p",
                "auto", null, false, false);

        assertThat(definition.dialect())
                .as("dialect=auto 在这里没有意义：URL 已知，归一成 null 让推断生效，"
                        + "否则会解析成 GenericDialect")
                .isNull();
    }

    /**
     * {@code password: sealed:…}——Druid {@code ConfigTools} 那个场景：配置文件里不留明文口令。
     *
     * <p>与运行时那条路的关键差别在这里被钉住：配置里的密文<b>不要求有效期</b>（进程重启必须还能用），
     * 但绑定关系照样强制，而且解密失败一律致命——即便 {@code required: false}。
     */
    @Nested
    class SealedPasswords {

        private static final String URL = "jdbc:postgresql://host:5432/reporting";

        private String seal(String connectionName, String username, String jdbcUrl, Duration ttl) {
            return CredentialCipher.seal(keyPair.getPublic(), new SealedCredential(
                    CredentialCipher.CURRENT_VERSION, connectionName, username, "s3cret-from-cipher",
                    CredentialCipher.fingerprint(jdbcUrl),
                    ttl == null ? null : Instant.now().plus(ttl)));
        }

        private Definition sealedDefinition(String sealed, String username, boolean required) {
            return new Definition(URL, username, "sealed:" + sealed, null, null, true, required);
        }

        private CredentialCipher cipher() {
            return new CredentialCipher(keyPair.getPrivate());
        }

        @Test
        void decryptsThePasswordAndRegistersTheConnection() {
            var declared = Map.of("pg-reporting",
                    sealedDefinition(seal("pg-reporting", "mcp_reader", URL, null), "mcp_reader", true));

            registrar(declared, cipher()).run(null);

            var captor = ArgumentCaptor.forClass(ConnectionProperties.class);
            verify(manager).registerPinned(eq("pg-reporting"), captor.capture());
            assertThat(captor.getValue().password()).isEqualTo("s3cret-from-cipher");
        }

        /** 没有有效期在这条路上是合法的：配置项不该在某个凌晨自己失效。 */
        @Test
        void acceptsACredentialWithoutAnExpiry() {
            var declared = Map.of("pg-reporting",
                    sealedDefinition(seal("pg-reporting", "mcp_reader", URL, null), "mcp_reader", false));

            registrar(declared, cipher()).run(null);

            verify(manager).registerPinned(eq("pg-reporting"), any());
        }

        /** 但运维一旦封了有效期，过期后就必须失效，否则那个 ttl 只是句空话。 */
        @Test
        void stillRejectsAnExpiredCredential() {
            var declared = Map.of("pg-reporting", sealedDefinition(
                    seal("pg-reporting", "mcp_reader", URL, Duration.ofSeconds(-30)), "mcp_reader", false));

            assertThatThrownBy(() -> registrar(declared, cipher()).run(null))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("已过期");
        }

        @Test
        void rejectsACredentialSealedForAnotherConnectionName() {
            var declared = Map.of("pg-reporting",
                    sealedDefinition(seal("other-db", "mcp_reader", URL, null), "mcp_reader", false));

            assertThatThrownBy(() -> registrar(declared, cipher()).run(null))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("连接名");
        }

        @Test
        void rejectsACredentialSealedForAnotherJdbcUrl() {
            var declared = Map.of("pg-reporting", sealedDefinition(
                    seal("pg-reporting", "mcp_reader", "jdbc:postgresql://attacker:5432/reporting", null),
                    "mcp_reader", false));

            assertThatThrownBy(() -> registrar(declared, cipher()).run(null))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("jdbcUrl");
        }

        /** 配置里写 mcp_reader、密文里其实是别的账号——不核对的话谁都看不出来。 */
        @Test
        void rejectsAUsernameThatDisagreesWithTheDeclaredOne() {
            var declared = Map.of("pg-reporting",
                    sealedDefinition(seal("pg-reporting", "sys", URL, null), "mcp_reader", false));

            assertThatThrownBy(() -> registrar(declared, cipher()).run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("username");
        }

        /** 私钥没配却写了密文：这是部署错误，required=false 也不能降级成"跳过这台库"。 */
        @Test
        void missingPrivateKeyIsFatalEvenForAnOptionalConnection() {
            var declared = Map.of("pg-reporting",
                    sealedDefinition(seal("pg-reporting", "mcp_reader", URL, null), "mcp_reader", false));

            assertThatThrownBy(() -> registrar(declared, null).run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("credential-cipher.private-key");

            verify(manager, never()).registerPinned(any(), any());
        }
    }
}
