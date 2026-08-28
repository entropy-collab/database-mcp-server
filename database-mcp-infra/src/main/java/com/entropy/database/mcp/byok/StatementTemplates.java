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
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * One {@link JdbcTemplate} per statement category over a single {@link DataSource}.
 *
 * <p>This is where the timeout ceiling is enforced for template-based access. {@code JdbcTemplate}
 * applies its {@code queryTimeout} to every statement it creates, including the ones handed to it
 * by a {@code PreparedStatementCreator}, so a caller that picks the right template cannot forget to
 * bound its statement. Callers that build statements from a raw {@link java.sql.Connection} — the
 * transaction path — are outside this and must set the timeout themselves.
 *
 * <p>Four templates rather than one mutable template because {@code queryTimeout} is instance
 * state: mutating it per call would leak across concurrent callers sharing the pool. The templates
 * are stateless otherwise and hold no connections, so the extra instances cost nothing measurable
 * next to the pool they front.
 */
public record StatementTemplates(JdbcTemplate read, JdbcTemplate write, JdbcTemplate ddl, JdbcTemplate etl,
                                 StatementTimeouts timeouts) {

    /**
     * Bind {@code read} to the read ceiling and derive one template per remaining category over the
     * same datasource.
     *
     * <p>{@code read} is configured in place rather than replaced so that collaborators already
     * holding it — the read repository, the health monitor — observe the same ceiling.
     */
    public static StatementTemplates over(DataSource dataSource, JdbcTemplate read, StatementTimeouts timeouts) {
        StatementTimeouts effective = timeouts != null ? timeouts : StatementTimeouts.defaults();
        read.setQueryTimeout(effective.readSeconds());
        return new StatementTemplates(
                read,
                derive(dataSource, effective.writeSeconds()),
                derive(dataSource, effective.ddlSeconds()),
                derive(dataSource, effective.etlSeconds()),
                effective);
    }

    private static JdbcTemplate derive(DataSource dataSource, int timeoutSeconds) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(timeoutSeconds);
        return template;
    }
}
