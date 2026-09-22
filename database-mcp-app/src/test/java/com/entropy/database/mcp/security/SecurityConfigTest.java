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
package com.entropy.database.mcp.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.entropy.database.mcp.DatabaseMcpApplication;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Verifies that {@code entropy.mcp.security.enabled} actually switches an authentication
 * mechanism on and off. Before HTTP Basic was wired into the filter chain, enabling the flag
 * turned every request into a permanent 401 because no filter could ever authenticate a caller.
 *
 * <p>The admin password is read from a system property by {@code SecurityConfig}, so it has to be
 * in place before any application context is created.
 */
class SecurityConfigTest {

    private static final String ADMIN_PASSWORD = "test-admin-password";
    private static final String ADMIN_PASSWORD_PROPERTY = "mcp.security.admin-password";

    /**
     * 以前这里是一个 {@code static {}} 块，设了就再也不清理。surefire 默认 {@code reuseForks=true}，
     * 同一个 JVM 里跑的其他测试类（以及 Spring 缓存下来的其他上下文）会看到这个属性，等于把
     * 「admin 密码已配置」偷偷塞给了别人的断言——比如某个想验证「没配密码就该启动失败」的测试会莫名通过。
     *
     * <p>{@code @BeforeAll} 仍然早于任何 {@code @Nested} 上下文的创建，所以时序上够；{@code @AfterAll}
     * 把它清掉，污染范围收敛到本类。
     */
    @BeforeAll
    static void setAdminPassword() {
        System.setProperty(ADMIN_PASSWORD_PROPERTY, ADMIN_PASSWORD);
    }

    @AfterAll
    static void clearAdminPassword() {
        System.clearProperty(ADMIN_PASSWORD_PROPERTY);
    }

