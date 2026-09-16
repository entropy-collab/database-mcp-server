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
package com.entropy.database.mcp.aop;

import com.entropy.database.mcp.byok.DynamicDataSourceManagerImpl;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.facade.TransactionalWork;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.routing.RoutingDatabaseFacade;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 钉住审计切面的四件事，全部来自线上 audit.log 的实测缺陷：
 * <ol>
 *   <li>审计范围：连接池/注册表的只读记账方法不得进入审计（原来 99 条记录里 77 条是记账噪声，
 *       定时任务每 5 分钟固定产 3 条、一天 864 条，而审计缓冲区只有 100 条，空闲服务器约 2.8 小时
 *       就能把真实审计历史冲干净）；</li>
 *   <li>行数：{@code executeQuery} 返回 {@code PaginatedQueryResult}，原来恒记 rows=0；</li>
 *   <li>SQL：首参是连接名的方法把连接名写进了 sql 字段；</li>
 *   <li>连接名：缺省 connection 时原来兜底成不存在的 "primary"。</li>
 * </ol>
 *
 * <p>断言落在"哪些 tool 名到达了 QueryAuditLogger"上，而不是日志文件文本：审计的写入点是这个接口，
 * 日志格式只是它的一种输出。
 */
class PerformanceTimingAspectAuditTest {

    private static final String CONNECTION = "qditp";

    /** 必须继续审计的注册表方法：改注册表状态或与安全相关。 */
    private static final Set<String> AUDITED_REGISTRY_METHODS =
            Set.of("registerExisting", "registerPinned", "shutdown", "destroy");

    /** 必须被排除的只读 / 记账方法，与线上噪声一一对应。 */
    private static final Set<String> BOOKKEEPING_METHODS = Set.of(
            "addEvictionListener", "acquire", "getConnectionMetadata", "isReadonly",
            "listConnectionKeys", "getConnectionCount", "getAllConnectionMetadata",
            "getActiveConnectionCount", "evictExpired", "getPoolStats");

    private final CapturingAuditLogger auditLogger = new CapturingAuditLogger();

    private final DatabaseHealthMonitor healthMonitor = mock(DatabaseHealthMonitor.class);

    private final McpMetricsCollector metricsCollector = mock(McpMetricsCollector.class);

    private final PerformanceTimingAspect aspect = new PerformanceTimingAspect(
            healthMonitor, metricsCollector, auditLogger);

    private static AspectJExpressionPointcut auditedOperations() {
        return pointcutFor(PerformanceTimingAspect.AUDITED_OPERATIONS);
    }

    private static AspectJExpressionPointcut registryBookkeeping() {
        return pointcutFor(PerformanceTimingAspect.CONNECTION_REGISTRY_BOOKKEEPING);
    }

    private static AspectJExpressionPointcut pointcutFor(String expression) {
        AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
        pointcut.setExpression(expression);
        return pointcut;
    }

    private static List<Method> publicApiOf(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .toList();
    }

    /**
     * 缺陷 1：把注册表的每个 public 方法都过一遍切点，只有命中的才真正驱动切面，最后看有哪些 tool 名
     * 落进了审计。原来的切点是 {@code DynamicDataSourceManagerImpl.*(..)}，这里会看到 acquire、
     * listConnectionKeys、getActiveConnectionCount、evictExpired 等 10 个记账方法一起进审计。
     */
    @Test
    void onlyStateChangingRegistryMethodsReachTheAuditLogger() throws Throwable {
        AspectJExpressionPointcut pointcut = auditedOperations();
        List<Method> publicApi = publicApiOf(DynamicDataSourceManagerImpl.class);

        // AspectJ 表达式写错类名/方法名不会报错，只会匹配零个连接点。先确认这个类真的还有 public 方法，
        // 免得下面的断言在"什么都没匹配到"和"正确地只匹配了 4 个"之间无法区分。
        assertThat(publicApi).isNotEmpty();

        for (Method method : publicApi) {
            if (pointcut.matches(method, DynamicDataSourceManagerImpl.class)) {
                aspect.timeDatabaseOperation(joinPoint(method.getName(), new Object[]{CONNECTION}, null));
            }
        }

        assertThat(auditLogger.toolNames())
                .containsExactlyInAnyOrderElementsOf(AUDITED_REGISTRY_METHODS);
    }

