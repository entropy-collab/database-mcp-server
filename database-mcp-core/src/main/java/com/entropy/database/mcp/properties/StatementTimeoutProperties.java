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

/**
 * Statement timeouts for the write, DDL and ETL categories.
 *
 * <p>The read timeout is deliberately absent: {@code entropy.mcp.database.query.timeout-seconds}
 * already is the read ceiling, and a second key for the same thing would let the two disagree.
 * {@link #resolve(int)} joins them into the single value the BYOK layer consumes.
 */
@ConfigurationProperties(prefix = "entropy.mcp.database.statement-timeout")
public record StatementTimeoutProperties(
        int writeSeconds,
        int ddlSeconds,
        int etlSeconds
) {
    public StatementTimeoutProperties {
        writeSeconds = writeSeconds > 0 ? writeSeconds : StatementTimeouts.DEFAULT_WRITE_SECONDS;
        ddlSeconds = ddlSeconds > 0 ? ddlSeconds : StatementTimeouts.DEFAULT_DDL_SECONDS;
        etlSeconds = etlSeconds > 0 ? etlSeconds : StatementTimeouts.DEFAULT_ETL_SECONDS;
    }

    public StatementTimeouts resolve(int readSeconds) {
        return new StatementTimeouts(readSeconds, writeSeconds, ddlSeconds, etlSeconds);
    }
}
