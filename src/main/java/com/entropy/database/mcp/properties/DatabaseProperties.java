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
package com.entropy.database.mcp.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entropy.mcp.database")
public record DatabaseProperties(
    boolean enabled,
    String dialect,

    QueryProperties query,

    AuditProperties audit,

    DdlProperties ddl,

    SecurityProperties security,

    EtlProperties etl,

    CacheProperties cache,

    ConnectionPoolProperties connectionPool,

    PreparedStatementProperties preparedStatement,

    MetricsProperties metrics
) {
    public DatabaseProperties {
        if (dialect == null || dialect.isBlank()) {
            dialect = "oracle";
        }
        if (query == null) {
            query = new QueryProperties(100, 30, true, 10000, 500, 100);
        }
        if (audit == null) {
            audit = new AuditProperties(true, 7);
        }
        if (ddl == null) {
            ddl = new DdlProperties(false);
        }
        if (security == null) {
            security = new SecurityProperties(10, 5);
        }
        if (etl == null) {
            etl = new EtlProperties(4);
        }
        if (cache == null) {
            cache = new CacheProperties(1000, 30, 5, 10);
        }
        if (connectionPool == null) {
            connectionPool = new ConnectionPoolProperties(30000, 600000, 1800000);
        }
        if (preparedStatement == null) {
            preparedStatement = new PreparedStatementProperties(250, 2048);
        }
        if (metrics == null) {
            metrics = new MetricsProperties(5000);
        }
    }

    public record CacheProperties(
        int maxSize,
        int queryCacheTtlSeconds,
        int metadataCacheTtlMinutes,
        int warmCacheTtlMinutes
    ) {
        public CacheProperties {
            maxSize = maxSize > 0 ? maxSize : 1000;
            queryCacheTtlSeconds = queryCacheTtlSeconds > 0 ? queryCacheTtlSeconds : 30;
            metadataCacheTtlMinutes = metadataCacheTtlMinutes > 0 ? metadataCacheTtlMinutes : 5;
            warmCacheTtlMinutes = warmCacheTtlMinutes > 0 ? warmCacheTtlMinutes : 10;
        }
    }

    public record ConnectionPoolProperties(
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs
    ) {
        public ConnectionPoolProperties {
            connectionTimeoutMs = connectionTimeoutMs > 0 ? connectionTimeoutMs : 30000;
            idleTimeoutMs = idleTimeoutMs > 0 ? idleTimeoutMs : 600000;
            maxLifetimeMs = maxLifetimeMs > 0 ? maxLifetimeMs : 1800000;
        }
    }

    public record PreparedStatementProperties(
        int cacheSize,
        int sqlLimit
    ) {
        public PreparedStatementProperties {
            cacheSize = cacheSize > 0 ? cacheSize : 250;
            sqlLimit = sqlLimit > 0 ? sqlLimit : 2048;
        }
    }

    public record MetricsProperties(
        long slowQueryThresholdMs
    ) {
        public MetricsProperties {
            slowQueryThresholdMs = slowQueryThresholdMs > 0 ? slowQueryThresholdMs : 5000;
        }
    }

    public record AuditProperties(
        boolean enabled,
        int retentionDays,
        int maxBufferSize,
        int sqlTruncateLength,
        int entrySqlTruncateLength
    ) {
        public AuditProperties {
            enabled = Boolean.TRUE.equals(enabled);
            retentionDays = retentionDays > 0 ? retentionDays : 7;
            maxBufferSize = maxBufferSize > 0 ? maxBufferSize : 100;
            sqlTruncateLength = sqlTruncateLength > 0 ? sqlTruncateLength : 200;
            entrySqlTruncateLength = entrySqlTruncateLength > 0 ? entrySqlTruncateLength : 500;
        }

        public AuditProperties(boolean enabled, int retentionDays) {
            this(enabled, retentionDays, 100, 200, 500);
        }
    }

    public record QueryProperties(
        int maxRows,
        int timeoutSeconds,
        boolean cacheEnabled,
        int maxResultRows,
        int maxExportRows,
        int fetchSize
    ) {
        public QueryProperties {
            maxRows = maxRows > 0 ? maxRows : 100;
            timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
            cacheEnabled = cacheEnabled;
            maxResultRows = maxResultRows > 0 ? maxResultRows : 10000;
            maxExportRows = maxExportRows > 0 ? maxExportRows : 500;
            fetchSize = fetchSize > 0 ? fetchSize : 100;
        }
    }

    public record DdlProperties(boolean allowed) {
        public DdlProperties {
            allowed = Boolean.TRUE.equals(allowed);
        }
    }

    public record SecurityProperties(
        int maxJoins,
        int maxSubqueryDepth
    ) {
        public SecurityProperties {
            maxJoins = maxJoins > 0 ? maxJoins : 10;
            maxSubqueryDepth = maxSubqueryDepth > 0 ? maxSubqueryDepth : 5;
        }
    }

    public record EtlProperties(
        int threadPoolSize,
        int batchSize
    ) {
        public EtlProperties {
            threadPoolSize = threadPoolSize > 0 ? threadPoolSize : 4;
            batchSize = batchSize > 0 ? batchSize : 1000;
        }

        public EtlProperties(int threadPoolSize) {
            this(threadPoolSize, 1000);
        }
    }
}
