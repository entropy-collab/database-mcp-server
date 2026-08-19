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
package com.entropy.database.mcp.config;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.security.SqlValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * Database configuration for MCP server.
 */
@Configuration
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfig {

    @Bean
    @ConditionalOnMissingBean
    public DialectResolver dialectResolver() {
        return new DialectResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseDialect databaseDialect(DialectResolver dialectResolver, DatabaseProperties properties, DataSource dataSource) {
        return dialectResolver.resolve(properties.dialect(), dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(name = "primaryJdbcTemplate")
    public JdbcTemplate primaryJdbcTemplate(DataSource primaryDataSource) {
        return new JdbcTemplate(primaryDataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlValidator sqlValidator(DatabaseProperties properties) {
        var validator = new SqlValidator();
        validator.setMaxRows(properties.query().maxRows());
        return validator;
    }

    @Bean
    @ConditionalOnMissingBean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }
}
