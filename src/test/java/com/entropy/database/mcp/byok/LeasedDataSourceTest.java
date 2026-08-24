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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeasedDataSourceTest {

    @Test
    void renewLeaseExtendsExpiry() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        LeasedDataSource leased = new LeasedDataSource(
                "key1", context, Duration.ofMinutes(30), Duration.ofHours(2)
        );

        Instant beforeRenew = Instant.now();
        ByokDataSourceContext renewed = leased.renewLease();

        assertThat(renewed).isSameAs(context);
        assertThat(leased.getLeaseExpiry()).isAfter(beforeRenew.plus(Duration.ofMinutes(29)));
    }

    @Test
    void renewLeaseThrowsWhenMaxLifetimeExceeded() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        // Use short lease + maxLifetime just slightly over it so we can force expiry
        LeasedDataSource leased = new LeasedDataSource(
                "key1", context, Duration.ofMillis(10), Duration.ofMillis(20)
        );

        // Wait for max lifetime to expire
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThatThrownBy(leased::renewLease)
                .isInstanceOf(McpLeaseExpiredException.class)
                .hasMessageContaining("Datasource exceeded max lifetime: key1");
    }

    @Test
    void isExpiredReturnsFalseWhenRecentlyRenewed() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        LeasedDataSource leased = new LeasedDataSource(
                "key1", context, Duration.ofMinutes(30), Duration.ofHours(2)
        );
        leased.renewLease();

        assertThat(leased.isExpired()).isFalse();
    }

    @Test
    void isMaxLifetimeExceededReturnsTrueAfterMaxLifetime() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        LeasedDataSource leased = new LeasedDataSource(
                "key1", context, Duration.ofMillis(10), Duration.ofMillis(20)
        );

        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(leased.isMaxLifetimeExceeded()).isTrue();
    }

    @Test
    void closeCallsContextClose() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        doNothing().when(context).close();

        LeasedDataSource leased = new LeasedDataSource(
                "key1", context, Duration.ofMinutes(30), Duration.ofHours(2), true
        );

        leased.close();

        verify(context).close();
    }

    @Test
    void closeSkipsForNonCloseable() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        LeasedDataSource leased = new LeasedDataSource(
                "key1", context, Duration.ofMinutes(30), Duration.ofHours(2), false
        );

        leased.close();

        verifyNoInteractions(context);
    }

    @Test
    void gettersReturnExpectedValues() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        LeasedDataSource leased = new LeasedDataSource(
                "key1", context, Duration.ofMinutes(30), Duration.ofHours(2)
        );

        assertThat(leased.getKey()).isEqualTo("key1");
        assertThat(leased.getContext()).isSameAs(context);
        assertThat(leased.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(leased.getMaxLifetime()).isAfter(Instant.now());
    }

    @Test
    void toStringContainsKeyAndTimestamps() {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        LeasedDataSource leased = new LeasedDataSource(
                "key1", context, Duration.ofMinutes(30), Duration.ofHours(2)
        );

        String text = leased.toString();

        assertThat(text).contains("key='key1'");
        assertThat(text).contains("createdAt=");
        assertThat(text).contains("maxLifetime=");
        assertThat(text).contains("leaseExpiry=");
    }
}
