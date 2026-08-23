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
package com.entropy.database.mcp.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Cross-session context store for sharing data between MCP tool calls across different sessions.
 *
 * <p>This provides a shared, thread-safe key-value store where tools can store and retrieve
 * contextual data that persists beyond a single tool invocation. For example:
 * <ul>
 *   <li>Save query results from one tool to be used by another</li>
 *   <li>Share connection preferences across tools</li>
 *   <li>Store intermediate ETL pipeline state</li>
 * </ul>
 *
 * <p>Data is organized under session-scoped namespaces to prevent collisions between
 * concurrent MCP sessions. Entries automatically expire after {@link #DEFAULT_TTL_MINUTES} minutes.
 *
 * <p>Usage:
 * <pre>{@code
 * // Store a value
 * multiSession.set("user-queries", "my-table-data", resultRows);
 *
 * // Retrieve a value
 * var data = multiSession.get("user-queries", "my-table-data");
 *
 * // Remove a value
 * multiSession.remove("user-queries", "my-table-data");
 *
 * // Clear all values for a namespace
 * multiSession.clearNamespace("user-queries");
 * }</pre>
 *
 * <p>This follows the same pattern as Spring's {@code RequestContextHolder} but scoped
 * to the MCP protocol's session model.
 */
@Component
public class MultiSessionContext {

    /** Default TTL for cached entries in minutes. */
    public static final long DEFAULT_TTL_MINUTES = 30;

    /** Namespace for query results shared across tools. */
    public static final String NAMESPACE_QUERIES = "queries";

    /** Namespace for ETL pipeline state. */
    public static final String NAMESPACE_ETL = "etl";

    /** Namespace for user preferences. */
    public static final String NAMESPACE_PREFERENCES = "preferences";

    /** Namespace for temporary scratch data. */
    public static final String NAMESPACE_SCRATCH = "scratch";

    private static final AtomicLong sessionIdCounter = new AtomicLong(0);

    /**
     * Session-scoped storage: sessionId -> namespace -> key -> CacheEntry.
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, ConcurrentHashMap<String, CacheEntry>>> store
            = new ConcurrentHashMap<>();

    /**
     * Get or create the current session ID.
     */
    public long currentSessionId() {
        Long sid = McpToolContext.currentSessionId().orElse(null);
        if (sid == null) {
            sid = sessionIdCounter.incrementAndGet();
            McpToolContext.initSession(sid);
        }
        return sid;
    }

    /**
     * Store a value under the current session's namespace.
     *
     * @param namespace the namespace (e.g., {@link #NAMESPACE_QUERIES})
     * @param key       the key within the namespace
     * @param value     the value to store
     */
    public void set(String namespace, String key, Object value) {
        set(currentSessionId(), namespace, key, value);
    }

    /**
     * Store a value under a specific session's namespace.
     *
     * @param sessionId the session ID
     * @param namespace the namespace
     * @param key       the key
     * @param value     the value
     */
    public void set(long sessionId, String namespace, String key, Object value) {
        store
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(namespace, k -> new ConcurrentHashMap<>())
                .put(key, new CacheEntry(value, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(DEFAULT_TTL_MINUTES)));
    }

    /**
     * Retrieve a value from the current session's namespace.
     *
     * @param namespace the namespace
     * @param key       the key
     * @return the value, or null if not found or expired
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String namespace, String key) {
        return (T) doGet(currentSessionId(), namespace, key);
    }

    /**
     * Retrieve a value from a specific session's namespace.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(long sessionId, String namespace, String key) {
        return (T) doGet(sessionId, namespace, key);
    }

    private Object doGet(long sessionId, String namespace, String key) {
        var nsMap = store.get(sessionId);
        if (nsMap == null) return null;
        var keyMap = nsMap.get(namespace);
        if (keyMap == null) return null;
        CacheEntry entry = keyMap.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expireAt()) {
            keyMap.remove(key);
            return null;
        }
        return entry.value();
    }

    /**
     * Remove a specific entry.
     */
    public void remove(String namespace, String key) {
        remove(currentSessionId(), namespace, key);
    }

    public void remove(long sessionId, String namespace, String key) {
        var nsMap = store.get(sessionId);
        if (nsMap == null) return;
        var keyMap = nsMap.get(namespace);
        if (keyMap != null) keyMap.remove(key);
    }

    /**
     * Clear all entries in a namespace for the current session.
     */
    public void clearNamespace(String namespace) {
        clearNamespace(currentSessionId(), namespace);
    }

    public void clearNamespace(long sessionId, String namespace) {
        var nsMap = store.get(sessionId);
        if (nsMap != null) nsMap.remove(namespace);
    }

    /**
     * Get all keys in a namespace for the current session.
     */
    public java.util.Set<String> keys(String namespace) {
        var nsMap = store.get(currentSessionId());
        if (nsMap == null) return java.util.Set.of();
        var keyMap = nsMap.get(namespace);
        return keyMap != null ? keyMap.keySet() : java.util.Set.of();
    }

    /**
     * Purge all expired entries across all sessions.
     */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        store.values().forEach(nsMap ->
                nsMap.values().forEach(keyMap ->
                        keyMap.entrySet().removeIf(e -> now > e.getValue().expireAt())));
        // Clean up empty namespace maps
        store.values().forEach(nsMap ->
                nsMap.entrySet().removeIf(e -> e.getValue().isEmpty()));
        // Clean up empty sessions
        store.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /**
     * Get the number of active sessions.
     */
    public int sessionCount() {
        return store.size();
    }

    // ─── Nested types ─────────────────────────────────────────────────────

    private record CacheEntry(Object value, long expireAt) {
    }
}