    /** 缺陷 1 的反向断言：被排除的方法确实还在这个类上，否则上面的"没命中"可能只是方法改名了。 */
    @Test
    void bookkeepingMethodsStillExistAndStayOutOfTheAuditScope() {
        AspectJExpressionPointcut pointcut = auditedOperations();
        List<Method> publicApi = publicApiOf(DynamicDataSourceManagerImpl.class);

        assertThat(publicApi.stream().map(Method::getName).toList())
                .containsAll(BOOKKEEPING_METHODS)
                .containsAll(AUDITED_REGISTRY_METHODS);

        assertThat(publicApi.stream()
                .filter(method -> BOOKKEEPING_METHODS.contains(method.getName()))
                .filter(method -> pointcut.matches(method, DynamicDataSourceManagerImpl.class))
                .map(Method::getName)
                .toList())
                .as("连接池记账方法不应进入审计")
                .isEmpty();
    }

    /**
     * 另外 4 个类不受本次改动影响，仍然整类审计。这条同时是切点表达式的"非空"哨兵：
     * 一旦有人把类名写错，这里会立刻红。
     */
    @Test
    void databaseFacadeStaysFullyAudited() throws Exception {
        AspectJExpressionPointcut pointcut = auditedOperations();

        assertThat(pointcut.matches(RoutingDatabaseFacade.class.getMethod(
                "executeQuery", String.class, int.class, String.class, String.class),
                RoutingDatabaseFacade.class)).isTrue();
        assertThat(pointcut.matches(RoutingDatabaseFacade.class.getMethod(
                "inTransaction", String.class, TransactionalWork.class),
                RoutingDatabaseFacade.class)).isTrue();
        assertThat(pointcut.matches(RoutingDatabaseFacade.class.getMethod(
                "executeDdl", String.class, String.class),
                RoutingDatabaseFacade.class)).isTrue();
    }

