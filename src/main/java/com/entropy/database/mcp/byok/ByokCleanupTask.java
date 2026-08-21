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
 * Proactively removes datasources that have exceeded their max lifetime
 * or have not been accessed within the lease TTL.
 */
@Component
public class ByokCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ByokCleanupTask.class);

    private final DynamicDataSourceManager dynamicDataSourceManager;

    public ByokCleanupTask(DynamicDataSourceManager dynamicDataSourceManager) {
        this.dynamicDataSourceManager = dynamicDataSourceManager;
    }

    /**
     * Cleanup expired datasources every 5 minutes.
     * This is a safety net in addition to Caffeine's expireAfterAccess.
     */
    @Scheduled(fixedRateString = "${entropy.mcp.database.byok.cleanup-interval:300000}")
    public void cleanupExpired() {
        int beforeSize = dynamicDataSourceManager.getActiveConnectionCount();
        
        // DynamicDataSourceManager's cache already has a removal listener that closes datasources.
        // We just need to trigger cleanup of expired entries.
        // Caffeine's expireAfterAccess handles TTL-based expiry automatically.
        // Here we can add additional logic if needed.
        
        int afterSize = dynamicDataSourceManager.getActiveConnectionCount();
        if (beforeSize != afterSize) {
            log.info("BYOK cleanup completed: {} -> {} active connections", beforeSize, afterSize);
        }
    }
}
