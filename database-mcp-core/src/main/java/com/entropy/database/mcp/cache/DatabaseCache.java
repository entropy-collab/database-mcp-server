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

/**
 * Two-cache database cache: query results and schema metadata, each with its own size budget
 * and TTL.
 *
 * <p>This used to be declared as a "multi-tier cache with hot/warm/cold layers and refresh-ahead
 * support" and carried a {@code CacheTier} enum with {@code HOT}/{@code WARM}/{@code COLD}
 * constants. None of that existed: the three tiers were no-op branches in the implementation, the
 * tier-taking {@code get}/{@code put}/{@code evict} overloads had no callers anywhere (every caller
 * uses {@code getQuery}/{@code getMetadata} directly), and the {@code warm-cache-ttl} property that
 * appeared to configure them was never read. The names have been removed rather than implemented,
 * because nothing in this server needs a third tier — access-based expiry already keeps hot entries
 * alive and lets cold ones fall out.
 */
public interface DatabaseCache {

    /**
     * Get a value from the query cache.
     */
    Object getQuery(String key);

    /**
     * Get a value from the query cache with a loader function.
     * If the key is absent, the loader is called atomically to load the value.
     */
    default Object getQuery(String key, java.util.function.Function<String, Object> loader) {
        Object cached = getQuery(key);
        if (cached != null) {
            return cached;
        }
        if (loader != null) {
            return loader.apply(key);
        }
        return null;
    }

    /**
     * Put a value into the query cache.
     */
    void putQuery(String key, Object value);

    /**
     * Evict a value from the query cache.
     */
    void evictQuery(String key);

    /**
     * Get a value from the metadata cache.
     */
    Object getMetadata(String key);

    /**
     * Get a value from the metadata cache with a loader function.
     */
    <T> T getMetadata(String key, java.util.function.Function<String, T> loader);

    /**
     * Put a value into the metadata cache.
     */
    void putMetadata(String key, Object value);

    /**
     * Evict a value from the metadata cache.
     */
    void evictMetadata(String key);

    /**
     * Evict every metadata entry whose key satisfies {@code keyFilter}; returns how many were
     * removed.
     *
     * <p>Needed because the caller that knows a schema change happened does not know which cache
     * keys it invalidated. A table's column metadata is keyed by the <em>resolved</em> schema
     * ({@code columns:QDITP.T}), and a DDL statement rarely names a schema, so the exact key cannot
     * be reconstructed — only recognised. Expressed as a predicate rather than a prefix because the
     * discriminating part of these keys is the table name at the end, not the category at the front.
     *
     * <p>The filter sees the caller's own key space: a connection-scoped cache does not leak its
     * scope prefix into the predicate, same as {@link #getMetadata(String, java.util.function.Function)}
     * does not leak it into the loader.
     */
    int evictMetadataWhere(java.util.function.Predicate<String> keyFilter);

    /**
     * Clear all caches.
     */
    void clear();

    /**
     * Invalidate all cache entries.
     */
    void invalidateAll();

    /**
     * Shutdown the cache and release resources.
     */
    void shutdown();

    /**
     * Get the total size of all caches.
     */
    long size();

    /**
     * Get the query cache size.
     */
    long queryCacheSize();

    /**
     * Get the metadata cache size.
     */
    long metadataCacheSize();

    /**
     * Get the maximum cache size.
     */
    int maxSize();

    /**
     * Get the query cache hit rate.
     */
    double queryHitRate();

    /**
     * Get the metadata cache hit rate.
     */
    double metadataHitRate();

    /**
     * Get cache statistics.
     */
    java.util.Map<String, Object> getStatistics();

    /**
     * Query-key membership pre-check. Returns {@code false} only when the key is definitely
     * absent from the query cache, so a caller can skip the cache lookup entirely.
     *
     * <p>Exposed as a predicate rather than as the underlying {@code BloomFilter}: a scoped
     * cache has to prefix the key with its own scope before consulting the shared filter,
     * which is impossible if callers hold the filter directly.
     */
    boolean mightContainQuery(String key);

    /**
     * Record a query key in the membership filter. Call this whenever the corresponding
     * entry is written via {@link #putQuery(String, Object)}.
     */
    void recordQueryKey(String key);
}
