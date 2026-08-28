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
import com.entropy.database.mcp.cache.ConnectionScopedCache;
import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.cache.DatabaseCacheImpl;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitorImpl;
import com.entropy.database.mcp.properties.ByokProperties;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.properties.StatementTimeoutProperties;
import com.entropy.database.mcp.properties.StatementTimeouts;
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

    private final Supplier<SqlValidator> sqlValidator;
    private final Supplier<DataMaskingService> maskingService;
    private final Supplier<AuditLogRepository> auditLogRepository;
    private final Supplier<DatabaseProperties> databaseProperties;
    private final Supplier<ByokProperties> byokProperties;
    private final Supplier<SqlAuditService> sqlAuditService;
    private final Supplier<StatementTimeoutProperties> statementTimeoutProperties;
    private final Supplier<DatabaseCacheImpl> sharedCache;
    private final int defaultFetchSize;

    public ByokDataSourceFactory(Supplier<SqlValidator> sqlValidator,
                                  Supplier<DataMaskingService> maskingService,
                                  Supplier<AuditLogRepository> auditLogRepository,
                                  Supplier<DatabaseProperties> databaseProperties,
                                  Supplier<ByokProperties> byokProperties,
                                  Supplier<SqlAuditService> sqlAuditService,
                                  Supplier<StatementTimeoutProperties> statementTimeoutProperties,
                                  Supplier<DatabaseCacheImpl> sharedCache,
                                  int defaultFetchSize) {
        this.sqlValidator = sqlValidator;
        this.maskingService = maskingService;
        this.auditLogRepository = auditLogRepository;
        this.databaseProperties = databaseProperties;
        this.byokProperties = byokProperties;
        this.sqlAuditService = sqlAuditService;
        this.statementTimeoutProperties = statementTimeoutProperties;
        this.sharedCache = sharedCache;
        this.defaultFetchSize = defaultFetchSize;
    }

    // ─── Public factory method ──────────────────────────────────────────────

    /**
     * Create a fully-wired {@link ByokDataSourceContext} for the given connection.
     * Lifecycle (close / lease expiry) is managed by the caller ({@link LeasedDataSource}).
     */
    public ByokDataSourceContext create(String key, ConnectionProperties connection, DatabaseDialect dialect) {
        StatementTimeouts timeouts = resolveTimeouts();
        HikariDataSource dataSource = createDataSource(connection, dialect, timeouts);
        StatementTemplates templates = StatementTemplates.over(
                dataSource, new JdbcTemplate(dataSource), timeouts);
        ByokInfrastructure infrastructure = createInfrastructure(key, templates, dialect);
        return new ByokDataSourceContext(key, dataSource, dialect, templates, infrastructure);
    }

    /**
     * Register an externally-managed DataSource as a BYOK connection.
     * The returned context has a non-closeable leased wrapper.
     */
    public ByokDataSourceContext createExisting(String key, DataSource externalDataSource, DatabaseDialect dialect) {
        StatementTemplates templates = StatementTemplates.over(
                externalDataSource, new JdbcTemplate(externalDataSource), resolveTimeouts());
        ByokInfrastructure infrastructure = createInfrastructure(key, templates, dialect);
        return new ByokDataSourceContext(key, externalDataSource, dialect, templates, infrastructure);
    }

    // ─── Internal factories ─────────────────────────────────────────────────

    /**
     * Join the read ceiling — which lives in {@code query.timeout-seconds} — with the write, DDL and
     * ETL ceilings so there is exactly one source of truth per category.
     */
    private StatementTimeouts resolveTimeouts() {
        int readSeconds = databaseProperties.get().query().timeoutSeconds();
        StatementTimeoutProperties props = statementTimeoutProperties.get();
        return props != null ? props.resolve(readSeconds)
                : new StatementTimeouts(readSeconds, 0, 0, 0);
    }

    private HikariDataSource createDataSource(ConnectionProperties connection, DatabaseDialect dialect,
                                              StatementTimeouts timeouts) {
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
        // Milliseconds, and derived from the longest statement ceiling. The previous value was the
        // literal 60 against this millisecond setter, which is below HikariCP's 2s floor — so leak
        // detection was silently off, not the 60s the comment claimed. Deriving it also keeps a
        // legitimate long ETL or DDL statement from being reported as a leak.
        config.setLeakDetectionThreshold(timeouts.leakDetectionThresholdMs());

        log.info("Configured HikariCP pool for dialect={}: poolSize={}, minIdle={}, "
                        + "statementTimeoutsSec(read/write/ddl/etl)={}/{}/{}/{}, leakDetectionMs={}",
                dialect.getClass().getSimpleName(),
                byokProps.poolSize(), byokProps.minIdle(),
                timeouts.readSeconds(), timeouts.writeSeconds(), timeouts.ddlSeconds(),
                timeouts.etlSeconds(), timeouts.leakDetectionThresholdMs());
        return new HikariDataSource(config);
    }

    private ByokInfrastructure createInfrastructure(String key, StatementTemplates templates, DatabaseDialect dialect) {
        SqlValidator validator = sqlValidator.get();
        DataMaskingService masking = maskingService.get();
        AuditLogRepository auditRepo = auditLogRepository.get();
        DatabaseProperties dbProps = databaseProperties.get();
        int fetchSize = defaultFetchSize;
        int queryTimeoutSeconds = dbProps.query().timeoutSeconds();
        JdbcTemplate readTemplate = templates.read();

        // One shared Caffeine instance for the whole process, viewed through a per-connection
        // key prefix. Building a cache per connection made total cache memory scale with
        // max-cached-connections while the entry budget was written as if only one existed.
        DatabaseCache cache = new ConnectionScopedCache(sharedCache.get(), key);
        DatabaseHealthMonitor healthMonitor = new DatabaseHealthMonitorImpl(readTemplate, cache);
        QueryAuditLogger auditLogger = new QueryAuditLoggerImpl(auditRepo, dbProps, sqlAuditService.get());
        DatabaseReadRepository readRepo = new DatabaseReadRepository(
                readTemplate, dialect, validator, cache, masking,
                dbProps.query().maxRows(),
                dbProps.query().maxResultRows(),
                fetchSize, queryTimeoutSeconds);
        // DDL rather than the read template: executeDdl issues ALTER/CREATE, which can wait on a
        // metadata lock for longer than any interactive query is allowed to.
        ByokWriteRepository writeRepo = new ByokWriteRepository(templates.ddl(), validator);
        ExecutionPlanRepository executionPlanRepo = new ExecutionPlanRepositoryImpl(
                readTemplate, dialect, validator);

        return new ByokInfrastructure(cache, healthMonitor, auditLogger,
                readRepo, writeRepo, executionPlanRepo);
    }
}
