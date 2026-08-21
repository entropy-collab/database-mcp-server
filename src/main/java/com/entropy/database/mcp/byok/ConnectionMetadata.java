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

import java.time.Instant;

/**
 * Metadata for a registered BYOK datasource connection.
 * <p>
 * Captures operational attributes for observability, auditing,
 * and lifecycle management without exposing sensitive credentials.
 * </p>
 */
public record ConnectionMetadata(
    String key,
    String dialect,
    String jdbcUrlMasked,
    String owner,
    Instant createdAt,
    java.time.Duration leaseTtl,
    java.time.Duration maxLifetime,
    boolean isPrimary,
    int poolSize,
    int activeConnections
) {

    public ConnectionMetadata {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        if (dialect == null || dialect.isBlank()) {
            throw new IllegalArgumentException("dialect is required");
        }
        if (jdbcUrlMasked == null || jdbcUrlMasked.isBlank()) {
            throw new IllegalArgumentException("jdbcUrlMasked is required");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (leaseTtl == null || leaseTtl.isNegative() || leaseTtl.isZero()) {
            leaseTtl = java.time.Duration.ofMinutes(30);
        }
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) {
            maxLifetime = java.time.Duration.ofHours(2);
        }
    }

    public Instant getLeaseExpiry() {
        return createdAt.plus(leaseTtl);
    }

    public Instant getMaxLifetimeExpiry() {
        return createdAt.plus(maxLifetime);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(getLeaseExpiry());
    }

    public boolean isMaxLifetimeExceeded() {
        return Instant.now().isAfter(getMaxLifetimeExpiry());
    }

    public String getStatus() {
        if (isMaxLifetimeExceeded()) return "EXPIRED_MAX_LIFETIME";
        if (isExpired()) return "EXPIRED_LEASE";
        return "ACTIVE";
    }
}
