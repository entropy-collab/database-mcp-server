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

import java.time.Duration;

/**
 * Centralized constants for database MCP server.
 * Eliminates magic numbers scattered across the codebase.
 */
public final class DatabaseConstants {

    private DatabaseConstants() {
    }

    // ─── Cache ────────────────────────────────────────────────────────────

    /**
     * Default maximum cache size (number of entries).
     */
    public static final int DEFAULT_CACHE_MAX_SIZE = 1000;

    /**
     * Default query cache TTL.
     */
    public static final Duration DEFAULT_QUERY_CACHE_TTL = Duration.ofSeconds(30);

    /**
     * Default metadata cache TTL.
     */
    public static final Duration DEFAULT_METADATA_CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Default warm cache TTL.
     */
    public static final Duration DEFAULT_WARM_CACHE_TTL = Duration.ofMinutes(10);

    // ─── Connection Pool ──────────────────────────────────────────────────

    /**
     * Default connection timeout in milliseconds.
     */
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = Duration.ofSeconds(30).toMillis();

    /**
     * Default idle timeout in milliseconds.
     */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();

    /**
     * Default connection max lifetime in milliseconds.
     */
    public static final Duration DEFAULT_CONNECTION_MAX_LIFETIME = Duration.ofMinutes(30);

    // ─── SQL Validation ───────────────────────────────────────────────────

    /**
     * Default maximum rows allowed per query.
     */
    public static final int DEFAULT_MAX_ROWS = 1000;

    /**
     * Default maximum number of joins allowed.
     */
    public static final int DEFAULT_MAX_JOINS = 10;

    /**
     * Default maximum subquery depth.
     */
    public static final int DEFAULT_MAX_SUBQUERY_DEPTH = 5;

    // ─── Prepared Statement Cache ─────────────────────────────────────────

    /**
     * Default prepared statement cache size.
     */
    public static final int DEFAULT_PREP_STMT_CACHE_SIZE = 250;

    /**
     * Default prepared statement cache SQL limit.
     */
    public static final int DEFAULT_PREP_STMT_CACHE_SQL_LIMIT = 2048;

    // ─── Metrics ──────────────────────────────────────────────────────────

    /**
     * Slow query threshold in milliseconds (5 seconds).
     */
    public static final long SLOW_QUERY_THRESHOLD_MS = 5000;

    // ─── ETL ──────────────────────────────────────────────────────────────

    /**
     * Default batch size for ETL operations.
     */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    /**
     * Default fetch size for query results (JDBC fetch size).
     */
    public static final int DEFAULT_FETCH_SIZE = 100;

    // ─── Audit ────────────────────────────────────────────────────────────

    /**
     * Maximum number of audit log entries to retain in memory.
     */
    public static final int MAX_AUDIT_BUFFER_SIZE = 100;

    /**
     * Default truncation length for SQL in audit logs.
     */
    public static final int DEFAULT_AUDIT_SQL_TRUNCATE_LENGTH = 200;

    /**
     * Default truncation length for SQL in audit entries.
     */
    public static final int DEFAULT_AUDIT_ENTRY_SQL_TRUNCATE_LENGTH = 500;
}
