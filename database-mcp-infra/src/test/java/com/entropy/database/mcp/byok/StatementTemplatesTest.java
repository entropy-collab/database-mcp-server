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

import com.entropy.database.mcp.properties.StatementTimeouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The timeout ceiling has to reach the statements, not just the config record.
 */
class StatementTemplatesTest {

    private final DataSource dataSource = mock(DataSource.class);

    @Test
    @DisplayName("every category template carries its own ceiling")
    void eachCategoryGetsItsOwnTimeout() {
        StatementTemplates templates = StatementTemplates.over(dataSource, new JdbcTemplate(dataSource),
                new StatementTimeouts(10, 20, 30, 40));

        assertThat(templates.read().getQueryTimeout()).isEqualTo(10);
        assertThat(templates.write().getQueryTimeout()).isEqualTo(20);
        assertThat(templates.ddl().getQueryTimeout()).isEqualTo(30);
        assertThat(templates.etl().getQueryTimeout()).isEqualTo(40);
    }

    @Test
    @DisplayName("the read template is configured in place so existing holders see the ceiling")
    void readTemplateIsConfiguredInPlace() {
        JdbcTemplate read = new JdbcTemplate(dataSource);

        StatementTemplates templates = StatementTemplates.over(dataSource, read, StatementTimeouts.defaults());

        assertThat(templates.read()).isSameAs(read);
        assertThat(read.getQueryTimeout()).isEqualTo(StatementTimeouts.DEFAULT_READ_SECONDS);
    }

    @Test
    @DisplayName("no configured timeouts still yields bounded statements")
    void nullTimeoutsFallBackToDefaults() {
        StatementTemplates templates = StatementTemplates.over(dataSource, new JdbcTemplate(dataSource), null);

        assertThat(templates.read().getQueryTimeout()).isEqualTo(StatementTimeouts.DEFAULT_READ_SECONDS);
        assertThat(templates.etl().getQueryTimeout()).isEqualTo(StatementTimeouts.DEFAULT_ETL_SECONDS);
    }

    @Test
    @DisplayName("leak detection sits above the longest ceiling, in milliseconds")
    void leakDetectionIsDerivedFromTheLongestCeiling() {
        StatementTimeouts timeouts = new StatementTimeouts(30, 120, 300, 600);

        // Milliseconds, not seconds: the previous code passed 60 to a millisecond setter, which is
        // under HikariCP's 2s floor, so leak detection was silently disabled.
        assertThat(timeouts.leakDetectionThresholdMs()).isEqualTo(660_000L);
        assertThat(timeouts.leakDetectionThresholdMs())
                .isGreaterThan(timeouts.etlSeconds() * 1000L);
    }
}
