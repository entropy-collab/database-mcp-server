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

import jakarta.servlet.Filter;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.entropy.database.mcp.DatabaseMcpApplication;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
        private SecurityFilterChain securityFilterChain;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        }

        @Test
        void filterChainRegistersBasicAuthenticationFilter() {
            assertThat(securityFilterChain.getFilters())
                .anyMatch(BasicAuthenticationFilter.class::isInstance);
        }

        @Test
        void mcpWithoutCredentialsIsUnauthorized() throws Exception {
            mockMvc.perform(get("/mcp"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
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
            assertThat(securityFilterChain.getFilters())
                .noneMatch(filter -> filter.getClass().getName().contains("BearerTokenAuthenticationFilter"));
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
        private SecurityFilterChain securityFilterChain;

        @Test
        void bearerTokenFilterIsWiredAlongsideBasic() {
            assertThat(securityFilterChain.getFilters())
                .anyMatch(BasicAuthenticationFilter.class::isInstance);
            assertThat(securityFilterChain.getFilters())
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
        private SecurityFilterChain securityFilterChain;

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
            assertThat(securityFilterChain.getFilters())
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
        private SecurityFilterChain securityFilterChain;

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
            assertThat(securityFilterChain.getFilters())
                .noneMatch(BasicAuthenticationFilter.class::isInstance);
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
