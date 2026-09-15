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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpLeaseExpiredException;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.monitor.HikariPoolStats;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.properties.ByokProperties;
import com.entropy.database.mcp.util.JdbcUrlMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import org.springframework.beans.factory.DisposableBean;

/**
 * Central manager for all datasources.
 * Handles lifecycle management with TTL-based lease renewal.
 * All connections are equal BYOK connections; there is no primary/default concept.
 *
 * <p>Uses {@link ByokDataSourceFactory} (factory pattern) to create per-connection
 * infrastructure, following Spring's DataSourceBuilder pattern.
 */
public class DynamicDataSourceManagerImpl implements DynamicDataSourceManager, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceManagerImpl.class);

    private final DialectResolver dialectResolver;
    private final ByokDataSourceFactory dataSourceFactory;
    private final com.github.benmanes.caffeine.cache.Cache<String, LeasedDataSource> leasedCache;

    /**
     * Everything we know about a live connection, as one immutable value.
     *
     * <p>Metadata, the read-only flag and the content fingerprint used to be three separate
     * collections keyed by connection name. Caffeine's {@code removalListener} is asynchronous
     * (it runs on {@link ForkJoinPool#commonPool()} by default), so a lease that expired could have
     * its callback delivered <em>after</em> a business thread had already rebuilt the connection under
     * the same name - and the callback then deleted the fresh entry's read-only flag by name. Since
     * {@code McpToolExceptionAspect} treats a missing flag as "writable", a read-only connection
     * silently became writable.
     *
     * <p>Binding the three facts to the {@link LeasedDataSource} that owns them turns that race into a
     * no-op: a late callback can only remove state it still owns (see
     * {@link #unregisterIfOwnedBy(String, LeasedDataSource)}).
     *
     * @param owner       the leased datasource these facts describe; identity, not equality, decides
     *                    ownership
     * @param metadata    what {@code listConnections} reports
     * @param readonly    whether write tools must be rejected for this connection
     * @param fingerprint content fingerprint for pool de-duplication, {@code null} for externally
     *                    managed datasources that are never de-duplicated
     */
    private record ConnectionRegistration(LeasedDataSource owner,
                                          ConnectionMetadata metadata,
                                          boolean readonly,
                                          String fingerprint) {
    }

    /** connection name → its registration. Mutated only under {@link #registryLock}. */
    private final Map<String, ConnectionRegistration> registrations = new ConcurrentHashMap<>();

    /** content fingerprint → canonical connection name. Mutated only under {@link #registryLock}. */
    private final Map<String, String> contentFingerprintToKey = new ConcurrentHashMap<>();

    /**
     * Fixed set of striped monitors guarding per-key connection creation and eviction.
     *
     * <p>Striping rather than a per-key map with eviction: an evictable lock map can hand two
     * threads two <em>different</em> monitors for the same key (entry expires between the two
     * lookups), silently losing mutual exclusion. A fixed array always maps a key to the same
     * monitor and cannot grow without bound. The cost is that unrelated keys sharing a stripe
     * serialize occasionally, which is harmless for pool creation.
     */
    private static final int LOCK_STRIPES = 64;
    private final Object[] keyLocks = new Object[LOCK_STRIPES];

    /** Guards {@link #registrations} and {@link #contentFingerprintToKey} as one unit. */
    private final Object registryLock = new Object();

    /**
     * connection name → 正在进行中的建池动作。
     *
     * <p>建池（{@code ByokDataSourceFactory.create} 里的 Hikari 初始化与首连接探测）过去在条带锁内执行，目标库
     * 不可达时这段是 TCP 超时量级，同条带（64 分之一）的其它连接名全部串行等在后面——这是可用性问题，不是死锁。
     * 现在锁内只做「查缓存 / 认领指纹 / 放占位」这些纯 map 操作，真正建池搬到锁外，同 key 的并发者 join 同一个
     * future。
     *
     * <p>否决了「锁外各自建池、再用 {@code putIfAbsent} 竞争、失败者关掉自己多建的池」：那个写法在目标库慢的时候
     * 会把 N 个并发调用变成 N 次真实建连接（正是我们想避免的开销），而且「同一个 key 只建一个池」从此只能靠
     * 「最终只留下一个」来近似断言，测不出白建的那几个。
     *
     * <p>条目由建池方在 {@code finally} 里按身份移除（{@code remove(key, mine)}），所以这个 map 不会随历史连接名
     * 增长。
     */
    private final Map<String, CompletableFuture<LeasedDataSource>> inFlightCreations = new ConcurrentHashMap<>();

    /**
     * 「连接名已失效」的订阅者，见 {@link DynamicDataSourceManager#addEvictionListener}。
     *
     * <p>{@link CopyOnWriteArrayList} 而不是加锁的 ArrayList：注册只发生在启动阶段（每个上层缓存一次），遍历
     * 却发生在每次驱逐，且遍历时不能持有任何锁——回调是别人的代码，在锁内调用它就把外部实现的耗时纳入了本类的
     * 锁序。
     */
    private final List<EvictionListener> evictionListeners = new CopyOnWriteArrayList<>();

    private final Duration leaseDuration;
    private final Duration maxLifetime;
    private final int maxCachedConnections;
    private final ByokProperties byokProperties;
    private final McpMetricsCollector metricsCollector;

    /**
     * Dependencies record for DynamicDataSourceManagerImpl.
     * Groups constructor parameters to simplify bean definition.
     *
     * @param cacheMaintenanceExecutor executor Caffeine uses for eviction bookkeeping and removal
     *                                 notifications; {@code null} keeps Caffeine's default
     *                                 {@link ForkJoinPool#commonPool()}. Tests inject a deterministic
     *                                 executor to reproduce late-callback interleavings.
     */
    public record Dependencies(
            DialectResolver dialectResolver,
            ByokDataSourceFactory dataSourceFactory,
            ByokProperties byokProperties,
            McpMetricsCollector metricsCollector,
            Executor cacheMaintenanceExecutor) {

        public Dependencies(DialectResolver dialectResolver,
                            ByokDataSourceFactory dataSourceFactory,
                            ByokProperties byokProperties,
                            McpMetricsCollector metricsCollector) {
            this(dialectResolver, dataSourceFactory, byokProperties, metricsCollector, null);
        }
    }

    public DynamicDataSourceManagerImpl(Dependencies deps) {
        this.dialectResolver = deps.dialectResolver();
        this.dataSourceFactory = deps.dataSourceFactory();
        this.byokProperties = deps.byokProperties();
        this.leaseDuration = deps.byokProperties().leaseDuration();
        this.maxLifetime = deps.byokProperties().maxLifetime();
        this.maxCachedConnections = deps.byokProperties().maxCachedConnections();
        this.metricsCollector = deps.metricsCollector();

        java.util.Arrays.setAll(this.keyLocks, i -> new Object());

        Executor cacheExecutor = deps.cacheMaintenanceExecutor() != null
                ? deps.cacheMaintenanceExecutor()
                : ForkJoinPool.commonPool();

        // Pinned connections (entropy.mcp.database.connections) must survive for the life of the
        // process. A flag on LeasedDataSource is not enough: expireAfterAccess and maximumSize are
        // Caffeine's own policies and ignore anything on the value. Both are therefore replaced by
        // pinned-aware equivalents — otherwise a configured Oracle connection vanishes after one
        // idle hour, or gets squeezed out once max-cached-connections BYOK pools show up.
        this.leasedCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumWeight(maxCachedConnections)
                .weigher((String key, LeasedDataSource value) -> value.isPinned() ? 0 : 1)
                .expireAfter(new com.github.benmanes.caffeine.cache.Expiry<String, LeasedDataSource>() {
                    @Override
                    public long expireAfterCreate(String key, LeasedDataSource value, long currentTime) {
                        return value.isPinned() ? Long.MAX_VALUE : leaseDuration.toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, LeasedDataSource value,
                                                  long currentTime, long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, LeasedDataSource value,
                                                long currentTime, long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }
                })
                .executor(cacheExecutor)
                .removalListener(this::onCacheRemoval)
                .build();
    }

    /**
     * Removal callback for every cause (expiry, size, explicit invalidation, replacement).
     *
     * <p>Deliberately does <em>not</em> take the striped lock. It used to, to keep a concurrent
     * {@code acquire()} from handing out a datasource that is being closed - but that never worked,
     * because the fast path of {@code acquire()} reads the cache before taking the lock. What actually
     * makes this safe is that all state removal is scoped to {@code value}'s identity, so a callback
     * that arrives after the connection was rebuilt cannot touch the new connection. Staying off the
     * lock also means pool shutdown never blocks unrelated keys that hash to the same stripe.
     *
     * <p>关闭动作走 {@link #closeIfUnreferenced(LeasedDataSource)}：一个池可能同时挂在 canonical 名字和
     * 若干别名下，别名过期时无条件 close 会把 canonical 正在用的池一起关掉。
     *
     * <p>这里是唯一的「连接名彻底消失」汇聚点（过期、体积淘汰、显式 invalidate、被替换、shutdown 都会到这里），
     * 所以 {@link #notifyEvicted(String)} 也放在这里，上层按名字缓存的派生对象才有机会跟着释放。
     */
    private void onCacheRemoval(String key,
                                LeasedDataSource value,
                                com.github.benmanes.caffeine.cache.RemovalCause cause) {
        if (key != null && value != null) {
            unregisterIfOwnedBy(key, value);
        }
        if (metricsCollector != null) {
            metricsCollector.recordByokConnectionRemoved();
        }
        if (value != null) {
            log.info("Removing datasource: {} (cause: {})", key, cause);
            closeIfUnreferenced(value);
        }
        if (key != null) {
            notifyEvicted(key);
        }
    }

    /**
     * 广播「连接名已失效」。
     *
     * <p>不持任何锁，且逐个吞掉监听器抛出的异常：监听器是上层（features 层的 facade 缓存）注册进来的外部代码，
     * 它出错不该让池的清理半途而废，更不该把自己的耗时算进本类的锁序。
     *
     * <p>会不会误报？{@code RemovalCause.REPLACED} 也会走到这里——池被同名的新池替换时，上层刚好可能已经缓存了
     * 指向<em>新</em>上下文的 facade，于是它被白清一次。代价只是下一次调用重新 new 一个瘦包装对象；反过来，为了
     * 躲这一次白清而把 REPLACED 排除掉，就得假定「替换必然伴随一次 acquire」——{@code registerPinned} 的替换路径
     * 并不满足这个假定，漏掉的那次就是永久泄漏。
     */
    private void notifyEvicted(String key) {
        for (EvictionListener listener : evictionListeners) {
            try {
                listener.onConnectionEvicted(key);
            } catch (RuntimeException e) {
                log.warn("Eviction listener failed for connection '{}': {}", key, e.getMessage(), e);
            }
        }
    }

    @Override
    public void addEvictionListener(EvictionListener listener) {
        if (listener != null) {
            evictionListeners.add(listener);
        }
    }

    /**
     * Resolve the striped monitor for a key. Always returns the same monitor for the same key.
     */
    private Object lockFor(String key) {
        return keyLocks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    /**
     * 一次 {@link #acquire(String, ConnectionProperties)} 最多尝试几轮。
     *
     * <p>2 = 一次正常尝试 + 一次自愈重试，而不是 {@code while (true)}：会触发重试的两种情况（交付前发现池已被
     * 移除回调关掉、名字在我们建池期间被 {@code registerPinned}/{@code registerExisting} 抢走）都是一次性窗口，
     * 重来一轮必然落到新条目上。如果重来还是不行，说明有东西在稳定地关闭这个名字，无限重试只会把一次可诊断的
     * 报错变成一次挂死。
     */
    private static final int MAX_ACQUIRE_ATTEMPTS = 2;

    /**
     * Acquire a datasource context by key.
     * Uses content fingerprint to deduplicate: if the same physical connection (jdbcUrl+username+dialect)
     * is already cached under a different name, this name becomes an alias to the existing pool.
     *
     * <p>整个流程被拆成「有界重试 + 单轮尝试」，因为快路径与移除回调之间存在一个真实的竞态：回调不持条带锁
     * （理由见 {@link #onCacheRemoval}），所以 {@code renewLease()} 返回之后、调用方真正
     * {@code getConnection()} 之前，池可能已经被关掉，业务侧看到的是 "HikariDataSource has been closed"，而缓存
     * 条目此时已经消失、下一次调用又正常——典型的间歇性、不可复现报错。
     */
    @Override
    public ByokDataSourceContext acquire(String key, ConnectionProperties connection) {
        guardJdbcUrl(key, connection.jdbcUrl());

        for (int attempt = 1; attempt <= MAX_ACQUIRE_ATTEMPTS; attempt++) {
            ByokDataSourceContext context = attemptAcquire(key, connection);
            if (context != null) {
                return context;
            }
            log.warn("Datasource {} could not be handed out (pool closed concurrently), retrying {}/{}",
                    key, attempt, MAX_ACQUIRE_ATTEMPTS);
        }
        throw new McpToolException(ErrorCode.CONNECTION_FAILED,
                "Connection '" + key + "' could not be handed out: its pool was closed concurrently on "
                        + MAX_ACQUIRE_ATTEMPTS + " consecutive attempts", key);
    }

    /**
     * 一轮获取尝试。
     *
     * @return 可以交给调用方的上下文；{@code null} 表示这一轮拿到的东西已经不可用（池在交付前被关闭，或名字被
     *         别的注册路径抢走），状态已经清理干净，由 {@link #acquire} 再来一轮
     */
    private ByokDataSourceContext attemptAcquire(String key, ConnectionProperties connection) {
        String fingerprint = connection.getCacheKey();

        // Check if an identical physical connection already exists under a different name
        String canonicalKey = contentFingerprintToKey.get(fingerprint);
        if (canonicalKey != null && !canonicalKey.equals(key)) {
            ByokDataSourceContext adopted = adoptAlias(key, canonicalKey, connection);
            if (adopted != null) {
                return adopted;
            }
        }

        // 快路径：不取任何锁读缓存。这里不再为「过期后驱逐」额外取条带锁——evictIfCurrent 的每一步都按对象身份
        // 收口（见其注释），条带锁在这条路径上没有保护任何东西，只是让 Hikari 的 close 挡住同条带的其它连接名。
        ByokDataSourceContext reused = reuseCached(key, leasedCache.getIfPresent(key));
        if (reused != null) {
            return reused;
        }
        return createOrJoin(key, connection, fingerprint);
    }

    /**
     * 复用缓存里已有的条目。
     *
     * <p>{@code null} 有两种含义，但对调用方是同一件事「没有可复用的池，去建一个」：条目本来就不存在，或者条目
     * 已经过期/已关闭并在这里被摘掉了。
     */
    private ByokDataSourceContext reuseCached(String key, LeasedDataSource cached) {
        if (cached == null) {
            return null;
        }
        if (!cached.isClosed()) {
            try {
                return handOff(key, cached, cached.renewLease());
            } catch (McpLeaseExpiredException e) {
                log.warn("Datasource {} exceeded max lifetime, evicting and recreating", key);
            }
        } else {
            // 移除回调正在关这个池
            log.warn("Datasource {} is closed (eviction in progress), evicting and recreating", key);
        }
        evictIfCurrent(key, cached);
        return null;
    }

    /**
     * 交付前的最后一道复查：{@code renewLease()} 返回之后池仍然活着，才把上下文交出去。
     *
     * <p>这只把竞态窗口从「整个租约过期窗口」缩到「复查与调用方 getConnection() 之间的几纳秒」，并没有彻底消除。
     * 彻底消除需要引用计数式的借还协议（借出期间不许 close，close 改成摘条目 + 延迟关池给已发出的引用一个
     * grace）——否决它的理由有三条：调用方遍布 tools/features，没有任何一层现在有「用完归还」的生命周期，漏还一
     * 次就是永久泄漏一个池；延迟关闭需要一个本类目前没有的调度器；而 {@code shutdown()} 作为
     * {@code DisposableBean} 必须同步关完，延迟关闭会让进程退出时留下活连接。
     *
     * @return {@code context}，或者 {@code null} 表示池已经关闭、条目已被摘除，交给上层重试
     */
    private ByokDataSourceContext handOff(String key, LeasedDataSource leased, ByokDataSourceContext context) {
        if (!leased.isClosed()) {
            return context;
        }
        evictIfCurrent(key, leased);
        return null;
    }

    /**
     * 建池，或者搭上同 key 已经在进行中的那次建池。
     *
     * <p>锁的使用分成两段：占位与复查用 {@link #inFlightCreations}（CHM 的 putIfAbsent 本身就是原子的），发布
     * （register + 写缓存）才取条带锁——发布是纯 map 操作，而条带锁要挡住的是同样持条带锁的
     * {@code registerPinned}/{@code registerExisting}。建池本身在两段之间，不持任何锁。
     *
     * <p>因此「占位后复查缓存」和「发布前再复查一次缓存」两处复查都不可省：占位只保证同一时刻同一个 key 只有一个
     * 建池方，不保证建池期间没有别的路径占用这个名字。
     */
    private ByokDataSourceContext createOrJoin(String key, ConnectionProperties connection, String fingerprint) {
        CompletableFuture<LeasedDataSource> mine = new CompletableFuture<>();
        CompletableFuture<LeasedDataSource> inFlight = inFlightCreations.putIfAbsent(key, mine);
        if (inFlight != null) {
            return joinCreation(key, inFlight);
        }
        try {
            // 抢到占位之后再复查：占位挡不住「上一个建池方刚刚发布完就退出」
            ByokDataSourceContext reused = reuseCached(key, leasedCache.getIfPresent(key));
            if (reused != null) {
                return reused;
            }

            // Re-check canonical key in case another thread created it
            String canonicalKey = contentFingerprintToKey.get(fingerprint);
            if (canonicalKey != null && !canonicalKey.equals(key)) {
                ByokDataSourceContext adopted = adoptAlias(key, canonicalKey, connection);
                if (adopted != null) {
                    return adopted;
                }
            }

            // 建池在锁外：createLeasedDataSource 里是 Hikari 初始化（视配置做首连接探测），dialectResolver 传
            // DataSource 时同样会 getConnection()，目标库不可达时这两步都是 TCP 超时量级。放在条带锁内会让同条带
            // （64 分之一）的其它连接名全部串行等在后面。
            DatabaseDialect dialect = dialectResolver.resolve(connection.dialect(), null);
            LeasedDataSource newLeased = createLeasedDataSource(key, connection, dialect);

            boolean published;
            synchronized (lockFor(key)) {
                LeasedDataSource concurrent = leasedCache.getIfPresent(key);
                // 建池期间这个名字被别的路径占了（典型是启动阶段的 registerPinned）。让它赢：pinned 连接绝不能
                // 被一个带租约的池覆盖掉。
                published = concurrent == null || concurrent == newLeased || concurrent.isClosed();
                if (published) {
                    // Register before publishing to the cache: the other order leaves a window in which a
                    // concurrent acquire() finds the context but isReadonly() still answers false, which for a
                    // read-only connection means write tools are let through.
                    register(key, newLeased, connection, dialect.getClass().getSimpleName(), fingerprint);
                    leasedCache.put(key, newLeased);
                    mine.complete(newLeased);
                }
            }
            if (!published) {
                log.warn("Connection '{}' was taken by another registration path while its pool was being "
                        + "built; discarding the pool we just created", key);
                // 关池在锁外，理由同上
                newLeased.close();
                return null;
            }
            return handOff(key, newLeased, newLeased.renewLease());
        } catch (RuntimeException | Error e) {
            // 让等待方拿到和我们一样的失败原因，而不是各自再去连一次不可达的库
            mine.completeExceptionally(e);
            throw e;
        } finally {
            // 先摘占位再兜底完成：顺序反过来的话，刚好在这两步之间挂上来的等待方会拿到一个永远不会完成的 future。
            // complete(null) 对已完成的 future 是 no-op，只用于覆盖上面那些「复用/别名」提前 return 的分支。
            inFlightCreations.remove(key, mine);
            mine.complete(null);
        }
    }

    /**
     * 等同 key 正在进行中的那次建池。
     *
     * <p>等待发生在锁外——等待方既不持条带锁也不持 {@code registryLock}，建池方也只在发布那一小段取条带锁，所以
     * 这里不引入新的锁序。
     *
     * @return 建池方发布出来的上下文；{@code null} 表示建池方最后没有发布（走了复用/别名/被抢占分支），由
     *         {@link #acquire} 的有界重试再来一轮
     */
    private ByokDataSourceContext joinCreation(String key, CompletableFuture<LeasedDataSource> inFlight) {
        LeasedDataSource shared;
        try {
            shared = inFlight.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new McpToolException(ErrorCode.CONNECTION_FAILED,
                    "Connection '" + key + "' failed to initialise: " + cause.getMessage(), cause, key);
        }
        if (shared == null) {
            return null;
        }
        return handOff(key, shared, shared.renewLease());
    }

    /**
     * Let {@code key} share the pool that {@code canonicalKey} already owns.
     *
     * <p>指纹命中过去只是 {@code return existing.renewLease()}，既不注册也不写缓存，于是别名连接对外
     * 等于不存在：{@code acquire(alias)} 抛 "Connection not found"、{@code isReadonly(alias)} 是 false
     * （readonly 连接的写工具会被放过）、{@code listConnections} 也看不到它。这里补上注册与缓存写入，
     * 顺序仍然是「先 register 再 put」，理由见 {@link #acquire(String, ConnectionProperties)}。
     *
     * <p>别名的 fingerprint 传 {@code null}：指纹归属留给 canonical，否则两个名字会在
     * {@link #unregisterIfOwnedBy(String, LeasedDataSource)} 里互相删对方的索引项，最终指纹索引指向一个
     * 已经消失的名字。代价是 canonical 先过期时指纹索引会清空，同内容的下一个名字会另开一个池——只是少了
     * 一次去重，不会出错。
     *
     * <p>这里<em>不</em>取任何条带锁（两个调用点也都不再持锁：建池已经搬到锁外，见 {@link #createOrJoin}）。
     * 一旦在这里去拿 canonical 的锁，两个名字互为对方指纹 canonical 时就会出现「A 等 B、B 等 A」的锁序环。不加锁
     * 是安全的，因为所有状态改动都按对象身份收口：{@code evictIfCurrent} 只在缓存里仍是同一个对象时才移除，注册
     * 完成后又会复查 {@code isClosed()} 以防和过期回调撞车。
     *
     * @return 复用成功时的上下文；{@code null} 表示这次没能复用（canonical 已过期/已关闭/归属不一致），
     *         调用方应继续走新建流程
     */
    private ByokDataSourceContext adoptAlias(String key, String canonicalKey, ConnectionProperties connection) {
        LeasedDataSource shared = leasedCache.getIfPresent(canonicalKey);
        if (shared == null) {
            return null;
        }
        if (shared.isClosed()) {
            log.warn("Canonical connection '{}' is closed, evicting", canonicalKey);
            evictIfCurrent(canonicalKey, shared);
            return null;
        }
        ConnectionRegistration canonical = registrations.get(canonicalKey);
        if (canonical == null || canonical.owner() != shared) {
            // 指纹索引与实际归属已经不一致：宁可新建一个池，也不要把别名挂到来历不明的连接上
            log.warn("Canonical connection '{}' has no matching registration, not aliasing '{}'",
                    canonicalKey, key);
            return null;
        }
        ByokDataSourceContext context;
        try {
            context = shared.renewLease();
        } catch (McpLeaseExpiredException e) {
            log.warn("Canonical connection '{}' expired, recreating", canonicalKey);
            evictIfCurrent(canonicalKey, shared);
            return null;
        }

        register(key, shared, connection, canonical.metadata().dialect(), null);
        leasedCache.put(key, shared);
        log.info("Connection '{}' is an alias for existing connection '{}', reusing same pool",
                key, canonicalKey);

        if (shared.isClosed()) {
            // 移除回调不持锁，所以它可能正好在我们注册期间关掉了这个池。别名不能对外暴露一个死池。
            log.warn("Canonical connection '{}' was closed while aliasing '{}', discarding alias",
                    canonicalKey, key);
            evictIfCurrent(key, shared);
            return null;
        }
        return context;
    }

    /**
     * Acquire an existing datasource context by key.
     * Throws IllegalArgumentException if the connection does not exist.
     */
    @Override
    public ByokDataSourceContext acquire(String key) {
        LeasedDataSource existing = leasedCache.getIfPresent(key);
        if (existing == null) {
            throw new IllegalArgumentException("Connection not found: " + key +
                    ". Use createNamedConnection first.");
        }
        try {
            return existing.renewLease();
        } catch (McpLeaseExpiredException e) {
            log.warn("Datasource {} exceeded max lifetime", key);
            throw new IllegalArgumentException("Connection expired: " + key, e);
        }
    }

    /**
     * Register an existing datasource as a BYOK connection.
     * The datasource is NOT closed when the lease expires (managed externally).
     */
    @Override
    public void registerExisting(String key, DataSource existingDataSource, DatabaseDialect dialect) {
        guardJdbcUrl(key, jdbcUrlOf(existingDataSource));

        // createExisting 只是包一层已有的 DataSource，不建池、不连库，所以留在锁内没有可用性代价。
        LeasedDataSource previous;
        synchronized (lockFor(key)) {
            previous = leasedCache.getIfPresent(key);
            if (previous != null) {
                log.warn("Datasource {} already registered, replacing", key);
                detachIfCurrent(key, previous);
            }

            ByokDataSourceContext context = dataSourceFactory.createExisting(key, existingDataSource, dialect);
            LeasedDataSource leased = new LeasedDataSource(key, context, leaseDuration, maxLifetime, false);

            // Same ordering rule as acquire(): registry first, cache second.
            register(key, leased, null, dialect.getClass().getSimpleName(), null);
            leasedCache.put(key, leased);

            log.info("Registered existing datasource as BYOK connection: {}", key);
            if (metricsCollector != null) {
                metricsCollector.recordByokConnectionCreated();
            }
        }
        // 关池搬到锁外：HikariDataSource.close() 会等在用连接归还，锁内做会挡住同条带的其它连接名
        if (previous != null) {
            closeIfUnreferenced(previous);
        }
    }

    @Override
    public void registerPinned(String key, ConnectionProperties connection) {
        // Configured connections go through the same URL guard as caller-supplied ones. The guard's
        // real job is rejecting code-execution parameters (H2 INIT/RUNSCRIPT, MySQL
        // allowLoadLocalInfile); a typo or a copy-pasted URL in application.yml deserves that check
        // just as much as a tool call does.
        guardJdbcUrl(key, connection.jdbcUrl());

        DatabaseDialect dialect = dialectResolver.resolve(connection.dialect(), null);

        // 建池在锁外，理由同 createOrJoin：启动阶段目标库不可达时，这一步是 TCP 超时量级，锁内做会把同条带的其它
        // 配置连接一起堵住，进而拖长整个 ApplicationContext 的启动。
        ByokDataSourceContext context = dataSourceFactory.create(key, connection, dialect);
        LeasedDataSource previous = null;
        try {
            LeasedDataSource leased = LeasedDataSource.pinned(key, context);
            synchronized (lockFor(key)) {
                previous = leasedCache.getIfPresent(key);
                if (previous != null) {
                    log.warn("Connection {} already registered, replacing with the configured one", key);
                    detachIfCurrent(key, previous);
                }

                // Same ordering rule as acquire(): registry first, cache second.
                // Fingerprint is deliberately null: a pinned pool must not become the alias target of
                // a later BYOK acquire() with matching credentials, or that caller would silently
                // inherit a connection that never expires.
                register(key, leased, connection, dialect.getDialectName(), null);
                leasedCache.put(key, leased);
            }

            log.info("Registered pinned connection '{}' (dialect={}, readonly={})",
                    key, dialect.getDialectName(), connection.readonly());
            if (metricsCollector != null) {
                metricsCollector.recordByokConnectionCreated();
            }
        } catch (Exception e) {
            try {
                context.closePool();
            } catch (Exception closeEx) {
                log.warn("Failed to close pool after failed pinned registration: {}", key, closeEx);
            }
            throw e;
        }
        // 关掉被替换的旧池，同样在锁外
        if (previous != null) {
            closeIfUnreferenced(previous);
        }
    }

    /**
     * Create a new LeasedDataSource for the given key and connection.
     */
    private LeasedDataSource createLeasedDataSource(String key, ConnectionProperties connection,
                                                   DatabaseDialect dialect) {
        log.info("Creating new datasource: {}", key);

        ByokDataSourceContext context = null;
        try {
            context = dataSourceFactory.create(key, connection, dialect);

            LeasedDataSource leased = new LeasedDataSource(
                    key, context, leaseDuration, maxLifetime
            );
            if (metricsCollector != null) {
                metricsCollector.recordByokConnectionCreated();
            }
            return leased;
        } catch (Exception e) {
            // Close any partially created resources
            if (context != null) {
                try {
                    context.closePool();
                } catch (Exception closeEx) {
                    log.warn("Failed to close context after creation failure: {}", key, closeEx);
                }
            }
            throw e;
        }
    }

    // ─── JDBC URL guard ─────────────────────────────────────────────────────

    /**
     * Apply {@link ByokProperties.UrlGuard} to a caller-supplied JDBC URL.
     *
     * <p>This is the chokepoint every connection registration passes through, which is why the guard
     * lives here rather than in {@link ConnectionProperties#validate()}: that method is a value-object
     * self-check with no access to configuration and is only called by one tool, so a URL reaching the
     * manager by any other route would skip it.
     *
     * @throws McpValidationException if the URL violates the policy. The message names the offending
     *                                parameter or host but never echoes the URL, which usually carries
     *                                the password.
     */
    private void guardJdbcUrl(String key, String jdbcUrl) {
        String violation = byokProperties.urlGuard().findViolation(jdbcUrl);
        if (violation != null) {
            log.warn("Rejected connection '{}': {}", key, violation);
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "Connection '" + key + "' was rejected: " + violation);
        }
    }

    /**
     * Best-effort JDBC URL of an externally created datasource, so that
     * {@link #registerExisting(String, DataSource, DatabaseDialect)} is guarded too. Returns
     * {@code null} when the URL cannot be determined, in which case there is nothing to guard: the
     * pool was configured by the host application, not by a caller-supplied string.
     */
    private static String jdbcUrlOf(DataSource dataSource) {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            try {
                return hikari.getJdbcUrl();
            } catch (RuntimeException e) {
                log.debug("Could not read jdbcUrl from external datasource: {}", e.getMessage());
            }
        }
        return null;
    }

    // ─── Connection Registry ────────────────────────────────────────────────

    /**
     * Publish metadata, read-only flag and fingerprint for {@code owner} as one atomic unit.
     *
     * @param dialectName 上报给 {@code listConnections} 的方言名。别名路径直接沿用 canonical 已经登记好的
     *                    名字，省掉一次 {@code DialectResolver} 解析，也避免两个名字显示成不同方言
     * @param fingerprint 指纹归属；别名传 {@code null}，见 {@link #adoptAlias}
     */
    private void register(String key, LeasedDataSource owner, ConnectionProperties connection,
                          String dialectName, String fingerprint) {
        var metadata = new ConnectionMetadata(
                key,
                dialectName,
                JdbcUrlMasker.mask(connection != null ? connection.jdbcUrl() : "external"),
                "system",
                java.time.Instant.now(),
                leaseDuration,
                maxLifetime,
                byokProperties.poolSize(),
                0
        );
        boolean readonly = connection != null && Boolean.TRUE.equals(connection.readonly());
        var registration = new ConnectionRegistration(owner, metadata, readonly, fingerprint);

        synchronized (registryLock) {
            ConnectionRegistration previous = registrations.put(key, registration);
            if (previous != null && previous.fingerprint() != null
                    && !previous.fingerprint().equals(fingerprint)) {
                contentFingerprintToKey.remove(previous.fingerprint(), key);
            }
            if (fingerprint != null) {
                contentFingerprintToKey.put(fingerprint, key);
            }
        }
        log.debug("Registered connection: {} -> {} (readonly={})", key, metadata, readonly);
    }

    /**
     * Drop the registration for {@code key} only while it still belongs to {@code owner}.
     *
     * <p>The identity check is the whole point: a removal notification for a lease that expired can be
     * delivered long after the connection was rebuilt, and a by-name removal would then strip the
     * fresh connection of its read-only flag and metadata.
     *
     * @return whether anything was removed
     */
    private boolean unregisterIfOwnedBy(String key, LeasedDataSource owner) {
        synchronized (registryLock) {
            ConnectionRegistration current = registrations.get(key);
            if (current == null || current.owner() != owner) {
                return false;
            }
            registrations.remove(key);
            if (current.fingerprint() != null) {
                contentFingerprintToKey.remove(current.fingerprint(), key);
            }
            return true;
        }
    }

    /**
     * Evict {@code value} and close it, but only remove the cache entry while it still holds exactly
     * that value - a plain {@code invalidate(key)} would throw away a replacement another thread has
     * already installed.
     *
     * <p>不要在条带锁内调用：{@link #closeIfUnreferenced} 里的 {@code HikariDataSource.close()} 会等在用连接
     * 归还，锁内做就把同条带（64 分之一）的其它连接名一起堵住了。持锁的调用点请改用
     * {@link #detachIfCurrent(String, LeasedDataSource)}，出锁后再关。
     */
    private void evictIfCurrent(String key, LeasedDataSource value) {
        if (value == null) {
            return;
        }
        detachIfCurrent(key, value);
        closeIfUnreferenced(value);
    }

    /**
     * 只把条目从注册表与缓存里摘掉，<em>不</em>关池。
     *
     * <p>拆出来是为了让「摘条目」和「关池」能分处锁内锁外：摘条目是纯 map 操作，关池不是。
     *
     * @return 缓存里当时是否确实还是 {@code value}
     */
    private boolean detachIfCurrent(String key, LeasedDataSource value) {
        if (value == null) {
            return false;
        }
        unregisterIfOwnedBy(key, value);
        return leasedCache.asMap().remove(key, value);
    }

    /**
     * 只有在没有任何注册项还指向 {@code value} 时才关闭它。
     *
     * <p>一个池可以同时挂在 canonical 名字和若干别名下（见 {@link #adoptAlias}）。按名字无条件 close 的话，
     * 别名先过期就会把 canonical 仍在使用的 Hikari 池关掉，之后 canonical 的每次取连接都变成
     * "HikariDataSource has been closed"。引用计数直接从 {@link #registrations} 里按对象身份数出来，
     * 而不是另开一个计数器：计数器和注册表一旦不同步，泄漏或提前关闭都是静默的。
     */
    private void closeIfUnreferenced(LeasedDataSource value) {
        synchronized (registryLock) {
            for (ConnectionRegistration registration : registrations.values()) {
                if (registration.owner() == value) {
                    log.debug("Keeping pool of '{}' open: still referenced by '{}'",
                            value.getKey(), registration.metadata().key());
                    return;
                }
            }
        }
        value.close();
    }

    // ─── Public Metadata API ────────────────────────────────────────────────

    @Override
    public ConnectionMetadata getConnectionMetadata(String key) {
        if (key == null || key.isBlank()) return null;
        ConnectionRegistration registration = registrations.get(key);
        return registration != null ? registration.metadata() : null;
    }

    @Override
    public boolean isReadonly(String key) {
        if (key == null || key.isBlank()) return false;
        ConnectionRegistration registration = registrations.get(key);
        return registration != null && registration.readonly();
    }

    @Override
    public Collection<String> listConnectionKeys() {
        return registrations.keySet();
    }

    @Override
    public int getConnectionCount() {
        return registrations.size();
    }

    @Override
    public Collection<ConnectionMetadata> getAllConnectionMetadata() {
        // Return a snapshot to avoid ConcurrentModificationException if registrations change mid-iteration
        return registrations.values().stream()
                .map(ConnectionRegistration::metadata)
                .toList();
    }

    // ─── Shutdown ───────────────────────────────────────────────────────────

    @Override
    public void shutdown() {
        // Snapshot the cache to avoid ConcurrentModificationException during iteration
        Map<String, LeasedDataSource> snapshot = new java.util.HashMap<>(leasedCache.asMap());
        for (var entry : snapshot.entrySet()) {
            String key = entry.getKey();
            LeasedDataSource leased = entry.getValue();
            // Acquire the striped lock before closing to ensure no concurrent access
            synchronized (lockFor(key)) {
                try {
                    leased.close();
                } catch (Exception e) {
                    log.warn("Failed to close datasource during shutdown: {}", key, e);
                }
            }
        }
        leasedCache.invalidateAll();
        synchronized (registryLock) {
            contentFingerprintToKey.clear();
            registrations.clear();
        }
        log.info("All datasources shut down");
    }

    @Override
    public void destroy() {
        shutdown();
    }

    @Override
    public int getActiveConnectionCount() {
        // Use size() on snapshot to ensure consistency during iteration
        return (int) leasedCache.asMap().size();
    }

    /**
     * Force Caffeine to check for expired entries and trigger the removal callback.
     * Safe to call concurrently.
     */
    @Override
    public void evictExpired() {
        leasedCache.cleanUp();
    }

    /**
     * {@inheritDoc}
     *
     * <p>每个缓存 key 仍然产出一条记录——{@code getPoolStatsForConnection} 是按名字查 map 的，别名条目一旦
     * 缺失，用别名查就会变成「连接不存在」。但同一个物理池会挂在规范名和若干别名下（见 {@link #adoptAlias}），
     * 所以每条记录都带上 {@code canonicalName = leased.getKey()}：{@link LeasedDataSource} 是别名与规范名
     * 共享的同一个对象，它的 key 就是当初建池时用的规范名。汇总方（{@code PoolMonitorTools.getPoolStats}）
     * 按这个字段去重计数，否则池数量和健康池数量都会按别名个数放大。
     */
    @Override
    public Map<String, HikariPoolStats> getPoolStats() {
        Map<String, HikariPoolStats> result = new java.util.LinkedHashMap<>();
        // Take a snapshot to avoid ConcurrentModificationException during iteration
        Map<String, LeasedDataSource> snapshot = new java.util.HashMap<>(leasedCache.asMap());
        for (var entry : snapshot.entrySet()) {
            String key = entry.getKey();
            LeasedDataSource leased = entry.getValue();
            // Skip closed datasources to avoid "HikariDataSource has been closed" errors
            if (leased.isClosed()) {
                log.debug("Skipping closed datasource: {}", key);
                continue;
            }
            // 别名与规范名是两个 key 指向同一个 LeasedDataSource，它的 key 即物理池身份
            String canonicalName = leased.getKey() != null ? leased.getKey() : key;
            ByokDataSourceContext context = leased.getContext();
            DataSource ds = context.getDataSource();
            ConnectionMetadata meta = getConnectionMetadata(key);

            try {
                if (ds instanceof com.zaxxer.hikari.HikariDataSource hikari) {
                    com.zaxxer.hikari.HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
                    if (pool != null) {
                        int active = pool.getActiveConnections();
                        int idle = pool.getIdleConnections();
                        int total = pool.getTotalConnections();
                        int waiting = pool.getThreadsAwaitingConnection();
                        java.util.List<String> warnings = new java.util.ArrayList<>();
                        boolean healthy = true;
                        if (waiting > 5) {
                            warnings.add("High wait count: " + waiting + " threads pending");
                            healthy = false;
                        }
                        if (total > 0 && (double) active / total > 0.9) {
                            warnings.add("Pool near exhaustion: " + active + "/" + total + " connections active");
                            healthy = false;
                        }
                        result.put(key, new HikariPoolStats(
                                key,
                                canonicalName,
                                meta != null ? meta.dialect() : "unknown",
                                meta != null ? meta.jdbcUrlMasked() : "unknown",
                                total, active, idle, waiting,
                                hikari.getMaximumPoolSize(),
                                hikari.getMinimumIdle(),
                                hikari.getConnectionTimeout(),
                                hikari.getIdleTimeout(),
                                hikari.getMaxLifetime(),
                                hikari.getLeakDetectionThreshold(),
                                healthy,
                                warnings
                        ));
                    } else {
                        // Pool not initialized yet (HikariCP lazy init)
                        result.put(key, new HikariPoolStats(
                                key,
                                canonicalName,
                                meta != null ? meta.dialect() : "unknown",
                                meta != null ? meta.jdbcUrlMasked() : "unknown",
                                0, 0, 0, 0,
                                hikari.getMaximumPoolSize(),
                                hikari.getMinimumIdle(),
                                hikari.getConnectionTimeout(),
                                hikari.getIdleTimeout(),
                                hikari.getMaxLifetime(),
                                hikari.getLeakDetectionThreshold(),
                                false,
                                java.util.List.of("Pool not yet initialized")
                        ));
                    }
                }
            } catch (IllegalStateException e) {
                // DataSource was closed during iteration
                log.debug("Skipping datasource {} as it was closed during stats collection", key);
            } catch (Exception e) {
                log.warn("Failed to collect stats for datasource {}: {}", key, e.getMessage());
            }
        }
        return result;
    }
}
