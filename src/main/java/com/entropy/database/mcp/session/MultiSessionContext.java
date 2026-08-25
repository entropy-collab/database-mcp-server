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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Scope-partitioned context store for sharing data between MCP tool calls.
 *
 * <p>This provides a shared, thread-safe key-value store where tools can store and retrieve
 * contextual data. For example:
 * <ul>
 *   <li>Save query results from one tool to be used by another</li>
 *   <li>Share connection preferences across tools</li>
 *   <li>Store intermediate ETL pipeline state</li>
 * </ul>
 *
 * <p>Data is organized under scope-keyed namespaces to prevent collisions between concurrent
 * callers. Entries automatically expire after {@link #DEFAULT_TTL_MINUTES} minutes.
 *
 * <h2>Scope, and why it is not an MCP session</h2>
 * <p>The store is partitioned by whatever {@link McpToolContext#sessionId()} reports. Under this
 * server's deployment shape — {@code spring.ai.mcp.server.protocol: STATELESS} — the MCP protocol
 * has no session identity to partition by (see the {@link McpToolContext} class javadoc for what
 * the transport does and does not expose). The default scope is therefore <b>one tool
 * invocation</b>: a value written by {@code sessionStore} is <em>not</em> visible to the next
 * {@code sessionGet}, and the TTL only bounds how long an abandoned partition lingers.
 *
 * <p>Cross-call sharing requires the caller to supply the scope key explicitly, via the
 * {@code scope}-taking overloads here (or {@link McpToolContext#create(String, String)} plus an MCP
 * tool parameter carrying the key). That is the only correct option on a stateless transport:
 * deriving the partition from a clock — the previous behaviour — both broke sharing for one caller
 * and merged the namespaces of two different callers that arrived in the same millisecond, which
 * under BYOK multi-tenancy is cross-tenant data visibility.
 *
 * <p>Usage:
 * <pre>{@code
 * // Store a value in the current (call-scoped) partition
 * multiSession.set("user-queries", "my-table-data", resultRows);
 *
 * // Store a value under a caller-supplied scope so a later call can read it
 * multiSession.set(callerScope, "user-queries", "my-table-data", resultRows);
 * var data = multiSession.get(callerScope, "user-queries", "my-table-data");
 * }</pre>
 */
@Component
public class MultiSessionContext {

    /** Default TTL for cached entries in minutes. */
    public static final long DEFAULT_TTL_MINUTES = 60;

    /** Namespace for query results shared across tools. */
    public static final String NAMESPACE_QUERIES = "queries";

    /** Namespace for ETL pipeline state. */
    public static final String NAMESPACE_ETL = "etl";

    /** Namespace for user preferences. */
    public static final String NAMESPACE_PREFERENCES = "preferences";

    /** Namespace for temporary scratch data. */
    public static final String NAMESPACE_SCRATCH = "scratch";

    /**
     * Counter for the fallback scope handed out when no {@link McpToolContext} exists (background
     * tasks, direct programmatic use). Deliberately a per-thread scope rather than one shared
     * bucket: an unidentified caller must not land in a partition another caller can read.
     */
    private static final AtomicLong fallbackScopeCounter = new AtomicLong(0);

    /**
     * Scope-partitioned storage: scope -> namespace -> key -> CacheEntry.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, CacheEntry>>> store
            = new ConcurrentHashMap<>();

    /**
     * The scope key the no-scope overloads write to and read from.
     *
     * <p>Normally the current tool invocation's scope. Falls back to a fresh thread-local scope
     * when there is no {@link McpToolContext} at all, so that a caller outside the MCP request path
     * still gets isolation rather than sharing a global partition.
     */
    public String currentSessionId() {
        String scope = McpToolContext.currentSessionId().orElse(null);
        if (scope == null) {
            scope = "thread:" + Thread.currentThread().threadId()
                    + ":" + fallbackScopeCounter.incrementAndGet();
            McpToolContext.initSession(scope);
        }
        return scope;
    }

    /**
     * Whether the current scope disappears at the end of this tool call, i.e. whether anything
     * written now can be read back later. False only when the caller supplied a scope key.
     */
    public boolean currentScopeIsCallScoped() {
        return McpToolContext.isCallScoped(currentSessionId());
    }

    /**
     * Store a value under the current scope's namespace.
     *
     * @param namespace the namespace (e.g., {@link #NAMESPACE_QUERIES})
     * @param key       the key within the namespace
     * @param value     the value to store
     */
    public void set(String namespace, String key, Object value) {
        set(currentSessionId(), namespace, key, value);
    }

    /**
     * Store a value under an explicit scope's namespace.
     *
     * @param scope     the scope key (see the class javadoc)
     * @param namespace the namespace
     * @param key       the key
     * @param value     the value
     */
    public void set(String scope, String namespace, String key, Object value) {
        store
                .computeIfAbsent(scope, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(namespace, k -> new ConcurrentHashMap<>())
                .put(key, new CacheEntry(value, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(DEFAULT_TTL_MINUTES)));
    }

    /**
     * Retrieve a value from the current scope's namespace.
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
     * Retrieve a value from an explicit scope's namespace.
     */
    @SuppressWarnings("unchecked")
    public <T> T getInScope(String scope, String namespace, String key) {
        return (T) doGet(scope, namespace, key);
    }

    private Object doGet(String scope, String namespace, String key) {
        var nsMap = store.get(scope);
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
        removeInScope(currentSessionId(), namespace, key);
    }

    public void removeInScope(String scope, String namespace, String key) {
        var nsMap = store.get(scope);
        if (nsMap == null) return;
        var keyMap = nsMap.get(namespace);
        if (keyMap != null) keyMap.remove(key);
    }

    /**
     * Clear all entries in a namespace for the current scope.
     */
    public void clearNamespace(String namespace) {
        clearNamespaceInScope(currentSessionId(), namespace);
    }

    public void clearNamespaceInScope(String scope, String namespace) {
        var nsMap = store.get(scope);
        if (nsMap != null) nsMap.remove(namespace);
    }

    /**
     * Get all keys in a namespace for the current scope.
     */
    public java.util.Set<String> keys(String namespace) {
        var nsMap = store.get(currentSessionId());
        if (nsMap == null) return Set.of();
        var keyMap = nsMap.get(namespace);
        return keyMap != null ? keyMap.keySet() : Set.of();
    }

    /**
     * Purge all expired entries across all scopes.
     *
     * <p>Scheduled rather than relying on the lazy check in {@link #doGet}: a scope that is
     * written to and never read again would otherwise retain its payload for the process
     * lifetime, since nothing ever evaluates its expiry.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedRateString = "${entropy.mcp.housekeeping.prune-interval:600000}")
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        store.values().forEach(nsMap ->
                nsMap.values().forEach(keyMap ->
                        keyMap.entrySet().removeIf(e -> now > e.getValue().expireAt())));
        // Clean up empty namespace maps
        store.values().forEach(nsMap ->
                nsMap.entrySet().removeIf(e -> e.getValue().isEmpty()));
        // Clean up empty scopes
        store.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /**
     * Get the number of active scopes.
     */
    public int sessionCount() {
        return store.size();
    }

    // ─── Nested types ─────────────────────────────────────────────────────

    private record CacheEntry(Object value, long expireAt) {
    }
}
