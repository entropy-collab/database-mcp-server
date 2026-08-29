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
import com.entropy.database.mcp.properties.ConfiguredConnectionProperties;
import com.entropy.database.mcp.properties.ConfiguredConnectionProperties.Definition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    private final DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);

    private static Definition definition(String url, boolean required) {
        return new Definition(url, "mcp_reader", "secret", null, null, true, required);
    }

    private ConfiguredConnectionRegistrar registrar(Map<String, Definition> connections) {
        return new ConfiguredConnectionRegistrar(
                new ConfiguredConnectionProperties(connections), manager);
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
}
