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
package com.entropy.database.mcp.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * BYOK connection lifecycle configuration.
 * Allows customization of lease duration, max lifetime, and pool settings.
 */
@ConfigurationProperties(prefix = "entropy.mcp.database.byok")
public record ByokProperties(
    Duration leaseDuration,
    Duration maxLifetime,
    Duration cleanupInterval,
    Integer maxCachedConnections,
    Integer poolSize,
    Integer minIdle
) {
    public ByokProperties {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            leaseDuration = Duration.ofHours(1);
        }
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) {
            // maxLifetime must be strictly greater than leaseDuration; default to 2h
            // to satisfy the constraint while giving ample headroom.
            maxLifetime = Duration.ofHours(2);
        }
        if (cleanupInterval == null || cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            cleanupInterval = Duration.ofMinutes(5);
        }
        if (maxCachedConnections == null || maxCachedConnections <= 0) {
            maxCachedConnections = 100;
        }
        if (poolSize == null || poolSize <= 0) {
            poolSize = 10;
        }
        if (minIdle == null || minIdle <= 0) {
            minIdle = 2;
        }
    }
}
