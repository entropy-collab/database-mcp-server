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

import com.google.common.hash.BloomFilter;

/**
 * Multi-tier database cache interface.
 * Provides hot/warm/cold cache layers with refresh-ahead support.
 */
public interface DatabaseCache {

    /**
     * Cache tier enumeration.
     */
    enum CacheTier {
        HOT, WARM, COLD, QUERY, METADATA
    }

    /**
     * Get a value from the cache.
     */
    Object get(String key, CacheTier tier);

    /**
     * Put a value into the cache.
     */
    void put(String key, Object value, CacheTier tier);

    /**
     * Evict a value from the cache.
     */
    void evict(String key, CacheTier tier);

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
     * Get the query bloom filter.
     */
    BloomFilter<String> getQueryBloomFilter();
}
