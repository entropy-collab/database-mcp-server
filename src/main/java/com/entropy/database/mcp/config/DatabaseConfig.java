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

import com.entropy.database.mcp.byok.ByokInfrastructureFactory;
import com.entropy.database.mcp.byok.ConnectionPoolFactory;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.byok.DynamicDataSourceManagerImpl;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.etl.JobExecutionEngine;
import com.entropy.database.mcp.facade.DatabaseFacade;
import com.entropy.database.mcp.facade.FacadeDependencies;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import com.entropy.database.mcp.properties.ByokProperties;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import com.entropy.database.mcp.repository.DatabaseWriteRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepository;
import com.entropy.database.mcp.security.DataMaskingService;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.security.SqlValidatorImpl;
import com.entropy.database.mcp.service.DatabaseBackupService;
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
@EnableConfigurationProperties({DatabaseProperties.class, ByokProperties.class})
public class DatabaseConfig {

    @Bean
    @ConditionalOnMissingBean
    public QueryConfig queryConfig(DatabaseProperties properties) {
        var q = properties.query();
        return new QueryConfig(q.maxRows(), q.maxResultRows(), q.fetchSize(), q.maxExportRows());
    }

    @Bean
    @ConditionalOnMissingBean
    public EtlConfig etlConfig(DatabaseProperties properties) {
        var e = properties.etl();
        return new EtlConfig(e.threadPoolSize(), e.batchSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheConfig cacheConfig(DatabaseProperties properties) {
        var c = properties.cache();
        return new CacheConfig(
                c.maxSize(),
                java.time.Duration.ofSeconds(c.queryCacheTtlSeconds()),
                java.time.Duration.ofMinutes(c.metadataCacheTtlMinutes()),
                java.time.Duration.ofMinutes(c.warmCacheTtlMinutes())
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public com.entropy.database.mcp.cache.DatabaseCache databaseCache(CacheConfig cacheConfig) {
        return new com.entropy.database.mcp.cache.DatabaseCacheImpl(
                cacheConfig.maxSize(),
                cacheConfig.queryCacheTtl(),
                cacheConfig.metadataCacheTtl()
        );
    }

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
        return new SqlValidatorImpl(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseWriteRepository databaseWriteRepository(JdbcTemplate primaryJdbcTemplate,
                                                            SqlValidator sqlValidator) {
        return new DatabaseWriteRepository(primaryJdbcTemplate, sqlValidator);
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseReadRepository databaseReadRepository(JdbcTemplate primaryJdbcTemplate,
                                                          DatabaseDialect dialect,
                                                          SqlValidator sqlValidator,
                                                          com.entropy.database.mcp.cache.DatabaseCache cache,
                                                          DataMaskingService maskingService,
                                                          QueryConfig queryConfig) {
        return new DatabaseReadRepository(
                primaryJdbcTemplate, dialect, sqlValidator, cache, maskingService,
                queryConfig.maxRows(), queryConfig.maxResultRows(), queryConfig.fetchSize(), null);
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseFacade databaseFacade(DatabaseReadRepository readRepo,
                                         DatabaseWriteRepository writeRepo,
                                         DatabaseBackupService backupService,
                                         ExecutionPlanRepository executionPlanRepo,
                                         com.entropy.database.mcp.cache.DatabaseCache cache,
                                         com.entropy.database.mcp.monitor.DatabaseHealthMonitor healthMonitor,
                                         com.entropy.database.mcp.security.QueryAuditLogger auditLogger,
                                         com.entropy.database.mcp.monitor.McpMetricsCollector metricsCollector) {
        var deps = new FacadeDependencies(readRepo, writeRepo, backupService, executionPlanRepo,
                cache, healthMonitor, auditLogger, metricsCollector);
        return new DatabaseFacade(deps);
    }

    @Bean
    @ConditionalOnMissingBean
    public RoutingDatabaseFacade routingDatabaseFacade(DatabaseFacade primaryFacade,
                                                       DynamicDataSourceManager dynamicDataSourceManager) {
        return new RoutingDatabaseFacade(primaryFacade, dynamicDataSourceManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public ByokInfrastructureFactory byokInfrastructureFactory(SqlValidator sqlValidator,
                                                                DataMaskingService maskingService,
                                                                com.entropy.database.mcp.audit.AuditLogRepository auditLogRepository,
                                                                DatabaseProperties properties,
                                                                CacheConfig cacheConfig) {
        return new ByokInfrastructureFactory(sqlValidator, maskingService, auditLogRepository, properties, cacheConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectionPoolFactory connectionPoolFactory(ByokProperties byokProperties,
                                                       DatabaseProperties databaseProperties) {
        return new ConnectionPoolFactory(byokProperties, databaseProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public DynamicDataSourceManager dynamicDataSourceManager(DialectResolver dialectResolver,
                                                              ByokInfrastructureFactory infrastructureFactory,
                                                              ConnectionPoolFactory connectionPoolFactory,
                                                              DataSource primaryDataSource,
                                                              QueryConfig queryConfig,
                                                              DatabaseDialect databaseDialect,
                                                              com.entropy.database.mcp.properties.ByokProperties byokProperties,
                                                              com.entropy.database.mcp.monitor.McpMetricsCollector metricsCollector) {
        int fetchSize = queryConfig.fetchSize();
        var deps = new DynamicDataSourceManagerImpl.Dependencies(
                dialectResolver,
                infrastructureFactory, connectionPoolFactory, byokProperties,
                fetchSize, metricsCollector
        );
        DynamicDataSourceManagerImpl manager = new DynamicDataSourceManagerImpl(deps);

        // Register primary datasource as a special BYOK connection (wrap existing Spring-managed datasource)
        manager.registerExisting("primary", primaryDataSource, databaseDialect);

        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public JobExecutionEngine jobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                                                  DatabaseProperties properties,
                                                  EtlConfig etlConfig,
                                                  org.springframework.core.task.TaskExecutor taskExecutor,
                                                  com.entropy.database.mcp.monitor.McpMetricsCollector metricsCollector) {
        return new JobExecutionEngine(dataSourceManager, metricsCollector, etlConfig, taskExecutor);
    }

}
