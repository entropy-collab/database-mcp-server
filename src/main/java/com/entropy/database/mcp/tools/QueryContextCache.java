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
package com.entropy.database.mcp.tools;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Context-aware cache for MCP tool invocations.
 *
 * <p>Provides two caching features:
 * <ul>
 *   <li>Recently accessed tables per connection (FIFO queue, max 50 entries, 5-minute TTL)</li>
 *   <li>Connection health status (last check result, 2-minute TTL)</li>
 * </ul>
 *
 * <p>Call {@link #pruneExpired()} periodically (recommended every ~10 minutes) to clean stale entries.
 */
@Component
public class QueryContextCache {

    private static final int RECENT_TABLES_MAX = 50;
    private static final long TABLE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long HEALTH_CACHE_TTL_MS = 2 * 60 * 1000L;

    /** Recently queried tables per connection, ordered FIFO. */
    private final ConcurrentHashMap<String, Deque<String>> recentTables = new ConcurrentHashMap<>();

    /** Last access timestamp per connection+table key. */
    private final ConcurrentHashMap<String, Long> tableLastAccess = new ConcurrentHashMap<>();

    /** Connection health status cache. */
    private final ConcurrentHashMap<String, HealthStatus> healthCache = new ConcurrentHashMap<>();

    /**
     * Records that a table was accessed via the given connection.
     * Adds to the front of the deque; evicts the oldest if capacity exceeded.
     * The timestamp update is performed inside compute() for atomicity with deque mutation.
     */
    public void recordTableAccess(String connection, String tableName) {
        if (connection == null || tableName == null) {
            return;
        }
        recentTables.compute(connection, (conn, deque) -> {
            long now = Instant.now().toEpochMilli();
            if (deque == null) {
                deque = new ConcurrentLinkedDeque<>();
            }
            // Remove existing entry to move to front
            deque.remove(tableName);
            deque.addFirst(tableName);
            // Update timestamp atomically with deque mutation
            tableLastAccess.put(conn + "::" + tableName, now);
            // Evict oldest if over capacity (also updates tableLastAccess)
            while (deque.size() > RECENT_TABLES_MAX) {
                String evicted = deque.removeLast();
                tableLastAccess.remove(conn + "::" + evicted);
            }
            return deque;
        });
    }

    /**
     * Returns the list of recently accessed tables for a connection, most recent first.
     * Only includes tables whose last access is within the TTL window.
     */
    public List<String> getRecentTables(String connection) {
        if (connection == null) {
            return List.of();
        }
        Deque<String> deque = recentTables.get(connection);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        long now = Instant.now().toEpochMilli();
        return deque.stream()
                .filter(t -> {
                    Long lastAccess = tableLastAccess.get(connection + "::" + t);
                    return lastAccess != null && (now - lastAccess) < TABLE_CACHE_TTL_MS;
                })
                .toList();
    }

    /**
     * Sets the health status for a connection.
     */
    public void setHealthStatus(String connection, boolean healthy) {
        if (connection == null) {
            return;
        }
        healthCache.put(connection, new HealthStatus(healthy, Instant.now().toEpochMilli()));
    }

    /**
     * Returns the cached health status if it exists and has not expired.
     */
    public Optional<HealthStatus> getHealthStatus(String connection) {
        if (connection == null) {
            return Optional.empty();
        }
        HealthStatus status = healthCache.get(connection);
        if (status == null) {
            return Optional.empty();
        }
        long now = Instant.now().toEpochMilli();
        if ((now - status.checkedAt()) > HEALTH_CACHE_TTL_MS) {
            return Optional.empty();
        }
        return Optional.of(status);
    }

    /**
     * Removes expired entries from both the table cache and health cache.
     * Should be called periodically (e.g., every ~10 minutes).
     */
    public void pruneExpired() {
        long now = Instant.now().toEpochMilli();

        // Prune health cache
        healthCache.entrySet().removeIf(entry ->
                (now - entry.getValue().checkedAt()) > HEALTH_CACHE_TTL_MS);

        // Prune table access timestamps and orphaned deque entries
        tableLastAccess.entrySet().removeIf(entry ->
                (now - entry.getValue()) > TABLE_CACHE_TTL_MS);

        // Clean up empty or fully-expired connection entries
        recentTables.entrySet().removeIf(entry -> {
            String conn = entry.getKey();
            Deque<String> deque = entry.getValue();
            // Remove expired table references from deque
            deque.removeIf(tableName -> {
                Long lastAccess = tableLastAccess.get(conn + "::" + tableName);
                return lastAccess == null || (now - lastAccess) > TABLE_CACHE_TTL_MS;
            });
            // Remove connection if deque is now empty
            return deque.isEmpty();
        });
    }

    /**
     * Record of connection health check result.
     *
     * @param healthy whether the connection is currently healthy
     * @param checkedAt epoch millisecond timestamp of the check
     */
    public record HealthStatus(boolean healthy, long checkedAt) {}
}
