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
import com.entropy.database.mcp.tools.DatabaseHealthTools;
import com.entropy.database.mcp.tools.PoolMonitorTools;
import com.entropy.database.mcp.tools.SqlAuditTools;
import com.entropy.database.mcp.tools.ToolCatalog;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读运维页面（{@code /index.html}）的后端。除了本类，页面还直接用已有的
 * {@link AuditLogController}（{@code /api/audit/logs} 与 {@code /api/audit/history}），
 * 以及 {@link WebUiExplorerController}——它承载 schema / 资产 / 血缘 / 作业 / 备份 / CDC /
 * 质量 / SQL 体检那一批浏览端点，和本类共用 {@code /api/ui} 前缀（Spring 按具体路径匹配）。
 * 本类留下的是进程内状态（连接、池、审计、指标、服务自述）与 DBA 诊断视图。
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
 * <p><b>{@link #dba} 是本类里唯一会真的向业务库发 SQL 的端点</b>，其余端点读的都是进程内状态
 * （{@link WebUiExplorerController} 那边大部分端点都会连库，那一批的暴露面在那个类里单独讨论）。
 * 它读的是数据字典与动态性能视图（{@code v$session}、{@code dba_data_files} 之类），因此裸跑时
 * 它把「服务器进程里有什么」扩大到了「被注册的那些库里有什么」。这不是本类新引入的权限——同样的
 * 查询作为 MCP 工具一直可调，且仍受连接注册与账号权限约束——但它是上面那条「暴露面」讨论里
 * 唯一涉及外部系统的一项，评估部署风险时要单独看。
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

    /**
     * {@code /api/ui/audit-reports} 的窗口上限，单位小时（7 天）。
     *
     * <p>上限存在的原因和 {@link #MAX_LIMIT} 不同：这里不是为了回显诚实，而是因为
     * {@code getProtectionReport} 会把区间内的审计流水<b>全部拉进内存</b>再统计（上限 10000 条，
     * 见 {@link #auditReports}），窗口越大越接近那个硬上限，报告越可能是被截断的样本。
     */
    static final int MAX_WINDOW_HOURS = 168;

    /**
     * {@code /api/ui/dba} 支持的 {@code view} 取值，也是 {@code /api/ui/dba/views} 的返回内容。
     *
     * <p>这份清单只在这里出现一次，前端从 {@code /api/ui/dba/views} 取：两边各写一份的话，
     * 后端加了一个 view 而前端选择器没加等于这个 view 不存在，反过来则是一个必然 400 的选项。
     * 顺序即前端渲染顺序，因此用 {@link List} 而不是 Set。
     *
     * <p>最后三个（{@code grants}、{@code privileges}、{@code indexStatus}）读的是权限与索引状态，
     * 和前面九个一样走 {@link DatabaseHealthTools} 的只读方法。它们留在这个类而不是搬进
     * {@code WebUiExplorerController}，唯一原因就是这份清单与 {@link #dbaViews()}
     * 只能有一份：分开之后前端就得从两个地方拼出「有哪些视图」。
     */
    static final List<String> DBA_VIEWS = List.of(
            "health", "sessions", "locks", "blocking", "tablespaces",
            "datafiles", "undo", "invalid", "tableSize",
            "grants", "privileges", "indexStatus");

    private final ConnectionAdminTools connectionAdminTools;
    private final PoolMonitorTools poolMonitorTools;
    private final SqlAuditTools sqlAuditTools;
    private final DatabaseHealthTools databaseHealthTools;
    private final ToolCatalog toolCatalog;

    /**
     * 用来报 active profiles 与那些没有 {@code @ConfigurationProperties} 类的开关。
     *
     * <p>{@code entropy.mcp.gateway.enabled} 就是后者：仓库里既有的读法是
     * {@code environment.getProperty(键, "false")} + {@code Boolean.parseBoolean}
     * （{@code DdlExecutionTools}、{@code CrossDatabaseTools} 都这么读）。这里跟着读同一个
     * {@link Environment}，报的就是<b>生效值</b>——注意代码里的 fallback 是 {@code false} 而
     * {@code application.yml} 配的是 {@code true}，照代码默认值猜会在默认部署上报错。
     */
    private final Environment environment;

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

    /**
     * {@code SecurityConfig} 的逃生阀，默认值与那边的 {@code @Value} 一致的 {@code false}。
     * 它单独报是因为它和 {@code authEnabled=false} 组合起来才是「production 上真的裸跑」，
     * 只看 {@code authEnabled} 的话看不出这个部署是被允许裸跑还是应该启动失败。
     */
    private final boolean allowUnauthenticatedInProduction;

    /**
     * 服务名与版本号的唯一来源：{@code spring.ai.mcp.server.name} / {@code .version}，后者由
     * Maven 资源过滤把 {@code @project.version@} 填进 {@code application.yml}。
     *
     * <p>不走 {@code /actuator/info} 的 build-info，也不读 jar manifest 的
     * {@code Implementation-Version}：这个仓库两者都没有生成，读出来只会是 {@code null}。
     * 默认值给 {@code unknown} 是为了让「资源过滤没生效」表现为页面上一个显眼的 unknown，
     * 而不是上下文启动失败。
     */
    private final String serviceName;
    private final String version;

    /**
     * 进程启动时刻的<b>近似值</b>：本类是单例，由容器在 refresh 期间构造，因此这个时间戳
     * 比真实的 JVM 启动晚、比 {@code ApplicationReadyEvent} 早，误差是秒级。
     *
     * <p>刻意不注入 {@code ApplicationContext} 去拿 {@code getStartupDate()}：为了一个只给人看的
     * 时间戳把整个容器句柄拉进一个只读控制器，代价是这个类从此什么都能碰（也会给 ArchUnit
     * 那批依赖规则开一个口子），而收益只是几秒精度。同理不监听 {@code ApplicationReadyEvent}：
     * 那需要多一个可变字段和「事件还没到时报什么」的分支。
     */
    private final Instant startedAt = Instant.now();

    public WebUiController(ConnectionAdminTools connectionAdminTools,
                           PoolMonitorTools poolMonitorTools,
                           SqlAuditTools sqlAuditTools,
                           DatabaseHealthTools databaseHealthTools,
                           ToolCatalog toolCatalog,
                           Environment environment,
                           @Nullable McpMetricsCollector metricsCollector,
                           @Nullable AuditLogRepository auditLogRepository,
                           @Value("${entropy.mcp.security.enabled:true}") boolean authEnabled,
                           @Value("${entropy.mcp.security.allow-unauthenticated-in-production:false}")
                           boolean allowUnauthenticatedInProduction,
                           @Value("${spring.ai.mcp.server.name:unknown}") String serviceName,
                           @Value("${spring.ai.mcp.server.version:unknown}") String version) {
        this.connectionAdminTools = connectionAdminTools;
        this.poolMonitorTools = poolMonitorTools;
        this.sqlAuditTools = sqlAuditTools;
        this.databaseHealthTools = databaseHealthTools;
        this.toolCatalog = toolCatalog;
        this.environment = environment;
        this.metricsCollector = metricsCollector;
        this.auditLogRepository = auditLogRepository;
        this.authEnabled = authEnabled;
        this.allowUnauthenticatedInProduction = allowUnauthenticatedInProduction;
        this.serviceName = serviceName;
        this.version = version;
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

    // ─── 服务自述 ──────────────────────────────────────────────────────────

    /**
     * 页面页脚与「这台服务器是什么」那一栏：服务名、版本、生效的 profile、启动时间、四个开关、工具数。
     *
     * <p>GET /api/ui/info →
     * {@code {serviceName, version, activeProfiles: [...], startedAt, uptimeSeconds,
     *          switches: {authEnabled, allowUnauthenticatedInProduction, gatewayEnabled, auditPersistence},
     *          toolCount: {total, exposed}}}
     *
     * <p>{@code switches} 里的四项<b>全部是生效值</b>，不是代码里的默认值：
     * {@code gatewayEnabled} 尤其要注意，既有代码的 fallback 是 {@code false} 而
     * {@code application.yml} 配的是 {@code true}，按代码默认值报会在默认部署上说反。
     *
     * <p>{@code startedAt} 是近似值，理由见 {@link #startedAt} 的注释；{@code uptimeSeconds} 由它推出，
     * 因此同样是近似值，别拿它算 SLA。
     *
     * <p>{@code toolCount.total} 与 {@code exposed} 会不相等：前者是容器里反射出来的全部工具，
     * 后者是 {@code ToolExposureFilter} 裁剪后真正交给客户端的那些。两个都报是因为只报一个的话，
     * 配了工具预设的部署会让人以为工具丢了（只看 exposed）或者以为裁剪没生效（只看 total）。
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> switches = new LinkedHashMap<>();
        switches.put("authEnabled", authEnabled);
        switches.put("allowUnauthenticatedInProduction", allowUnauthenticatedInProduction);
        switches.put("gatewayEnabled",
                Boolean.parseBoolean(environment.getProperty("entropy.mcp.gateway.enabled", "false")));
        switches.put("auditPersistence", auditLogRepository != null);

        Map<String, Object> toolCount = new LinkedHashMap<>();
        toolCount.put("total", toolCatalog.size());
        toolCount.put("exposed", toolCatalog.exposedSize());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceName", serviceName);
        result.put("version", version);
        result.put("activeProfiles", List.of(environment.getActiveProfiles()));
        result.put("startedAt", startedAt.toString());
        result.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        result.put("switches", switches);
        result.put("toolCount", toolCount);
        return result;
    }

    /**
     * 工具清单：这台服务器实际暴露了哪些 MCP 工具、各属哪个分组。
     *
     * <p>GET /api/ui/tools →
     * {@code {total, exposed, groups: [...], tools: [{name, group, summary, tags: [...]}]}}
     *
     * <p>{@code tools} 只列 {@link ToolCatalog#exposedDescriptors()}，而 {@code total} 是未裁剪的
     * {@link ToolCatalog#size()}：页面要能显示「暴露 40 / 共 126」这种口径，列出一个已被摘掉、
     * 根本调不到的工具比不列更糟。{@code groups} 用的是全量分组名（{@code ToolCatalog.groups()}
     * 刻意不受裁剪影响），所以可能出现一个分组下当前一个工具都没暴露。
     *
     * <p><b>已知限制：这里没有 {@code inputSchema}。</b>{@link ToolCatalog} 是从
     * {@code @McpTool} 方法反射出来的元数据索引，参数的 JSON Schema 由下游的
     * {@code SyncToolSpecification} 持有，索引里根本没有。想补 schema 得注入
     * {@code ObjectProvider<List<SyncToolSpecification>>}，而容器里该类型<b>有多个 bean</b>，
     * 且 {@code ToolExposureFilter} 是 {@code BeanPostProcessor}——注入时机决定了拿到的是裁剪前
     * 还是裁剪后的列表，这条路本次刻意不走。页面因此只做「有哪些工具」的目录，不做参数表单。
     */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        List<Map<String, Object>> descriptors = new ArrayList<>();
        toolCatalog.exposedDescriptors().stream()
                .sorted(Comparator.comparing(ToolCatalog.ToolDescriptor::name))
                .forEach(descriptor -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", descriptor.name());
                    entry.put("group", descriptor.group());
                    entry.put("summary", descriptor.summary());
                    entry.put("tags", descriptor.tags());
                    descriptors.add(entry);
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", toolCatalog.size());
        result.put("exposed", toolCatalog.exposedSize());
        result.put("groups", List.copyOf(toolCatalog.groups()));
        result.put("tools", descriptors);
        return result;
    }

    // ─── 审计与合规 ────────────────────────────────────────────────────────

    /**
     * 合规页的一次性快照：实时指标 + 数据访问报告 + 保护级别报告。
     *
     * <p>GET /api/ui/audit-reports?hours=24&amp;limit=100 →
     * {@code {hours, from, to, limit, metrics: {...}, dataAccess: {...}, protection: {...}}}
     *
     * <p>{@code from}/{@code to} 必须回显：两个报告工具的时间参数是<b>必填</b>的 ISO-8601 字符串，
     * 窗口由本端点算（{@code Instant.now()} 往前推 {@code hours}），页面自己算不出来。不回显的话
     * 页面上就是一份没人知道覆盖哪段时间的报告——刷新一次数字变了也没法解释是窗口滑动还是真有新流水。
     *
     * <p>{@code hours} 夹到 1..{@value #MAX_WINDOW_HOURS}，{@code limit} 走 {@link #clampLimit}，
     * 两个都按夹取后的值回显，理由同 {@code /api/ui/performance}。
     *
     * <p>审计未落库时这两个报告<b>不报错</b>：返回体里带 {@code status="skipped"} 与 {@code reason}，
     * 与 {@code /api/ui/config} 的 {@code auditPersistence=false} 是同一个原因（没有
     * {@link AuditLogRepository} bean）。页面据此显示「未启用审计留存」而不是一个错误。
     *
     * <p><b>{@code protection} 里有两个与全局配置无关的硬编码</b>（{@code ComplianceReportService}）：
     * 区间内最多取 <b>10000</b> 条流水，慢查询判定固定 <b>5000ms</b>。后者和
     * {@code metrics.slowQueryThresholdMs}（即 {@code entropy.mcp...slow-query-threshold-ms}）
     * <b>不是同一个阈值</b>，所以同一份返回里 {@code metrics.slowQueryCount} 和
     * {@code protection} 的慢查询数按不同标准算，对不上是正常的。窗口越大越容易撞到 10000 条上限，
     * 撞到之后报告描述的是被截断的样本而不是整个窗口——这也是 {@code hours} 有上限的原因。
     */
    @GetMapping("/audit-reports")
    public Map<String, Object> auditReports(
            @RequestParam(name = "hours", defaultValue = "24") int hours,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        int effectiveHours = clampWindowHours(hours);
        int effectiveLimit = clampLimit(limit);
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(effectiveHours));
        String fromText = from.toString();
        String toText = to.toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hours", effectiveHours);
        result.put("from", fromText);
        result.put("to", toText);
        result.put("limit", effectiveLimit);
        result.put("metrics", sqlAuditTools.getAuditMetrics());
        result.put("dataAccess", sqlAuditTools.getDataAccessReport(fromText, toText, effectiveLimit));
        result.put("protection", sqlAuditTools.getProtectionReport(fromText, toText));
        return result;
    }

    // ─── 单连接池 ──────────────────────────────────────────────────────────

    /**
     * 单个连接的池指标，供页面在连接列表里展开一行时取。
     *
     * <p>GET /api/ui/pool?connection=xxx → 原样透传 {@link PoolMonitorTools#getPoolStatsForConnection}。
     *
     * <p>{@code connection} 缺失时是 400 而不是让它走到工具里：那边的必填校验会抛
     * {@code McpToolException}，在 HTTP 上表现为 500——「调用方少传了一个参数」和「服务器坏了」
     * 是两件事，页面对这两者的处理也不同（前者不该弹重试）。连接名不存在仍然由工具判定，
     * 那是它的口径（canonicalName 去重、别名折叠），这里不去重复实现。
     */
    @GetMapping("/pool")
    public Map<String, Object> pool(
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        if (connection == null || connection.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "connection is required: pass the connectionName reported by /api/ui/connections");
        }
        return poolMonitorTools.getPoolStatsForConnection(connection);
    }

    // ─── DBA 诊断 ──────────────────────────────────────────────────────────

    /**
     * DBA 诊断视图，按 {@code view} 分派到 {@link DatabaseHealthTools} 的对应只读方法。
     *
     * <p>GET /api/ui/dba?view=sessions&amp;connection=&amp;schema=&amp;table=&amp;userName=
     * → 原样透传对应工具的 {@code {dialect, rows}}。
     *
     * <p>合法 {@code view} 见 {@link #DBA_VIEWS}，也由 {@link #dbaViews()} 暴露给前端。
     * 取值非法（含缺失）时返回 400，响应体里带上合法取值清单——让人从错误消息里就能改对，
     * 而不是去翻源码。{@code invalid} 额外用 {@code schema}，{@code tableSize} 额外用
     * {@code table}（必填，缺失同样是 400）与 {@code schema}，{@code grants} 额外用
     * {@code userName}（必填），{@code indexStatus} 额外用 {@code table}（必填）与 {@code schema}。
     *
     * <p>{@code grants} 的 {@code userName} 在 Oracle 数据字典里存的是<b>大写</b>，
     * 传小写通常查出空 {@code rows}——这是数据字典的口径，不在这里做大小写转换：
     * 悄悄把它转成大写会让「MySQL 上用户名区分大小写」那类库上的查询变成查不到的另一个用户。
     *
     * <p>{@code indexStatus} 的 {@code table} 在工具签名里是<b>可选</b>的（省略即返回整个 Schema
     * 的索引），这里刻意收紧成必填：页面上那是一次可能返回上万行的请求，而触发它只需要
     * 选中视图却忘了填表名。要看整个 Schema 的索引仍然可以直接调 MCP 工具。
     *
     * <p><b>这一组会真的连库执行查询，而且失败是常态。</b>底下的 SQL 由方言提供，绝大多数视图
     * 只有 Oracle 实现（{@code v$session}、{@code dba_data_files}、{@code v$undostat} 这类），
     * 别的方言上是 {@code null} → 工具抛 {@code McpToolException}；{@code connection} 不存在、
     * 连不上、或账号没有数据字典权限时同样抛。这里<b>刻意不 catch</b>：把方言不支持和权限不足
     * 压成一个 200 + 空 {@code rows}，页面就永远显示「没有会话」，而真实原因藏在服务端日志里。
     * 页面靠状态码与消息区分这几种失败，属于预期行为，不需要报警。
     */
    @GetMapping("/dba")
    public Map<String, Object> dba(
            @RequestParam(name = "view", required = false) @Nullable String view,
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "schema", required = false) @Nullable String schema,
            @RequestParam(name = "table", required = false) @Nullable String table,
            @RequestParam(name = "userName", required = false) @Nullable String userName) {
        if (view == null || !DBA_VIEWS.contains(view)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unknown view '" + view + "'; supported views are " + DBA_VIEWS);
        }
        return switch (view) {
            case "health" -> databaseHealthTools.checkHealth(connection);
            case "sessions" -> databaseHealthTools.listActiveSessions(connection);
            case "locks" -> databaseHealthTools.showLocks(connection);
            case "blocking" -> databaseHealthTools.showBlockingTree(connection);
            case "tablespaces" -> databaseHealthTools.listTablespaces(connection);
            case "datafiles" -> databaseHealthTools.listDataFiles(connection);
            case "undo" -> databaseHealthTools.showUndoUsage(connection);
            case "invalid" -> databaseHealthTools.listInvalidObjects(schema, connection);
            case "tableSize" -> {
                if (table == null || table.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "table is required for view 'tableSize'");
                }
                yield databaseHealthTools.estimateTableSize(table, schema, connection);
            }
            case "grants" -> {
                if (userName == null || userName.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "userName is required for view 'grants'");
                }
                yield databaseHealthTools.listGrants(userName, connection);
            }
            case "privileges" -> databaseHealthTools.listCurrentPrivileges(connection);
            case "indexStatus" -> {
                if (table == null || table.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "table is required for view 'indexStatus'");
                }
                yield databaseHealthTools.showIndexStatus(table, schema, connection);
            }
            // DBA_VIEWS 的 contains 已经挡住了其它取值；这一支存在只是为了让「往 DBA_VIEWS 里
            // 加了名字却忘了加分派」表现为一次明确的 500，而不是编译不过或静默返回 null。
            default -> throw new IllegalStateException("view '" + view + "' is listed but not dispatched");
        };
    }

    /**
     * {@link #dba} 支持的 {@code view} 清单。
     *
     * <p>GET /api/ui/dba/views → {@code {views: [...]}}
     *
     * <p>存在的唯一理由是让前端选择器不必再写一份同样的清单：写两份之后，后端加的 view
     * 前端点不到，前端多出的 view 点了必然 400，而两种坏法都不会有人在评审时发现。
     */
    @GetMapping("/dba/views")
    public Map<String, Object> dbaViews() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("views", DBA_VIEWS);
        return result;
    }

    /** {@code hours} 的夹取：小于 1 抬到 1，大于上限压到上限。语义与 {@link #clampLimit} 一致。 */
    private static int clampWindowHours(int hours) {
        if (hours < 1) {
            return 1;
        }
        return Math.min(hours, MAX_WINDOW_HOURS);
    }
}
