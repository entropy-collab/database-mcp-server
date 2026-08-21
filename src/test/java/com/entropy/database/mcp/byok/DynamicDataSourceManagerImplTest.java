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

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.properties.ByokProperties;
import com.entropy.database.mcp.security.DataMaskingService;
import com.entropy.database.mcp.security.QueryAuditLogger;
import com.entropy.database.mcp.security.SqlValidator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicDataSourceManagerImplTest {

    @Mock
    private DialectResolver dialectResolver;

    @Mock
    private SqlValidator sqlValidator;

    @Mock
    private DataMaskingService maskingService;

    @Mock
    private QueryAuditLogger queryAuditLogger;

    @Mock
    private ByokInfrastructureFactory infrastructureFactory;

    @Mock
    private ConnectionPoolFactory connectionPoolFactory;

    @Mock
    private McpMetricsCollector metricsCollector;

    private DynamicDataSourceManagerImpl createManager() {
        ByokProperties properties = new ByokProperties(
                Duration.ofMinutes(30),
                Duration.ofHours(2),
                Duration.ofMinutes(5),
                100,
                10,
                2
        );
        return new DynamicDataSourceManagerImpl(
                new DynamicDataSourceManagerImpl.Dependencies(
                        dialectResolver,
                        infrastructureFactory,
                        connectionPoolFactory,
                        properties,
                        100,
                        metricsCollector
                )
        );
    }

    @Test
    void acquireNewConnection() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties connection = new ConnectionProperties(
                "jdbc:mysql://localhost:3306/test",
                "user",
                "pass",
                "mysql",
                "com.mysql.cj.jdbc.Driver",
                false
        );
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ByokInfrastructure infrastructure = mock(ByokInfrastructure.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(connectionPoolFactory.createDataSource(connection, dialect)).thenReturn(dataSource);
        when(infrastructureFactory.create(anyString(), any(JdbcTemplate.class), any(DatabaseDialect.class), anyInt())).thenReturn(infrastructure);

        ByokDataSourceContext result = manager.acquire("key1", connection);

        assertThat(result).isNotNull();
        assertThat(result.getKey()).isEqualTo("key1");
        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    void acquireExistingConnection() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties connection = new ConnectionProperties(
                "jdbc:mysql://localhost:3306/test",
                "user",
                "pass",
                "mysql",
                "com.mysql.cj.jdbc.Driver",
                false
        );
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ByokInfrastructure infrastructure = mock(ByokInfrastructure.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(connectionPoolFactory.createDataSource(connection, dialect)).thenReturn(dataSource);
        when(infrastructureFactory.create(anyString(), any(JdbcTemplate.class), any(DatabaseDialect.class), anyInt())).thenReturn(infrastructure);

        // First acquire - creates new connection
        manager.acquire("key1", connection);

        // Second acquire - should return existing connection
        ByokDataSourceContext result = manager.acquire("key1", connection);

        assertThat(result).isNotNull();
        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    void acquireExistingKeyThrowsWhenNotFound() {
        DynamicDataSourceManagerImpl manager = createManager();

        assertThrows(IllegalArgumentException.class, () -> manager.acquire("nonexistent"));
    }

    @Test
    void acquireExistingKeyReturnsContext() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties connection = new ConnectionProperties(
                "jdbc:mysql://localhost:3306/test",
                "user",
                "pass",
                "mysql",
                "com.mysql.cj.jdbc.Driver",
                false
        );
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ByokInfrastructure infrastructure = mock(ByokInfrastructure.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(connectionPoolFactory.createDataSource(connection, dialect)).thenReturn(dataSource);
        when(infrastructureFactory.create(anyString(), any(JdbcTemplate.class), any(DatabaseDialect.class), anyInt())).thenReturn(infrastructure);

        manager.acquire("key1", connection);
        ByokDataSourceContext result = manager.acquire("key1");

        assertThat(result).isNotNull();
    }

    @Test
    void registerExistingDataSource() {
        DynamicDataSourceManagerImpl manager = createManager();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        DataSource dataSource = mock(DataSource.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ByokInfrastructure infrastructure = mock(ByokInfrastructure.class);

        when(infrastructureFactory.create(anyString(), any(JdbcTemplate.class), any(DatabaseDialect.class), anyInt())).thenReturn(infrastructure);

        manager.registerExisting("primary", dataSource, dialect);

        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);
        assertThat(manager.getConnectionCount()).isEqualTo(1);
        assertThat(manager.listConnectionKeys()).containsExactly("primary");
    }

    @Test
    void shutdownClearsAll() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties connection = new ConnectionProperties(
                "jdbc:mysql://localhost:3306/test",
                "user",
                "pass",
                "mysql",
                "com.mysql.cj.jdbc.Driver",
                false
        );
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ByokInfrastructure infrastructure = mock(ByokInfrastructure.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(connectionPoolFactory.createDataSource(connection, dialect)).thenReturn(dataSource);
        when(infrastructureFactory.create(anyString(), any(JdbcTemplate.class), any(DatabaseDialect.class), anyInt())).thenReturn(infrastructure);

        manager.acquire("key1", connection);
        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);

        manager.shutdown();

        assertThat(manager.getActiveConnectionCount()).isEqualTo(0);
        assertThat(manager.getConnectionCount()).isEqualTo(0);
    }

    @Test
    void getConnectionMetadataForRegisteredConnection() {
        DynamicDataSourceManagerImpl manager = createManager();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        DataSource dataSource = mock(DataSource.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ByokInfrastructure infrastructure = mock(ByokInfrastructure.class);

        when(infrastructureFactory.create(anyString(), any(JdbcTemplate.class), any(DatabaseDialect.class), anyInt())).thenReturn(infrastructure);

        manager.registerExisting("primary", dataSource, dialect);

        ConnectionMetadata metadata = manager.getConnectionMetadata("primary");
        assertThat(metadata).isNotNull();
        assertThat(metadata.key()).isEqualTo("primary");
    }

    @Test
    void getConnectionMetadataReturnsNullForBlankKey() {
        DynamicDataSourceManagerImpl manager = createManager();

        assertThat(manager.getConnectionMetadata(null)).isNull();
        assertThat(manager.getConnectionMetadata("")).isNull();
        assertThat(manager.getConnectionMetadata("  ")).isNull();
    }

    @Test
    void maskKeyWithPassword() {
        DynamicDataSourceManagerImpl manager = createManager();

        // Access via reflection to test private method
        try {
            java.lang.reflect.Method method = DynamicDataSourceManagerImpl.class.getDeclaredMethod("maskKey", String.class);
            method.setAccessible(true);
            String result = (String) method.invoke(manager, "jdbc:mysql://user:secret@localhost:3306/test");
            assertThat(result).isEqualTo("jdbc:mysql://****@localhost:3306/test");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void maskKeyWithoutPassword() {
        DynamicDataSourceManagerImpl manager = createManager();

        try {
            java.lang.reflect.Method method = DynamicDataSourceManagerImpl.class.getDeclaredMethod("maskKey", String.class);
            method.setAccessible(true);
            String result = (String) method.invoke(manager, "jdbc:mysql://localhost:3306/test");
            assertThat(result).isEqualTo("jdbc:mysql://localhost:3306/test");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void maskKeyNull() {
        DynamicDataSourceManagerImpl manager = createManager();

        try {
            java.lang.reflect.Method method = DynamicDataSourceManagerImpl.class.getDeclaredMethod("maskKey", String.class);
            method.setAccessible(true);
            String result = (String) method.invoke(manager, (Object) null);
            assertThat(result).isEqualTo("null");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
