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

@Component
public class QueryAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(QueryAuditLogger.class);

    // Bounded buffer: keeps last 100 audit entries for SSE/polling consumers
    private static final int MAX_BUFFER_SIZE = 100;

    private final ConcurrentLinkedQueue<Map<String, Object>> buffer = new ConcurrentLinkedQueue<>();

    /**
     * Log audit entry asynchronously to avoid blocking query execution.
     */
    @Async
    public void log(String tool, String sql, int rowCount, long durationMs, boolean success) {
        log(tool, sql, rowCount, durationMs, success, null);
    }

    @Async
    public void log(String tool, String sql, int rowCount, long durationMs, boolean success, @Nullable String error) {
        // Use WARN level for audit logs to distinguish from debug output
        log.warn(
            "mcp.db.audit tool={} sql=\"{}\" rows={} durationMs={} success={} error={}",
            tool,
            truncate(sql, 200),
            rowCount,
            durationMs,
            success,
            error != null ? error : ""
        );

        Map<String, Object> entry = Map.of(
            "tool", tool,
            "sql", truncate(sql, 500),
            "rows", rowCount,
            "durationMs", durationMs,
            "success", success,
            "timestamp", Instant.now().toString()
        );
        buffer.offer(entry);
        evictOld();
    }

    /**
     * Returns the most recent {@code limit} buffered audit log entries, newest first.
     */
    @SuppressWarnings("unchecked")
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
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
