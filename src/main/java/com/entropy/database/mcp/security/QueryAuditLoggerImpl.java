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
package com.entropy.database.mcp.security;

import com.entropy.database.mcp.audit.AuditLogEntity;
import com.entropy.database.mcp.audit.AuditLogRepository;
import com.entropy.database.mcp.audit.SqlAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Query audit logger for tracking database operations.
 * Buffers entries in memory for SSE/polling consumers and optionally persists to database.
 * If the default database is not configured (no audit_log table), falls back to file logging.
 */
@Component
public class QueryAuditLoggerImpl implements QueryAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(QueryAuditLoggerImpl.class);
    private static final Logger auditLog = LoggerFactory.getLogger("auditLogger");

    // Bounded buffer: keeps last 100 audit entries for SSE/polling consumers
    private static final int MAX_BUFFER_SIZE = 100;

    /** Placeholder that replaces a secret while keeping the statement's shape readable. */
    private static final String MASK = "'***'";

    /** Field names whose value is a secret wherever they appear as {@code name = value}. */
    private static final String SECRET_FIELDS =
            "password|passwd|pwd|secret|token|api_key|apikey|credential|credentials";

    /**
     * Secret-bearing SQL and URL shapes, applied in order. A single "field = value" regex is not
     * enough: Oracle's {@code IDENTIFIED BY} carries no {@code =}, JDBC URLs carry the password in
     * the authority component, and {@code SET PASSWORD} puts a user reference between the keyword
     * and the value.
     */
    private static final List<Masker> MASKERS = List.of(
            // SET PASSWORD [FOR 'u'@'h'] = 'x'  →  the user reference is kept, the value is not
            new Masker("(?i)(\\bSET\\s+PASSWORD\\b[^=;]{0,200}?=\\s*)(?:'[^']*'|\"[^\"]*\"|`[^`]*`|[^\\s;,)]+)", "$1" + MASK),
            // IDENTIFIED BY "x" / IDENTIFIED BY x / IDENTIFIED BY VALUES 'hash'
            new Masker("(?i)(\\bIDENTIFIED\\s+BY\\s+(?:VALUES\\s+)?)(?:'[^']*'|\"[^\"]*\"|`[^`]*`|[^\\s;,)]+)", "$1" + MASK),
            // password = 'x' / "pwd"='x' / token: x  (optionally quoted field name, = or :=)
            new Masker("(?i)(['\"`]?\\b(?:" + SECRET_FIELDS + ")\\b['\"`]?\\s*(?::=|=|:)\\s*)"
                    + "(?:'[^']*'|\"[^\"]*\"|`[^`]*`|[^\\s;,)]+)", "$1" + MASK),
            // jdbc:mysql://user:pass@host, and any other userinfo-carrying URL
            new Masker("(//[^/@:\\s]{1,200}:)[^@/\\s]{1,200}(@)", "$1***$2"),
            // Oracle easy-connect style user/pass@service
            new Masker("(?i)(\\b[A-Za-z][A-Za-z0-9_$#]{0,127}/)[^\\s/@]{1,200}(@)", "$1***$2"));

    /** A compiled secret shape and the replacement that keeps its structure. */
    private record Masker(Pattern pattern, String replacement) {
        Masker(String regex, String replacement) {
            this(Pattern.compile(regex), replacement);
        }

        String apply(String sql) {
            return pattern.matcher(sql).replaceAll(replacement);
        }
    }

    private final ConcurrentLinkedQueue<Map<String, Object>> buffer = new ConcurrentLinkedQueue<>();

    private final AuditLogRepository auditLogRepository;
    private final boolean persistenceEnabled;
    private volatile boolean dbAvailable;
    private final com.entropy.database.mcp.properties.DatabaseProperties properties;
    private final SqlAuditService sqlAuditService;

    public QueryAuditLoggerImpl(@org.springframework.lang.Nullable AuditLogRepository auditLogRepository,
                                com.entropy.database.mcp.properties.DatabaseProperties properties,
                                SqlAuditService sqlAuditService) {
        this.auditLogRepository = auditLogRepository;
        this.persistenceEnabled = properties != null && properties.audit() != null && properties.audit().enabled();
        this.properties = properties;
        this.sqlAuditService = sqlAuditService;
        // No probe insert: writing a synthetic "_probe_" row to test the table put a junk record in
        // audit_log for every BYOK connection that was created. Availability is assumed here and
        // revoked by the failure handling in log(), which only works because
        // AuditLogRepository.insert propagates its failures instead of swallowing them.
        this.dbAvailable = auditLogRepository != null;
        if (!dbAvailable) {
            log.info("No audit repository wired; audit logs will be written to file only");
        }
    }

    /**
     * Log audit entry asynchronously to avoid blocking query execution.
     */
    @Override
    @Async
    public void log(String tool, String sql, int rowCount, long durationMs, boolean success, @Nullable String connectionKey) {
        log(tool, sql, rowCount, durationMs, success, null, connectionKey);
    }

    @Override
    @Async
    public void log(String tool, String sql, int rowCount, long durationMs, boolean success, @Nullable String error, @Nullable String connectionKey) {
        String safeSql = maskSensitiveValues(sql);
        auditLog.info(
            "mcp.db.audit tool={} sql=\"{}\" rows={} durationMs={} success={} error={} connection={}",
            tool,
            truncate(safeSql, properties.audit().sqlTruncateLength()),
            rowCount,
            durationMs,
            success,
            error != null ? error : "",
            connectionKey != null ? connectionKey : ""
        );

        Map<String, Object> entry = Map.of(
            "tool", tool,
            "sql", truncate(safeSql, properties.audit().entrySqlTruncateLength()),
            "rows", rowCount,
            "durationMs", durationMs,
            "success", success,
            "timestamp", Instant.now().toString(),
            "connectionKey", connectionKey != null ? connectionKey : ""
        );
        buffer.offer(entry);
        evictOld();

        // Record in SqlAuditService for slow query analysis and pattern stats
        if (sqlAuditService != null) {
            try {
                sqlAuditService.recordQuery(tool, safeSql, rowCount, durationMs, success, connectionKey);
            } catch (Exception e) {
                log.warn("Failed to record query in SqlAuditService: {}", e.getMessage(), e);
            }
        }

        // Persist to database asynchronously (only if default datasource is available)
        if (persistenceEnabled && dbAvailable) {
            try {
                auditLogRepository.insert(new AuditLogEntity(
                    null,
                    tool,
                    safeSql,
                    rowCount,
                    durationMs,
                    success,
                    error,
                    Instant.now(),
                    connectionKey
                ));
            } catch (Exception e) {
                log.warn("Failed to persist audit log to database, falling back to file only", e);
                // Disable DB persistence for subsequent calls
                this.dbAvailable = false;
            }
        }
    }

    /**
     * Returns the most recent {@code limit} buffered audit log entries, newest first.
     * Uses toArray() to take a consistent snapshot, avoiding ConcurrentModificationException
     * that could occur with direct stream() on a concurrent queue during concurrent writes.
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<Map<String, Object>> getRecentLogs(int limit) {
        Map<String, Object>[] snapshot = buffer.toArray(new Map[0]);
        return java.util.Arrays.stream(snapshot)
            .sorted((a, b) -> ((String) b.get("timestamp")).compareTo((String) a.get("timestamp")))
            .limit(limit)
            .toList();
    }

    private synchronized void evictOld() {
        while (buffer.size() > MAX_BUFFER_SIZE) {
            buffer.poll();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Mask secrets in a SQL string before it reaches the audit log, the in-memory buffer or the
     * {@code audit_log} table. Statement structure is preserved so the audit trail stays readable:
     * only the value is replaced.
     *
     * <p>Package-private so the masking can be asserted directly.
     */
    static String maskSensitiveValues(String sql) {
        if (sql == null || sql.isEmpty()) return sql;
        String masked = sql;
        for (Masker masker : MASKERS) {
            masked = masker.apply(masked);
        }
        return masked;
    }
}
