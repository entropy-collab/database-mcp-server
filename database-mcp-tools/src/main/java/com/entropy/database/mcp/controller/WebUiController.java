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
package com.entropy.database.mcp.controller;

import com.entropy.database.mcp.audit.AuditLogRepository;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.tools.ConnectionAdminTools;
import com.entropy.database.mcp.tools.PoolMonitorTools;
import com.entropy.database.mcp.tools.SqlAuditTools;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读运维页面（{@code /index.html}）的后端。除了本类，页面还直接用已有的
 * {@link AuditLogController}（{@code /api/audit/logs} 与 {@code /api/audit/history}）。
 *
 * <h2>这个页面扩大了无鉴权暴露面</h2>
 * <p><b>{@code entropy.mcp.security.enabled=false} 时，本类的每个端点、{@code /api/audit/**} 以及
 * {@code /index.html} 都对任何能连上端口的人开放。</b>在这之前，裸跑部署对外可读的东西需要有人知道
 * {@code /api/audit/logs} 这个路径并手工发请求；现在浏览器打开根路径就能看到审计流水里的 SQL 原文、
 * 慢查询原文、以及全部已注册连接的名字与方言。这不是本页面新引入的权限，但它把「要知道路径才看得到」
 * 变成了「点开就看到」，这个差别在实践中就是暴露面的差别，因此：
 * <ul>
 *   <li>页面顶部有一条由 {@link #uiConfig()} 的 {@code authEnabled} 驱动的红色横幅，
 *       裸跑时必须显示，不允许改成写死的文案或可关闭的提示；</li>
 *   <li>关掉这个暴露的唯一开关是 {@code entropy.mcp.security.enabled=true}（同时需要配
 *       {@code MCP_SECURITY_ADMIN_PASSWORD}）。打开后 {@code SecurityConfig} 把
 *       {@code /api/**} 与页面静态资源一起收进 {@code ROLE_ADMIN}，两者是同一档，
 *       不存在「页面能开、接口打不开」或者反过来的组合。</li>
 * </ul>
 *
 * <h2>为什么全部走已有的工具 bean，而不自己读注册表</h2>
 * <p>{@link ConnectionAdminTools}、{@link PoolMonitorTools}、{@link SqlAuditTools} 已经是这些数据
 * 唯一的读取口径（连接元数据的别名折叠、池按 canonicalName 去重、JDBC URL 脱敏、慢查询 SQL 截断），
 * 页面复用它们意味着 UI 与 MCP 工具永远给出同一个答案，而不是第二份会漂移的实现。附带的好处是本类
 * 不需要碰 {@code DynamicDataSourceManager}，也就不会新增 ArchUnit R2 那一类依赖。
 *
 * <p>刻意没有暴露 {@code DatabaseHealthMonitor.getHealthStatus()}：它把
 * {@code DatabaseMetaData.getURL()} <b>未脱敏</b>地放进返回值（{@code DatabaseHealthMonitorImpl} 第 55 行），
 * 而且描述的是默认数据源（审计库）而不是页面关心的 BYOK 连接。连接健康度改由池统计里的
 * {@code isPoolHealthy} / {@code healthWarnings} / {@code degradedPools} 表达，那条路径上的 URL
 * 是经过 {@code JdbcUrlMasker} 的。
 *
 * <p>另一件已知的事：{@code PerformanceTimingAspect.CONNECTION_REGISTRY_BOOKKEEPING} 会给
 * {@code DynamicDataSourceManagerImpl} 的每个读方法喂一条 {@code recordToolExecution}，所以页面每刷一次
 * {@code /api/ui/connections}，{@code /api/ui/performance} 里 {@code getPoolStats.count} 这类计数就涨一次。
 * 页面因此把自动刷新做成默认关闭的开关，而不是固定轮询——指标里混进 UI 自己的流量比少看几秒新鲜度更糟。
 */
@RestController
@RequestMapping("/api/ui")
public class WebUiController {

    /**
     * {@code limit} 的上限。与 {@link SqlAuditTools} 内部的 1..500 夹取一致：两处都夹是刻意的,
     * 这里夹是为了让 {@code /api/ui/performance} 回显的 {@code limit} 就是真实生效的条数，
     * 否则页面显示 5000 而后端只给 500，看起来像是数据丢了。
     */
    static final int MAX_LIMIT = 500;

    private final ConnectionAdminTools connectionAdminTools;
    private final PoolMonitorTools poolMonitorTools;
    private final SqlAuditTools sqlAuditTools;

    /**
     * {@code @ConditionalOnClass(MeterRegistry)}，micrometer-core 是 infra 的编译依赖所以实际总在，
     * 但仍按可缺失处理：缺了它 {@code /api/ui/performance} 应该少一块 metrics，而不是让整个页面起不来。
     */
    private final @Nullable McpMetricsCollector metricsCollector;

    /**
     * 审计落库是 opt-in 的（{@link AuditLogRepository} 挂在 {@code spring.datasource.url} 上），
     * 没配时这里是 {@code null}，与 {@link AuditLogController#getHistory} 返回 503 的判据是同一个 bean，
     * 因此 {@code /api/ui/config} 报的 {@code auditPersistence} 不可能和 history 的真实行为不一致。
     */
    private final @Nullable AuditLogRepository auditLogRepository;

    /**
     * 直读 {@code entropy.mcp.security.enabled}，默认值与 {@code SecurityConfig} 保持一致的 {@code true}。
     * 不去问 {@code SecurityFilterChain}：那上面读不出「这套规则要不要凭证」这个语义，
     * 而这个键就是 {@code SecurityConfig} 自己的唯一判据，同一个真相来源比推断可靠。
     */
    private final boolean authEnabled;

    public WebUiController(ConnectionAdminTools connectionAdminTools,
                           PoolMonitorTools poolMonitorTools,
                           SqlAuditTools sqlAuditTools,
                           @Nullable McpMetricsCollector metricsCollector,
                           @Nullable AuditLogRepository auditLogRepository,
                           @Value("${entropy.mcp.security.enabled:true}") boolean authEnabled) {
        this.connectionAdminTools = connectionAdminTools;
        this.poolMonitorTools = poolMonitorTools;
        this.sqlAuditTools = sqlAuditTools;
        this.metricsCollector = metricsCollector;
        this.auditLogRepository = auditLogRepository;
        this.authEnabled = authEnabled;
    }

    /**
     * 页面自举用的两个事实：有没有 HTTP 鉴权、审计有没有落库。
     *
     * <p>GET /api/ui/config → {@code {authEnabled, auditPersistence, maxLimit}}
     *
     * <p>页面顶部的无鉴权横幅和「审计历史不可用」这两个状态都由这里驱动，不在前端写死：写死的文案
     * 会在部署方打开鉴权之后继续吓人，或者更糟——在关掉鉴权之后继续说「已鉴权」。
     */
    @GetMapping("/config")
    public Map<String, Object> uiConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("authEnabled", authEnabled);
        config.put("auditPersistence", auditLogRepository != null);
        config.put("maxLimit", MAX_LIMIT);
        return config;
    }

    /**
     * 连接总览：已注册连接的元数据 + 各连接池的实时指标。
     *
     * <p>GET /api/ui/connections →
     * {@code {registered: {totalConnections, activeConnections, connections: [...]},
     *          pools: {totalConnections, totalConnectionNames, healthyPools, degradedPools,
     *                  aliasNames: [...], pools: [...]}}}
     *
     * <p>两块各自原样透传对应工具的返回值，不做二次拼装：拼装就意味着这里出现第二套字段名，
     * 而页面、MCP 工具描述、这段注释三处会各自漂移。
     */
    @GetMapping("/connections")
    public Map<String, Object> connections() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registered", connectionAdminTools.listConnections());
        result.put("pools", poolMonitorTools.getPoolStats());
        return result;
    }

    /**
     * 性能页的一次性快照：慢查询、SQL 模式统计、服务指标。
     *
     * <p>GET /api/ui/performance?limit=50 →
     * {@code {limit, summary: {...}, slowQueries: [...], totalTrackedPatterns, patterns: [...],
     *          metrics: {...} | null}}
     *
     * <p>三块合成一个请求而不是三个端点：它们在页面上是同一个 tab 的同一次刷新，拆开只会让
     * 前端多写两段错误处理，还会让三块数据来自三个不同时刻。
     *
     * <p>{@code @RequestParam} 显式写出 {@code name}，理由见 {@link AuditLogController#getLogs}：
     * 不写时参数名依赖编译期的 {@code -parameters}，下游用自己的构建配置重打包后会退化成
     * {@code arg0}，表现为每次请求都 {@code IllegalArgumentException}，而不是编译期报错。
     */
    @GetMapping("/performance")
    public Map<String, Object> performance(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        int effective = clampLimit(limit);
        Map<String, Object> slow = sqlAuditTools.getSlowQueries(effective);
        Map<String, Object> patterns = sqlAuditTools.getSqlPatternStats(effective);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("limit", effective);
        result.put("summary", slow.get("summary"));
        result.put("slowQueries", slow.get("slowQueries"));
        result.put("totalTrackedPatterns", patterns.get("totalTracked"));
        result.put("patterns", patterns.get("patterns"));
        result.put("metrics", metricsCollector != null ? metricsCollector.getMetrics() : null);
        return result;
    }

    /** 与 {@link AuditLogController} 同样的夹取语义：小于 1 抬到 1，大于上限压到上限。 */
    private static int clampLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
