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
package com.entropy.database.mcp.monitor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Query performance statistics tracker.
 * Thread-safe counters for monitoring.
 */
public class QueryStats {

    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong totalRowsReturned = new AtomicLong(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicLong lastQueryTime = new AtomicLong(0);

    public void recordQuery(long durationMs, int rowsReturned, boolean success) {
        totalQueries.incrementAndGet();
        totalDurationMs.addAndGet(durationMs);
        totalRowsReturned.addAndGet(rowsReturned);
        lastQueryTime.set(System.currentTimeMillis());
        if (!success) {
            errorCount.incrementAndGet();
        }
    }

    public long getTotalQueries() {
        return totalQueries.get();
    }

    public long getTotalDurationMs() {
        return totalDurationMs.get();
    }

    public long getTotalRowsReturned() {
        return totalRowsReturned.get();
    }

    public int getErrorCount() {
        return errorCount.get();
    }

    public long getLastQueryTime() {
        return lastQueryTime.get();
    }

    public long getAverageDurationMs() {
        long total = totalQueries.get();
        return total > 0 ? totalDurationMs.get() / total : 0;
    }

    public double getRowsPerQuery() {
        long total = totalQueries.get();
        return total > 0 ? (double) totalRowsReturned.get() / total : 0;
    }

    public double getErrorRate() {
        long total = totalQueries.get();
        return total > 0 ? (double) errorCount.get() / total * 100 : 0;
    }

    public String toSummary() {
        return String.format(
            "queries=%d, avg_duration=%dms, rows=%d, error_rate=%.2f%%, last_query=%s ago",
            totalQueries.get(),
            getAverageDurationMs(),
            totalRowsReturned.get(),
            getErrorRate(),
            formatTimeAgo(System.currentTimeMillis() - lastQueryTime.get())
        );
    }

    private String formatTimeAgo(long millis) {
        if (millis < MILLIS_PER_SECOND) return millis + "ms";
        if (millis < MILLIS_PER_MINUTE) return (millis / MILLIS_PER_SECOND) + "s";
        if (millis < MILLIS_PER_HOUR) return (millis / MILLIS_PER_MINUTE) + "m";
        return (millis / MILLIS_PER_HOUR) + "h";
    }
}
