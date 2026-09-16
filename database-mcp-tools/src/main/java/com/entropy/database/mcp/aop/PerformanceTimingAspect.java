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

import com.entropy.database.mcp.aop.ConnectionArgExtractor;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * AOP aspect for unified performance timing and metrics collection.
 * Eliminates duplicated timing boilerplate across repositories and facades.
 */
@Aspect
@Component
public class PerformanceTimingAspect {

    private static final Logger log = LoggerFactory.getLogger(PerformanceTimingAspect.class);

    private final DatabaseHealthMonitor healthMonitor;
    private final McpMetricsCollector metricsCollector;
    private final QueryAuditLogger auditLogger;

    public PerformanceTimingAspect(DatabaseHealthMonitor healthMonitor,
                                   McpMetricsCollector metricsCollector,
                                   QueryAuditLogger auditLogger) {
        this.healthMonitor = healthMonitor;
        this.metricsCollector = metricsCollector;
        this.auditLogger = auditLogger;
    }

    /**
     * 真正承载数据库访问的四个类，整类纳入审计：它们的每个 public 方法都会碰到用户数据或执行计划。
     */
    static final String DATABASE_ACCESS_CLASSES =
            "execution(* com.entropy.database.mcp.routing.RoutingDatabaseFacade.*(..)) || "
            + "execution(* com.entropy.database.mcp.routing.ByokDatabaseFacade.*(..)) || "
            + "execution(* com.entropy.database.mcp.repository.ExecutionPlanRepositoryImpl.*(..)) || "
            + "execution(* com.entropy.database.mcp.etl.JobExecutionEngine.*(..))";

    /**
     * 连接注册表里"改状态 / 与安全相关"的方法白名单。
     *
     * <p>这里原来是 {@code DynamicDataSourceManagerImpl.*(..)}，把整个连接池/注册表的 public 方法都
     * 当成数据库操作审计了一遍。线上 30 分钟的 audit.log 里 99 条记录只有 22 条是真实数据库访问，其余
     * 77 条是池记账：{@code acquire} 26 次、{@code listConnectionKeys} 16 次、
     * {@code getActiveConnectionCount} 15 次、{@code evictExpired} 7 次……其中 {@code evictExpired} 与
     * {@code getActiveConnectionCount} 由定时器每 5 分钟固定产出 3 条（07:06:35、07:11:35 … 07:36:35），
     * 一天 864 条纯噪声。{@code QueryAuditLoggerImpl.MAX_BUFFER_SIZE} 是 100，于是一台完全空闲的服务器
     * 约 2.8 小时就能把审计缓冲区整轮冲干净——真实的审计历史被记账噪声挤掉了。
     *
     * <p>刻意写成白名单而不是"排除若干只读方法"的黑名单：黑名单在有人给注册表新增一个记账方法时会
     * 默默地把它也审计上，而这正是本缺陷的成因。新增方法要进审计必须显式写到这里。
     *
     * <p>注意 AspectJ 的切点表达式写错类名或方法名不会报错，只会匹配到零个连接点、静默地什么都不审计。
     * {@code PerformanceTimingAspectAuditTest} 因此对着 {@code DynamicDataSourceManagerImpl} 的真实
     * 方法列表逐个断言，白名单里的 4 个方法必须命中、被排除的记账方法必须不命中。
     */
    static final String CONNECTION_REGISTRY_STATE_CHANGES =
            "execution(* com.entropy.database.mcp.byok.DynamicDataSourceManagerImpl.registerExisting(..)) || "
            + "execution(* com.entropy.database.mcp.byok.DynamicDataSourceManagerImpl.registerPinned(..)) || "
            + "execution(* com.entropy.database.mcp.byok.DynamicDataSourceManagerImpl.shutdown(..)) || "
            + "execution(* com.entropy.database.mcp.byok.DynamicDataSourceManagerImpl.destroy(..))";

    /** 审计范围的唯一定义处，测试直接引用这个常量，避免测的是另一份复制品。 */
    static final String AUDITED_OPERATIONS =
            DATABASE_ACCESS_CLASSES + " || " + CONNECTION_REGISTRY_STATE_CHANGES;

