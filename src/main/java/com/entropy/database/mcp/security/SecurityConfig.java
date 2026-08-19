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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration. Always active to override Spring Boot defaults.
 * Authentication is controlled by entropy.mcp.security.enabled (default: false).
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "entropy.mcp.security.test-mode", havingValue = "false", matchIfMissing = true)
public class SecurityConfig {

    @Value("${entropy.mcp.security.enabled:false}")
    private boolean securityEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/actuator/info").permitAll();
                if (securityEnabled) {
                    auth.requestMatchers("/mcp").authenticated()
                        .anyRequest().denyAll();
                } else {
                    auth.requestMatchers("/mcp").permitAll()
                        .anyRequest().permitAll();
                }
            });
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnProperty(name = "entropy.mcp.security.enabled", havingValue = "true")
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        if (System.getProperty("mcp.security.admin-password") == null && System.getenv("MCP_SECURITY_ADMIN_PASSWORD") == null) {
            throw new IllegalStateException(
                "MCP security is enabled but no admin password is configured. " +
                "Set MCP_SECURITY_ADMIN_PASSWORD or mcp.security.admin-password property.");
        }
        String adminPassword = System.getenv("MCP_SECURITY_ADMIN_PASSWORD") != null
            ? System.getenv("MCP_SECURITY_ADMIN_PASSWORD")
            : System.getProperty("mcp.security.admin-password");

        // BCrypt encode the password - format: $2a$10$<22 char salt><31 char hash>
        String encodedPassword = passwordEncoder.encode(adminPassword);

        UserDetails admin = User.builder()
            .username("admin")
            .password(encodedPassword)  // Store encoded password
            .roles("ADMIN", "DBA")
            .build();

        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    @ConditionalOnProperty(name = "entropy.mcp.security.enabled", havingValue = "true")
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}
