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

import com.entropy.database.mcp.audit.AuditLogRepository;
import com.entropy.database.mcp.audit.SqlAuditService;
import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.cache.DatabaseCacheImpl;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitorImpl;
import com.entropy.database.mcp.properties.ByokProperties;
import com.entropy.database.mcp.config.CacheConfig;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.byok.ByokWriteRepository;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepositoryImpl;
import com.entropy.database.mcp.security.DataMaskingService;
import com.entropy.database.mcp.security.QueryAuditLogger;
import com.entropy.database.mcp.security.QueryAuditLoggerImpl;
import com.entropy.database.mcp.security.SqlValidator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.function.Supplier;

/**
 * Unified factory for creating per-datasource BYOK infrastructure.
 *
 * <p>Merges the responsibilities of the former {@link ConnectionPoolFactory} and
 * {@link ByokInfrastructureFactory} into a single entry point, following Spring's
 * own factory pattern (e.g., {@code DataSourceBuilder}).
 *
 * <p>Shared infrastructure beans (SqlValidator, Cache, etc.) are injected via
 * {@link Supplier} to avoid cyclic coupling and to support lazy resolution
 * during runtime connection creation.
 *
 * <p>Factory method: {@link #create(String, ConnectionProperties, DatabaseDialect)}
 */
public class ByokDataSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(ByokDataSourceFactory.class);
    private static final int DEFAULT_LEAK_DETECTION_THRESHOLD_SECONDS = 60;

    private final Supplier<SqlValidator> sqlValidator;
    private final Supplier<DataMaskingService> maskingService;
    private final Supplier<AuditLogRepository> auditLogRepository;
    private final Supplier<DatabaseProperties> databaseProperties;
    private final Supplier<ByokProperties> byokProperties;
    private final Supplier<CacheConfig> cacheConfig;
    private final Supplier<SqlAuditService> sqlAuditService;
    private final int defaultFetchSize;

    public ByokDataSourceFactory(Supplier<SqlValidator> sqlValidator,
                                  Supplier<DataMaskingService> maskingService,
                                  Supplier<AuditLogRepository> auditLogRepository,
                                  Supplier<DatabaseProperties> databaseProperties,
                                  Supplier<ByokProperties> byokProperties,
                                  Supplier<CacheConfig> cacheConfig,
                                  Supplier<SqlAuditService> sqlAuditService,
                                  int defaultFetchSize) {
        this.sqlValidator = sqlValidator;
        this.maskingService = maskingService;
        this.auditLogRepository = auditLogRepository;
        this.databaseProperties = databaseProperties;
        this.byokProperties = byokProperties;
        this.cacheConfig = cacheConfig;
        this.sqlAuditService = sqlAuditService;
        this.defaultFetchSize = defaultFetchSize;
    }

    // ─── Public factory method ──────────────────────────────────────────────

    /**
     * Create a fully-wired {@link ByokDataSourceContext} for the given connection.
     * Lifecycle (close / lease expiry) is managed by the caller ({@link LeasedDataSource}).
     */
    public ByokDataSourceContext create(String key, ConnectionProperties connection, DatabaseDialect dialect) {
        HikariDataSource dataSource = createDataSource(connection, dialect);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        ByokInfrastructure infrastructure = createInfrastructure(key, jdbcTemplate, dialect);
        return new ByokDataSourceContext(key, dataSource, dialect, jdbcTemplate, infrastructure);
    }

    /**
     * Register an externally-managed DataSource as a BYOK connection.
     * The returned context has a non-closeable leased wrapper.
     */
    public ByokDataSourceContext createExisting(String key, DataSource externalDataSource, DatabaseDialect dialect) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(externalDataSource);
        ByokInfrastructure infrastructure = createInfrastructure(key, jdbcTemplate, dialect);
        return new ByokDataSourceContext(key, externalDataSource, dialect, jdbcTemplate, infrastructure);
    }

    // ─── Internal factories ─────────────────────────────────────────────────

    private HikariDataSource createDataSource(ConnectionProperties connection, DatabaseDialect dialect) {
        DatabaseProperties dbProps = databaseProperties.get();
        ByokProperties byokProps = byokProperties.get();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(connection.jdbcUrl());
        config.setUsername(connection.username());
        config.setPassword(connection.password());
        config.setDriverClassName(connection.driverClassName());
        config.setPoolName("HikariPool-" + System.nanoTime());
        config.setMaximumPoolSize(byokProps.poolSize());
        config.setMinimumIdle(byokProps.minIdle());
        config.setConnectionTimeout(dbProps.connectionPool().connectionTimeoutMs());
        config.setIdleTimeout(dbProps.connectionPool().idleTimeoutMs());
        config.setMaxLifetime(byokProps.maxLifetime().toMillis());
        config.setConnectionTestQuery(dialect.connectionTestQuery());
        dialect.configureDataSource(config, dbProps);
        // Enable connection leak detection to catch unreturned connections early.
        // Set to 60s — slightly less than default leaseDuration (30min) but catches bugs during dev.
        config.setLeakDetectionThreshold(DEFAULT_LEAK_DETECTION_THRESHOLD_SECONDS);

        log.info("Configured HikariCP pool for dialect={}: poolSize={}, minIdle={}",
                dialect.getClass().getSimpleName(),
                byokProps.poolSize(), byokProps.minIdle());
        return new HikariDataSource(config);
    }

    private ByokInfrastructure createInfrastructure(String key, JdbcTemplate jdbcTemplate, DatabaseDialect dialect) {
        SqlValidator validator = sqlValidator.get();
        DataMaskingService masking = maskingService.get();
        AuditLogRepository auditRepo = auditLogRepository.get();
        DatabaseProperties dbProps = databaseProperties.get();
        CacheConfig cc = cacheConfig.get();
        int fetchSize = defaultFetchSize;
        int queryTimeoutSeconds = dbProps.query().timeoutSeconds();

        DatabaseCache cache = new DatabaseCacheImpl(
                cc.maxSize(), cc.queryCacheTtl(), cc.metadataCacheTtl());
        DatabaseHealthMonitor healthMonitor = new DatabaseHealthMonitorImpl(jdbcTemplate, cache);
        QueryAuditLogger auditLogger = new QueryAuditLoggerImpl(auditRepo, dbProps, sqlAuditService.get());
        DatabaseReadRepository readRepo = new DatabaseReadRepository(
                jdbcTemplate, dialect, validator, cache, masking,
                DatabaseReadRepository.DEFAULT_MAX_ROWS,
                DatabaseReadRepository.DEFAULT_MAX_RESULT_ROWS,
                fetchSize, queryTimeoutSeconds, null);
        ByokWriteRepository writeRepo = new ByokWriteRepository(jdbcTemplate, validator);
        ExecutionPlanRepository executionPlanRepo = new ExecutionPlanRepositoryImpl(
                jdbcTemplate, dialect, validator);

        return new ByokInfrastructure(cache, healthMonitor, auditLogger,
                readRepo, writeRepo, executionPlanRepo);
    }
}
