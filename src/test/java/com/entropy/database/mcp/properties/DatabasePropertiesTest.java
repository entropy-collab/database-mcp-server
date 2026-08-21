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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class DatabasePropertiesTest {

    @Test
    void defaultsDialectToOracleWhenBlank() {
        var properties = new DatabaseProperties(
            true,
            "   ",
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

        Assertions.assertThat(properties.dialect()).isEqualTo("oracle");
    }

    @Test
    void keepsExplicitDialect() {
        var properties = new DatabaseProperties(
            true,
            "mysql",
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

        Assertions.assertThat(properties.dialect()).isEqualTo("mysql");
    }

    @Test
    void queryPropertiesClampToDefaults() {
        var properties = new DatabaseProperties.QueryProperties(0, 0, true, 0, 0, 0);

        Assertions.assertThat(properties.maxRows()).isEqualTo(100);
        Assertions.assertThat(properties.timeoutSeconds()).isEqualTo(30);
        Assertions.assertThat(properties.cacheEnabled()).isTrue();
        Assertions.assertThat(properties.maxExportRows()).isEqualTo(500);
        Assertions.assertThat(properties.fetchSize()).isEqualTo(100);
    }

    @Test
    void ddlPropertiesNormalizesNull() {
        var properties = new DatabaseProperties.DdlProperties(false);

        Assertions.assertThat(properties.allowed()).isFalse();
    }

    @Test
    void etlPropertiesClampToDefaults() {
        var properties = new DatabaseProperties.EtlProperties(0);

        Assertions.assertThat(properties.threadPoolSize()).isEqualTo(4);
    }

    @Test
    void cachePropertiesClampToDefaults() {
        var properties = new DatabaseProperties.CacheProperties(0, 0, 0, 0);

        Assertions.assertThat(properties.maxSize()).isEqualTo(1000);
        Assertions.assertThat(properties.queryCacheTtlSeconds()).isEqualTo(30);
        Assertions.assertThat(properties.metadataCacheTtlMinutes()).isEqualTo(5);
        Assertions.assertThat(properties.warmCacheTtlMinutes()).isEqualTo(10);
    }

    @Test
    void connectionPoolPropertiesClampToDefaults() {
        var properties = new DatabaseProperties.ConnectionPoolProperties(0, 0, 0);

        Assertions.assertThat(properties.connectionTimeoutMs()).isEqualTo(30000);
        Assertions.assertThat(properties.idleTimeoutMs()).isEqualTo(600000);
        Assertions.assertThat(properties.maxLifetimeMs()).isEqualTo(1800000);
    }

    @Test
    void preparedStatementPropertiesClampToDefaults() {
        var properties = new DatabaseProperties.PreparedStatementProperties(0, 0);

        Assertions.assertThat(properties.cacheSize()).isEqualTo(250);
        Assertions.assertThat(properties.sqlLimit()).isEqualTo(2048);
    }

    @Test
    void metricsPropertiesClampToDefaults() {
        var properties = new DatabaseProperties.MetricsProperties(0);

        Assertions.assertThat(properties.slowQueryThresholdMs()).isEqualTo(5000);
    }

    @Test
    void auditPropertiesBackwardCompatible() {
        var properties = new DatabaseProperties.AuditProperties(true, 7);

        Assertions.assertThat(properties.enabled()).isTrue();
        Assertions.assertThat(properties.retentionDays()).isEqualTo(7);
        Assertions.assertThat(properties.maxBufferSize()).isEqualTo(100);
        Assertions.assertThat(properties.sqlTruncateLength()).isEqualTo(200);
        Assertions.assertThat(properties.entrySqlTruncateLength()).isEqualTo(500);
    }
}
