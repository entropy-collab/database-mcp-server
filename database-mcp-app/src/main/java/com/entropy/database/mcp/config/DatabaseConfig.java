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

import com.entropy.database.mcp.audit.SqlAuditService;
import com.entropy.database.mcp.backup.DatabaseBackupService;
import com.entropy.database.mcp.byok.ByokDataSourceFactory;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.byok.DynamicDataSourceManagerImpl;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.etl.JobExecutionEngine;
import com.entropy.database.mcp.routing.RoutingDatabaseFacade;
import com.entropy.database.mcp.properties.ByokProperties;
import com.entropy.database.mcp.properties.BackupProperties;
import com.entropy.database.mcp.properties.CacheConfig;
import com.entropy.database.mcp.properties.CatalogProperties;
import com.entropy.database.mcp.properties.CdcProperties;
import com.entropy.database.mcp.properties.EtlConfig;
import com.entropy.database.mcp.properties.LineageProperties;
import com.entropy.database.mcp.properties.OptimizerProperties;
import com.entropy.database.mcp.properties.QualityProperties;
import com.entropy.database.mcp.properties.QueryConfig;
import com.entropy.database.mcp.properties.StatementTimeoutProperties;
import com.entropy.database.mcp.properties.ToolExposureProperties;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.security.DataMaskingService;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.security.SqlValidatorImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Supplier;

/**
 * Database configuration for MCP server.
 * Uses Supplier-based dependency injection for shared components,
 * following Spring's DataSourceBuilder pattern.
 */
@Configuration
@EnableConfigurationProperties({DatabaseProperties.class, ByokProperties.class, BackupProperties.class, CatalogProperties.class, CdcProperties.class, LineageProperties.class, OptimizerProperties.class, QualityProperties.class, StatementTimeoutProperties.class, ToolExposureProperties.class})
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public QueryConfig queryConfig(DatabaseProperties properties) {
        var q = properties.query();
        return new QueryConfig(q.maxRows(), q.maxResultRows(), q.fetchSize(), q.maxExportRows(), q.timeoutSeconds());
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

    /**
     * One shared cache for the whole process. Per-connection isolation is layered on top by
     * {@code ConnectionScopedCache} in {@link ByokDataSourceFactory}, so
     * {@code entropy.mcp.database.cache.max-size} is a global entry budget.
     */
    @Bean
    @ConditionalOnMissingBean(com.entropy.database.mcp.cache.DatabaseCache.class)
    public com.entropy.database.mcp.cache.DatabaseCacheImpl databaseCache(CacheConfig cacheConfig) {
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
    public SqlValidator sqlValidator(DatabaseProperties properties) {
        return new SqlValidatorImpl(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RoutingDatabaseFacade routingDatabaseFacade(
            DynamicDataSourceManager dynamicDataSourceManager,
            @org.springframework.context.annotation.Lazy DatabaseBackupService backupService) {
        return new RoutingDatabaseFacade(dynamicDataSourceManager, backupService);
    }

    // ─── Unified BYOK Factory ──────────────────────────────────────────────
    //
    // Replaces: ConnectionPoolFactory + ByokInfrastructureFactory
    // Uses Supplier<T> for shared dependencies to follow Spring DataSourceBuilder pattern.
    // Suppliers defer resolution until runtime connection creation, avoiding circular init.

    @Bean
    @ConditionalOnMissingBean
    public ByokDataSourceFactory byokDataSourceFactory(
            SqlValidator sqlValidator,
            DataMaskingService maskingService,
            @Nullable com.entropy.database.mcp.audit.AuditLogRepository auditLogRepository,
            DatabaseProperties properties,
            ByokProperties byokProperties,
            SqlAuditService sqlAuditService,
            StatementTimeoutProperties statementTimeoutProperties,
            com.entropy.database.mcp.cache.DatabaseCacheImpl sharedCache,
            QueryConfig queryConfig) {
        // Use Suppliers to defer shared-component resolution until factory.create() is called.
        // This avoids circular dependency during bean initialization.
        Supplier<SqlValidator> sv = () -> sqlValidator;
        Supplier<DataMaskingService> ms = () -> maskingService;
        Supplier<com.entropy.database.mcp.audit.AuditLogRepository> ar = () -> auditLogRepository;
        Supplier<DatabaseProperties> dp = () -> properties;
        Supplier<ByokProperties> bp = () -> byokProperties;
        Supplier<SqlAuditService> sas = () -> sqlAuditService;
        Supplier<StatementTimeoutProperties> stp = () -> statementTimeoutProperties;
        Supplier<com.entropy.database.mcp.cache.DatabaseCacheImpl> sc = () -> sharedCache;
        int fetchSize = queryConfig != null ? queryConfig.fetchSize() : 100;

        return new ByokDataSourceFactory(sv, ms, ar, dp, bp, sas, stp, sc, fetchSize);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "entropy.mcp.database.enabled", matchIfMissing = true)
    public DynamicDataSourceManager dynamicDataSourceManager(
            DialectResolver dialectResolver,
            ByokDataSourceFactory dataSourceFactory,
            ByokProperties byokProperties,
            com.entropy.database.mcp.monitor.McpMetricsCollector metricsCollector) {
        var deps = new DynamicDataSourceManagerImpl.Dependencies(
                dialectResolver, dataSourceFactory, byokProperties, metricsCollector);
        var manager = new DynamicDataSourceManagerImpl(deps);
        log.info("DynamicDataSourceManager initialized without default datasource. "
                + "Use createNamedConnection to register BYOK connections.");
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "entropy.mcp.database.enabled", matchIfMissing = true)
    public JobExecutionEngine jobExecutionEngine(DynamicDataSourceManager dataSourceManager,
                                                  DatabaseProperties properties,
                                                  EtlConfig etlConfig,
                                                  @org.springframework.beans.factory.annotation.Qualifier("etlTaskExecutor")
                                                  org.springframework.core.task.TaskExecutor etlTaskExecutor,
                                                  com.entropy.database.mcp.monitor.McpMetricsCollector metricsCollector,
                                                  SqlValidator sqlValidator) {
        // The validator was omitted here, which left JobExecutionEngine.validateSourceSql a no-op:
        // ETL source SQL reached the database without the table allow-list, join or
        // subquery-depth checks that every other read path goes through. The bean existed all
        // along; only this call site failed to pass it.
        return new JobExecutionEngine(dataSourceManager, metricsCollector, etlConfig, etlTaskExecutor,
                sqlValidator);
    }

}
