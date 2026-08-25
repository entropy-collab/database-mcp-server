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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
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

    static {
        System.setProperty("mcp.security.admin-password", ADMIN_PASSWORD);
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
}
