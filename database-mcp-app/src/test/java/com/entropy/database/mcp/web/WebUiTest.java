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
package com.entropy.database.mcp.web;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 只读运维页面的端到端契约：{@code WebUiController} 的各个端点、限值夹取、审计未落库时的降级路径，
 * 以及<strong>构建出来的前端产物真的被服务出去</strong>。
 *
 * <p>最后一条是这里最值得测的：静态资源在 IDE 里从 {@code target/classes/static} 直接可见，
 * 打成 fat jar 之后走的是另一套 {@code ClassPathResource} 解析，「本地好、jar 里 404」是这类改动
 * 最典型的失败形态。这个测试跑的是完整应用上下文（含 Boot 的
 * {@code WelcomePageHandlerMapping}），所以 {@code GET /} 和 {@code GET /index.html} 两条路径都被钉住。
 *
 * <h2>为什么资源名是从 index.html 里解析出来的，而不是写死</h2>
 * <p>0.6.0 起页面由 Vite 构建，产物带内容 hash（{@code assets/index-DKIYf9Qg.js}）。
 * 把 hash 写进断言的话，前端每改一行、重新构建，这个测试就红一次——而它红的原因和被测行为无关。
 * 所以这里的做法是：先取 {@code index.html}，用正则把它自己引用的 {@code /assets/...} 路径抠出来，
 * 再逐个请求。这样断言的是「HTML 里引用的每一个资源都取得到」这个真正的契约，
 * 与 hash 是什么无关；顺带还能抓到"HTML 引用了一个不存在的产物"这种更隐蔽的破法。
 *
 * <p>{@code authEnabled} 在两个方向上各钉一次：页面顶部的无鉴权横幅完全由它驱动，报错了就等于横幅
 * 会在错误的部署上出现或消失。
 */
class WebUiTest {

    private static final String ADMIN_PASSWORD = "web-ui-test-password";
    private static final String ADMIN_PASSWORD_PROPERTY = "mcp.security.admin-password";

    /**
     * Vite 产物引用的匹配式。覆盖 {@code <script src="/assets/x.js">} 与
     * {@code <link href="/assets/x.css">} 两种写法，因此只认 {@code /assets/} 前缀这一点，
     * 不去区分标签——标签形式是 Vite 的实现细节，前缀才是和 {@code SecurityConfig} 约定好的东西。
     */
    private static final Pattern ASSET_REFERENCE = Pattern.compile("[\"'](/assets/[^\"']+)[\"']");

    /** 从 index.html 正文里抠出它引用的所有 /assets/ 路径。 */
    static List<String> assetPaths(String indexHtml) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = ASSET_REFERENCE.matcher(indexHtml);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    /** 与 {@code SecurityConfigTest} 同样的理由：surefire 复用 JVM，设了必须清掉。 */
    @BeforeAll
    static void setAdminPassword() {
        System.setProperty(ADMIN_PASSWORD_PROPERTY, ADMIN_PASSWORD);
    }

    @AfterAll
    static void clearAdminPassword() {
        System.clearProperty(ADMIN_PASSWORD_PROPERTY);
    }

    private static String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** 公共夹具：带上 Spring Security 过滤器链的 MockMvc，否则测不到 401。 */
    abstract static class WithMockMvc {

        @Autowired
        WebApplicationContext context;

        @Autowired
        @Qualifier("springSecurityFilterChain")
        Filter springSecurityFilterChain;

        MockMvc mockMvc;

        @BeforeEach
        void setUpMockMvc() {
            this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(springSecurityFilterChain)
                    .build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> json(MvcResult result) throws Exception {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(result.getResponse().getContentAsString(), Map.class);
        }
    }
    // ─── 目标部署形态：鉴权关闭、审计未落库 ────────────────────────────────

