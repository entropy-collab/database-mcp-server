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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionMetadataTest {

    private Instant fixedNow() {
        return Instant.parse("2025-01-01T00:00:00Z");
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> new ConnectionMetadata("", "oracle", "jdbc:oracle:...", "user", null, null, null, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key is required");
    }

    @Test
    void rejectsBlankDialect() {
        assertThatThrownBy(() -> new ConnectionMetadata("key1", "", "jdbc:oracle:...", "user", null, null, null, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dialect is required");
    }

    @Test
    void rejectsBlankJdbcUrlMasked() {
        assertThatThrownBy(() -> new ConnectionMetadata("key1", "oracle", "", "user", null, null, null, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbcUrlMasked is required");
    }

    @Test
    void defaultsCreatedAtToNow() {
        Instant before = Instant.now();
        ConnectionMetadata metadata = new ConnectionMetadata("key1", "oracle", "jdbc:oracle:...", "user", null, null, null, 10, 0);
        Instant after = Instant.now();

        assertThat(metadata.createdAt()).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
    }

    @Test
    void defaultsLeaseTtlToThirtyMinutes() {
        ConnectionMetadata metadata = new ConnectionMetadata("key1", "oracle", "jdbc:oracle:...", "user", null, null, null, 10, 0);

        assertThat(metadata.leaseTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void defaultsMaxLifetimeToTwoHours() {
        ConnectionMetadata metadata = new ConnectionMetadata("key1", "oracle", "jdbc:oracle:...", "user", null, null, null, 10, 0);

        assertThat(metadata.maxLifetime()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void preservesExplicitValues() {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        ConnectionMetadata metadata = new ConnectionMetadata(
                "key1", "oracle", "jdbc:oracle:...", "user",
                createdAt, Duration.ofMinutes(15), Duration.ofHours(1),
                20, 5
        );

        assertThat(metadata.key()).isEqualTo("key1");
        assertThat(metadata.dialect()).isEqualTo("oracle");
        assertThat(metadata.jdbcUrlMasked()).isEqualTo("jdbc:oracle:...");
        assertThat(metadata.owner()).isEqualTo("user");
        assertThat(metadata.createdAt()).isEqualTo(createdAt);
        assertThat(metadata.leaseTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(metadata.maxLifetime()).isEqualTo(Duration.ofHours(1));
        assertThat(metadata.poolSize()).isEqualTo(20);
        assertThat(metadata.activeConnections()).isEqualTo(5);
    }

    @Test
    void getLeaseExpiryAddsLeaseTtlToCreatedAt() {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        ConnectionMetadata metadata = new ConnectionMetadata(
                "key1", "oracle", "jdbc:oracle:...", "user",
                createdAt, Duration.ofMinutes(30), Duration.ofHours(2),
                10, 0
        );

        assertThat(metadata.getLeaseExpiry()).isEqualTo(createdAt.plus(Duration.ofMinutes(30)));
    }

    @Test
    void getMaxLifetimeExpiryAddsMaxLifetimeToCreatedAt() {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        ConnectionMetadata metadata = new ConnectionMetadata(
                "key1", "oracle", "jdbc:oracle:...", "user",
                createdAt, Duration.ofMinutes(30), Duration.ofHours(2),
                10, 0
        );

        assertThat(metadata.getMaxLifetimeExpiry()).isEqualTo(createdAt.plus(Duration.ofHours(2)));
    }

    @Test
    void getStatusReturnsActiveWhenNotExpired() {
        ConnectionMetadata metadata = new ConnectionMetadata(
                "key1", "oracle", "jdbc:oracle:...", "user",
                Instant.now(), Duration.ofHours(2), Duration.ofHours(2),
                10, 0
        );

        assertThat(metadata.getStatus()).isEqualTo("ACTIVE");
    }
}
