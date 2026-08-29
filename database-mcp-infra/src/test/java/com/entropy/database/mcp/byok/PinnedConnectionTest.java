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

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.properties.ByokProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Connections declared in configuration must outlive the BYOK lease.
 *
 * <p>These are the assertions that make the pinned design real. A {@code pinned} flag on
 * {@link LeasedDataSource} alone does <em>not</em> keep a connection alive: {@code expireAfterAccess}
 * and the size cap are Caffeine's own policies and ignore anything on the value object. The manager
 * therefore replaces both with pinned-aware equivalents, and the two eviction paths are exercised
 * separately below — an idle-time eviction and a capacity eviction. Get either wrong and a configured
 * Oracle connection disappears mid-flight, which no unit test on {@code LeasedDataSource} would catch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PinnedConnectionTest {

    @Mock
    private DialectResolver dialectResolver;

    @Mock
    private ByokDataSourceFactory dataSourceFactory;

    @Mock
    private McpMetricsCollector metricsCollector;

    private DynamicDataSourceManagerImpl manager(Duration leaseDuration, int maxCachedConnections) {
        ByokProperties properties = new ByokProperties(
                leaseDuration, Duration.ofHours(2), Duration.ofMinutes(5),
                maxCachedConnections, 10, 2);
        DatabaseDialect dialect = new H2Dialect();
        when(dialectResolver.resolve(any(), any())).thenReturn(dialect);
        when(dataSourceFactory.create(anyString(), any(), any()))
                .thenAnswer(invocation -> mock(ByokDataSourceContext.class));
        return new DynamicDataSourceManagerImpl(
                new DynamicDataSourceManagerImpl.Dependencies(
                        dialectResolver, dataSourceFactory, properties, metricsCollector,
                        // Same-thread executor so Caffeine's maintenance is observable synchronously.
                        Runnable::run));
    }

    private static ConnectionProperties connection(String jdbcUrl) {
        return ConnectionProperties.builder()
                .jdbcUrl(jdbcUrl)
                .username("user")
                .password("pass")
                .dialect("h2")
                .build();
    }

    @Test
    void pinnedConnectionSurvivesIdleExpiryWhileByokConnectionDoesNot() throws Exception {
        var manager = manager(Duration.ofMillis(50), 100);

        manager.registerPinned("oracle-prod", connection("jdbc:h2:mem:pinned"));
        manager.acquire("byok-temp", connection("jdbc:h2:mem:temp"));

        assertThat(manager.listConnectionKeys()).contains("oracle-prod", "byok-temp");

        // 这个 sleep 不能缩短。Caffeine 的可变过期（expireAfter）走时间轮，最细一层的跨度约
        // 1.07 秒；只有当 cleanUp() 把轮子推过桶边界时，桶里的条目才会被清理。睡 30ms 的话
        // 50ms 的租约早就到点了，但轮子还在同一个桶里，什么都不会被逐出——对照组就会空转。
        Thread.sleep(1300);
        manager.evictExpired();

        assertThat(manager.listConnectionKeys())
                .as("配置声明的连接必须活过租约；BYOK 连接应当被回收")
                .contains("oracle-prod")
                .doesNotContain("byok-temp");
    }

    @Test
    void pinnedConnectionIsNotSqueezedOutByTheConnectionCap() {
        // Cap of 1: without weight 0 for pinned entries, the BYOK pool below would evict the pinned one.
        var manager = manager(Duration.ofMinutes(30), 1);

        manager.registerPinned("oracle-prod", connection("jdbc:h2:mem:pinned"));
        manager.acquire("byok-a", connection("jdbc:h2:mem:a"));
        manager.acquire("byok-b", connection("jdbc:h2:mem:b"));
        manager.evictExpired();

        assertThat(manager.listConnectionKeys())
                .as("pinned 条目权重为 0，不该参与 max-cached-connections 的竞争")
                .contains("oracle-prod");
    }

    @Test
    void pinnedLeaseNeverExpiresAndNeverThrows() {
        var context = mock(ByokDataSourceContext.class);
        var pinned = LeasedDataSource.pinned("oracle-prod", context);

        assertThat(pinned.isPinned()).isTrue();
        assertThat(pinned.isExpired()).isFalse();
        assertThat(pinned.isMaxLifetimeExceeded()).isFalse();
        // renewLease() throws McpLeaseExpiredException past maxLifetime for leased connections;
        // for pinned ones it must simply hand back the context.
        assertThat(pinned.renewLease()).isSameAs(context);
    }

    @Test
    void replacingAPinnedConnectionKeepsASingleRegistration() {
        var manager = manager(Duration.ofMinutes(30), 100);

        manager.registerPinned("oracle-prod", connection("jdbc:h2:mem:one"));
        manager.registerPinned("oracle-prod", connection("jdbc:h2:mem:two"));

        assertThat(manager.listConnectionKeys()).containsOnlyOnce("oracle-prod");
    }
}
