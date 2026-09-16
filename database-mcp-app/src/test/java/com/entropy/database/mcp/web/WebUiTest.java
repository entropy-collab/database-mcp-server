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
 * 只读运维页面的端到端契约：{@code WebUiController} 的三个端点、限值夹取、审计未落库时的降级路径，
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
    }


}