    private static String basicAuth(String username, String password) {
        String token = username + ":" + password;
        return "Basic " + Base64.getEncoder()
            .encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=true"
    })
    class AuthenticationEnabled {

        @Autowired
        private WebApplicationContext context;

        @Autowired
        @Qualifier("springSecurityFilterChain")
        private Filter springSecurityFilterChain;

        @Autowired
        @Qualifier("mcpSecurityFilterChain")
        private SecurityFilterChain mcpChain;

        @Autowired
        @Qualifier("webSecurityFilterChain")
        private SecurityFilterChain webChain;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        }

        /** Basic 必须在<b>两条</b>链上：/mcp 的客户端用它，运维脚本与本文件的用例也用它。 */
        @Test
        void filterChainRegistersBasicAuthenticationFilter() {
            assertThat(mcpChain.getFilters())
                .as("MCP 客户端只有 Basic / Bearer 两种凭据形式，这条链上丢了 Basic 等于客户端全连不上")
                .anyMatch(BasicAuthenticationFilter.class::isInstance);
            assertThat(webChain.getFilters())
                .as("表单登录不能把 Basic 顶掉：curl -u 与全部 MockMvc 用例都走它")
                .anyMatch(BasicAuthenticationFilter.class::isInstance);
        }

        /**
         * 表单登录只能在浏览器那条链上。
         *
         * <p>这是"加登录页不影响 MCP"这件事的<b>结构性</b>保证：一旦 formLogin 落到 /mcp 那条链上，
         * 未认证的 MCP 请求会收到 302 + 登录页 HTML 而不是 401，而客户端在等一个带
         * {@code WWW-Authenticate} 的 401，表现是"连不上但看不出为什么"。
         */
        @Test
        void formLoginIsOnlyWiredOnTheBrowserChain() {
            assertThat(webChain.getFilters())
                .anyMatch(UsernamePasswordAuthenticationFilter.class::isInstance);
            assertThat(mcpChain.getFilters())
                .as("/mcp 那条链不能有表单登录：它会把 401 变成 302")
                .noneMatch(UsernamePasswordAuthenticationFilter.class::isInstance);
        }

        /**
         * CSRF 只在浏览器那条链上。
         *
         * <p>同理的另一半：MCP 的每次工具调用都是 POST，CSRF 一旦覆盖到 /mcp 就是全量 403。
         */
        @Test
        void csrfIsOnlyEnforcedOnTheBrowserChain() {
            assertThat(webChain.getFilters())
                .as("有了会话 cookie 就必须有 CSRF：cookie 会被浏览器自动附加")
                .anyMatch(CsrfFilter.class::isInstance);
            assertThat(mcpChain.getFilters())
                .as("CSRF 覆盖到 /mcp 的症状是每一次工具调用都 403")
                .noneMatch(CsrfFilter.class::isInstance);
        }

        @Test
        void mcpWithoutCredentialsIsUnauthorized() throws Exception {
            mockMvc.perform(get("/mcp"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        }

        /** MCP 的调用是 POST，且不带任何 CSRF token——它必须照样过。 */
        @Test
        void mcpPostWithBasicCredentialsIsNotRejectedAsCsrf() throws Exception {
            mockMvc.perform(post("/mcp")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("403 说明 CSRF 漏到了 /mcp 上")
                    .isNotEqualTo(403));
        }

        @Test
        void mcpWithValidBasicCredentialsIsNotUnauthorized() throws Exception {
            mockMvc.perform(get("/mcp")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
        }

        @Test
        void mcpWithWrongPasswordIsUnauthorized() throws Exception {
            mockMvc.perform(get("/mcp")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", "wrong-password")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        }

        @Test
        void auditApiWithoutCredentialsIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/audit/logs"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        }

        @Test
        void auditApiWithAdminCredentialsIsAllowed() throws Exception {
            mockMvc.perform(get("/api/audit/logs")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        }

        @Test
        void healthStaysPublic() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
        }

        @Test
        void otherActuatorEndpointsRequireAuthentication() throws Exception {
            mockMvc.perform(get("/actuator/metrics"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        }

        @Test
        void jwtIsNotWiredWhenNoIssuerIsConfigured() {
            assertThat(mcpChain.getFilters())
                .noneMatch(filter -> filter.getClass().getName().contains("BearerTokenAuthenticationFilter"));
            assertThat(webChain.getFilters())
                .noneMatch(filter -> filter.getClass().getName().contains("BearerTokenAuthenticationFilter"));
        }

        // ─── 登录界面与表单登录 ────────────────────────────────────────────

        /**
         * 面板的 HTML 与构建产物匿名可读：登录界面是 SPA 的一个视图，SPA 得先能加载。
         *
         * <p>判据与代价见 {@code SecurityConfig.WEB_UI_RESOURCES} 的注释——要保护的是"数据"
         * （{@code /api/**}，仍然 ROLE_ADMIN）而不是"代码"。
         */
        @Test
        void thePanelShellIsReachableWithoutCredentials() throws Exception {
            mockMvc.perform(get("/index.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        }

        /**
         * 未认证的浏览器导航<b>不再是 302</b>：页面本身放通了，所以"跳去哪儿"这件事不存在。
         *
         * <p>这条与上一版（302 跳 /login.html）相反，是刻意的反转：登录界面进了 SPA，
         * 未登录状态由前端在收到 401 后自己渲染。
         */
        @Test
        void unauthenticatedApiCallsAreNotRedirected() throws Exception {
            mockMvc.perform(get("/api/ui/config").accept(MediaType.TEXT_HTML))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("302 说明还挂着一个跳转入口点，而登录页已经不存在了")
                    .isEqualTo(401));
        }

        /**
         * fetch（{@code Accept: application/json}）拿到的 401 <b>不带 WWW-Authenticate</b>。
         *
         * <p>带上它会让某些浏览器在 XHR/fetch 的 401 上弹出原生 Basic 对话框，那个框会和 SPA 自己的
         * 登录表单同时出现。不带 Accept 的脚本仍然走 BasicAuthenticationEntryPoint，那一档要带头，
         * {@code curl -u} 的体验靠它。
         */
        @Test
        void jsonClientsGetPlainUnauthorizedWhileScriptsKeepTheBasicChallenge() throws Exception {
            mockMvc.perform(get("/api/ui/config").accept(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(401);
                    assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .as("fetch 的 401 不能带 Basic 挑战，否则浏览器会弹原生登录框")
                        .isNull();
                });
            mockMvc.perform(get("/api/ui/config"))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(401);
                    assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .as("curl / 脚本要靠这个头知道该带 Basic 凭据")
                        .startsWith("Basic");
                });
        }

        /**
         * 浏览器自动发的 {@code /favicon.ico} <b>不能</b>换回一个带 Basic 挑战的 401。
         *
         * <p>这条钉住的是一个具体的故障现象：打开面板、或者在登录界面上操作时，浏览器会弹出一个
         * <b>原生的 Basic 认证对话框</b>，和 SPA 自己的登录表单同时出现。
         *
         * <p>成因与 {@code /api/**} 无关，链路是这样的：
         * <ol>
         *   <li>{@code index.html} 没有声明 favicon，浏览器就按约定自己去取 {@code /favicon.ico}；</li>
         *   <li>这个路径不在 {@code WEB_UI_RESOURCES} 里，落到 {@code anyRequest().denyAll()}；</li>
         *   <li>匿名请求上的 {@code AccessDeniedException} 会被 {@code ExceptionTranslationFilter}
         *       转成"去认证"，于是调用入口点；</li>
         *   <li>favicon 请求的 {@code Accept} 是 {@code image/*}，匹配不上"纯 401"那一档，
         *       落到 {@code BasicAuthenticationEntryPoint} → 带 {@code WWW-Authenticate: Basic}
         *       → 浏览器弹原生对话框。</li>
         * </ol>
         *
         * <p>修法有两半，缺一半线上就照样弹框：把 favicon 划进匿名可读的页面外壳（判据同
         * {@code WEB_UI_RESOURCES}：要保护的是数据而不是装饰），<b>以及</b>放通容器的 ERROR
         * 派发（见下一条用例）。404 是可以接受的结果——仓库里确实没有这个文件；
         * 这里断言的是"不带 Basic 挑战"，而不是"一定有图标"。
         */
        @Test
        void faviconDoesNotTriggerTheNativeBasicDialog() throws Exception {
            mockMvc.perform(get("/favicon.ico")
                    .header(HttpHeaders.ACCEPT, "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"))
                .andExpect(result -> {
                    assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .as("favicon 的响应带了 Basic 挑战，浏览器会在面板上弹出原生登录框")
                        .isNull();
                    assertThat(result.getResponse().getStatus())
                        .as("favicon 不该要求凭据；404（没有这个文件）是可以接受的")
                        .isNotEqualTo(401);
                });
        }

        /**
         * 容器内部的 ERROR 派发不能换回一个带 Basic 挑战的 401。
         *
         * <p>这条是上一条用例<b>漏掉</b>的那一半，2026-09-21 在长春 {@code 0.6.0-authz2} 上现形：
         * 只把 {@code /favicon.ico} 放进 {@code WEB_UI_RESOURCES} 之后，MockMvc 里这条路径已经绿了，
         * 而线上 {@code curl -H 'Accept: image/*' /favicon.ico} 依旧是
         * {@code 401 + WWW-Authenticate: Basic}。差别在于 <b>MockMvc 默认只做 REQUEST 派发</b>：
         * 真实容器里文件不存在会 404 → 转发到 {@code /error} → 同一个请求第二次进过滤器链
         * （{@code DispatcherType=ERROR}）→ 再一次落到 {@code denyAll} → 入口点 → 挑战头。
         *
         * <p>所以这里显式把派发类型设成 {@code ERROR} 来复现那条第二趟。注意<b>不是</b>直接
         * {@code GET /error}（那是 REQUEST 派发，仍然应该被 {@code denyAll} 拒掉，
         * 由 {@link #directErrorPathStaysDenied()} 钉住）。
         */
        @Test
        void errorDispatchDoesNotTriggerTheNativeBasicDialog() throws Exception {
            mockMvc.perform(get("/error")
                    .header(HttpHeaders.ACCEPT, "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .with(request -> {
                        request.setDispatcherType(DispatcherType.ERROR);
                        return request;
                    }))
                .andExpect(result -> {
                    assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .as("404 的 ERROR 派发带了 Basic 挑战，浏览器会在面板上弹出原生登录框")
                        .isNull();
                    assertThat(result.getResponse().getStatus())
                        .as("ERROR 派发是容器内部转发，不该再要一次凭据")
                        .isNotEqualTo(401);
                });
        }

        /**
         * 放通 ERROR 派发<b>不等于</b>把 {@code /error} 这个路径交出去。
         *
         * <p>外部直接请求 {@code /error} 走的是 REQUEST 派发，仍然落在 {@code anyRequest().denyAll()}
         * 上。这条用例存在的意义是划清上一条的边界：放通的是"容器内部转发"这个维度，不是一条路径。
         */
        @Test
        void directErrorPathStaysDenied() throws Exception {
            mockMvc.perform(get("/error").accept(MediaType.APPLICATION_JSON))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        }

        /** 没带 CSRF token 的表单登录必须 403：这条钉住"浏览器那条链上 CSRF 真的开着"。 */
        @Test
        void formLoginWithoutCsrfTokenIsForbidden() throws Exception {
            mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("username", "admin")
                    .param("password", ADMIN_PASSWORD))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));
        }

        /**
         * 完整的登录流程：取 token → POST /login → 204 → 用同一个会话读 API → 200 → 退出 → 401。
         *
         * <p>刻意手动走双提交（取 XSRF-TOKEN cookie、按 X-XSRF-TOKEN 回传）而不是引
         * {@code spring-security-test} 的 {@code csrf()}：这里要验证的正是 cookie 真的被下发了
         * （靠 SecurityConfig 里那个 CsrfCookieFilter），而 {@code csrf()} 会绕过那一步。
         */
        @Test
        void formLoginCreatesASessionAndLogoutInvalidatesIt() throws Exception {
            MvcResult page = mockMvc.perform(get("/")).andReturn();
            Cookie csrfCookie = page.getResponse().getCookie("XSRF-TOKEN");
            assertThat(csrfCookie)
                .as("csrf().spa() 没生效：登录表单拿不到 token，任何 POST /login 都会 403")
                .isNotNull();

            MvcResult login = mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .cookie(csrfCookie)
                    .header("X-XSRF-TOKEN", csrfCookie.getValue())
                    .param("username", "admin")
                    .param("password", ADMIN_PASSWORD))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("成功必须是 204 而不是 302：登录表单用 fetch 提交，整页不刷新才能保住 hash 里的现场")
                    .isEqualTo(204))
                .andReturn();

            MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
            assertThat(session).isNotNull();

            mockMvc.perform(get("/api/ui/config").session(session))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

            mockMvc.perform(post("/logout")
                    .session(session)
                    .cookie(csrfCookie)
                    .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));

            mockMvc.perform(get("/api/ui/config").session(session))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("退出之后同一个会话必须不再能读 API")
                    .isEqualTo(401));
        }

        @Test
        void formLoginWithWrongPasswordIsUnauthorized() throws Exception {
            Cookie csrfCookie = mockMvc.perform(get("/"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

            mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .cookie(csrfCookie)
                    .header("X-XSRF-TOKEN", csrfCookie.getValue())
                    .param("username", "admin")
                    .param("password", "wrong-password"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                    .as("失败必须是 401 而不是 302：登录视图要能就地把错误显示出来")
                    .isEqualTo(401));
        }

        /**
         * 带 {@code Authorization} 头的写请求<b>不</b>要求 CSRF token。
         *
         * <p>CSRF 防的是"浏览器自动附带的凭据"，而 Basic 头是调用方显式放上去的。不留这条例外，
         * {@code curl -u ... -X PUT} 会先要求去取一个 token，运维脚本全得改成三步。
         * 断言 400 而不是"不是 403"：400 是那个不存在的工具名换来的，说明请求真的走到了控制器里。
         */
        @Test
        void basicAuthenticatedWritesDoNotNeedACsrfToken() throws Exception {
            mockMvc.perform(put("/api/tools/noSuchToolAtAll")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth("admin", ADMIN_PASSWORD))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"disabled\": true}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=true",
        // Lazy decoder: no network access at startup, unlike issuer-uri.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:19999/jwks.json"
    })
    class JwtResourceServerConfigured {

        @Autowired
        @Qualifier("mcpSecurityFilterChain")
        private SecurityFilterChain mcpChain;

        /** JWT 要挂在<b>两条</b>链上：MCP 客户端可能用 Bearer，浏览器那条链上它也不该缺。 */
        @Test
        void bearerTokenFilterIsWiredAlongsideBasic() {
            assertThat(mcpChain.getFilters())
                .anyMatch(BasicAuthenticationFilter.class::isInstance);
            assertThat(mcpChain.getFilters())
                .anyMatch(filter -> filter.getClass().getSimpleName().equals("BearerTokenAuthenticationFilter"));
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false"
        // entropy.mcp.security.enabled 刻意不设：本类验证 0.4.0 起的默认值是"开"。
    })
    class DefaultsToAuthenticationEnabled {

        @Autowired
        private WebApplicationContext context;

        @Autowired
        @Qualifier("springSecurityFilterChain")
        private Filter springSecurityFilterChain;

        @Autowired
        @Qualifier("mcpSecurityFilterChain")
        private SecurityFilterChain mcpChain;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        }

        /**
         * 破坏性变更（0.4.0 引入，沿用至今）：默认值由 {@code false} 改为 {@code true}。
         *
         * <p>这个开关的两个方向不对称——开着而没配密码会启动失败（当场发现），关着则是 {@code /mcp}
         * 对任何能连上端口的人开放（要靠读启动日志才发现）。默认值必须站在会响的那一侧。
         */
        @Test
        void mcpRequiresCredentialsWithoutAnyExplicitConfiguration() throws Exception {
            mockMvc.perform(get("/mcp"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        }

        @Test
        void basicAuthenticationFilterIsWiredByDefault() {
            assertThat(mcpChain.getFilters())
                .anyMatch(BasicAuthenticationFilter.class::isInstance);
        }

        /** 探针拿不到凭证，鉴权默认打开之后 health 仍必须匿名可达。 */
        @Test
        void healthStaysPublicByDefault() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.security.enabled=false"
    })
    class AuthenticationDisabled {

        @Autowired
        private WebApplicationContext context;

        @Autowired
        @Qualifier("springSecurityFilterChain")
        private Filter springSecurityFilterChain;

        @Autowired
        @Qualifier("mcpSecurityFilterChain")
        private SecurityFilterChain mcpChain;

        @Autowired
        @Qualifier("webSecurityFilterChain")
        private SecurityFilterChain webChain;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        }

        @Test
        void mcpIsOpenWithoutCredentials() throws Exception {
            mockMvc.perform(get("/mcp"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
        }

        @Test
        void noAuthenticationFilterIsRegistered() {
            assertThat(mcpChain.getFilters())
                .noneMatch(BasicAuthenticationFilter.class::isInstance);
            assertThat(webChain.getFilters())
                .noneMatch(BasicAuthenticationFilter.class::isInstance);
        }

        /**
         * 鉴权关闭时<b>没有</b>登录页也没有会话：挂一个谁都能过的登录表单比没有登录页更糟，
         * 它会让人以为服务有门。前端同理——它永远收不到 401，所以永远不会跳过去。
         */
        @Test
        void noFormLoginAndNoCsrfWhenAuthenticationIsDisabled() {
            assertThat(webChain.getFilters())
                .noneMatch(UsernamePasswordAuthenticationFilter.class::isInstance);
            assertThat(webChain.getFilters())
                .noneMatch(CsrfFilter.class::isInstance);
        }

        /** 页面导航也不跳登录页：这个部署形态下根本没有"未登录"这个状态。 */
        @Test
        void browserNavigationIsNotRedirected() throws Exception {
            mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(302));
        }
    }

    // ─── production profile 下关闭鉴权：从 warn 升级为启动失败 ────────────────

    /**
     * 这两个用例刻意用 {@link SpringApplicationBuilder} 手动起上下文，而不是再加一个 {@code @Nested}
     * {@code @SpringBootTest}：要断言的是「上下文起不来」，而 {@code @SpringBootTest} 的上下文在
     * 测试方法执行之前就已经加载，加载失败只会得到一个无法断言消息的初始化错误。
     *
     * <p>配置一律用命令行参数传：{@code SpringApplicationBuilder.properties()} 落在 defaultProperties 里，
     * 优先级低于 {@code application-production.yml}，会被那边显式写着的 {@code security.enabled: true}
     * 盖掉，测试就测不到「生产 + 关鉴权」这个组合了。
     */
    private static ConfigurableApplicationContext runWithProductionProfile(boolean escapeValveOpen) {
        return new SpringApplicationBuilder(DatabaseMcpApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--spring.profiles.active=production",
                // 随机端口：这个上下文和其他测试上下文可能同时存活
                "--server.port=0",
                "--spring.main.banner-mode=off",
                // 生产 profile 里 dialect 是 oracle、gateway 打开，测试环境两者都没有，覆盖掉
                "--entropy.mcp.database.enabled=true",
                "--entropy.mcp.database.dialect=generic",
                "--entropy.mcp.gateway.enabled=false",
                "--entropy.mcp.security.enabled=false",
                "--entropy.mcp.security.allow-unauthenticated-in-production=" + escapeValveOpen);
    }

    /**
     * 这就是 {@code .env.prod} 里 {@code ENTROPY_MCP_SECURITY_ENABLED=false} 那一行的效果：
     * 以前服务会带着「/mcp + /api/**（原文 SQL 审计）+ 全部 actuator 无凭证开放」正常启动，只在日志里留一段
     * 没人读的横幅。现在必须当场炸掉。
     */
    @Test
    void productionProfileWithAuthenticationDisabledFailsTheContext() {
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext started = runWithProductionProfile(false)) {
                // 一行都不用写：能返回一个上下文就说明校验没生效，assertThatThrownBy 会因为「没抛异常」失败。
                // try-with-resources 只是为了不把这个上下文和它的 Tomcat 漏出去。
            }
        })
            .hasStackTraceContaining("production profile 下不允许关闭鉴权")
            .hasStackTraceContaining("entropy.mcp.security.allow-unauthenticated-in-production=true")
            // 不断言 rootCause 的类型：Boot 有可能把 @PostConstruct 抛出的异常原样重抛（此时没有 cause），
            // 也可能包一层 BeanCreationException。两种形态下类型名都在栈里。
            .hasStackTraceContaining("java.lang.IllegalStateException");
    }

    /**
     * 逃生阀必须真的能开：否则下一个被它挡住的人会直接把这段校验删掉，而删掉是不可见的，
     * 显式写一行难看的配置是可见的。
     */
    @Test
    void explicitEscapeValveLetsProductionStartWithoutAuthentication() {
        try (ConfigurableApplicationContext context = runWithProductionProfile(true)) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getActiveProfiles()).contains("production");
        }
    }
}
