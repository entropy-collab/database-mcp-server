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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified database cache with Caffeine.
 * Merges QueryCache and SchemaCache into a single high-performance cache.
 *
 * Uses two internal caches:
 * - queryCache: For query results (smaller, shorter TTL)
 * - metadataCache: For schema metadata (larger, longer TTL)
 *
 * <p><b>One instance is shared by every BYOK connection.</b> {@code maxSize} is therefore a
 * process-wide entry budget, not a per-connection one: previously one instance was built per
 * connection, so the worst case was {@code maxSize × maxCachedConnections} entries on a heap
 * sized for a single cache. Per-connection isolation is provided by
 * {@link ConnectionScopedCache}, which prefixes keys and invalidates by prefix.
 */
public class DatabaseCacheImpl implements DatabaseCache {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCacheImpl.class);

    private static final String QUERY_PREFIX = "query:";
    private static final String METADATA_PREFIX = "meta:";

    private Cache<String, Object> queryCache;
    private Cache<String, Object> metadataCache;

    /** Bloom filter for query cache key pre-check (schema.sql -> boolean). */
    private BloomFilter<String> queryBloomFilter;

    private final int queryCacheSize;
    private final int metadataCacheSize;
    private final Duration queryTtl;
    private final Duration metadataTtl;

    public DatabaseCacheImpl(
            int maxSize,
            Duration queryTtl,
            Duration metadataTtl) {

        this.queryTtl = queryTtl;
        this.metadataTtl = metadataTtl;
        this.queryCacheSize = maxSize / 10;  // Query cache is smaller
        this.metadataCacheSize = maxSize;

        try {
            // Query result cache - smaller, faster expiration
            this.queryCache = Caffeine.newBuilder()
                .maximumSize(queryCacheSize)
                .expireAfterAccess(queryTtl)
                .recordStats()
                .build();

            // Metadata cache - larger, slower expiration
            this.metadataCache = Caffeine.newBuilder()
                .maximumSize(metadataCacheSize)
                .expireAfterAccess(metadataTtl)
                .recordStats()
                .build();

            log.info("DatabaseCache initialized: querySize={}, metadataSize={}, queryTTL={}ms, metadataTTL={}ms",
                queryCacheSize, metadataCacheSize, queryTtl.toMillis(), metadataTtl.toMillis());

            // Initialize Bloom filter for query pre-check.
            // Bounded to at most 10000 entries — BloomFilter is a growing data structure with no eviction,
            // so we cap capacity to avoid unbounded memory growth.
            int bloomCapacity = Math.min(queryCacheSize * 10, 10000);
            this.queryBloomFilter = BloomFilter.create(
                (Funnel<String>) (str, into) -> into.putString(str, StandardCharsets.UTF_8),
                bloomCapacity,
                0.01                 // false positive rate: 1%
            );

        } catch (Exception e) {
            log.error("Failed to initialize Caffeine cache, using fallback BloomFilter only", e);
            // queryCache and metadataCache remain null — all get/put calls will safely degrade
        }
    }

    // ─── Query Cache Operations ───────────────────────────────────────────

    @Override
    public Object getQuery(String key) {
        if (queryCache == null) return null;
        return queryCache.getIfPresent(QUERY_PREFIX + key);
    }

    @Override
    public Object getQuery(String key, java.util.function.Function<String, Object> loader) {
        if (queryCache == null) {
            return loader != null ? loader.apply(key) : null;
        }
        String fullKey = QUERY_PREFIX + key;
        try {
            return queryCache.get(fullKey, k -> loader.apply(key));
        } catch (Exception e) {
            log.warn("Failed to load query cache for key {}: {}", key, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void putQuery(String key, Object value) {
        if (queryCache == null) return;
        queryCache.put(QUERY_PREFIX + key, value);
    }

    @Override
    public void evictQuery(String key) {
        if (queryCache != null) {
            queryCache.invalidate(QUERY_PREFIX + key);
        }
    }

    // ─── Metadata Cache Operations ────────────────────────────────────────

    @Override
    public Object getMetadata(String key) {
        if (metadataCache == null) return null;
        return metadataCache.getIfPresent(METADATA_PREFIX + key);
    }

    @Override
    public <T> T getMetadata(String key, java.util.function.Function<String, T> loader) {
        if (metadataCache == null) {
            return loader.apply(key);
        }
        @SuppressWarnings("unchecked")
        T result = (T) metadataCache.get(METADATA_PREFIX + key, loader);
        return result;
    }

    @Override
    public void putMetadata(String key, Object value) {
        if (metadataCache == null) return;
        metadataCache.put(METADATA_PREFIX + key, value);
    }

    @Override
    public void evictMetadata(String key) {
        if (metadataCache != null) {
            metadataCache.invalidate(METADATA_PREFIX + key);
        }
    }

    // ─── Stats Cache Operations ───────────────────────────────────────────

    @Override
    public Object get(String key, CacheTier tier) {
        return switch (tier) {
            case QUERY -> getQuery(key);
            case METADATA -> getMetadata(key);
            case HOT, WARM, COLD -> null;
        };
    }

    @Override
    public void put(String key, Object value, CacheTier tier) {
        switch (tier) {
            case QUERY -> putQuery(key, value);
            case METADATA -> putMetadata(key, value);
            case HOT, WARM, COLD -> { /* no-op */ }
        }
    }

    @Override
    public void evict(String key, CacheTier tier) {
        switch (tier) {
            case QUERY -> evictQuery(key);
            case METADATA -> evictMetadata(key);
            case HOT, WARM, COLD -> { /* no-op */ }
        }
    }

    // ─── Bulk Operations ──────────────────────────────────────────────────

    @Override
    public void clear() {
        if (queryCache != null) queryCache.invalidateAll();
        if (metadataCache != null) metadataCache.invalidateAll();
        log.debug("DatabaseCache cleared");
    }

    @Override
    public void invalidateAll() {
        clear();
    }

    @Override
    public void shutdown() {
        clear();
    }

    // ─── Statistics ───────────────────────────────────────────────────────

    @Override
    public long size() {
        long querySize = queryCache != null ? queryCache.estimatedSize() : 0;
        long metaSize = metadataCache != null ? metadataCache.estimatedSize() : 0;
        return querySize + metaSize;
    }

    @Override
    public long queryCacheSize() {
        return queryCache != null ? queryCache.estimatedSize() : 0;
    }

    @Override
    public long metadataCacheSize() {
        return metadataCache != null ? metadataCache.estimatedSize() : 0;
    }

    @Override
    public int maxSize() {
        return queryCacheSize + metadataCacheSize;
    }

    @Override
    public double queryHitRate() {
        if (queryCache == null) return 0.0;
        return queryCache.stats().hitRate();
    }

    @Override
    public double metadataHitRate() {
        if (metadataCache == null) return 0.0;
        return metadataCache.stats().hitRate();
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("totalSize", size());
        stats.put("queryCacheSize", queryCacheSize());
        stats.put("metadataCacheSize", metadataCacheSize());
        stats.put("maxSize", maxSize());
        stats.put("queryTTL", queryTtl.toMillis() / 1000 + "s");
        stats.put("metadataTTL", metadataTtl.toMillis() / 1000 + "s");
        
        if (queryCache != null) {
            var qs = queryCache.stats();
            stats.put("queryHits", qs.hitCount());
            stats.put("queryMisses", qs.missCount());
            stats.put("queryHitRate", String.format("%.2f%%", qs.hitRate() * 100));
            stats.put("queryEvictions", qs.evictionCount());
        }
        
        if (metadataCache != null) {
            var ms = metadataCache.stats();
            stats.put("metadataHits", ms.hitCount());
            stats.put("metadataMisses", ms.missCount());
            stats.put("metadataHitRate", String.format("%.2f%%", ms.hitRate() * 100));
            stats.put("metadataEvictions", ms.evictionCount());
        }

        return stats;
    }

    @Override
    public boolean mightContainQuery(String key) {
        // No filter means "cannot rule it out" — degrade to always consulting the cache.
        return queryBloomFilter == null || queryBloomFilter.mightContain(key);
    }

    @Override
    public void recordQueryKey(String key) {
        if (queryBloomFilter != null) {
            queryBloomFilter.put(key);
        }
    }

    // ─── Scope Operations (used by ConnectionScopedCache) ─────────────────

    /**
     * Invalidate every entry whose logical key starts with {@code scope}.
     * This is what makes {@code clearCache(connection)} still mean "this connection only"
     * now that one Caffeine instance is shared across connections.
     */
    void invalidateScope(String scope) {
        int removed = 0;
        removed += removeByPrefix(queryCache, QUERY_PREFIX + scope);
        removed += removeByPrefix(metadataCache, METADATA_PREFIX + scope);
        // The bloom filter has no removal, so a cleared scope keeps reporting "might contain".
        // That costs one extra cache lookup that misses, never a stale hit.
        log.debug("DatabaseCache scope cleared: scope={}, entries={}", scope, removed);
    }

    /** Number of live query entries in the given scope. */
    long scopedQuerySize(String scope) {
        return countByPrefix(queryCache, QUERY_PREFIX + scope);
    }

    /** Number of live metadata entries in the given scope. */
    long scopedMetadataSize(String scope) {
        return countByPrefix(metadataCache, METADATA_PREFIX + scope);
    }

    private static int removeByPrefix(Cache<String, Object> cache, String prefix) {
        if (cache == null) return 0;
        var keys = cache.asMap().keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        cache.invalidateAll(keys);
        return keys.size();
    }

    private static long countByPrefix(Cache<String, Object> cache, String prefix) {
        if (cache == null) return 0;
        return cache.asMap().keySet().stream().filter(k -> k.startsWith(prefix)).count();
    }
}
