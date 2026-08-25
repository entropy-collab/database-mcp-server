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

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${entropy.mcp.security.enabled:false}")
    private boolean securityEnabled;

    @Value("${entropy.mcp.security.admin-username:admin}")
    private String adminUsername;

    /**
     * Make the unauthenticated deployment mode impossible to run into by accident.
     *
     * <p>With {@code entropy.mcp.security.enabled=false} the {@code /mcp} endpoint is open to
     * anyone who can reach the port, and every registered BYOK connection — including any with
     * DDL rights — is reachable through it. That is a legitimate choice for a laptop, and a
     * serious exposure anywhere else, so it is stated explicitly at startup instead of being
     * inferable only from the config file.
     */
    @PostConstruct
    void warnIfUnauthenticated() {
        if (securityEnabled) {
            log.info("MCP HTTP authentication is ENABLED; /mcp requires an authenticated principal.");
            return;
        }
        log.warn("""
                ================================================================
                MCP HTTP authentication is DISABLED (entropy.mcp.security.enabled=false).
                /mcp accepts unauthenticated requests, which can execute queries,
                DDL and ETL writes against every registered BYOK connection.
                Only run this way on a host that is not reachable by others.
                To enable: set entropy.mcp.security.enabled=true and provide
                MCP_SECURITY_ADMIN_PASSWORD.
                ================================================================""");
    }

    /**
     * Property names that switch on the OAuth2 resource server. Any one of them being set means
     * Spring Boot can build a {@code JwtDecoder}, so the JWT filter can be wired safely; with none
     * of them set, adding {@code oauth2ResourceServer} would fail the context at startup.
     */
    private static final String[] JWT_PROPERTIES = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        "spring.security.oauth2.resourceserver.jwt.public-key-location"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Environment environment,
            ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/info").permitAll();
                if (securityEnabled) {
                    // Audit history replays raw SQL, which can contain inlined credentials;
                    // keep it and the remaining actuator surface behind an authenticated admin.
                    auth.requestMatchers("/api/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/mcp").authenticated()
                        .anyRequest().denyAll();
                } else {
                    auth.requestMatchers("/mcp").permitAll()
                        .anyRequest().permitAll();
                }
            });

        if (securityEnabled) {
            // Without an authentication mechanism the rules above can never be satisfied.
            http.httpBasic(basic -> basic.realmName("database-mcp-server"));

            if (jwtResourceServerConfigured(environment)) {
                JwtAuthenticationConverter converter = jwtAuthenticationConverter.getIfAvailable();
                http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                    if (converter != null) {
                        jwt.jwtAuthenticationConverter(converter);
                    }
                }));
                log.info("MCP HTTP authentication accepts HTTP Basic and Bearer (JWT) credentials.");
            } else {
                log.info("MCP HTTP authentication accepts HTTP Basic credentials "
                    + "(no spring.security.oauth2.resourceserver.jwt.* configured, JWT disabled).");
            }
        }
        return http.build();
    }

    private static boolean jwtResourceServerConfigured(Environment environment) {
        for (String property : JWT_PROPERTIES) {
            String value = environment.getProperty(property);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
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
            .username(adminUsername)
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
