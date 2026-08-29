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
 *
 * <h2>Leased vs. pinned</h2>
 * The lease model exists for BYOK: the caller brings a connection at runtime, so it must not live
 * forever — {@code leaseDuration} reclaims idle pools and {@code maxLifetime} is a hard ceiling that
 * forces credentials to be re-presented.
 *
 * <p>Connections declared in configuration ({@code entropy.mcp.database.connections}) have the
 * opposite requirement: the deployment owns them and they must stay available for the life of the
 * process. Those are {@linkplain #pinned pinned} — no lease expiry, no max lifetime, never closed by
 * the cleanup task.
 *
 * <p><strong>A pinned flag here is not sufficient on its own.</strong> The flag only governs
 * {@link #isExpired()} / {@link #renewLease()}, which is application-level logic. Eviction is
 * Caffeine's, so {@code DynamicDataSourceManagerImpl} must also exempt pinned entries from
 * {@code expireAfterAccess} and from the size cap — see the {@code Expiry} and {@code weigher} in
 * its constructor. Without that, a pinned connection silently disappears after one idle hour.
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
    private final boolean pinned;

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
        this.pinned = false;
    }

    /** Pinned constructor: no lease, no max lifetime. */
    private LeasedDataSource(String key, ByokDataSourceContext context) {
        this.key = key;
        this.context = context;
        this.createdAt = Instant.now();
        // Instant.MAX rather than null so getMaxLifetime()/getLeaseExpiry() keep their contract for
        // the monitoring and listConnections paths, which read them unconditionally.
        this.maxLifetime = Instant.MAX;
        this.leaseDuration = java.time.Duration.ZERO;
        this.leaseExpiry = new AtomicReference<>(Instant.MAX);
        this.closeable = true;
        this.pinned = true;
    }

    /**
     * A connection that lives for the life of the process, for
     * {@code entropy.mcp.database.connections} entries.
     *
     * <p>{@code closeable} is {@code true} on purpose even though nothing evicts it: this server
     * created the pool, so {@link #close()} during {@code shutdown()} must actually release it.
     */
    public static LeasedDataSource pinned(String key, ByokDataSourceContext context) {
        return new LeasedDataSource(key, context);
    }

    /** Whether this connection is exempt from lease expiry and the connection-count cap. */
    public boolean isPinned() {
        return pinned;
    }

    /**
     * Renew the lease (called on each access).
     */
    public synchronized ByokDataSourceContext renewLease() {
        if (pinned) {
            return context;
        }
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
     * Check if the lease has expired. Always {@code false} for a pinned connection.
     */
    public boolean isExpired() {
        return !pinned && Instant.now().isAfter(leaseExpiry.get());
    }

    /**
     * Check if max lifetime has been exceeded. Always {@code false} for a pinned connection.
     */
    public boolean isMaxLifetimeExceeded() {
        return !pinned && Instant.now().isAfter(maxLifetime);
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
            context.closePool();
            log.debug("Closed leased datasource: {}", key);
        } catch (Exception e) {
            log.warn("Failed to close leased datasource: {}", key, e);
        }
    }

    @Override
    public String toString() {
        return "LeasedDataSource{" +
                "key='" + key + '\'' +
                ", pinned=" + pinned +
                ", createdAt=" + createdAt +
                ", maxLifetime=" + maxLifetime +
                ", leaseExpiry=" + leaseExpiry.get() +
                '}';
    }
}
