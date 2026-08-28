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

/**
 * Resolved JDBC statement timeout per work category, for one BYOK connection.
 *
 * <p>Every statement this server issues must be bounded. Without a timeout a single unresponsive
 * database holds its request thread until the driver's own socket timeout expires — which for most
 * drivers is "never" by default — so a handful of slow statements can consume the whole servlet
 * thread pool and take every unrelated tool down with them.
 *
 * <p>One global value cannot do that job: 30s is right for an interactive query and fatal for an
 * ETL step, while an ETL-sized ceiling on a read path defeats the purpose. Hence four categories,
 * ordered by how long the caller can legitimately be expected to wait.
 */
public record StatementTimeouts(int readSeconds, int writeSeconds, int ddlSeconds, int etlSeconds) {

    public static final int DEFAULT_READ_SECONDS = 30;
    public static final int DEFAULT_WRITE_SECONDS = 120;
    public static final int DEFAULT_DDL_SECONDS = 300;
    public static final int DEFAULT_ETL_SECONDS = 600;

    /**
     * How much longer than the longest statement a connection may be held before Hikari reports it
     * as leaked. A statement that is still inside its own timeout is not a leak, so the threshold
     * has to sit above the ceiling rather than at an unrelated fixed value.
     */
    private static final int LEAK_DETECTION_HEADROOM_SECONDS = 60;

    /** HikariCP silently disables leak detection below this value. */
    private static final long HIKARI_MIN_LEAK_DETECTION_MS = 2_000L;

    public StatementTimeouts {
        readSeconds = readSeconds > 0 ? readSeconds : DEFAULT_READ_SECONDS;
        writeSeconds = writeSeconds > 0 ? writeSeconds : DEFAULT_WRITE_SECONDS;
        ddlSeconds = ddlSeconds > 0 ? ddlSeconds : DEFAULT_DDL_SECONDS;
        etlSeconds = etlSeconds > 0 ? etlSeconds : DEFAULT_ETL_SECONDS;
    }

    public static StatementTimeouts defaults() {
        return new StatementTimeouts(0, 0, 0, 0);
    }

    /**
     * Leak detection threshold in <em>milliseconds</em>, derived from the longest category.
     *
     * <p>Derived rather than configured so it cannot drift below a legitimate long-running
     * statement and flood the log with false leak reports on every ETL or DDL call.
     */
    public long leakDetectionThresholdMs() {
        int longest = Math.max(Math.max(readSeconds, writeSeconds), Math.max(ddlSeconds, etlSeconds));
        return Math.max(HIKARI_MIN_LEAK_DETECTION_MS,
                (longest + LEAK_DETECTION_HEADROOM_SECONDS) * 1000L);
    }
}
