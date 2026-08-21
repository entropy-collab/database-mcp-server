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
import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.cache.DatabaseCacheImpl;
import com.entropy.database.mcp.config.CacheConfig;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitorImpl;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepositoryImpl;
import com.entropy.database.mcp.security.DataMaskingService;
import com.entropy.database.mcp.security.QueryAuditLogger;
import com.entropy.database.mcp.security.QueryAuditLoggerImpl;
import com.entropy.database.mcp.security.SqlValidator;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * Factory for creating per-datasource infrastructure components.
 * Eliminates code duplication in DynamicDataSourceManager.createContext().
 */
public class ByokInfrastructureFactory {

    private final SqlValidator sqlValidator;
    private final DataMaskingService maskingService;
    private final AuditLogRepository auditLogRepository;
    private final DatabaseProperties properties;
    private final CacheConfig cacheConfig;

    public ByokInfrastructureFactory(SqlValidator sqlValidator,
                                     DataMaskingService maskingService,
                                     AuditLogRepository auditLogRepository,
                                     DatabaseProperties properties,
                                     CacheConfig cacheConfig) {
        this.sqlValidator = sqlValidator;
        this.maskingService = maskingService;
        this.auditLogRepository = auditLogRepository;
        this.properties = properties;
        this.cacheConfig = cacheConfig;
    }

    /**
     * Create all per-datasource infrastructure components.
     */
    public ByokInfrastructure create(String key, JdbcTemplate jdbcTemplate, DatabaseDialect dialect, int fetchSize) {
        // Create per-datasource infrastructure
        DatabaseCacheImpl cache = new DatabaseCacheImpl(
                cacheConfig.maxSize(),
                cacheConfig.queryCacheTtl(),
                cacheConfig.metadataCacheTtl()
        );
        DatabaseHealthMonitorImpl healthMonitor = new DatabaseHealthMonitorImpl(jdbcTemplate, cache);
        QueryAuditLoggerImpl auditLogger = new QueryAuditLoggerImpl(auditLogRepository, properties);

        // Create repositories
        DatabaseReadRepository readRepository = new DatabaseReadRepository(
                jdbcTemplate, dialect, sqlValidator, cache, maskingService,
                DatabaseReadRepository.DEFAULT_MAX_ROWS,
                DatabaseReadRepository.DEFAULT_MAX_RESULT_ROWS,
                fetchSize,
                null
        );

        ByokWriteRepository writeRepository = new ByokWriteRepository(
                jdbcTemplate
        );

        ExecutionPlanRepositoryImpl executionPlanRepository = new ExecutionPlanRepositoryImpl(
                jdbcTemplate, dialect, sqlValidator
        );

        return new ByokInfrastructure(
                cache, healthMonitor, auditLogger, readRepository, writeRepository, executionPlanRepository
        );
    }
}
