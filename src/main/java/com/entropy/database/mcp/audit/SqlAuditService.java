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
package com.entropy.database.mcp.audit;

import com.entropy.database.mcp.properties.DatabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * SQL audit service for slow query analysis and SQL pattern statistics.
 * Thread-safe, in-memory aggregation with bounded storage.
 */
@Component
public class SqlAuditService {

    private static final Logger log = LoggerFactory.getLogger(SqlAuditService.class);

    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong totalSlowQueries = new AtomicLong(0);
    private final int maxSlowQueries;
    private final int maxSqlPatterns;
    private final long slowQueryThresholdMs;

    // Slow queries: bounded circular buffer
    private final Deque<SlowQueryRecord> slowQueryBuffer = new LinkedList<>();
    private final Object slowQueryLock = new Object();

    // SQL patterns: LRU-style map
    private final LinkedHashMap<String, SqlPatternStat> sqlPatternStats;
    private final Object patternLock = new Object();

    // Precompiled patterns for SQL normalization
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern LITERAL_PATTERN = Pattern.compile("'[^']*'");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");

    public SqlAuditService(DatabaseProperties properties) {
        var audit = properties != null && properties.audit() != null ? properties.audit()
                : new DatabaseProperties.AuditProperties(true, 7);
        this.slowQueryThresholdMs = audit.slowQueryThresholdMs();
        this.maxSlowQueries = audit.maxSlowQueries();
        this.maxSqlPatterns = audit.maxSqlPatterns();
        this.sqlPatternStats = new LinkedHashMap<>(maxSqlPatterns, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SqlPatternStat> eldest) {
                return size() > maxSqlPatterns;
            }
        };
    }

    /**
     * Record a query execution for audit and analysis.
     */
    public void recordQuery(String tool, String sql, int rows, long durationMs, boolean success,
                            String connectionKey) {
        totalQueries.incrementAndGet();

        if (!success) {
            return;
        }

        // Record slow query
        if (durationMs >= slowQueryThresholdMs) {
            recordSlowQuery(tool, sql, rows, durationMs, connectionKey);
        }

        // Update SQL pattern statistics
        updateSqlPattern(sql, durationMs, rows);
    }

    /**
     * Get top N slowest queries.
     */
    public List<Map<String, Object>> getSlowQueries(int limit) {
        synchronized (slowQueryLock) {
            return slowQueryBuffer.stream()
                    .limit(limit)
                    .map(r -> Map.<String, Object>of(
                            "tool", r.tool,
                            "sql", truncate(r.sql, 500),
                            "rows", r.rows,
                            "durationMs", r.durationMs,
                            "connectionKey", r.connectionKey,
                            "timestamp", r.timestamp.toString()
                    ))
                    .toList();
        }
    }

    /**
     * Get SQL pattern statistics (TOP N by frequency).
     */
    public List<Map<String, Object>> getSqlPatternStats(int limit) {
        synchronized (patternLock) {
            return sqlPatternStats.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().count, a.getValue().count))
                    .limit(limit)
                    .map(e -> {
                        SqlPatternStat stat = e.getValue();
                        return Map.<String, Object>of(
                                "pattern", e.getKey(),
                                "count", stat.count,
                                "totalDurationMs", stat.totalDurationMs,
                                "avgDurationMs", stat.count > 0 ? stat.totalDurationMs / stat.count : 0,
                                "maxDurationMs", stat.maxDurationMs,
                                "totalRows", stat.totalRows,
                                "avgRows", stat.count > 0 ? stat.totalRows / stat.count : 0
                        );
                    })
                    .toList();
        }
    }

    /**
     * Get overall audit summary.
     */
    public Map<String, Object> getAuditSummary() {
        long total = totalQueries.get();
        long slow = totalSlowQueries.get();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalQueries", total);
        summary.put("slowQueryCount", slow);
        summary.put("slowQueryRate", total > 0 ? String.format("%.2f%%", slow * 100.0 / total) : "0.00%");
        summary.put("slowQueryThresholdMs", slowQueryThresholdMs);
        summary.put("trackedPatterns", sqlPatternStats.size());
        summary.put("maxSlowQueries", maxSlowQueries);
        summary.put("maxSqlPatterns", maxSqlPatterns);

        return summary;
    }

    // ─── Private Methods ───────────────────────────────────────────────────

    private void recordSlowQuery(String tool, String sql, int rows, long durationMs, String connectionKey) {
        SlowQueryRecord record = new SlowQueryRecord(tool, sql, rows, durationMs, connectionKey, Instant.now());
        synchronized (slowQueryLock) {
            slowQueryBuffer.addFirst(record);
            while (slowQueryBuffer.size() > maxSlowQueries) {
                slowQueryBuffer.removeLast();
            }
        }
        totalSlowQueries.incrementAndGet();
        log.warn("Slow query: tool={}, duration={}ms, sql={}", tool, durationMs, truncate(sql, 100));
    }

    private void updateSqlPattern(String sql, long durationMs, int rows) {
        String pattern = normalizeSql(sql);
        SqlPatternStat stat;
        synchronized (patternLock) {
            stat = sqlPatternStats.computeIfAbsent(pattern, k -> new SqlPatternStat());
            stat.count++;
            stat.totalDurationMs += durationMs;
            stat.maxDurationMs = Math.max(stat.maxDurationMs, durationMs);
            stat.totalRows += rows;
        }
    }

    /**
     * Normalize SQL by removing whitespace and replacing literals with placeholders.
     */
    static String normalizeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String normalized = WHITESPACE_PATTERN.matcher(sql).replaceAll(" ");
        normalized = LITERAL_PATTERN.matcher(normalized).replaceAll("?");
        normalized = NUMBER_PATTERN.matcher(normalized).replaceAll("?");
        return normalized.trim().toUpperCase();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ─── Inner Classes ─────────────────────────────────────────────────────

    private static class SlowQueryRecord {
        final String tool;
        final String sql;
        final int rows;
        final long durationMs;
        final String connectionKey;
        final Instant timestamp;

        SlowQueryRecord(String tool, String sql, int rows, long durationMs, String connectionKey, Instant timestamp) {
            this.tool = tool;
            this.sql = sql;
            this.rows = rows;
            this.durationMs = durationMs;
            this.connectionKey = connectionKey;
            this.timestamp = timestamp;
        }
    }

    private static class SqlPatternStat {
        long count = 0;
        long totalDurationMs = 0;
        long maxDurationMs = 0;
        int totalRows = 0;
    }
}
