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

import com.entropy.database.mcp.audit.AuditLogEntity;
import com.entropy.database.mcp.audit.AuditLogRepository;
import com.entropy.database.mcp.audit.ComplianceReportService;
import com.entropy.database.mcp.audit.SqlAuditService;
import com.entropy.database.mcp.toggle.ToolToggleRepository;
import com.entropy.database.mcp.tools.ToolToggleRegistry;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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

        /**
         * 鉴权关闭时 {@code /api/ui/me} 必须是 <b>200 + authenticated=false</b>，不是 401。
         *
         * <p>这条钉的是顶栏头像的降级路径。回 401 会被前端 {@code api.js} 的
         * {@code notifyUnauthorized} 当成会话超时，把人踢到登录界面——而这个部署形态下
         * 那个界面登录不了任何东西（压根没有鉴权），页面会卡死在一个假的登录页上。
         *
         * <p>{@code username} 一并断言为 null：没有登录者时报一个空串或 "anonymous"
         * 会让头像渲染出一个看起来像真人的占位符。
         */
        @Test
        void meReportsNoPrincipalWhenAuthIsDisabled() throws Exception {
            Map<String, Object> me = json(mockMvc.perform(get("/api/ui/me"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(me.get("authEnabled")).isEqualTo(false);
            assertThat(me.get("authenticated")).isEqualTo(false);
            assertThat(me.get("username")).isNull();
        }

        /** 降级路径：config 说没落库，history 就必须是 503，而不是空数组或 500。 */
        @Test
        void auditHistoryIsUnavailableWithoutDatasource() throws Exception {
            mockMvc.perform(get("/api/audit/history"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(503));
        }

        /**
         * 身份表也挂在 {@code spring.datasource.url} 上：三个端点在这个部署形态下全是 503。
         *
         * <p>三个动词都测而不是只测 GET：前端「身份管理」页把 503 渲染成"功能未启用"的 info 横幅
         * 而不是故障，那个分支读的正是 {@code error.status}。哪天写路径变成 500（比如有人给控制器
         * 加了非空断言），页面就会把一个受支持的部署形态显示成服务器故障。
         *
         * <p>POST 的体刻意是个空对象：没有 {@code UserAdminService} 时必须在校验请求体之前就停在
         * 503，而不是先回一个"用户名不能为空"的 400——后者会让运维去找一个填不对的表单，
         * 而真相是这台服务器没配状态库。
         */
        @Test
        void userAdminApisAreUnavailableWithoutDatasource() throws Exception {
            mockMvc.perform(get("/api/users"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(503));
            mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(503));
            mockMvc.perform(delete("/api/users/whoever"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(503));
        }

        @Test
        void auditLogsBufferIsServed() throws Exception {
            mockMvc.perform(get("/api/audit/logs?limit=5"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString()).startsWith("["));
        }

        /**
         * {@code /api/authz} 在真实上下文里的两件事：<b>bean 接得上</b>，以及默认部署下
         * {@code enabled=false}。
         *
         * <p>前者是这条用例的主要价值——{@code AuthzViewController} 住在 tools 模块、
         * {@code ToolAuthzProperties} 住在 infra 的 {@code authz} 包，而后者不在
         * {@code @ConfigurationPropertiesScan} 覆盖的 {@code properties} 包里，靠 {@code DatabaseConfig}
         * 上那个 {@code @EnableConfigurationProperties} 显式带上。哪天有人删掉那一行，
         * 症状是<b>整个应用起不来</b>（控制器构造不出来），而单测一条都抓不到。
         *
         * <p>后者是页面的判断依据：{@code enabled=false} 时授权判定全放行，页面要出 error 横幅说
         * "这张表不反映实际权限"。断言它默认是 false，等于钉住"这是个必须由部署方显式打开的开关"。
         */
        @Test
        @SuppressWarnings("unchecked")
        void authzViewIsServedAndReportsTheSwitchAsOffByDefault() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/authz"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());

            assertThat(body).containsKeys("enabled", "grantCount", "subjectCount",
                    "connectionCount", "roles", "grants");
            assertThat(body.get("enabled"))
                    .as("授权是破坏性开关，默认必须是关的")
                    .isEqualTo(false);
            assertThat((List<Map<String, Object>>) body.get("roles"))
                    .as("角色图例由后端给，前端不写第二份——它为空会让页面画不出读写能力")
                    .isNotEmpty();
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

        @Autowired
        SqlAuditService sqlAuditService;

        /**
         * 慢查询缓冲里存在 {@code connectionKey} 为 null 的记录时，性能页必须照样 200。
         *
         * <p>这不是构造出来的边界值：不带连接参数的工具调用（{@code registerPinned}、
         * {@code getDatabaseInfo} 之类）在审计里的 {@code connectionKey} 本来就是 null，
         * 现网 {@code /api/audit/history} 的返回里就有。只要这类调用中有一条耗时越过慢查询阈值
         * 进了缓冲，{@code getSlowQueries} 的 {@code Map.of} 就会因为 null value 抛 NPE，
         * 被工具层包成 {@code McpToolException} → HTTP 500，整个「性能」页打不开。
         *
         * <p>先记录再请求是这条用例成立的前提：缓冲为空时那段 map 构造根本不会执行，端点照样 200。
         * 阈值默认 5000ms，所以这里给 6000ms。
         *
         * <p>断言 {@code connectionKey} 仍是 null 而不是空串：{@code "—"} 是前端
         * {@code displayValue()} 的职责，后端把 null 折成字符串会让「这次调用没有连接名」和
         * 「连接名就叫那个字符串」不可区分。
         */
        @Test
        @SuppressWarnings("unchecked")
        void performanceServesSlowQueriesWithoutConnectionKey() throws Exception {
            sqlAuditService.recordQuery("slowQueryWithoutConnection",
                    "SELECT 1 FROM slow_without_connection", 1, 6000L, true, null);

            Map<String, Object> body = json(mockMvc.perform(get("/api/ui/performance"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus())
                            .as("慢查询记录缺连接名不该让性能页 500")
                            .isEqualTo(200))
                    .andReturn());

            List<Map<String, Object>> slowQueries = (List<Map<String, Object>>) body.get("slowQueries");
            Map<String, Object> recorded = slowQueries.stream()
                    .filter(entry -> "slowQueryWithoutConnection".equals(entry.get("tool")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("刚记录的慢查询没有出现在 performance 返回里"));

            assertThat(recorded).containsKeys("tool", "sql", "rows", "durationMs",
                    "connectionKey", "timestamp");
            assertThat(recorded.get("connectionKey"))
                    .as("缺连接名的慢查询必须保持 null，不能被后端改写成空串或短横线")
                    .isNull();
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

        /**
         * 鉴权打开时 {@code /api/ui/me} 报出<b>登录者是谁</b>。顶栏头像与个人信息框读的就是它。
         *
         * <p><b>这一组的部署形态下 {@code type} 与 {@code subjectRef} 必定是 null</b>，而且这正是
         * 要钉住的东西。本类没配 {@code spring.datasource.url} 也没配 {@code users-file}，于是
         * {@code SecurityConfig.userDetailsService} 走到最后一行 {@code new
         * InMemoryUserDetailsManager(List.of(admin))}；那个类的 {@code loadUserByUsername} 会把
         * {@code McpPrincipal} 重新包装成 Spring 自己的 {@code User}，{@code type} 在这一步丢掉，
         * 所以 {@code /me} 只能落到它的 else 分支。
         *
         * <p>决定它有没有值的是<b>哪一个 UserDetailsService 在起作用</b>，不是用了表单登录还是
         * HTTP Basic——两种机制都从同一个 service 取用户。配了状态库（{@code UserStoreUserDetailsService}）
         * 时才会真的拿到 {@code McpPrincipal}，那条路目前没有对应的 {@code @Nested}：它要求
         * 「鉴权打开 + 有状态库」，而现有两组各占一半。
         *
         * <p>断言的是<b>键存在且值为 null</b>，不是键缺失：{@code WebUiController.me} 的注释要求前端
         * 按可空处理、拿不到时只显示用户名，而「键不存在」和「键为 null」在 JS 里同样是 undefined/null，
         * 但契约上前者意味着后端换了形状。
         *
         * <p>也刻意<b>不</b>断言 {@code subjectRef} 等于审计流水 {@code principal} 列的值：
         * {@code QueryAuditLoggerImpl.currentPrincipal()} 拿不到 {@code McpPrincipal} 时回落到
         * {@code ud.getUsername()}（不带 {@code user:} 前缀），在这个形态下两者本就不同。
         *
         * <p>{@code authorities} 用 authority 全名（{@code ROLE_ADMIN}），与 {@code /api/users}
         * 的 {@code roles} 同一口径：两处显示的字符串必须能对上，否则「身份管理」页里的
         * ROLE_ADMIN 和顶栏里的 ADMIN 会被当成两种东西。
         */
        @SuppressWarnings("unchecked")
        @Test
        void meReportsTheLoggedInPrincipal() throws Exception {
            Map<String, Object> me = json(mockMvc.perform(get("/api/ui/me")
                            .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD)))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(me.get("authEnabled")).isEqualTo(true);
            assertThat(me.get("authenticated")).isEqualTo(true);
            assertThat(me.get("username")).isEqualTo("admin");
            assertThat(me).containsKeys("type", "subjectRef");
            assertThat(me.get("type")).isNull();
            assertThat(me.get("subjectRef")).isNull();
            assertThat((List<String>) me.get("authorities")).contains("ROLE_ADMIN");
        }

        @Test
        void uiApisRequireCredentials() throws Exception {
            mockMvc.perform(get("/api/ui/config"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/ui/me"))
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
            // /api/authz 与 /api/users 都不在 /api/ui 之下，靠的是同一条 /api/** 规则收编。
            // 前者是一份"谁能碰哪张表"的全景图，后者能造出管理员——两条都必须在 401 上停住。
            mockMvc.perform(get("/api/authz"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
            mockMvc.perform(get("/api/users"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        }

        /**
         * 页面外壳与构建产物<b>匿名可读</b>（0.6.0 起的破坏性变更，与上一版这条用例相反）。
         *
         * <p>上一版断言的是 401：页面与 {@code /api/**} 同档。改成 permitAll 是为了让登录界面成为
         * SPA 自己的一个视图——它必须匿名可读，而它要加载 {@code /assets/**} 里的 bundle 才能渲染。
         * 判据变成了"保护数据而不是代码"：{@code /api/**} 仍然是 {@code ROLE_ADMIN}，
         * 匿名能拿到的只有 HTML 外壳与 JS/CSS，里面没有任何业务数据。
         */
        @Test
        void thePageShellIsPublicWhileApisStayProtected() throws Exception {
            mockMvc.perform(get("/"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
            mockMvc.perform(get("/index.html"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
            // 反面：数据端点一个都没放开
            mockMvc.perform(get("/api/ui/config"))
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

        /**
         * 产物也匿名可读——这一条是 {@code /assets/**} 那条通配规则的另一半守门人。
         *
         * <p>它取代了上一版的 {@code assetsRequireCredentialsToo}（断言 401）。反转的理由同上：
         * 未登录的浏览器要先加载 bundle 才能渲染登录表单。这里仍然逐个请求 index.html 引用到的产物，
         * 所以"白名单里的文件名过期了"那个坑照样能被抓到。
         */
        @Test
        void assetsArePublicToo() throws Exception {
            String html = mockMvc.perform(get("/index.html"))
                    .andReturn().getResponse().getContentAsString();
            List<String> assets = assetPaths(html);
            assertThat(assets).isNotEmpty();
            for (String asset : assets) {
                mockMvc.perform(get(asset))
                        .andExpect(r -> assertThat(r.getResponse().getStatus())
                                .as("%s 不带凭证必须是 200：登录界面在 SPA 里，它要先能加载", asset)
                                .isEqualTo(200));
            }
        }
    }

    // ─── 鉴权打开 + 有状态库：McpPrincipal 真的透得出来的那条路 ──────────────

    /**
     * {@code /api/ui/me} 的另一半：{@code type} 与 {@code subjectRef} <b>有值</b>的那个形态。
     *
     * <p>为什么要单独一个上下文：决定这两个字段有没有值的是「哪一个 {@code UserDetailsService}
     * 在起作用」，而那取决于有没有配 {@code spring.datasource.url}——
     * 配了走 {@code SecurityConfig.UserStoreUserDetailsService}（它对管理员直接
     * {@code return admin}，也就是 {@code McpPrincipal} 原物），没配走
     * {@code InMemoryUserDetailsManager}（它把 {@code McpPrincipal} 重新包装成 Spring 的
     * {@code User}，{@code type} 在这一步丢掉）。
     *
     * <p>这两个条件此前被现有的两组各占一半：{@code AuthEnabled} 有鉴权没库，
     * {@code UserAdminApi} 有库但 {@code security.enabled=false}。于是「配了状态库时
     * subjectRef 确实是 user:admin」这条从来没有被任何用例覆盖过，而它正是个人信息框
     * 显示「主体标识」、运维拿它去权限视图对照的依据。
     *
     * <p>{@code spring.application.name} 与库名都单独给一个，理由同 {@code UserAdminApi}
     * 上那段：admin 口令是建上下文那一刻读死的，共享上下文会让两组互相串。
     */
    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=true",
        "spring.application.name=web-ui-auth-with-user-store-test",
        "spring.datasource.url=jdbc:h2:mem:web_ui_user_store_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    })
    class AuthEnabledWithUserStore extends WithMockMvc {

        @SuppressWarnings("unchecked")
        @Test
        void meReportsTypeAndSubjectRefWhenThePrincipalSurvives() throws Exception {
            Map<String, Object> me = json(mockMvc.perform(get("/api/ui/me")
                            .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD)))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(me.get("authEnabled")).isEqualTo(true);
            assertThat(me.get("authenticated")).isEqualTo(true);
            assertThat(me.get("username")).isEqualTo("admin");
            // McpPrincipal.subjectRef() 是 type + ':' + username，前端与「权限视图」按这个形状对照
            assertThat(me.get("type")).isEqualTo("user");
            assertThat(me.get("subjectRef")).isEqualTo("user:admin");
            assertThat((List<String>) me.get("authorities")).contains("ROLE_ADMIN");
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

        @Autowired
        AuditLogRepository auditLogRepository;

        @Autowired
        ComplianceReportService complianceReportService;

        /**
         * 落一条「执行成功」的审计记录：{@code error} 与 {@code connectionKey} 都是 {@code null}。
         *
         * <p>这不是构造出来的边界值，而是成功路径的常态——成功记录没有错误信息（{@code error} 列本就为空），
         * 未指定连接名时 {@code connection_key} 也是空。两列在建表 DDL 里都可为空。
         *
         * <p>先插数据是这两条用例成立的前提：空表时 {@code getHistory} 的 stream map 与
         * {@code generateDataAccessReport} 的明细构造根本不会被执行，端点照样 200。
         *
         * @return 记录的时间戳，供报告用例框定查询区间
         */
        private Instant insertSuccessfulRowWithNullColumns(String tool) {
            Instant eventTime = Instant.now();
            auditLogRepository.insert(new AuditLogEntity(
                    null, tool, "SELECT 1", 1, 12L, true, null, eventTime, null, null));
            return eventTime;
        }

        /**
         * {@code /api/audit/history} 在库里存在成功记录（{@code error} 为 null）时必须 200，
         * 且该字段照样以 null 出现在返回里。
         *
         * <p>钉的是「后端不把 null 变成字符串」：{@code "—"} 是前端 {@code displayValue()} 的职责，
         * 后端一旦替换成空串或短横线，「字段确实为空」与「字段值就是短横线」就不可区分了。
         */
        @Test
        @SuppressWarnings("unchecked")
        void auditHistoryServesRowsWithNullError() throws Exception {
            insertSuccessfulRowWithNullColumns("historyNullError");

            MvcResult result = mockMvc.perform(get("/api/audit/history?limit=5"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn();

            List<Map<String, Object>> entries = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(result.getResponse().getContentAsString(), List.class);
            Map<String, Object> inserted = entries.stream()
                    .filter(entry -> "historyNullError".equals(entry.get("tool")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("刚插入的审计记录没有出现在 history 返回里"));

            assertThat(inserted).containsKeys("id", "tool", "sql", "rows", "durationMs",
                    "success", "error", "timestamp", "connectionKey");
            assertThat(inserted.get("error")).as("成功记录的 error 必须保持 null，不能被后端改写").isNull();
            assertThat(inserted.get("success")).isEqualTo(true);
        }

        /**
         * {@code getDataAccessReport} 对含 null 列的记录必须生成 {@code status=completed} 的报告。
         *
         * <p>NPE 会被 {@code generateDataAccessReport} 自己的 catch 吞成 {@code status=error}，
         * 所以这里断言的是 {@code completed} 而不是「不抛异常」——后者在修复前也成立。
         */
        @Test
        void dataAccessReportCompletesForRowsWithNullColumns() {
            Instant eventTime = insertSuccessfulRowWithNullColumns("reportNullError");

            Map<String, Object> report = complianceReportService.generateDataAccessReport(
                    eventTime.minusSeconds(60), eventTime.plusSeconds(60), 10);

            assertThat(report.get("status"))
                    .as("含 null 列的审计记录不该让报告降级成 error")
                    .isEqualTo("completed");
            assertThat((List<?>) report.get("entries")).isNotEmpty();
        }
    }

    // ─── 运行期启用/停用 MCP 工具 ──────────────────────────────────────────

    /**
     * {@code /api/tools} 跑在真实上下文里：这一组的第一价值是<b>上下文起得来</b>。
     *
     * <p>{@code ToolToggleRegistry} 要对 autoconfig 建出来的 {@code McpStatelessSyncServer} 做
     * add/removeTool，而那个 bean 的入参正是被 {@code ToolExposureFilter}（BeanPostProcessor）
     * 处理过的 spec 列表，而那个处理过程又要回调注册表的 {@code registerExposed}。这条链一旦被改成
     * 构造期直接注入就是循环依赖，表现是整个应用启动失败——单测一条都抓不到，只有这里会红。
     *
     * <p>{@code spring.application.name} 那一行不是配置需求，是为了让本类的上下文不与
     * {@code AuditPersistenceConfigured} 共享：Spring 的测试上下文缓存以合并后的配置为 key，
     * 属性完全相同的两个 {@code @Nested} 会共用一个上下文（见 {@code AuthEnabled} 上那段说明），
     * 而这一组会真的去停用工具、改动共享状态。给它一个独立的名字，两边互不影响。
     *
     * <p>配了 {@code spring.datasource.url}，所以这里同时是 {@code persisted=true} 那一侧的证明——
     * 它的反面（内存模式照常可用、{@code persisted=false}）在 {@code ToolAdminControllerTest} 里。
     */
    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=false",
        "spring.application.name=tool-toggle-admin-test",
        "spring.datasource.url=jdbc:h2:mem:tool_toggle_web_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    })
    class ToolToggleAdminApi extends WithMockMvc {

        @Autowired
        ToolToggleRegistry toggles;

        @Autowired
        McpStatelessSyncServer mcpServer;

        @Autowired
        JdbcTemplate jdbcTemplate;

        private List<String> listedTools() {
            return mcpServer.listTools().stream().map(McpSchema.Tool::name).toList();
        }

        private void setDisabled(String tool, boolean disabled) throws Exception {
            mockMvc.perform(put("/api/tools/" + tool)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"disabled\": " + disabled + "}"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        }

        /**
         * 注册表的暴露集快照与 server 真正持有的工具集必须对得上。
         *
         * <p>拿 {@code remainingExposed()} 比而不是拿快照大小比：同一个上下文里的其它用例可能正停着
         * 某个工具，而这条要钉的是「快照不是空的、且它与 {@code tools/list} 同步」这个不变量。
         * 快照为空的坏法尤其安静——开关会报"一个工具都动不了"，而不是报错。
         */
        @Test
        void theRegistrySnapshotMatchesWhatTheMcpServerHolds() {
            assertThat(toggles.exposedToolNames())
                    .as("快照必须在启动期由 ToolExposureFilter 喂进来；空的意味着那条回调没跑")
                    .isNotEmpty();
            assertThat(listedTools()).hasSize(toggles.remainingExposed());
            assertThat(toggles.exposedToolNames()).containsAll(listedTools());
        }

        @Test
        @SuppressWarnings("unchecked")
        void listReportsTheToolsAndSaysChangesArePersisted() throws Exception {
            Map<String, Object> body = json(mockMvc.perform(get("/api/tools"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());

            assertThat(body).containsKeys("total", "exposed", "disabledCount", "persisted", "tools");
            assertThat((Integer) body.get("exposed")).isPositive();
            assertThat(body.get("persisted"))
                    .as("配了 spring.datasource.url 就该说改动活得过重启")
                    .isEqualTo(true);
            List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
            assertThat(tools).isNotEmpty();
            assertThat(tools.getFirst()).containsKeys("name", "group", "exposed", "disabled");
        }

        /**
         * 停用真的让工具从 {@code tools/list} 消失，启用真的让它回来，并且落了库。
         *
         * <p>用完就放回去：本上下文被同组其它用例共享，留一个停用的工具会让它们的计数漂移。
         */
        @Test
        void disablingAToolOverHttpRemovesItFromToolsListAndPersistsIt() throws Exception {
            String tool = toggles.exposedToolNames().iterator().next();

            setDisabled(tool, true);
            try {
                assertThat(listedTools())
                        .as("停用的语义就是它从 tools/list 消失，而不是调用时报错")
                        .doesNotContain(tool);
                assertThat(toggles.isDisabled(tool)).isTrue();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT updated_by FROM " + ToolToggleRepository.TABLE_NAME
                                + " WHERE tool_name = ? AND disabled = 1", String.class, tool))
                        .as("""
                                操作者必须落库，且不能为空——"这个工具是谁停的"没有第二处记录。
                                这里是 anonymousUser 而不是 unknown：entropy.mcp.security.enabled=false 时
                                过滤器链仍然装着匿名认证，SecurityContextHolder 里有一个 AnonymousAuthenticationToken。
                                ToolToggleRegistry.UNKNOWN_ACTOR 那条回退路径要 SecurityContext 整个为空才会走到
                                （见 ToolToggleRegistryTest.actorFallsBackToUnknownWithoutASecurityContext），
                                它的注释说"鉴权关闭时就是这种情况"，实际不是——两者都不阻塞操作，所以行为没问题，
                                但谁要按那句注释去断言 unknown，会在这里发现对不上。""")
                        .isEqualTo("anonymousUser");
            } finally {
                setDisabled(tool, false);
            }

            assertThat(listedTools()).contains(tool);
            assertThat(toggles.isDisabled(tool)).isFalse();
        }

        /** 未知工具名是 400，不是 500——与 {@code ToolAdminControllerTest} 同一条，这里过真实的过滤器链。 */
        @Test
        void unknownToolNameIsRejectedWithBadRequest() throws Exception {
            mockMvc.perform(put("/api/tools/noSuchToolAtAll")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"disabled\": true}"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
        }
    }

    // ─── 按当前启用的工具生成提示词 ────────────────────────────────────────

    /**
     * {@code GET /api/tools/prompt} 在<b>真实的工具集</b>上的契约。
     *
     * <p>单测那边（{@code ToolPromptGeneratorTest}）用的是 3 个假工具，验的是文本结构；
     * 这里验的是它接在真东西上仍然成立：{@code toolCount} 等于注册表报的
     * {@code remainingExposed()}，而"约束"那一段报的是这个上下文真实的配置值。
     * 这两件事只有在完整上下文里才可能错——{@code ToolExposureFilter} 有没有把快照喂进来、
     * {@code ToolCatalog} 的反射索引有没有覆盖到全部工具 bean，都在这条链上。
     *
     * <p>{@code spring.application.name} 那一行不是配置需求，是为了让本类的上下文不与
     * {@code AuthDisabledWithoutAuditPersistence} 共享：Spring 的测试上下文缓存以合并后的配置为
     * key，属性完全相同的两个 {@code @Nested} 会共用一个上下文（见 {@code AuthEnabled} 上那段说明），
     * 而这一组会真的去停用工具、改动共享状态。给它一个独立的名字，两边互不影响——不给的话症状是
     * 「单跑绿、全量红」。
     *
     * <p>刻意不配 {@code spring.datasource.url}：提示词与开关是否落库无关，而这个形态是默认部署。
     */
    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=false",
        "spring.application.name=tool-prompt-api-test"
    })
    class ToolPromptApi extends WithMockMvc {

        @Autowired
        ToolToggleRegistry toggles;

        private Map<String, Object> prompt(String query) throws Exception {
            return json(mockMvc.perform(get("/api/tools/prompt" + query))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
        }

        /**
         * 缺省格式是 system，四个字段齐全，{@code toolCount} 与 {@code tools/list} 的长度一致，
         * 且「当前生效的约束」报的是这个上下文真实的开关值。
         */
        @Test
        void systemPromptIsTheDefaultAndDescribesTheRealServerState() throws Exception {
            Map<String, Object> body = prompt("");

            assertThat(body).containsOnlyKeys("format", "generatedAt", "toolCount", "text");
            assertThat(body.get("format")).isEqualTo("system");
            assertThat(body.get("toolCount"))
                    .as("提示词里的工具数就是此刻 tools/list 的长度")
                    .isEqualTo(toggles.remainingExposed());
            assertThat((Integer) body.get("toolCount"))
                    .as("快照为空意味着 ToolExposureFilter 那条回调没跑，那时提示词会是一句"
                            + "「当前没有可用工具」而不是报错——最安静的坏法")
                    .isPositive();

            String text = (String) body.get("text");
            assertThat(text).contains("## 当前生效的约束", "## 可用工具", "## 这份提示词是一份快照");
            assertThat(text)
                    .as("三个开关都必须报生效值：这个上下文 gateway=false、ddl 与 authz 都是默认的 false")
                    .contains("entropy.mcp.gateway.enabled=false",
                            "entropy.mcp.database.ddl.allowed=false",
                            "entropy.mcp.authz.enabled=false");
            assertThat(text).contains(body.get("generatedAt").toString());
            // 真实工具名确实进去了：清单不是空壳
            assertThat(text).contains("### " + toggles.exposedToolNames().iterator().next());
        }

        /** {@code list} 只有清单，没有系统提示词的那几段。 */
        @Test
        void listPromptIsOnlyTheCatalogSection() throws Exception {
            Map<String, Object> body = prompt("?format=list");

            assertThat(body.get("format")).isEqualTo("list");
            assertThat((String) body.get("text"))
                    .contains("### ")
                    .doesNotContain("## 当前生效的约束", "## 这份提示词是一份快照");
        }

        /**
         * 刚被停用的工具不许出现在提示词里。
         *
         * <p>这是这个功能唯一不能出错的地方：一个不在 {@code tools/list} 里的工具名写进提示词，
         * 就是让模型去调一个不存在的工具。
         *
         * <p>用完就放回去：本上下文被同组其它用例共享，留一个停用的工具会让它们的计数漂移。
         */
        @Test
        void aDisabledToolDisappearsFromThePrompt() throws Exception {
            String tool = toggles.exposedToolNames().iterator().next();
            int before = (Integer) prompt("").get("toolCount");

            mockMvc.perform(put("/api/tools/" + tool)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"disabled\": true}"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
            try {
                Map<String, Object> body = prompt("?format=list");
                assertThat((String) body.get("text"))
                        .as("被停用的工具名一个字都不该留在提示词里")
                        .doesNotContain("### " + tool);
                assertThat(body.get("toolCount")).isEqualTo(before - 1);
            } finally {
                mockMvc.perform(put("/api/tools/" + tool)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"disabled\": false}"))
                        .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
            }

            assertThat((String) prompt("?format=list").get("text")).contains("### " + tool);
        }

        /** 非法 format 是 400，不是 500——这里过真实的过滤器链与参数解析。 */
        @Test
        void unknownFormatIsRejectedWithBadRequest() throws Exception {
            mockMvc.perform(get("/api/tools/prompt?format=markdown"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
        }
    }

    // ─── 调用者身份：列出 / 新增 / 停用 ─────────────────────────────────────

    /**
     * {@code /api/users} 在真实上下文里的契约。前端「身份管理」页直接读这几个形状，
     * 所以这一组钉的是<b>页面赖以说真话的那些断言</b>，而不是 UserAdminService 的单元行为
     * （那些在 infra 模块的单测里）：
     *
     * <ul>
     *   <li>列表是<b>数组</b>且每行有 username / type / enabled / roles 四个键，<b>没有口令字段</b>——
     *       多出一个 password 键意味着哈希会进浏览器的内存与 CSV 导出；</li>
     *   <li>权限白名单在写入口 fail-closed（未知 role 是 400 而不是静默忽略）。页面上那两个
     *       Switch 是这份白名单的镜像，后端一旦悄悄放宽，镜像就开始说谎；</li>
     *   <li>停用之后<b>行还在</b>、只是 enabled 变 false，而且对已停用的身份再发一次 DELETE
     *       仍然是 200。这一条尤其要钉：{@code UserAdminController.disable} 的注释说返回体
     *       让调用方"能区分停用成功与本来就是停用状态"，而停用语句是无条件 UPDATE，
     *       两种情况的响应完全相同。页面按 SQL 的真实语义写文案，测试在这里替它兜住。</li>
     * </ul>
     *
     * <p>{@code spring.application.name} 单独给一个：这一组会往 {@code mcp_user} 里写行，
     * 与其它 {@code @Nested} 共享上下文会让彼此的计数漂移（同 {@code ToolToggleAdminApi} 上那段）。
     */
    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=false",
        "spring.application.name=user-admin-api-test",
        "spring.datasource.url=jdbc:h2:mem:user_admin_web_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    })
    class UserAdminApi extends WithMockMvc {

        private Map<String, Object> create(String username, String type, String rolesJson,
                                           int expectedStatus) throws Exception {
            MvcResult result = mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\": \"" + username + "\", \"type\": \"" + type
                                    + "\", \"password\": \"not-a-real-password\", \"roles\": "
                                    + rolesJson + "}"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(expectedStatus))
                    .andReturn();
            return expectedStatus == 201 ? json(result) : Map.of();
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> list() throws Exception {
            String body = mockMvc.perform(get("/api/users"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).as("列表是数组，不是包装对象——页面直接对它 map").startsWith("[");
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, List.class);
        }

        private Map<String, Object> find(String username) throws Exception {
            return list().stream()
                    .filter(row -> username.equals(row.get("username")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("刚建的身份没有出现在 /api/users 里: " + username));
        }

        @Test
        @SuppressWarnings("unchecked")
        void createdIdentityIsListedWithItsRolesAndWithoutAnyPasswordField() throws Exception {
            Map<String, Object> created = create("panel-dba", "agent", "[\"ROLE_DBA\"]", 201);

            assertThat(created).containsOnlyKeys("username", "type", "enabled", "roles");
            assertThat(created.get("enabled")).isEqualTo(true);
            assertThat((List<String>) created.get("roles")).containsExactly("ROLE_DBA");

            Map<String, Object> listed = find("panel-dba");
            assertThat(listed)
                    .as("响应里出现口令（原文或哈希）意味着它会进浏览器内存与 CSV 导出")
                    .containsOnlyKeys("username", "type", "enabled", "roles");
            assertThat(listed.get("type")).isEqualTo("agent");
            assertThat((List<String>) listed.get("roles")).containsExactly("ROLE_DBA");
        }

        /** 不给 roles 是合法的：那个身份能过认证，但 /api/** 与面板对它都是 403。 */
        @Test
        @SuppressWarnings("unchecked")
        void identityWithoutRolesIsCreatedWithAnEmptyRoleList() throws Exception {
            create("panel-plain", "service", "[]", 201);
            assertThat((List<String>) find("panel-plain").get("roles")).isEmpty();
        }

        /**
         * 未知权限是 400，不是"静默忽略"。
         *
         * <p>静默忽略的症状是"配了但不生效"：页面会显示一个成功横幅，而那个身份一条权限都没拿到。
         */
        @Test
        void unknownRoleIsRejectedWithBadRequest() throws Exception {
            create("panel-bogus-role", "user", "[\"ROLE_WHATEVER\"]", 400);
            assertThat(list().stream().map(row -> row.get("username")))
                    .as("被 400 拒掉的请求不能留下半行记录")
                    .doesNotContain("panel-bogus-role");
        }

        /** 与环境变量里那个管理员同名是 400：否则会出现"两处定义、谁生效取决于查找顺序"。 */
        @Test
        void creatingAnIdentityNamedLikeTheEnvAdminIsRejected() throws Exception {
            create("admin", "user", "[]", 400);
        }

        /** 重名是 400 而不是覆盖：用户名是主键，覆盖等于悄悄换掉一个身份的口令。 */
        @Test
        void duplicateUsernameIsRejected() throws Exception {
            create("panel-dup", "user", "[]", 201);
            create("panel-dup", "user", "[]", 400);
        }

        @Test
        void disablingKeepsTheRowAndStaysTwoHundredOnASecondCall() throws Exception {
            create("panel-doomed", "user", "[]", 201);

            Map<String, Object> first = json(mockMvc.perform(delete("/api/users/panel-doomed"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andReturn());
            assertThat(first.get("disabled")).isEqualTo(true);

            assertThat(find("panel-doomed").get("enabled"))
                    .as("停用是 UPDATE 而不是 DELETE：行必须还在，页面靠它显示「已停用」那一行")
                    .isEqualTo(false);

            Map<String, Object> second = json(mockMvc.perform(delete("/api/users/panel-doomed"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus())
                            .as("""
                                    停用语句是无条件的 UPDATE ... SET enabled = 0 WHERE username = ?，
                                    对一个已经停用的身份再发一次照样影响一行、照样 200。
                                    所以返回体里的 disabled:true 不代表"这次才变"——
                                    页面不能照着它说"已生效"，api.js 里那段注释钉的就是这件事。""")
                            .isEqualTo(200))
                    .andReturn());
            assertThat(second.get("disabled")).isEqualTo(true);
        }

        /** 用户名不存在才是 404。这是 404 的<b>唯一</b>来源。 */
        @Test
        void disablingAnUnknownIdentityIsNotFound() throws Exception {
            mockMvc.perform(delete("/api/users/nobody-by-that-name"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(404));
        }

        /** 停用管理员是 400：它是最后一个入口，停掉之后连改回来的接口都进不去。 */
        @Test
        void disablingTheEnvAdminIsRejected() throws Exception {
            mockMvc.perform(delete("/api/users/admin"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
        }
    }


}