    /**
     * 连接注册表里"只观测、不审计"的那一半：整个类减去上面的审计白名单。
     *
     * <p>把记账方法从审计里摘出去解决了审计噪声，但这个切面同时干三件事（计时、健康度、审计），
     * 摘出去的同时也把 {@code McpMetricsCollector.recordToolExecution} 一起摘掉了——{@code getMetrics}
     * 从此看不到 acquire / evictExpired / getActiveConnectionCount 的耗时。连接获取延迟是真实的诊断信号
     * （池被打满、租约过期风暴、Hikari 建连变慢，最先反映在 acquire 的耗时上），丢掉它是另一种回归，
     * 只是比审计噪声安静得多。所以这里补一条只喂指标的通知。
     *
     * <p>写成 {@code 整个类 && !(审计白名单)} 而不是再枚举一遍记账方法，是为了让"两个切点互不重叠"成为
     * 表达式的结构性质而不是靠人工对齐两份名单：以后给注册表新增任何方法，它要么在审计白名单里、
     * 要么自动落到这里，不可能两边都命中、也不可能两边都漏。审计优先——白名单里的方法只走审计通知，
     * 因为那条通知本身已经在喂 metricsCollector。
     */
    static final String CONNECTION_REGISTRY_BOOKKEEPING =
            "execution(* com.entropy.database.mcp.byok.DynamicDataSourceManagerImpl.*(..))"
            + " && !(" + CONNECTION_REGISTRY_STATE_CHANGES + ")";

    @Around(AUDITED_OPERATIONS)
    public Object timeDatabaseOperation(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String toolName = pjp.getSignature().getName();
        String sql = extractSql(pjp);
        String connectionKey = extractConnectionKey(pjp);

        try {
            Object result = pjp.proceed();
            long duration = System.currentTimeMillis() - start;
            // 没抛异常 ≠ 成功：本仓库有一批读路径把「表不存在」这类失败当返回值交出去
            // （DatabaseBackupServiceImpl 的 Map.of("error", "Table not found: ...")、
            // DatabaseReadRepository.describeTable 的 not-found 结果）。原来这些都被记成
            // success:true 且错误文本为空，审计里与真正成功的调用一模一样。
            String resultError = extractError(result);
            recordResult(toolName, sql, connectionKey, duration, extractRowCount(result),
                    resultError == null, resultError);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            // errorMessage 原来恒传 null，于是审计只剩一个 success:false 空壳——线上那次
            // describeTable 失败就是这样：43ms、success:false、错误文本为空、日志里没有任何一行。
            // 用 toString() 兜底是因为 NPE 这类异常 getMessage() 本身就是 null。
            recordResult(toolName, sql, connectionKey, duration, 0, false, describeFailure(e));
            throw e;
        }
    }

    /** 结果里自带的错误文本；没有就返回 null，表示这次调用确实成功。 */
    private String extractError(Object result) {
        if (result instanceof java.util.Map<?, ?> map) {
            Object error = map.get("error");
            if (error != null && !String.valueOf(error).isBlank()) {
                return String.valueOf(error);
            }
        }
        return null;
    }

    /** 异常的可读描述；{@code getMessage()} 为 null（NPE 就是）时退回类名。 */
    private String describeFailure(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.toString() : message;
    }


    /**
     * 连接池记账方法：只记耗时指标，绝不写审计。这条通知是整轮改动的不变式所在——
     * 可观测（observable），但不审计（not audited）。
     *
     * <p>刻意不调 {@code healthMonitor.recordQuery}：它喂的是 {@code QueryStats}，语义全是"每次查询"——
     * totalQueries、averageDurationMs、rowsPerQuery、errorRate。把一次亚毫秒级的 acquire 缓存命中和
     * 每 5 分钟一次的 evictExpired 算进去，会把查询总数凭空抬高、把平均查询耗时拉向 0、把
     * rowsPerQuery（记账方法没有行）和错误率一起稀释掉，也就是用记账淹没信号——和审计那个缺陷是同一种
     * 错误，只是换了个水池。连接池自身的健康度另有 {@code getPoolStats} 那条路，不该借查询统计表达。
     *
     * <p>指标记在 finally 里：失败的 acquire（池打满、建连超时）恰恰是最需要看到耗时的那一类，
     * 而 {@code recordToolExecution} 没有成功/失败入参，成功与失败都应当计入同一个 Timer。
     */
    @Around(CONNECTION_REGISTRY_BOOKKEEPING)
    public Object timeConnectionRegistryBookkeeping(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String toolName = pjp.getSignature().getName();
        try {
            return pjp.proceed();
        } finally {
            metricsCollector.recordToolExecution(toolName, System.currentTimeMillis() - start);
        }
    }

