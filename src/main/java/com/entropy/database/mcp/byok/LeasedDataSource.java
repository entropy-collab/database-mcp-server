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

import com.entropy.database.mcp.exception.McpLeaseExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Leased datasource with TTL-based lifecycle management.
 * Wraps ByokDataSourceContext with lease renewal and expiry detection.
 */
public class LeasedDataSource {
    private static final Logger log = LoggerFactory.getLogger(LeasedDataSource.class);

    private final String key;
    private final ByokDataSourceContext context;
    private final Instant createdAt;
    private final Instant maxLifetime;
    private final AtomicReference<Instant> leaseExpiry;
    private final java.time.Duration leaseDuration;
    private final boolean closeable;

    public LeasedDataSource(String key,
                            ByokDataSourceContext context,
                            java.time.Duration leaseDuration,
                            java.time.Duration maxLifetime) {
        this(key, context, leaseDuration, maxLifetime, true);
    }

    public LeasedDataSource(String key,
                            ByokDataSourceContext context,
                            java.time.Duration leaseDuration,
                            java.time.Duration maxLifetime,
                            boolean closeable) {
        if (maxLifetime.compareTo(leaseDuration) <= 0) {
            throw new IllegalArgumentException(
                "maxLifetime must be greater than leaseDuration, got maxLifetime=" + maxLifetime +
                ", leaseDuration=" + leaseDuration);
        }
        this.key = key;
        this.context = context;
        this.createdAt = Instant.now();
        this.maxLifetime = Instant.now().plus(maxLifetime);
        this.leaseDuration = leaseDuration;
        this.leaseExpiry = new AtomicReference<>(Instant.now().plus(leaseDuration));
        this.closeable = closeable;
    }

    /**
     * Renew the lease (called on each access).
     */
    public synchronized ByokDataSourceContext renewLease() {
        Instant now = Instant.now();
        
        // Check max lifetime first
        if (now.isAfter(maxLifetime)) {
            log.warn("Datasource {} exceeded max lifetime, will be replaced", key);
            throw new McpLeaseExpiredException("Datasource exceeded max lifetime: " + key);
        }
        
        // Renew lease
        leaseExpiry.set(now.plus(leaseDuration));
        return context;
    }

    public String getKey() {
        return key;
    }

    public ByokDataSourceContext getContext() {
        return context;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getMaxLifetime() {
        return maxLifetime;
    }

    public Instant getLeaseExpiry() {
        return leaseExpiry.get();
    }

    /**
     * Check if the lease has expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(leaseExpiry.get());
    }

    /**
     * Check if max lifetime has been exceeded.
     */
    public boolean isMaxLifetimeExceeded() {
        return Instant.now().isAfter(maxLifetime);
    }

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Check if this leased datasource has been closed.
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Close underlying datasource resources. Idempotent - safe to call multiple times.
     */
    public void close() {
        if (!closeable || !closed.compareAndSet(false, true)) {
            return;
        }
        try {
            context.close();
            log.debug("Closed leased datasource: {}", key);
        } catch (Exception e) {
            log.warn("Failed to close leased datasource: {}", key, e);
        }
    }

    @Override
    public String toString() {
        return "LeasedDataSource{" +
                "key='" + key + '\'' +
                ", createdAt=" + createdAt +
                ", maxLifetime=" + maxLifetime +
                ", leaseExpiry=" + leaseExpiry.get() +
                '}';
    }
}
