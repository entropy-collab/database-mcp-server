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
package com.entropy.database.mcp.config;

import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.properties.ThreadPoolProperties;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pools are configured rather than hardcoded, so these tests assert that the configured
 * numbers actually reach the executors.
 */
class AsyncConfigTest {

    private static DatabaseProperties propsWithEtlSize(int etlThreads) {
        // Every other component is null on purpose: the record's compact constructor fills in
        // defaults, which keeps the test to the one value it is about.
        return new DatabaseProperties(true, "h2", null, null, null, null, null,
                new DatabaseProperties.EtlProperties(etlThreads),
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void etlPoolWidthComesFromEtlThreadPoolSize() {
        AsyncConfig config = new AsyncConfig(ThreadPoolProperties.defaults(), propsWithEtlSize(7));

        ThreadPoolTaskExecutor etl = config.etlTaskExecutor();

        // Previously hardcoded at 2/4, so this key had no effect at all.
        assertThat(etl.getCorePoolSize()).isEqualTo(7);
        assertThat(etl.getMaxPoolSize()).isEqualTo(7);
        etl.shutdown();
    }

    @Test
    void asyncPoolWidthComesFromConfiguration() {
        ThreadPoolProperties pools = new ThreadPoolProperties(3, 6, 42, 0, 0, 0, 0);
        AsyncConfig config = new AsyncConfig(pools, propsWithEtlSize(4));

        ThreadPoolTaskExecutor async = (ThreadPoolTaskExecutor) config.getAsyncExecutor();

        assertThat(async.getCorePoolSize()).isEqualTo(3);
        assertThat(async.getMaxPoolSize()).isEqualTo(6);
        async.shutdown();
    }

    @Test
    void schedulerIsNotSingleThreadedByDefault() {
        AsyncConfig config = new AsyncConfig(ThreadPoolProperties.defaults(), propsWithEtlSize(4));

        ThreadPoolTaskScheduler scheduler = config.taskScheduler();

        // getPoolSize() reports live threads, which is 0 until something is scheduled — assert the
        // configured core size instead.
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .isEqualTo(ThreadPoolProperties.DEFAULT_SCHEDULER_SIZE);
        scheduler.shutdown();
    }

    @Test
    void asyncMaxBelowCoreIsRaisedToCoreRatherThanSilentlyClamped() {
        ThreadPoolProperties pools = new ThreadPoolProperties(8, 2, 0, 0, 0, 0, 0);

        assertThat(pools.asyncCoreSize()).isEqualTo(8);
        assertThat(pools.asyncMaxSize()).isEqualTo(8);
    }

    @Test
    void nonPositiveValuesFallBackToDefaults() {
        ThreadPoolProperties pools = ThreadPoolProperties.defaults();

        assertThat(pools.asyncCoreSize()).isEqualTo(ThreadPoolProperties.DEFAULT_ASYNC_CORE_SIZE);
        assertThat(pools.asyncMaxSize()).isEqualTo(ThreadPoolProperties.DEFAULT_ASYNC_MAX_SIZE);
        assertThat(pools.asyncQueueCapacity()).isEqualTo(ThreadPoolProperties.DEFAULT_ASYNC_QUEUE_CAPACITY);
        assertThat(pools.etlQueueCapacity()).isEqualTo(ThreadPoolProperties.DEFAULT_ETL_QUEUE_CAPACITY);
        assertThat(pools.federatedQuerySize()).isEqualTo(ThreadPoolProperties.DEFAULT_FEDERATED_QUERY_SIZE);
        assertThat(pools.catalogScanSize()).isEqualTo(ThreadPoolProperties.DEFAULT_CATALOG_SCAN_SIZE);
        assertThat(pools.schedulerSize()).isEqualTo(ThreadPoolProperties.DEFAULT_SCHEDULER_SIZE);
    }
}