    /**
     * 解析这次调用真正的连接名。
     *
     * <p>原来走的是 {@code extractConnectionName(args)} 这个无反射重载：它按顺序返回第一个"长得像连接名"
     * 的字符串参数，于是首参是表名的方法全被记错——{@code describeTable(table, schema, connection)}、
     * {@code backupData(tableName, maxRows, connection)}、{@code diffSchema(sourceTable, targetTable, connection)}
     * 的审计 connectionKey 拿到的是表名（如 "users"）而不是连接名 "qditp"。这和把缺省连接兜底成
     * 不存在的 "primary" 是同一类缺陷：审计的归属信息自信地写错，事后完全无法分辨。
     *
     * <p>改用带 {@link MethodSignature} 的反射重载，按"参数名叫 connection"来定位，与
     * {@code McpToolExceptionAspect} 用的是同一条路径。它依赖 {@code Parameter.isNamePresent()}，
     * 也就是编译时要带 {@code -parameters}：本工程继承 spring-boot-starter-parent，其
     * maven-compiler-plugin 配置了 {@code <parameters>true</parameters>}（javap 可见
     * {@code RoutingDatabaseFacade.describeTable} 的 MethodParameters 为 table/schema/connection），
     * 所以这条路径在本工程是成立的。万一将来参数名被剥掉，反射重载会静默退回到那个坏启发式，
     * 因此 {@code PerformanceTimingAspectAuditTest} 用三个真实签名把结果钉死，退化会当场变红。
     *
     * <p>拿不到 {@code MethodSignature}（理论上的非方法连接点）时退回无反射重载，保持旧行为而不是抛错：
     * 审计不该因为签名类型意外就打断调用方。
     */
    private String extractConnectionKey(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        Optional<String> connectionOpt = pjp.getSignature() instanceof MethodSignature signature
                ? ConnectionArgExtractor.extractConnectionName(args, signature)
                : ConnectionArgExtractor.extractConnectionName(args);
        return connectionOpt.orElse(null);
    }

    private void recordResult(String toolName, String sql, String connectionKey,
                              long duration, int rows, boolean success, String errorMessage) {
        healthMonitor.recordQuery(duration, rows, success);
        try {
            // connectionKey 直接透传 null，不再兜底成 "primary"。线上只有一条名为 qditp 的连接，
            // 根本不存在 primary：调用方省略可选的 connection 参数时，审计把这次操作记到了一个
            // 不存在的连接上（同一条 SQL 传了连接名就记 qditp、没传就记 primary），既查不出真实
            // 目标库，又让"按连接统计"凭空多出一行。QueryAuditLogger.log 的 connectionKey 本来就
            // 声明为 @Nullable，QueryAuditLoggerImpl 会把 null 归一成 ""，即"未指定"。
            auditLogger.log(toolName, sql, rows, duration, success, errorMessage, connectionKey);
        } catch (Exception e) {
            log.warn("Failed to record audit log for {}", toolName, e);
        }
        metricsCollector.recordToolExecution(toolName, duration);
    }

    /**
     * 首参是 String 并不等于首参是 SQL：连接注册表那批方法的首参是连接名。判断统一委托给
     * {@link ConnectionArgExtractor#isLikelySql}，与连接名识别共用同一套规则，见那里的注释。
     * 取不到 SQL 时仍然返回空串——这是本切面既有的"无 SQL"约定。
     */
    private String extractSql(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args != null && args.length > 0 && args[0] instanceof String s
                && ConnectionArgExtractor.isLikelySql(s)) {
            return s;
        }
        return "";
    }

    private int extractRowCount(Object result) {
        // RoutingDatabaseFacade.executeQuery 返回的是 PaginatedQueryResult 这个 record，既不是 Map
        // 也不是 Collection，原来直接落到最后的 return 0，于是所有 executeQuery 的审计行数恒为 0
        // （实测返回 2 行的查询被记成 "rows":0）。审计里的行数是判断"这次查询捞走了多少数据"的
        // 唯一依据，恒 0 等于这一列作废。
        if (result instanceof PaginatedQueryResult paginated) {
            return paginated.rows() == null ? 0 : paginated.rows().size();
        }
        if (result instanceof java.util.Map<?, ?> map) {
            Object rows = map.get("rows");
            if (rows instanceof java.util.Collection<?> col) {
                return col.size();
            }
            Object affected = map.get("affectedRows");
            if (affected instanceof Integer i) {
                return i;
            }
        } else if (result instanceof java.util.Collection<?> col) {
            return col.size();
        }
        return 0;
    }
}
