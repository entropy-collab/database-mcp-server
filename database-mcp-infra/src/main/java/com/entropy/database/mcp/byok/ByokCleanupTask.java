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
package com.entropy.database.mcp.byok;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled cleanup task for expired BYOK datasources.
 * Proactively removes expired and idle connections from the cache.
 */
@Component
public class ByokCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ByokCleanupTask.class);

    private final DynamicDataSourceManager dynamicDataSourceManager;

    public ByokCleanupTask(DynamicDataSourceManager dynamicDataSourceManager) {
        this.dynamicDataSourceManager = dynamicDataSourceManager;
    }

    /**
     * Cleanup expired and idle BYOK connections every 5 minutes.
     * Forces Caffeine cache to evict entries that have exceeded their lease duration.
     */
    @Scheduled(fixedRateString = "${entropy.mcp.database.byok.cleanup-interval:300000}")
    public void cleanupExpired() {
        int beforeSize = dynamicDataSourceManager.getActiveConnectionCount();
        
        // Force Caffeine to check for expired entries and trigger removal listener
        dynamicDataSourceManager.evictExpired();
        
        int afterSize = dynamicDataSourceManager.getActiveConnectionCount();
        if (beforeSize != afterSize) {
            log.info("BYOK cleanup completed: {} -> {} active connections", beforeSize, afterSize);
        }
    }
}