    /** 缺陷 2：{@code PaginatedQueryResult} 既不是 Map 也不是 Collection，原来一律记 rows=0。 */
    @Test
    void paginatedQueryResultReportsItsRealRowCount() throws Throwable {
        PaginatedQueryResult result = PaginatedQueryResult.from(
                List.of(Map.of("id", 1), Map.of("id", 2)), null, false);

        aspect.timeDatabaseOperation(joinPoint("executeQuery",
                new Object[]{"SELECT id FROM t", 100, null, CONNECTION}, result));

        assertThat(auditLogger.entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.rowCount()).isEqualTo(2);
                    assertThat(entry.sql()).isEqualTo("SELECT id FROM t");
                    assertThat(entry.connectionKey()).isEqualTo(CONNECTION);
                });
    }

    /** Map / Collection 两条既有分支不能被回归掉：executeDdl 的 affectedRows 走的就是 Map 分支。 */
    @Test
    void mapAndCollectionResultsStillReportRowCounts() throws Throwable {
        aspect.timeDatabaseOperation(joinPoint("executeDdl",
                new Object[]{"CREATE TABLE t (id INT)", CONNECTION},
                Map.of("affectedRows", 3)));
        aspect.timeDatabaseOperation(joinPoint("queryRows",
                new Object[]{"SELECT id FROM t", CONNECTION}, List.of(Map.of("id", 1))));

        assertThat(auditLogger.entries()).extracting(CapturingAuditLogger.Entry::rowCount)
                .containsExactly(3, 1);
    }

    /**
     * 缺陷 3：首参是连接名的方法（这里用同样被审计的 {@code inTransaction(String connection, ..)}）
     * 原来把 "qditp" 当成 SQL 记进了 sql 字段，配上审计库后就直接写进 sql_text 列。
     */
    @Test
    void aConnectionNameIsNeverRecordedAsSql() throws Throwable {
        aspect.timeDatabaseOperation(joinPoint("inTransaction",
                new Object[]{CONNECTION, (TransactionalWork<Object>) tx -> null}, Map.of()));

        assertThat(auditLogger.entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.sql()).isEmpty();
                    assertThat(entry.connectionKey()).isEqualTo(CONNECTION);
                });
    }

    /**
     * 缺陷 4：调用方省略可选的 connection 时，原来兜底成 "primary"——线上唯一的连接叫 qditp，
     * 根本没有 primary，这条记录被记到了一个不存在的连接上。null 才是"未指定"的真实值，
     * {@code QueryAuditLoggerImpl} 会把它归一成空串。
     */
    @Test
    void anAbsentConnectionIsRecordedAsNullNotAsAFabricatedName() throws Throwable {
        aspect.timeDatabaseOperation(joinPoint("executeQuery",
                new Object[]{"SELECT 1 FROM DUAL", 100, null, null},
                PaginatedQueryResult.from(List.of(), null, false)));

        assertThat(auditLogger.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.connectionKey()).isNull());
    }

    /** 失败路径也要审计，且异常必须原样抛出。 */
    @Test
    void aFailedOperationIsStillAudited() {
        RuntimeException failure = new IllegalStateException("ORA-00942");
        ProceedingJoinPoint pjp = failingJoinPoint("executeQuery",
                new Object[]{"SELECT 1 FROM missing", 100, null, CONNECTION}, failure);

        try {
            aspect.timeDatabaseOperation(pjp);
            org.assertj.core.api.Assertions.fail("the aspect must not swallow the failure");
        } catch (Throwable t) {
            assertThat(t).isSameAs(failure);
        }

        assertThat(auditLogger.entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.success()).isFalse();
                    assertThat(entry.rowCount()).isZero();
                });
    }

    /**
     * 整轮改动的不变式：连接池记账方法可观测、但不审计。窄化审计切点时把
     * {@code metricsCollector.recordToolExecution} 一起摘掉过，acquire 的耗时是判断池打满 / 建连变慢的
     * 第一手信号，不能跟着审计噪声一起消失。
     */
    @Test
    void registryBookkeepingIsMeasuredButNeverAudited() throws Throwable {
        AspectJExpressionPointcut pointcut = registryBookkeeping();
        List<Method> matched = publicApiOf(DynamicDataSourceManagerImpl.class).stream()
                .filter(method -> pointcut.matches(method, DynamicDataSourceManagerImpl.class))
                .toList();

        assertThat(matched).isNotEmpty();
        for (Method method : matched) {
            aspect.timeConnectionRegistryBookkeeping(
                    joinPoint(method.getName(), new Object[]{CONNECTION}, null));
        }

        ArgumentCaptor<String> tools = ArgumentCaptor.forClass(String.class);
        verify(metricsCollector, times(matched.size()))
                .recordToolExecution(tools.capture(), anyLong());
        assertThat(tools.getAllValues()).containsExactlyInAnyOrderElementsOf(
                matched.stream().map(Method::getName).toList());
        assertThat(tools.getAllValues()).containsAll(BOOKKEEPING_METHODS);

        assertThat(auditLogger.entries())
                .as("记账方法不得写审计")
                .isEmpty();
        // 不喂 QueryStats：它的每个指标都是"每次查询"语义，把亚毫秒的 acquire 和定时的 evictExpired
        // 算进去会抬高查询总数、拉低平均耗时、稀释 rowsPerQuery 与错误率
        verifyNoInteractions(healthMonitor);
    }

    /**
     * 两个切点必须互斥且合起来覆盖整个注册表：审计白名单优先，其余自动落到只观测那条。
     * 靠 {@code 整个类 && !(白名单)} 的表达式结构保证，而不是靠人工对齐两份名单。
     */
    @Test
    void theAuditAndMetricsPointcutsDoNotOverlap() {
        AspectJExpressionPointcut audited = auditedOperations();
        AspectJExpressionPointcut bookkeeping = registryBookkeeping();

        List<String> both = new ArrayList<>();
        List<String> neither = new ArrayList<>();
        for (Method method : publicApiOf(DynamicDataSourceManagerImpl.class)) {
            boolean inAudit = audited.matches(method, DynamicDataSourceManagerImpl.class);
            boolean inMetrics = bookkeeping.matches(method, DynamicDataSourceManagerImpl.class);
            if (inAudit && inMetrics) {
                both.add(method.getName());
            }
            if (!inAudit && !inMetrics) {
                neither.add(method.getName());
            }
        }

        assertThat(both).as("同一个方法不能同时命中审计与只观测两条通知").isEmpty();
        assertThat(neither).as("注册表的每个 public 方法都要么被审计、要么至少被观测").isEmpty();
    }

    /**
     * 反射重载依赖 {@code Parameter.isNamePresent()}，也就是编译时必须带 {@code -parameters}。
     * 本工程继承 spring-boot-starter-parent，其 maven-compiler-plugin 配了
     * {@code <parameters>true</parameters>}。这条断言把这个前提本身钉住：一旦参数名被剥掉，
     * 下面按名字定位 connection 的修复会静默退回到"第一个像连接名的参数"那个坏启发式，
     * 表面上仍然通过——所以前提要单独断言，不能只依赖行为断言。
     */
    @Test
    void parameterNamesAreRetainedAtRuntime() throws Exception {
        Parameter[] parameters = RoutingDatabaseFacade.class
                .getMethod("describeTable", String.class, String.class, String.class)
                .getParameters();

        assertThat(parameters[2].isNamePresent())
                .as("编译缺少 -parameters，按参数名解析连接名的修复会失效")
                .isTrue();
        assertThat(parameters[2].getName()).isEqualTo("connection");
    }

    /**
     * 连接名必须来自那个真的叫 connection 的参数，而不是"第一个长得像连接名的字符串"。
     *
     * <p>无反射重载按顺序扫参数，于是首参是表名的方法全被记错：这三个签名的审计 connectionKey
     * 拿到的是表名（describeTable → "users"、backupData → "users"、diffSchema → "users"）。
     * 这与把缺省连接兜底成不存在的 "primary" 是同一类缺陷：归属信息自信地写错，事后无法分辨。
     */
    @Test
    void theConnectionParameterWinsOverATableNameArgument() throws Throwable {
        aspect.timeDatabaseOperation(joinPointOf(
                RoutingDatabaseFacade.class.getMethod("describeTable", String.class, String.class, String.class),
                new Object[]{"users", "public", CONNECTION}, Map.of()));
        aspect.timeDatabaseOperation(joinPointOf(
                RoutingDatabaseFacade.class.getMethod("backupData", String.class, int.class, String.class),
                new Object[]{"users", 100, CONNECTION}, Map.of()));
        aspect.timeDatabaseOperation(joinPointOf(
                RoutingDatabaseFacade.class.getMethod("diffSchema", String.class, String.class, String.class),
                new Object[]{"users", "users_v2", CONNECTION}, Map.of()));

        assertThat(auditLogger.entries())
                .extracting(CapturingAuditLogger.Entry::tool, CapturingAuditLogger.Entry::connectionKey)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("describeTable", CONNECTION),
                        org.assertj.core.groups.Tuple.tuple("backupData", CONNECTION),
                        org.assertj.core.groups.Tuple.tuple("diffSchema", CONNECTION));
    }

    /** 声明了 connection 但调用方没给，仍然是 null（"未指定"），不能退回去捡表名。 */
    @Test
    void anOmittedConnectionParameterDoesNotFallBackToATableName() throws Throwable {
        aspect.timeDatabaseOperation(joinPointOf(
                RoutingDatabaseFacade.class.getMethod("describeTable", String.class, String.class, String.class),
                new Object[]{"users", "public", null}, Map.of()));

        assertThat(auditLogger.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.connectionKey()).isNull());
    }

    // ─── fixtures ───────────────────────────────────────────────────────────

    /**
     * 带真实 {@link MethodSignature} 的连接点：切面按参数名解析连接名，所以这里必须给出真方法，
     * 而不是只给一个方法名字符串。
     */
    private static ProceedingJoinPoint joinPointOf(Method method, Object[] args, Object result) {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getName()).thenReturn(method.getName());
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        try {
            when(pjp.proceed()).thenReturn(result);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        return pjp;
    }

    private static ProceedingJoinPoint joinPoint(String methodName, Object[] args, Object result) {
        ProceedingJoinPoint pjp = stubJoinPoint(methodName, args);
        try {
            when(pjp.proceed()).thenReturn(result);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        return pjp;
    }

    private static ProceedingJoinPoint failingJoinPoint(String methodName, Object[] args,
                                                       RuntimeException failure) {
        ProceedingJoinPoint pjp = stubJoinPoint(methodName, args);
        try {
            when(pjp.proceed()).thenThrow(failure);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        return pjp;
    }

    private static ProceedingJoinPoint stubJoinPoint(String methodName, Object[] args) {
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn(methodName);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        return pjp;
    }

    /** 记录每一次审计写入，断言直接落在这里而不是日志文件文本上。 */
    private static final class CapturingAuditLogger implements QueryAuditLogger {

        record Entry(String tool, String sql, int rowCount, long durationMs, boolean success,
                     String error, String connectionKey) {
        }

        private final List<Entry> entries = new ArrayList<>();

        @Override
        public void log(String tool, String sql, int rowCount, long durationMs, boolean success,
                        String connectionKey) {
            log(tool, sql, rowCount, durationMs, success, null, connectionKey);
        }

        @Override
        public void log(String tool, String sql, int rowCount, long durationMs, boolean success,
                        String error, String connectionKey) {
            entries.add(new Entry(tool, sql, rowCount, durationMs, success, error, connectionKey));
        }

        @Override
        public List<Map<String, Object>> getRecentLogs(int limit) {
            return List.of();
        }

        List<Entry> entries() {
            return entries;
        }

        List<String> toolNames() {
            return entries.stream().map(Entry::tool).toList();
        }
    }
}