    /**
     * 现网部署的实际配置：{@code entropy.mcp.security.enabled=false} 且没有
     * {@code spring.datasource.url}。这一组用例描述的就是运维实际会看到的那个页面。
     */
    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=false"
    })
    class AuthDisabledWithoutAuditPersistence extends WithMockMvc {

        @Test
        void configReportsUnauthenticatedAndNoPersistence() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/ui/config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn();
            Map<String, Object> config = json(result);
            assertThat(config.get("authEnabled")).isEqualTo(false);
            assertThat(config.get("auditPersistence")).isEqualTo(false);
            assertThat(config.get("maxLimit")).isEqualTo(500);
        }

        /** 降级路径：config 说没落库，history 就必须是 503，而不是空数组或 500。 */
        @Test
        void auditHistoryIsUnavailableWithoutDatasource() throws Exception {
            mockMvc.perform(get("/api/audit/history"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(503));
        }

        @Test
        void auditLogsBufferIsServed() throws Exception {
            mockMvc.perform(get("/api/audit/logs?limit=5"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString()).startsWith("["));
        }

        @Test
        @SuppressWarnings("unchecked")
        void connectionsExposesRegisteredAndPoolSections() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/connections"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());

            Map<String, Object> registered = (Map<String, Object>) body.get("registered");
            assertThat(registered).containsKeys("totalConnections", "activeConnections", "connections");
            assertThat(registered.get("connections")).isInstanceOf(List.class);

            Map<String, Object> pools = (Map<String, Object>) body.get("pools");
            assertThat(pools).containsKeys("totalConnections", "totalConnectionNames",
                    "healthyPools", "degradedPools", "aliasNames", "pools");
            assertThat(pools.get("pools")).isInstanceOf(List.class);
        }

        @Test
        void performanceExposesSummarySlowQueriesPatternsAndMetrics() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/performance"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());

            assertThat(body).containsKeys("limit", "summary", "slowQueries",
                    "totalTrackedPatterns", "patterns", "metrics");
            assertThat(body.get("slowQueries")).isInstanceOf(List.class);
            assertThat(body.get("patterns")).isInstanceOf(List.class);
            // 默认值，未传 limit 时
            assertThat(body.get("limit")).isEqualTo(20);
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) body.get("summary");
            assertThat(summary).containsKeys("totalQueries", "slowQueryCount",
                    "slowQueryThresholdMs", "trackedPatterns");
        }

        /** 上限夹取：回显的 limit 必须是真实生效的那个值，否则页面会显示一个骗人的条数。 */
        @Test
        void limitIsClampedToMaximum() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/performance?limit=9999"))
                    .andReturn());
            assertThat(body.get("limit")).isEqualTo(500);
        }

        @Test
        void limitIsClampedToMinimum() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/performance?limit=0"))
                    .andReturn());
            assertThat(body.get("limit")).isEqualTo(1);

            Map<String, Object> negative = json(mockMvc.perform(get("/api/ui/performance?limit=-7"))
                    .andReturn());
            assertThat(negative.get("limit")).isEqualTo(1);
        }

        // ─── 服务自述 ──────────────────────────────────────────────────

        /**
         * {@code /api/ui/info} 的四个开关必须全在，且 {@code version} 不能是空。
         *
         * <p>{@code version} 那一条断言的是 Maven 资源过滤真的把 {@code @project.version@} 填进了
         * {@code application.yml}：过滤没生效时这里是字面量 {@code @project.version@} 或者
         * {@code unknown}（{@code @Value} 的默认值），页面页脚就会显示一个假版本号。
         * 不断言具体版本号——那会让每次发版都红一次，而它红的原因和被测行为无关。
         *
         * <p>{@code switches} 逐键断言而不是只看 map 非空：少报一个开关的表现是页面上少一行，
         * 没有任何报错，而那一行恰好是「这台服务器裸跑吗」这类问题的答案。
         */
        @Test
        @SuppressWarnings("unchecked")
        void infoReportsVersionProfilesAndSwitches() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/info"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());

            assertThat(body).containsKeys("serviceName", "version", "activeProfiles",
                    "startedAt", "uptimeSeconds", "switches", "toolCount");
            assertThat((String) body.get("version"))
                    .as("version 必须来自 spring.ai.mcp.server.version；空或 @project.version@ 说明资源过滤没生效")
                    .isNotBlank()
                    .doesNotContain("project.version");
            assertThat(body.get("activeProfiles")).isInstanceOf(List.class);
            assertThat((String) body.get("startedAt")).isNotBlank();

            Map<String, Object> switches = (Map<String, Object>) body.get("switches");
            assertThat(switches).containsKeys("authEnabled", "allowUnauthenticatedInProduction",
                    "gatewayEnabled", "auditPersistence");
            // 本上下文的配置：鉴权关、没有 spring.datasource.url、gateway 显式关。
            assertThat(switches.get("authEnabled")).isEqualTo(false);
            assertThat(switches.get("auditPersistence")).isEqualTo(false);
            assertThat(switches.get("gatewayEnabled")).isEqualTo(false);

            Map<String, Object> toolCount = (Map<String, Object>) body.get("toolCount");
            assertThat(toolCount).containsKeys("total", "exposed");
            assertThat((Integer) toolCount.get("total")).isPositive();
        }

        /**
         * {@code /api/ui/tools} 必须真的列出工具，不能是一份空目录。
         *
         * <p>{@code total > 0} 钉住的是 {@code ToolCatalog} 的惰性索引在 HTTP 线程上也建得起来：
         * 它依赖 {@code ObjectProvider<McpToolBase>}，构建时机出问题的表现就是一份空清单而不是报错。
         * 不断言具体数字——工具增删是常事，数字会让这个测试变成一份需要人工同步的快照。
         */
        @Test
        @SuppressWarnings("unchecked")
        void toolsListsExposedDescriptors() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/tools"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());

            assertThat(body).containsKeys("total", "exposed", "groups", "tools");
            assertThat((Integer) body.get("total")).isPositive();
            assertThat(body.get("groups")).isInstanceOf(List.class);

            List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
            assertThat(tools).isNotEmpty();
            assertThat(tools.get(0)).containsKeys("name", "group", "summary", "tags");
        }

        // ─── 审计报告 ──────────────────────────────────────────────────

        /**
         * 窗口回显 + 三块内容；审计未落库时两个报告是 {@code skipped} 而不是错误。
         *
         * <p>窗口那三个键（{@code hours}/{@code from}/{@code to}）是这里最值得钉的：报告本身不带
         * 「我覆盖了哪段时间」，窗口是端点算出来再传给工具的，不回显的话页面上就是一份无从解释的数字。
         *
         * <p>{@code dataAccess.status=skipped} 与 {@code /api/ui/config} 的
         * {@code auditPersistence=false} 是同一个原因（没有 AuditLogRepository bean）。这条断言
         * 与 {@code AuditPersistenceConfigured} 里那条互为反面，两边一起才说明这个降级是由配置驱动的。
         */
        @Test
        @SuppressWarnings("unchecked")
        void auditReportsEchoWindowAndSkipReportsWithoutPersistence() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/audit-reports"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());

            assertThat(body).containsKeys("hours", "from", "to", "limit",
                    "metrics", "dataAccess", "protection");
            assertThat(body.get("hours")).isEqualTo(24);
            assertThat(body.get("limit")).isEqualTo(100);
            assertThat((String) body.get("from")).isNotBlank();
            assertThat((String) body.get("to")).isNotBlank();

            Map<String, Object> metrics = (Map<String, Object>) body.get("metrics");
            assertThat(metrics).containsKeys("totalQueries", "slowQueryCount", "slowQueryRate",
                    "slowQueryThresholdMs", "trackedPatterns", "maxSlowQueries", "maxSqlPatterns");

            Map<String, Object> dataAccess = (Map<String, Object>) body.get("dataAccess");
            assertThat(dataAccess.get("status"))
                    .as("没有 spring.datasource.url 时数据访问报告必须是 skipped，而不是空报告或 500")
                    .isEqualTo("skipped");
            assertThat(dataAccess).containsKey("reason");

            Map<String, Object> protection = (Map<String, Object>) body.get("protection");
            assertThat(protection.get("status")).isEqualTo("skipped");
            assertThat(protection).containsKey("reason");
        }

        /** {@code hours} 与 {@code limit} 一样按夹取后的值回显，理由同 performance 的 limit。 */
        @Test
        void auditReportWindowIsClamped() throws Exception {
            Map<String, Object> tooLarge = json(mockMvc.perform(
                            get("/api/ui/audit-reports?hours=9999&limit=9999"))
                    .andReturn());
            assertThat(tooLarge.get("hours")).isEqualTo(168);
            assertThat(tooLarge.get("limit")).isEqualTo(500);

            Map<String, Object> tooSmall = json(mockMvc.perform(
                            get("/api/ui/audit-reports?hours=0&limit=-3"))
                    .andReturn());
            assertThat(tooSmall.get("hours")).isEqualTo(1);
            assertThat(tooSmall.get("limit")).isEqualTo(1);
        }

        // ─── 参数校验：400 而不是 500 ──────────────────────────────────

        /**
         * {@code /api/ui/pool} 不带 {@code connection} 是 400。
         *
         * <p>不挡的话这个请求会走到工具的必填校验，抛 {@code McpToolException} → HTTP 500。
         * 「调用方少传参数」和「服务器坏了」在页面上的处理不同（前者不该弹重试、不该报警）。
         */
        @Test
        void poolRequiresConnection() throws Exception {
            mockMvc.perform(get("/api/ui/pool"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
        }

        /** 未知 {@code view} 是 400；不是 500，也不是一个静默的空结果。 */
        @Test
        void dbaRejectsUnknownView() throws Exception {
            mockMvc.perform(get("/api/ui/dba?view=nope"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
            // 缺失同样按非法处理：省略 view 时没有一个「默认视图」是合理的猜测。
            mockMvc.perform(get("/api/ui/dba"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
        }

        /** {@code tableSize} 缺 {@code table} 是 400，与 pool 缺 connection 同一个理由。 */
        @Test
        void dbaTableSizeRequiresTable() throws Exception {
            mockMvc.perform(get("/api/ui/dba?view=tableSize"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
        }

        /**
         * {@code /api/ui/dba/views} 是前端选择器的唯一数据源，空清单等于那个选择器渲染不出来。
         *
         * <p>不断言清单内容，只断言条数、以及和 {@link #dbaRejectsUnknownView} 里那个非法值不沾边：
         * 把十二个 view 名字抄进断言只会让「加一个视图」变成改两处。条数<b>要</b>断言——
         * {@code DBA_VIEWS} 与 {@code dba} 的分派是两处手写的清单，往常量里加了名字却忘了加分派
         * 只会在有人真的点那个视图时表现为 500，而这条断言让「加了几个」至少是显式的。
         */
        @Test
        @SuppressWarnings("unchecked")
        void dbaViewsListsSupportedViews() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/dba/views"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            List<String> views = (List<String>) body.get("views");
            assertThat(views).hasSize(12).doesNotContain("nope");
        }

        /** {@code grants} 缺 {@code userName} 是 400，与 tableSize 缺 table 同一个理由。 */
        @Test
        void dbaGrantsRequiresUserName() throws Exception {
            mockMvc.perform(get("/api/ui/dba?view=grants"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
        }

        /**
         * {@code indexStatus} 缺 {@code table} 是 400。
         *
         * <p>这一条钉的是控制器<b>刻意收紧</b>的语义：{@code showIndexStatus} 的 tableName 在工具
         * 签名里是可选的（省略即返回整个 Schema 的索引），页面这条路径上不给这个默认，
         * 因为触发它只需要选中视图却忘了填表名。有人"照工具签名对齐"把这里放开时，这条会变红。
         */
        @Test
        void dbaIndexStatusRequiresTable() throws Exception {
            mockMvc.perform(get("/api/ui/dba?view=indexStatus"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
        }

        // ─── 浏览端点：纯配置的形状 ────────────────────────────────────

        /**
         * 六个不连库的端点在 {@code dialect=generic} + 无真实库的环境下必须 200 并带上关键键。
         *
         * <p>只测这六个是刻意的：{@code WebUiExplorerController} 的其余端点都会真的对业务库发 SQL，
         * 在这个上下文里必然失败，测它们等于把「连不上库」写成断言。这六个是页面在<b>什么连接都没有</b>
         * 时唯一能显示出内容的东西，也是判断「某个模块有没有开」的唯一入口。
         *
         * <p>{@code /lineage/config} 的 {@code foreignKeyEnabled} 单独断言：页面必须把它和空血缘图
         * 一起显示，否则空图无法自解释（库里没外键 / 开关关掉，看起来都像"这张表没有上下游"）。
         * 这个键从返回里消失时，前端不会报错，只会安静地少显示那一行。
         */
        @Test
        void readOnlyConfigEndpointsAreServedWithoutADatabase() throws Exception {
            Map<String, Object> catalogConfig = json(mockMvc.perform(get("/api/ui/catalog/config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(catalogConfig).containsKeys("enabled", "maxSearchResults");

            Map<String, Object> lineageConfig = json(mockMvc.perform(get("/api/ui/lineage/config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(lineageConfig)
                    .as("页面要把 foreignKeyEnabled 和空血缘图一起显示，否则空图无法自解释")
                    .containsKeys("enabled", "foreignKeyEnabled", "maxTraversalDepth", "maxTablesPerGraph");

            Map<String, Object> backupConfig = json(mockMvc.perform(get("/api/ui/backups/config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(backupConfig).containsKey("enabled");

            Map<String, Object> cdcConfig = json(mockMvc.perform(get("/api/ui/cdc/config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(cdcConfig).containsKey("enabled");

            Map<String, Object> optimizerConfig = json(mockMvc.perform(get("/api/ui/sql/optimizer-config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(optimizerConfig).containsKeys("enabled", "maxSuggestionsPerQuery");

            Map<String, Object> templates = json(mockMvc.perform(get("/api/ui/quality/templates"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(templates.get("templates")).isInstanceOf(List.class);
        }

        /**
         * {@code /api/ui/jobs} 在 gateway 关闭时是 200 + {@code enabled=false}，不是 500。
         *
         * <p>这条钉住的是 {@code EtlTools} 的可缺失注入：那个 bean 挂在
         * {@code @ConditionalOnProperty(entropy.mcp.gateway.enabled)} 上且没有 matchIfMissing，
         * 写成必需依赖时本上下文（gateway 显式关）会在<b>启动阶段</b>就失败——表现是这个类
         * 全部用例一起红，而根因和作业列表没关系。{@code reason} 一起断言：
         * 页面要显示「本部署未启用 ETL」这句话，而不是一个空列表。
         */
        @Test
        void jobsReportsDisabledGatewayInsteadOfFailing() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/jobs"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(body.get("enabled")).isEqualTo(false);
            assertThat(body).containsKeys("reason", "property");
        }

        /**
         * 必填参数缺失一律 400，不是 500。
         *
         * <p>四条覆盖四种不同的必填形态：路径上唯一的表名（{@code /schema/table}）、
         * 血缘的分析对象（{@code /lineage}）、SQL 本体（{@code /sql/risk}）、
         * 以及一个纯内存查找的 id（{@code /backups/detail}）。它们分别落在不同的工具上，
         * 而这些工具的必填校验都会抛 {@code McpToolException} → 500，
         * 「调用方少传参数」和「服务器坏了」在页面上的处理不同（前者不该弹重试、不该报警）。
         *
         * <p>{@code /backups/detail} 那条尤其值得留着：它<b>不连库</b>，所以如果哪天这里退化成 500，
         * 那一定是控制器的校验掉了，而不是环境里没有数据库。
         */
        @Test
        void explorerEndpointsRejectMissingRequiredParams() throws Exception {
            mockMvc.perform(get("/api/ui/schema/table"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
            mockMvc.perform(get("/api/ui/lineage"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
            mockMvc.perform(get("/api/ui/sql/risk"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
            mockMvc.perform(get("/api/ui/backups/detail"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
        }

        // ─── 静态页面 ──────────────────────────────────────────────────

        /**
         * 根路径走 Boot 的 {@code WelcomePageHandlerMapping}，它是 {@code forward:index.html}。
         *
         * <p>这里断言的是转发目标而不是 HTML 正文：MockMvc 不执行 forward，只把目标记在
         * {@code forwardedUrl} 上，正文因此是空的。真正「/ 返回 text/html 且有内容」由打包后的
         * jar 上的 curl 覆盖（见 README 的验证一节），此处只钉住「/ 有人接管且指向 index.html」。
         */
        @Test
        void rootForwardsToTheHtmlPage() throws Exception {
            MvcResult result = mockMvc.perform(get("/"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn();
            assertThat(result.getResponse().getForwardedUrl()).contains("index.html");
        }

        /**
         * index.html 必须是构建出来的那一个：有 React 的挂载点，也有主题属性。
         *
         * <p>{@code data-astryx-theme} 这一条不是凑数的：Astryx 的主题 CSS 全部包在
         * {@code @scope ([data-astryx-theme="neutral"])} 里，属性掉了页面照样"能打开"，
         * 但所有 design token 都不会被赋值——渲染出来是一堆无样式的裸元素，而且不报任何错。
         * 这种坏法在 MockMvc 里唯一能抓到的形式就是断言这个属性还在。
         */
        @Test
        void indexHtmlIsServedDirectly() throws Exception {
            MvcResult result = mockMvc.perform(get("/index.html"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn();
            assertThat(result.getResponse().getContentType()).startsWith("text/html");
            String html = result.getResponse().getContentAsString();
            assertThat(html).contains("id=\"root\"");
            assertThat(html).contains("data-astryx-theme=\"neutral\"");
        }

        /**
         * index.html 引用的每一个 hash 化产物都必须取得到。
         *
         * <p>不写死 hash，见类注释。至少要有一个 .js 和一个 .css：只有 js 说明样式没打进去，
         * 页面会是一个无样式的白板；一个都没有说明 index.html 不是 Vite 的产物（很可能是
         * 前端构建被跳过、而 target/classes/static 里留着别的东西）。
         */
        @Test
        void hashedAssetsReferencedByIndexHtmlAreRetrievable() throws Exception {
            String html = mockMvc.perform(get("/index.html"))
                    .andReturn().getResponse().getContentAsString();
            List<String> assets = assetPaths(html);

            assertThat(assets)
                    .as("index.html 必须引用 Vite 产出的 /assets/ 资源；空清单意味着前端产物没进 classpath")
                    .isNotEmpty();
            assertThat(assets).anyMatch(p -> p.endsWith(".js"));
            assertThat(assets).anyMatch(p -> p.endsWith(".css"));

            for (String asset : assets) {
                MvcResult assetResult = mockMvc.perform(get(asset))
                        .andExpect(r -> assertThat(r.getResponse().getStatus())
                                .as("index.html 引用的资源 %s 必须可取", asset)
                                .isEqualTo(200))
                        .andReturn();
                assertThat(assetResult.getResponse().getContentAsByteArray())
                        .as("资源 %s 不能是空文件", asset)
                        .isNotEmpty();
            }
        }

        /** 页面不能把 MCP 协议端点顶掉：/mcp 仍然由 MCP 的 router function 处理。 */
        @Test
        void staticResourcesDoNotShadowMcpOrActuator() throws Exception {
            mockMvc.perform(get("/mcp"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(404));
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        }
    }
    // ─── 鉴权打开 ──────────────────────────────────────────────────────────

    /**
     * 鉴权打开时，页面必须仍然是<strong>能打开的</strong>（带 admin 凭证），并且在没有凭证时是 401。
     *
     * <p>这里钉住的是一个实际会被踩到的坑：{@code SecurityConfig} 打开鉴权后以
     * {@code anyRequest().denyAll()} 收尾，静态资源不显式列进 {@code ROLE_ADMIN} 白名单的话，
     * 页面在所有开了鉴权的部署上都是 403——连管理员也打不开。
     *
     * <p>{@code spring.application.name} 那一行不是配置需求，是为了让本类的上下文<b>不与
     * {@code SecurityConfigTest.AuthenticationEnabled} 共享</b>。Spring 的测试上下文缓存以合并后的
     * 配置为 key，而那个类的四个 property 与这里原本完全一致，于是两边共用同一个上下文；
     * 但 admin 密码是 {@code SecurityConfig} 在<b>建上下文那一刻</b>从系统属性读死的，
     * 谁先建就是谁的密码，后来的那个类拿自己的密码去认证只会得到 401——单独跑绿、全量跑红。
     * 加一个只影响 cache key 的属性把两个上下文分开，比让两个类去共享一个密码常量更不容易再坏。
     */
    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=true",
        "spring.application.name=web-ui-auth-enabled-test"
    })
    class AuthEnabled extends WithMockMvc {

        @Test
        void configReportsAuthEnabledForTheAdmin() throws Exception {
            Map<String, Object> config = json(mockMvc.perform(get("/api/ui/config")
                            .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD)))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(config.get("authEnabled")).isEqualTo(true);
        }

        @Test
        void uiApisRequireCredentials() throws Exception {
            mockMvc.perform(get("/api/ui/config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/connections"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/performance"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/info"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/tools"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/audit-reports"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            // 下面三条不带凭证时必须先撞上 401，而不是先走到参数校验的 400：
            // 过滤器链在 DispatcherServlet 之前，顺序反了意味着未鉴权的调用方能靠状态码
            // 区分「参数不对」和「端点不存在」，等于一个探测接口。
            mockMvc.perform(get("/api/ui/pool"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/dba?view=nope"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/dba/views"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            // WebUiExplorerController 与本类共用 /api/ui 前缀，靠的是 SecurityConfig 里
            // /api/** 那条 Ant 多段通配自动收编新路径。这几条覆盖三种情况：纯配置端点、
            // 必填参数缺失的端点、以及一个会连库的端点——全部必须在 401 上停住，
            // 不能先走到参数校验的 400（那等于给未鉴权的调用方一个探测接口），
            // 更不能因为路径没被规则覆盖而 200。
            mockMvc.perform(get("/api/ui/lineage/config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/catalog/config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/jobs"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/schema/table"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/sql/risk?sql=select%201"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/schema/schemas"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        }

        /** 页面本身也要凭证：它和 /api/** 是同一档，不存在「页面能开、表格全 401」的中间态。 */
        @Test
        void thePageItselfRequiresCredentials() throws Exception {
            mockMvc.perform(get("/"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/index.html"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        }

        /**
         * 页面与它的产物在带凭证时都要能取到。
         *
         * <p>这一条是 {@code /assets/**} 那条通配规则的守门人：如果哪天有人"为了更精确"
         * 把白名单改回具体文件名，构建出的新 hash 会立刻让这里变红，而不是等到部署后
         * 在生产环境看到一个空白页。
         */
        @Test
        void thePageIsReachableWithAdminCredentials() throws Exception {
            MvcResult result = mockMvc.perform(get("/index.html")
                            .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD)))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn();
            assertThat(result.getResponse().getContentType()).startsWith("text/html");

            List<String> assets = assetPaths(result.getResponse().getContentAsString());
            assertThat(assets).isNotEmpty();
            for (String asset : assets) {
                mockMvc.perform(get(asset)
                                .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD)))
                        .andExpect(r -> assertThat(r.getResponse().getStatus())
                                .as("带 admin 凭证时 %s 必须是 200；403 说明它落在了白名单之外", asset)
                                .isEqualTo(200));
            }
        }

        /** 反向：产物也是 ROLE_ADMIN 档，不带凭证时是 401，而不是悄悄放行。 */
        @Test
        void assetsRequireCredentialsToo() throws Exception {
            String html = mockMvc.perform(get("/index.html")
                            .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse().getContentAsString();
            for (String asset : assetPaths(html)) {
                mockMvc.perform(get(asset))
                        .andExpect(r -> assertThat(r.getResponse().getStatus())
                                .as("%s 不带凭证必须是 401", asset)
                                .isEqualTo(401));
            }
        }
    }

    // ─── 审计已落库 ────────────────────────────────────────────────────────

    /**
     * 另一半：配了 {@code spring.datasource.url} 时 {@code auditPersistence} 必须翻成 {@code true}，
     * 并且 history 从 503 变成正常返回。用进程内 H2 是因为这条路径要验证的是「有没有那个 bean」，
     * 而不是某个具体产品的 DDL。
     */
    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:web_ui_audit_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    })
    class AuditPersistenceConfigured extends WithMockMvc {

        @Test
        void configReportsPersistenceEnabled() throws Exception {
            Map<String, Object> config = json(mockMvc.perform(get("/api/ui/config"))
                    .andReturn());
            assertThat(config.get("auditPersistence")).isEqualTo(true);
        }

        @Test
        void auditHistoryAnswersInsteadOf503() throws Exception {
            mockMvc.perform(get("/api/audit/history?limit=5"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString()).startsWith("["));
        }

        /**
         * {@code AuthDisabledWithoutAuditPersistence} 里那条 {@code skipped} 断言的反面：
         * 有了 {@code spring.datasource.url} 撑起的 {@code AuditLogRepository}，
         * 数据访问报告就必须真的查一次库。
         *
         * <p>只断言「不是 skipped」而不是等于 {@code completed}：两者的差别只有仓储抛不抛异常，
         * 而这里要钉的是「报告的可用性由那个 bean 在不在决定」，不是 H2 的 DDL 行为。
         */
        @Test
        @SuppressWarnings("unchecked")
        void auditReportsAreNotSkippedWithPersistence() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/audit-reports?hours=1&limit=5"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            Map<String, Object> dataAccess = (Map<String, Object>) body.get("dataAccess");
            assertThat(dataAccess.get("status"))
                    .as("配了 spring.datasource.url 之后数据访问报告不该再被跳过")
                    .isNotEqualTo("skipped");
        }
    }


}
