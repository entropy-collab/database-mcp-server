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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async configuration with dedicated thread pools.
 * Replaces Spring's default SimpleAsyncTaskExecutor to prevent thread leakage.
 *
 * <p>All three pools run blocking JDBC, so their widths come from configuration rather than from
 * {@code availableProcessors()} — see {@link ThreadPoolProperties}.
 *
 * <p><strong>这个类必须保留 {@code proxyBeanMethods = true}（默认值），不要照着
 * {@code DatabaseConfig} 一起改。</strong>{@link #getAsyncExecutor()} 同时是
 * {@link AsyncConfigurer} 的接口方法和一个 {@code @Bean} 方法。关掉 CGLIB 代理之后，
 * {@code AsyncAnnotationBeanPostProcessor} 通过接口调用它时会绕过容器，当场 new 出第二个
 * {@code ThreadPoolTaskExecutor}——而且这个实例的 {@code initialize()} 永远不会被调用。
 * 于是 {@code @Async} 用的是一个野生线程池，{@code taskExecutor} bean 那个反而空转，
 * 两者的配置和监控指标都对不上，且不报任何错。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    private final ThreadPoolProperties pools;
    private final DatabaseProperties databaseProperties;

    public AsyncConfig(ThreadPoolProperties pools, DatabaseProperties databaseProperties) {
        this.pools = pools != null ? pools : ThreadPoolProperties.defaults();
        this.databaseProperties = databaseProperties;
    }

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        /*
         * 包一层 DelegatingSecurityContextAsyncTaskExecutor：把调用者的 SecurityContext 带过线程边界。
         *
         * 不包的后果不是"少一个特性"，而是审计表的 principal 列<b>恒为空</b>：QueryAuditLoggerImpl.log
         * 是 @Async 的，方法体已经在这个池的线程上跑，而 SecurityContextHolder 是 ThreadLocal——
         * 池线程上取到的是空上下文。症状是审计流水每一行都记得住"做了什么"却记不住"谁做的"，
         * 而且不报任何错。
         *
         * 包装器在任务结束后会清掉池线程上的上下文，所以线程复用不会串身份。
         */
        return new DelegatingSecurityContextAsyncTaskExecutor(asyncThreadPool());
    }

    /**
     * {@code @Async} 真正的线程池。单独抽出来是为了让它的宽度可以被直接断言——
     * {@link #getAsyncExecutor()} 返回的是包装器，拿不到里面的池。
     */
    ThreadPoolTaskExecutor asyncThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pools.asyncCoreSize());
        executor.setMaxPoolSize(pools.asyncMaxSize());
        executor.setQueueCapacity(pools.asyncQueueCapacity());
        executor.setThreadNamePrefix("mcp-async-");
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        // Audit and compliance logging run on this pool. The default AbortPolicy would drop
        // those tasks silently once the queue fills, producing gaps in the audit trail with no
        // trace. CallerRuns degrades latency instead of losing records.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("Async pool: core={}, max={}, queue={}",
                pools.asyncCoreSize(), pools.asyncMaxSize(), pools.asyncQueueCapacity());
        return executor;
    }

    /**
     * Dedicated pool for ETL job execution.
     *
     * <p>Kept separate from {@code taskExecutor} on purpose: an ETL job can run for minutes and
     * would otherwise occupy every worker plus the queue, starving the short-lived
     * {@code @Async} audit writes that share the pool.
     *
     * <p>Width comes from {@code entropy.mcp.database.etl.thread-pool-size}. That key existed and
     * reached {@code EtlConfig}, but nothing ever read it — the pool was hardcoded at 2/4, so
     * raising the documented knob changed nothing. Core equals max because an ETL worker is
     * blocked on JDBC, not on CPU: growing the pool only under queue pressure would leave
     * configured capacity unused while jobs queue.
     */
    @Bean(name = "etlTaskExecutor")
    public ThreadPoolTaskExecutor etlTaskExecutor() {
        int size = databaseProperties.etl().threadPoolSize();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(pools.etlQueueCapacity());
        executor.setThreadNamePrefix("mcp-etl-");
        executor.setKeepAliveSeconds(120);
        // ETL submission must fail loudly rather than block the MCP request thread.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("ETL pool: size={}, queue={}", size, pools.etlQueueCapacity());
        return executor;
    }

    /**
     * Scheduler for {@code @Scheduled} tasks.
     *
     * <p>Without this bean Spring falls back to a single-threaded scheduler whose threads are
     * unnamed and whose uncaught exceptions surface only as a generic log line. BYOK cleanup
     * closes Hikari pools, which blocks; on one thread any second scheduled task would wait
     * behind it, and a thrown exception would cancel a {@code fixedRate} task permanently.
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(pools.schedulerSize());
        scheduler.setThreadNamePrefix("mcp-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setErrorHandler(t ->
                log.error("Scheduled task threw {}: {}", t.getClass().getSimpleName(), t.getMessage(), t));
        scheduler.initialize();
        log.info("Scheduler pool: size={}", pools.schedulerSize());
        return scheduler;
    }

    /**
     * Global async exception handler — logs uncaught exceptions from @Async methods
     * so they are not silently swallowed by Spring's default handler.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncUncaughtExceptionHandler() {
            @Override
            public void handleUncaughtException(Throwable ex, java.lang.reflect.Method method, Object... params) {
                log.error("Async method {} threw uncaught exception: {}: {} | args={}",
                        method.getName(),
                        ex.getClass().getSimpleName(),
                        ex.getMessage(),
                        Arrays.toString(params),
                        ex);
            }
        };
    }
}
