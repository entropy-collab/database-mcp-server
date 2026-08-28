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

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Audit logger that writes to a dedicated SLF4J file logger instead of database.
 * Suitable for BYOK mode where no default database connection exists.
 * Note: This is a standalone utility, not a Spring-managed bean.
 * QueryAuditLoggerImpl already handles file fallback internally.
 */
public class FileAuditLogger implements QueryAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(FileAuditLogger.class);
    private static final Logger auditFile = LoggerFactory.getLogger("auditFile");

    private static final int MAX_BUFFER_SIZE = 100;

    // Bounded in-memory buffer for SSE/polling consumers
    private final ConcurrentLinkedQueue<Map<String, Object>> buffer = new ConcurrentLinkedQueue<>();

    public FileAuditLogger() {
    }

    @Override
    @Async
    public void log(String tool, String sql, int rowCount, long durationMs, boolean success, @Nullable String connectionKey) {
        log(tool, sql, rowCount, durationMs, success, null, connectionKey);
    }

    @Override
    @Async
    public void log(String tool, String sql, int rowCount, long durationMs, boolean success, @Nullable String error, @Nullable String connectionKey) {
        String timestamp = Instant.now().toString();
        String sqlShort = (sql != null && sql.length() > 500) ? sql.substring(0, 500) + "..." : sql;

        // Write to dedicated audit file logger (configured via logging.xml as auditFile)
        auditFile.info("mcp.db.audit tool={} sql=\"{}\" rows={} durationMs={} success={} error={} connection={}",
                tool, sqlShort, rowCount, durationMs, success,
                error != null ? error : "",
                connectionKey != null ? connectionKey : "");

        Map<String, Object> entry = Map.of(
                "tool", tool,
                "sql", sqlShort,
                "rows", rowCount,
                "durationMs", durationMs,
                "success", success,
                "error", error != null ? error : "",
                "timestamp", timestamp,
                "connectionKey", connectionKey != null ? connectionKey : ""
        );
        buffer.offer(entry);
        evictOld();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Map<String, Object>> getRecentLogs(int limit) {
        List<Map<String, Object>> list = new ArrayList<>(buffer);
        list.sort((a, b) -> ((String) b.get("timestamp")).compareTo((String) a.get("timestamp")));
        return list.stream().limit(limit).toList();
    }

    private void evictOld() {
        while (buffer.size() > MAX_BUFFER_SIZE) {
            buffer.poll();
        }
    }
}
