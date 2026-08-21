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
import com.entropy.database.mcp.properties.ByokProperties;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConnectionPoolFactoryTest {

    @Test
    void createHikariConfigSetsPoolSettings() {
        ByokProperties byokProperties = new ByokProperties(
                Duration.ofMinutes(30),
                Duration.ofHours(2),
                Duration.ofMinutes(5),
                100,
                10,
                2
        );
        DatabaseProperties databaseProperties = new DatabaseProperties(
                true,
                "oracle",
                new DatabaseProperties.QueryProperties(100, 30, true, 10000, 500, 100),
                new DatabaseProperties.AuditProperties(true, 7),
                new DatabaseProperties.DdlProperties(false),
                new DatabaseProperties.SecurityProperties(10, 5),
                new DatabaseProperties.EtlProperties(4),
                new DatabaseProperties.CacheProperties(1000, 30, 5, 10),
                new DatabaseProperties.ConnectionPoolProperties(30000, 600000, 1800000),
                new DatabaseProperties.PreparedStatementProperties(250, 2048),
                new DatabaseProperties.MetricsProperties(5000)
        );
        ConnectionPoolFactory factory = new ConnectionPoolFactory(byokProperties, databaseProperties);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ConnectionProperties connection = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost:1521/test",
                "user",
                "pass",
                "oracle",
                "oracle.jdbc.OracleDriver",
                false
        );

        when(dialect.connectionTestQuery()).thenReturn("SELECT 1");
        doNothing().when(dialect).configureDataSource(any(HikariConfig.class), any(DatabaseProperties.class));

        HikariConfig config = factory.createHikariConfig(connection, dialect);

        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:oracle:thin:@//localhost:1521/test");
        assertThat(config.getUsername()).isEqualTo("user");
        assertThat(config.getPassword()).isEqualTo("pass");
        assertThat(config.getDriverClassName()).isEqualTo("oracle.jdbc.OracleDriver");
        assertThat(config.getMaximumPoolSize()).isEqualTo(10);
        assertThat(config.getMinimumIdle()).isEqualTo(2);
        assertThat(config.getConnectionTestQuery()).isEqualTo("SELECT 1");
    }

    @Test
    void createHikariConfigAppliesDialectSpecificSettings() {
        ByokProperties byokProperties = new ByokProperties(
                Duration.ofMinutes(30),
                Duration.ofHours(2),
                Duration.ofMinutes(5),
                100,
                10,
                2
        );
        DatabaseProperties databaseProperties = new DatabaseProperties(
                true,
                "oracle",
                new DatabaseProperties.QueryProperties(100, 30, true, 10000, 500, 100),
                new DatabaseProperties.AuditProperties(true, 7),
                new DatabaseProperties.DdlProperties(false),
                new DatabaseProperties.SecurityProperties(10, 5),
                new DatabaseProperties.EtlProperties(4),
                new DatabaseProperties.CacheProperties(1000, 30, 5, 10),
                new DatabaseProperties.ConnectionPoolProperties(30000, 600000, 1800000),
                new DatabaseProperties.PreparedStatementProperties(250, 2048),
                new DatabaseProperties.MetricsProperties(5000)
        );
        ConnectionPoolFactory factory = new ConnectionPoolFactory(byokProperties, databaseProperties);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ConnectionProperties connection = new ConnectionProperties(
                "jdbc:oracle:thin:@//localhost:1521/test",
                "user",
                "pass",
                "oracle",
                "oracle.jdbc.OracleDriver",
                false
        );

        when(dialect.connectionTestQuery()).thenReturn("SELECT 1 FROM DUAL");
        doNothing().when(dialect).configureDataSource(any(HikariConfig.class), any(DatabaseProperties.class));

        HikariConfig config = factory.createHikariConfig(connection, dialect);

        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:oracle:thin:@//localhost:1521/test");
        assertThat(config.getDriverClassName()).isEqualTo("oracle.jdbc.OracleDriver");
        assertThat(config.getConnectionTestQuery()).isEqualTo("SELECT 1 FROM DUAL");
        verify(dialect).configureDataSource(eq(config), eq(databaseProperties));
    }
}
