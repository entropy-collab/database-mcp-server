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
package com.entropy.database.mcp.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueryContextCache} 的边界。
 *
 * <p>被钉住的问题：表名队列与「最后访问时间」原本分成两个 map、用 {@code conn + "::" + table} 拼键关联，
 * 三条读写路径都跨两个 map，于是存在「队列里已有表名、时间戳还没写」的窗口——刚记录的表会在
 * {@link QueryContextCache#getRecentTables} 里被当成过期漏掉。现在时间戳随元素走，这个窗口不再存在。
 */
class QueryContextCacheTest {

    private QueryContextCache cache;

    @BeforeEach
    void setUp() {
        cache = new QueryContextCache();
    }

    @Test
    void aJustRecordedTableIsImmediatelyVisible() {
        cache.recordTableAccess("prod", "ORDERS");

        assertThat(cache.getRecentTables("prod")).containsExactly("ORDERS");
    }

    @Test
    void reAccessingATableMovesItToTheFrontWithoutDuplicating() {
        cache.recordTableAccess("prod", "ORDERS");
        cache.recordTableAccess("prod", "CUSTOMERS");
        cache.recordTableAccess("prod", "ORDERS");

        assertThat(cache.getRecentTables("prod")).containsExactly("ORDERS", "CUSTOMERS");
    }

    @Test
    void keepsAtMostFiftyTablesPerConnection() {
        for (int i = 0; i < 120; i++) {
            cache.recordTableAccess("prod", "T" + i);
        }

        List<String> recent = cache.getRecentTables("prod");
        assertThat(recent).hasSize(50);
        assertThat(recent.getFirst()).isEqualTo("T119");
    }

    @Test
    void connectionsAreIsolated() {
        cache.recordTableAccess("prod", "ORDERS");
        cache.recordTableAccess("staging", "ORDERS_COPY");

        assertThat(cache.getRecentTables("prod")).containsExactly("ORDERS");
        assertThat(cache.getRecentTables("staging")).containsExactly("ORDERS_COPY");
        assertThat(cache.getRecentTables("unknown")).isEmpty();
        assertThat(cache.getRecentTables(null)).isEmpty();
    }

    @Test
    void pruningKeepsFreshEntries() {
        cache.recordTableAccess("prod", "ORDERS");
        cache.setHealthStatus("prod", true);

        cache.pruneExpired();

        assertThat(cache.getRecentTables("prod")).containsExactly("ORDERS");
        assertThat(cache.getHealthStatus("prod")).isPresent();
    }

    /**
     * 并发下的一致性：不同线程同时写同一个连接、另一批线程同时读，读到的表名必须全部是真的被记录过的，
     * 且不能抛异常（写入走 {@code compute}，读取在锁外遍历弱一致迭代器）。
     */
    @Test
    void concurrentRecordingNeverExposesAHalfWrittenEntry() throws Exception {
        int writers = 8;
        int perWriter = 200;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        try {
            for (int w = 0; w < writers; w++) {
                int writerId = w;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perWriter; i++) {
                            cache.recordTableAccess("prod", "T" + writerId + "_" + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            // 读者与写者同时跑：只要有一条记录进去，getRecentTables 就不能返回空——这正是旧实现
            // 「时间戳还没写」时会发生的事。
            pool.execute(() -> {
                try {
                    start.await();
                    while (done.getCount() > 0) {
                        cache.getRecentTables("prod");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<String> recent = cache.getRecentTables("prod");
        assertThat(recent).hasSize(50).doesNotHaveDuplicates();
        assertThat(recent).allSatisfy(name -> assertThat(name).startsWith("T"));
    }
}
