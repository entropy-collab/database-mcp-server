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
import java.util.stream.Collectors;

/**
 * Query audit logger for tracking database operations.
 * Buffers entries in memory for SSE/polling consumers and optionally persists to database.
 */
@Component
public class QueryAuditLoggerImpl implements QueryAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(QueryAuditLoggerImpl.class);
    private static final Logger auditLog = LoggerFactory.getLogger("auditLogger");

    // Bounded buffer: keeps last 100 audit entries for SSE/polling consumers
    private static final int MAX_BUFFER_SIZE = 100;

    private final ConcurrentLinkedQueue<Map<String, Object>> buffer = new ConcurrentLinkedQueue<>();

    private final AuditLogRepository auditLogRepository;
    private final boolean persistenceEnabled;
    private final com.entropy.database.mcp.properties.DatabaseProperties properties;

    public QueryAuditLoggerImpl(AuditLogRepository auditLogRepository,
                                com.entropy.database.mcp.properties.DatabaseProperties properties) {
        this.auditLogRepository = auditLogRepository;
        this.persistenceEnabled = properties != null && properties.audit() != null && properties.audit().enabled();
        this.properties = properties;
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
        auditLog.info(
            "mcp.db.audit tool={} sql=\"{}\" rows={} durationMs={} success={} error={} connection={}",
            tool,
            truncate(sql, properties.audit().sqlTruncateLength()),
            rowCount,
            durationMs,
            success,
            error != null ? error : "",
            connectionKey != null ? connectionKey : ""
        );

        Map<String, Object> entry = Map.of(
            "tool", tool,
            "sql", truncate(sql, properties.audit().entrySqlTruncateLength()),
            "rows", rowCount,
            "durationMs", durationMs,
            "success", success,
            "timestamp", Instant.now().toString(),
            "connectionKey", connectionKey != null ? connectionKey : ""
        );
        buffer.offer(entry);
        evictOld();

        // Persist to database asynchronously
        if (persistenceEnabled) {
            try {
                auditLogRepository.insert(new AuditLogEntity(
                    null,
                    tool,
                    sql,
                    rowCount,
                    durationMs,
                    success,
                    error,
                    Instant.now(),
                    connectionKey
                ));
            } catch (Exception e) {
                log.warn("Failed to persist audit log to database: {}", e.getMessage());
            }
        }
    }

    /**
     * Returns the most recent {@code limit} buffered audit log entries, newest first.
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<Map<String, Object>> getRecentLogs(int limit) {
        return buffer.stream()
            .sorted((a, b) -> ((String) b.get("timestamp")).compareTo((String) a.get("timestamp")))
            .limit(limit)
            .collect(Collectors.toList());
    }

    private void evictOld() {
        while (buffer.size() > MAX_BUFFER_SIZE) {
            buffer.poll();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
