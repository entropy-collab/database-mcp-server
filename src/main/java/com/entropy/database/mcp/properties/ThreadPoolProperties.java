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

/**
 * Sizes for the process-wide thread pools.
 *
 * <p>Every pool here runs <b>blocking JDBC</b>, so widths are expressed as absolute numbers and
 * never derived from {@code availableProcessors()}: a blocking worker spends nearly all its time
 * waiting on a socket, so CPU count is the wrong unit. Sizing the federated fan-out by CPU count
 * meant a 2-vCPU container ran "parallel" cross-database queries two at a time.
 *
 * <p>ETL width is not here on purpose — it stays {@code entropy.mcp.database.etl.thread-pool-size},
 * which is the key that already exists. Only the queue in front of it is configured here.
 */
@ConfigurationProperties(prefix = "entropy.mcp.database.thread-pool")
public record ThreadPoolProperties(
        int asyncCoreSize,
        int asyncMaxSize,
        int asyncQueueCapacity,
        int etlQueueCapacity,
        int federatedQuerySize,
        int catalogScanSize,
        int schedulerSize
) {

    public static final int DEFAULT_ASYNC_CORE_SIZE = 4;
    public static final int DEFAULT_ASYNC_MAX_SIZE = 8;
    public static final int DEFAULT_ASYNC_QUEUE_CAPACITY = 100;
    public static final int DEFAULT_ETL_QUEUE_CAPACITY = 50;
    public static final int DEFAULT_FEDERATED_QUERY_SIZE = 10;
    public static final int DEFAULT_CATALOG_SCAN_SIZE = 4;
    public static final int DEFAULT_SCHEDULER_SIZE = 2;

    public ThreadPoolProperties {
        asyncCoreSize = asyncCoreSize > 0 ? asyncCoreSize : DEFAULT_ASYNC_CORE_SIZE;
        asyncMaxSize = asyncMaxSize > 0 ? asyncMaxSize : DEFAULT_ASYNC_MAX_SIZE;
        // A max below core is silently clamped by ThreadPoolTaskExecutor, which would hide a
        // misconfiguration; normalise it here so the effective values are the reported ones.
        asyncMaxSize = Math.max(asyncMaxSize, asyncCoreSize);
        asyncQueueCapacity = asyncQueueCapacity > 0 ? asyncQueueCapacity : DEFAULT_ASYNC_QUEUE_CAPACITY;
        etlQueueCapacity = etlQueueCapacity > 0 ? etlQueueCapacity : DEFAULT_ETL_QUEUE_CAPACITY;
        federatedQuerySize = federatedQuerySize > 0 ? federatedQuerySize : DEFAULT_FEDERATED_QUERY_SIZE;
        catalogScanSize = catalogScanSize > 0 ? catalogScanSize : DEFAULT_CATALOG_SCAN_SIZE;
        schedulerSize = schedulerSize > 0 ? schedulerSize : DEFAULT_SCHEDULER_SIZE;
    }

    public static ThreadPoolProperties defaults() {
        return new ThreadPoolProperties(0, 0, 0, 0, 0, 0, 0);
    }
}
