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
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.properties.ByokProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Factory for creating configured HikariCP DataSource instances.
 * Centralizes connection pool configuration and dialect-specific settings.
 */
public class ConnectionPoolFactory {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolFactory.class);

    private final ByokProperties byokProperties;
    private final DatabaseProperties databaseProperties;

    public ConnectionPoolFactory(ByokProperties byokProperties, DatabaseProperties databaseProperties) {
        this.byokProperties = byokProperties;
        this.databaseProperties = databaseProperties;
    }

    /**
     * Create a HikariCP DataSource with standardized configuration.
     */
    public DataSource createDataSource(ConnectionProperties connection, DatabaseDialect dialect) {
        HikariConfig config = createHikariConfig(connection, dialect);
        return new HikariDataSource(config);
    }

    /**
     * Create HikariCP configuration with common and dialect-specific settings.
     */
    public HikariConfig createHikariConfig(ConnectionProperties connection, DatabaseDialect dialect) {
        HikariConfig config = new HikariConfig();

        // Basic connection settings
        config.setJdbcUrl(connection.jdbcUrl());
        config.setUsername(connection.username());
        config.setPassword(connection.password());
        config.setDriverClassName(connection.driverClassName());

        // Pool settings from centralized configuration
        config.setMaximumPoolSize(byokProperties.poolSize());
        config.setMinimumIdle(byokProperties.minIdle());
        config.setConnectionTimeout(databaseProperties.connectionPool().connectionTimeoutMs());
        config.setIdleTimeout(databaseProperties.connectionPool().idleTimeoutMs());
        config.setMaxLifetime(byokProperties.maxLifetime().toMillis());

        // Connection test query based on dialect
        config.setConnectionTestQuery(dialect.connectionTestQuery());

        // Apply dialect-specific configuration
        dialect.configureDataSource(config, databaseProperties);

        log.info("Configured HikariCP pool for dialect={}: poolSize={}, minIdle={}",
                dialect.getClass().getSimpleName(),
                byokProperties.poolSize(),
                byokProperties.minIdle());

        return config;
    }
}
