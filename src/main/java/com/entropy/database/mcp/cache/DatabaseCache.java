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
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Unified database cache with Caffeine.
 * Merges QueryCache and SchemaCache into a single high-performance cache.
 * 
 * Uses two internal caches:
 * - queryCache: For query results (smaller, shorter TTL)
 * - metadataCache: For schema metadata (larger, longer TTL)
 */
@Component
public class DatabaseCache {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCache.class);

    private static final String QUERY_PREFIX = "query:";
    private static final String METADATA_PREFIX = "meta:";
    private static final String STATS_PREFIX = "stats:";

    private Cache<String, Object> queryCache;
    private Cache<String, Object> metadataCache;
    private Cache<String, Long> accessTracker;

    /** Bloom filter for query cache key pre-check (schema.sql -> boolean). */
    public BloomFilter<String> queryBloomFilter;

    private final int queryCacheSize;
    private final int metadataCacheSize;
    private final Duration queryTtl;
    private final Duration metadataTtl;

    public DatabaseCache(
            @Value("${entropy.mcp.database.cache.max-size:1000}") int maxSize,
            @Value("${entropy.mcp.database.cache.query-ttl:30s}") Duration queryTtl,
            @Value("${entropy.mcp.database.cache.metadata-ttl:5m}") Duration metadataTtl) {
        
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

            // Access time tracker
            this.accessTracker = Caffeine.newBuilder()
                .maximumSize(maxSize * 2)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();

            log.info("DatabaseCache initialized: querySize={}, metadataSize={}, queryTTL={}ms, metadataTTL={}ms",
                queryCacheSize, metadataCacheSize, queryTtl.toMillis(), metadataTtl.toMillis());

            // Initialize Bloom filter for query pre-check
            this.queryBloomFilter = BloomFilter.create(
                (Funnel<String>) (str, into) -> into.putString(str, StandardCharsets.UTF_8),
                queryCacheSize * 10,  // expected inserts: 10x cache size
                0.01                 // false positive rate: 1%
            );

        } catch (Exception e) {
            log.error("Failed to initialize Caffeine cache, using fallback", e);
            this.queryCache = null;
            this.metadataCache = null;
            this.accessTracker = null;
            this.queryBloomFilter = BloomFilter.create(
                (Funnel<String>) (str, into) -> into.putString(str, StandardCharsets.UTF_8),
                1000,
                0.01
            );
        }
    }

    // ─── Query Cache Operations ───────────────────────────────────────────

    /**
     * Get query result from cache.
     */
    public Object getQuery(String key) {
        if (queryCache == null) return null;
        return queryCache.getIfPresent(QUERY_PREFIX + key);
    }

    /**
     * Put query result into cache.
     */
    public void putQuery(String key, Object value) {
        if (queryCache == null) return;
        queryCache.put(QUERY_PREFIX + key, value);
        trackAccess(key);
    }

    /**
     * Evict query result from cache.
     */
    public void evictQuery(String key) {
        if (queryCache != null) {
            queryCache.invalidate(QUERY_PREFIX + key);
            accessTracker.invalidate(QUERY_PREFIX + key);
        }
    }

    // ─── Metadata Cache Operations ────────────────────────────────────────

    /**
     * Get metadata from cache.
     */
    public Object getMetadata(String key) {
        if (metadataCache == null) return null;
        return metadataCache.getIfPresent(METADATA_PREFIX + key);
    }

    /**
     * Get or compute metadata with caching.
     */
    public <T> T getMetadata(String key, java.util.function.Function<String, T> loader) {
        if (metadataCache == null) {
            return loader.apply(key);
        }
        @SuppressWarnings("unchecked")
        T result = (T) metadataCache.get(METADATA_PREFIX + key, loader);
        trackAccess(key);
        return result;
    }

    /**
     * Put metadata into cache.
     */
    public void putMetadata(String key, Object value) {
        if (metadataCache == null) return;
        metadataCache.put(METADATA_PREFIX + key, value);
        trackAccess(key);
    }

    /**
     * Evict metadata from cache.
     */
    public void evictMetadata(String key) {
        if (metadataCache != null) {
            metadataCache.invalidate(METADATA_PREFIX + key);
            accessTracker.invalidate(METADATA_PREFIX + key);
        }
    }

    // ─── Stats Cache Operations ───────────────────────────────────────────

    /**
     * Get stats from cache.
     */
    public Object getStats(String key) {
        if (metadataCache == null) return null;
        return metadataCache.getIfPresent(STATS_PREFIX + key);
    }

    /**
     * Put stats into cache.
     */
    public void putStats(String key, Object value) {
        if (metadataCache == null) return;
        metadataCache.put(STATS_PREFIX + key, value);
    }

    // ─── Bulk Operations ──────────────────────────────────────────────────

    /**
     * Clear all cache entries.
     */
    public void clear() {
        if (queryCache != null) queryCache.invalidateAll();
        if (metadataCache != null) metadataCache.invalidateAll();
        if (accessTracker != null) accessTracker.invalidateAll();
        log.debug("DatabaseCache cleared");
    }

    /**
     * Invalidate all caches (for DDL operations).
     */
    public void invalidateAll() {
        clear();
    }

    // ─── Statistics ───────────────────────────────────────────────────────

    /**
     * Get total cache size.
     */
    public long size() {
        long querySize = queryCache != null ? queryCache.estimatedSize() : 0;
        long metaSize = metadataCache != null ? metadataCache.estimatedSize() : 0;
        return querySize + metaSize;
    }

    /**
     * Get query cache size.
     */
    public long queryCacheSize() {
        return queryCache != null ? queryCache.estimatedSize() : 0;
    }

    /**
     * Get metadata cache size.
     */
    public long metadataCacheSize() {
        return metadataCache != null ? metadataCache.estimatedSize() : 0;
    }

    /**
     * Get maximum cache size.
     */
    public int maxSize() {
        return queryCacheSize + metadataCacheSize;
    }

    /**
     * Get query cache hit rate.
     */
    public double queryHitRate() {
        if (queryCache == null) return 0.0;
        return queryCache.stats().hitRate();
    }

    /**
     * Get metadata cache hit rate.
     */
    public double metadataHitRate() {
        if (metadataCache == null) return 0.0;
        return metadataCache.stats().hitRate();
    }

    /**
     * Get detailed cache statistics.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new java.util.HashMap<>();
        
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

    // ─── Private Helpers ──────────────────────────────────────────────────

    private void trackAccess(String key) {
        if (accessTracker != null) {
            accessTracker.put(key, System.currentTimeMillis());
        }
    }
}
