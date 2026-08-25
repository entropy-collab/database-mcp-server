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
package com.entropy.database.mcp.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A per-connection view over a single process-wide {@link DatabaseCacheImpl}.
 *
 * <p>Every BYOK connection used to get its own {@code DatabaseCacheImpl}, so total cache
 * memory grew linearly with the number of cached connections while the entry budget was
 * expressed as if there were only one cache. This decorator keeps the per-connection
 * semantics callers rely on — keys cannot collide across connections, and
 * {@code clearCache(connection)} clears only that connection — while all connections
 * compete for one shared, bounded budget.
 *
 * <p>Isolation is by key prefix. {@link #SEPARATOR} is NUL, which cannot occur in a connection
 * key or in SQL-derived cache keys, so no pair of (connection, key) values can collide by
 * concatenation.
 */
public class ConnectionScopedCache implements DatabaseCache {

    /** Reserved separator — must not be producible by any connection key or cache key. */
    static final char SEPARATOR = '\u0000';

    private final DatabaseCacheImpl shared;
    private final String connectionKey;
    private final String scope;

    public ConnectionScopedCache(DatabaseCacheImpl shared, String connectionKey) {
        this.shared = shared;
        this.connectionKey = connectionKey != null ? connectionKey : "";
        this.scope = this.connectionKey + SEPARATOR;
    }

    private String scoped(String key) {
        return scope + key;
    }

    // ─── Tiered Operations ────────────────────────────────────────────────

    @Override
    public Object get(String key, CacheTier tier) {
        return shared.get(scoped(key), tier);
    }

    @Override
    public void put(String key, Object value, CacheTier tier) {
        shared.put(scoped(key), value, tier);
    }

    @Override
    public void evict(String key, CacheTier tier) {
        shared.evict(scoped(key), tier);
    }

    // ─── Query Cache Operations ───────────────────────────────────────────

    @Override
    public Object getQuery(String key) {
        return shared.getQuery(scoped(key));
    }

    @Override
    public Object getQuery(String key, Function<String, Object> loader) {
        // The loader is invoked with the caller's unscoped key: scoping is a storage concern
        // and must not leak into the value that gets loaded.
        return shared.getQuery(scoped(key), scopedKey -> loader.apply(key));
    }

    @Override
    public void putQuery(String key, Object value) {
        shared.putQuery(scoped(key), value);
    }

    @Override
    public void evictQuery(String key) {
        shared.evictQuery(scoped(key));
    }

    // ─── Metadata Cache Operations ────────────────────────────────────────

    @Override
    public Object getMetadata(String key) {
        return shared.getMetadata(scoped(key));
    }

    @Override
    public <T> T getMetadata(String key, Function<String, T> loader) {
        return shared.getMetadata(scoped(key), scopedKey -> loader.apply(key));
    }

    @Override
    public void putMetadata(String key, Object value) {
        shared.putMetadata(scoped(key), value);
    }

    @Override
    public void evictMetadata(String key) {
        shared.evictMetadata(scoped(key));
    }

    // ─── Bulk Operations ──────────────────────────────────────────────────

    @Override
    public void clear() {
        shared.invalidateScope(scope);
    }

    @Override
    public void invalidateAll() {
        clear();
    }

    @Override
    public void shutdown() {
        // Release this connection's entries only. The shared cache outlives any single
        // connection, so it must not be cleared or closed here.
        clear();
    }

    // ─── Statistics ───────────────────────────────────────────────────────

    @Override
    public long size() {
        return queryCacheSize() + metadataCacheSize();
    }

    @Override
    public long queryCacheSize() {
        return shared.scopedQuerySize(scope);
    }

    @Override
    public long metadataCacheSize() {
        return shared.scopedMetadataSize(scope);
    }

    @Override
    public int maxSize() {
        // The budget is shared, so this is a ceiling this connection could reach, not one
        // reserved for it.
        return shared.maxSize();
    }

    @Override
    public double queryHitRate() {
        return shared.queryHitRate();
    }

    @Override
    public double metadataHitRate() {
        return shared.metadataHitRate();
    }

    @Override
    public Map<String, Object> getStatistics() {
        // Hit rates and eviction counts come from Caffeine, which only records them globally.
        // Report the shared numbers and mark them as such, rather than presenting a
        // process-wide hit rate as if it belonged to this connection.
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("connection", connectionKey);
        stats.put("scope", "connection");
        stats.put("totalSize", size());
        stats.put("queryCacheSize", queryCacheSize());
        stats.put("metadataCacheSize", metadataCacheSize());
        stats.put("shared", shared.getStatistics());
        return stats;
    }

    @Override
    public boolean mightContainQuery(String key) {
        return shared.mightContainQuery(scoped(key));
    }

    @Override
    public void recordQueryKey(String key) {
        shared.recordQueryKey(scoped(key));
    }
}
