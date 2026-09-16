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

import java.util.Locale;
import java.util.Set;

/**
 * Security configuration. Always active to override Spring Boot defaults.
 * Authentication is controlled by entropy.mcp.security.enabled.
 *
 * <p><b>破坏性变更（0.4.0 引入，沿用至今）</b>：默认值由 {@code false} 改为 {@code true}。理由是这个开关的两个
 * 方向不对称——开着而没配密码会启动失败（响亮、当场发现），关着则是 {@code /mcp} 对任何能连上端口
 * 的人开放、且每个已注册的 BYOK 连接（含有 DDL 权限的）都能被打穿（无声、要靠读日志才发现）。
 * 默认值应该站在会响的那一侧。本地开发需要免密时显式写 {@code entropy.mcp.security.enabled=false}。
 *
 * <p>并且从 0.6.0 起，{@code production} profile 下把它关掉不再只是一条 {@code log.warn}，而是直接让上下文
 * 启动失败，除非同时显式打开 {@link #allowUnauthenticatedInProduction} 这个逃生阀。
 *
 * <p>{@code proxyBeanMethods = false}：两个 {@code @Bean} 方法之间没有互相调用，不需要
 * CGLIB 代理。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** 密码只认真实环境变量或真实 JVM {@code -D}，见 {@link #resolveAdminPassword()}。 */
    private static final String ADMIN_PASSWORD_ENV = "MCP_SECURITY_ADMIN_PASSWORD";
    private static final String ADMIN_PASSWORD_SYSTEM_PROPERTY = "mcp.security.admin-password";

    /**
     * 被当作「生产」的 profile 名。仓库里实际存在的是 {@code application-production.yml}
     * （{@code Dockerfile}、{@code docker-compose.yml}、{@code .env.example} 都写
     * {@code SPRING_PROFILES_ACTIVE=production}），{@code prod} 一并算进来只是因为它是最常见的手写简称，
     * 漏判的代价（生产裸跑）远大于误判的代价（本地被迫多写一个开关）。
     */
    private static final Set<String> PRODUCTION_PROFILES = Set.of("production", "prod");

    /** 逃生阀的属性名，出现在启动失败信息里，所以抽成常量避免两处文案漂移。 */
    private static final String ALLOW_UNAUTHENTICATED_IN_PRODUCTION =
        "entropy.mcp.security.allow-unauthenticated-in-production";

    @Value("${entropy.mcp.security.enabled:true}")
    private boolean securityEnabled;

    /**
     * production profile 下允许裸跑的显式逃生阀，默认 {@code false}。
     *
     * <p>为什么留阀而不是完全堵死：仓库的部署文件里确实存在 {@code ENTROPY_MCP_SECURITY_ENABLED=false}，
     * 硬堵的结果通常是有人把这段校验直接删掉或改回 warn，安全性反而更差。留一个必须显式写出、名字长到
     * 不好意思提交的开关，可以让「我知道这套生产环境在裸跑」变成配置里 grep 得到、code review 拦得住的事实。
     *
     * <p>否决的方案：(1) 只提高日志级别到 error——照样没人看；(2) 用 {@code @Profile("!production")} 挂条件
     * bean——条件不满足时是「少一个 bean」，症状是 404/403 而不是一条能读懂的启动错误；(3) 新建一个
     * {@code SecurityProperties} 类——{@code entropy.mcp.security.*} 一直由本类用 {@code @Value} 直读
     * （{@code enabled}、{@code admin-username} 都是），为一个布尔值引入第二处真相不值得。
     */
    @Value("${" + ALLOW_UNAUTHENTICATED_IN_PRODUCTION + ":false}")
    private boolean allowUnauthenticatedInProduction;

    @Value("${entropy.mcp.security.admin-username:admin}")
    private String adminUsername;

    private final Environment environment;

    /**
     * {@link Environment} 走构造注入而不是 {@code System.getProperty("spring.profiles.active")}：
     * 后者拿不到 {@code SPRING_PROFILES_ACTIVE} 环境变量、也拿不到 {@code @ActiveProfiles} 与
     * {@code spring.profiles.include} 合并后的结果，正好在容器部署这条最需要生效的路径上失灵。
     */
    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Make the unauthenticated deployment mode impossible to run into by accident.
     *
     * <p>With {@code entropy.mcp.security.enabled=false} the {@code /mcp} endpoint is open to
     * anyone who can reach the port, and every registered BYOK connection — including any with
     * DDL rights — is reachable through it. That is a legitimate choice for a laptop, and a
     * serious exposure anywhere else, so it is stated explicitly at startup instead of being
     * inferable only from the config file.
     *
     * <p><b>production profile 下不再只是告警，而是直接让上下文起不来。</b>原因是 warn 这一档已经被证明
     * 不够：日志里那段横幅存在期间，部署文件里照样写着 {@code ENTROPY_MCP_SECURITY_ENABLED=false}，
     * 而关掉鉴权不只是放开 {@code /mcp}——{@code anyRequest().permitAll()} 同时放开了会回放原文 SQL 的
     * {@code /api/**} 审计接口和全部 actuator 端点，100+ 个工具里包含 {@code executeDdl}、
     * {@code insertData}、{@code backupData}、{@code killSession}。启动失败是这里唯一会被人当场看见的信号。
     */
    @PostConstruct
    void failFastOrWarnIfUnauthenticated() {
        if (securityEnabled) {
            log.info("MCP HTTP authentication is ENABLED; /mcp requires an authenticated principal.");
            return;
        }
        String productionProfile = activeProductionProfile();
        if (productionProfile != null && !allowUnauthenticatedInProduction) {
            throw new IllegalStateException(
                "production profile 下不允许关闭鉴权；如确需裸跑请显式设置 "
                + ALLOW_UNAUTHENTICATED_IN_PRODUCTION + "=true 并自行承担。"
                + " (active profile '" + productionProfile
                + "' + entropy.mcp.security.enabled=false: /mcp、/api/** 审计接口（含原文 SQL）"
                + "与全部 actuator 端点都会无凭证开放，其中包括 executeDdl / insertData / backupData /"
                + " killSession 等写操作与运维操作工具。正常的修法是删掉那处"
                + " entropy.mcp.security.enabled=false 覆盖（ENTROPY_MCP_SECURITY_ENABLED 环境变量也算）"
                + "并配置 " + ADMIN_PASSWORD_ENV + "。)");
        }
        log.warn("""
                ================================================================
                MCP HTTP authentication is DISABLED.
                This is not the default — something explicitly set
                entropy.mcp.security.enabled=false.
                /mcp accepts unauthenticated requests, which can execute queries,
                DDL and ETL writes against every registered BYOK connection.
                The read-only web UI at / is also open: it renders the audit
                trail (raw SQL), slow queries and the connection list in a
                browser, so this exposure no longer requires knowing any URL.
                Only run this way on a host that is not reachable by others.
                To re-enable: drop that override and provide
                MCP_SECURITY_ADMIN_PASSWORD.
                ================================================================""");
        if (productionProfile != null) {
            // 逃生阀是打开的，所以不抛；但这条必须单独留痕，好让事后审计能定位到「谁在生产裸跑」。
            log.error("Running the '{}' profile WITHOUT authentication because {}=true.",
                productionProfile, ALLOW_UNAUTHENTICATED_IN_PRODUCTION);
        }
    }

    /**
     * 命中的生产 profile 名，没命中返回 {@code null}（返回名字而不是 boolean，是为了让错误信息里能写出
     * 到底是哪一个 profile 触发的）。
     *
     * <p>只看 {@code getActiveProfiles()}：default profile 不该被当成生产，否则本地不带任何 profile
     * 起服务也会被这条校验挡住。
     */
    private String activeProductionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (profile != null && PRODUCTION_PROFILES.contains(profile.trim().toLowerCase(Locale.ROOT))) {
                return profile;
            }
        }
        return null;
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

    /**
     * 只读运维页面的静态资源路径。产物由 {@code database-mcp-app/src/main/frontend} 的
     * Vite 构建生成，落在 {@code target/classes/static/}（进 jar 后是 {@code BOOT-INF/classes/static/}）。
     *
     * <p>为什么必须在这里单独列出来：鉴权打开时下面的规则以 {@code anyRequest().denyAll()} 收尾，
     * 所以这几个路径不写进白名单的话，页面在「鉴权已开」的部署上是 403，连管理员带正确凭证也打不开——
     * 那等于这个页面只在裸跑部署上存在，而裸跑正是最不该只有它能用的场景。
     *
     * <p>档位与 {@code /api/**} 完全相同（{@code ROLE_ADMIN}），不是 {@code permitAll}：页面本身不含数据，
     * 但它唯一的用途就是去读 {@code /api/**}，把两者放在不同档位只会造出「页面能开、表格全是 401」
     * 这种既不安全也不好用的中间态。{@code "/"} 单列是因为欢迎页是 Boot 的
     * {@code WelcomePageHandlerMapping} 转发到 {@code index.html} 的，请求路径就是 {@code "/"}。
     *
     * <h2>为什么第三项是通配 {@code /assets/**}，而不是把文件名一个个列出来</h2>
     * <p>这里原本是 {@code "/ui.css", "/ui.js"} 两个精确路径——那是零构建时代的产物名，固定不变。
     * 换成 Vite 之后产物名<b>带内容 hash</b>：{@code assets/index-DKIYf9Qg.js}、
     * {@code assets/index-6_unD-rs.css}，还会按需切出 {@code assets/MenuBottomSheet-<hash>.js}
     * 这类动态 chunk。hash 每次内容变化都会变，所以精确路径清单的失败形态是：
     * <b>改一行前端代码、重新打包、部署上去，页面在所有开了鉴权的环境里静默 403</b>——
     * HTML 能打开（{@code /index.html} 还在清单里），但 JS/CSS 全被拦掉，运维看到一个空白页，
     * 而日志里只有几条 403，没有任何东西指向"白名单里的文件名过期了"。
     * 这个坑一旦踩到很难反推，所以这一行必须是通配，并且不要"为了更精确"再改回文件名。
     *
     * <p>放通配的代价是可控的：{@code /assets/**} 下只有 Vite 的构建产物，
     * 这个目录里不会出现任何数据端点；而它的档位仍然是 {@code ROLE_ADMIN}，不是 {@code permitAll}，
     * 所以未认证的请求拿到的依旧是 401。
     */
    private static final String[] WEB_UI_RESOURCES = {"/", "/index.html", "/assets/**"};

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                // 存活/就绪探针拿不到凭证，所以 health 必须匿名可达；但「可达」只该给出 UP/DOWN。
                // 组件明细里会带上 db 组件的驱动异常（含 JDBC URL）等信息，因此明细由
                // management.endpoint.health.show-details=when-authorized 收口，而不是靠这里的路径规则。
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/info").permitAll();
                if (securityEnabled) {
                    // Audit history replays raw SQL, which can contain inlined credentials;
                    // keep it and the remaining actuator surface behind an authenticated admin.
                    auth.requestMatchers("/api/**").hasRole("ADMIN")
                        .requestMatchers(WEB_UI_RESOURCES).hasRole("ADMIN")
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/mcp").authenticated()
                        .anyRequest().denyAll();
                } else {
                    // 这里仍然是 permitAll：关掉鉴权的语义就是「整个服务不设门」，把 /api/** 或 actuator
                    // 单独留成 401 只会让人以为服务是安全的。真正的收口在 @PostConstruct——production
                    // profile 想走到这个分支必须显式打开逃生阀。
                    //
                    // 从 0.6.0 起这个分支还多放开了一个只读运维页面（/index.html + /api/ui/**，见
                    // WebUiController）。它没有引入任何新权限，但把审计流水从「要知道 /api/audit/logs
                    // 这个路径才读得到」变成了「浏览器打开根路径就看得到」，无鉴权的实际暴露面因此变大。
                    // 页面顶部那条红色横幅就是为这件事准备的，它由 /api/ui/config 的 authEnabled 驱动，
                    // 也就是由本开关驱动；打开本开关是关掉这个暴露的唯一方式。
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
    @ConditionalOnProperty(name = "entropy.mcp.security.enabled", havingValue = "true",
            matchIfMissing = true)
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        String adminPassword = resolveAdminPassword();

        UserDetails admin = User.builder()
            .username(adminUsername)
            .password(passwordEncoder.encode(adminPassword))
            .roles("ADMIN", "DBA")
            .build();

        return new InMemoryUserDetailsManager(admin);
    }

    /**
     * 解析管理员密码，缺失或空白都直接让上下文起不来。
     *
     * <p>刻意不走 Spring {@code Environment}：那会让 {@code entropy.mcp.security.admin-password}
     * 能写进 yml 并被提交进仓库。只认真实环境变量或真实 JVM {@code -D}，两者都不会进版本控制。
     */
    private static String resolveAdminPassword() {
        return requireNonBlankPassword(System.getenv(ADMIN_PASSWORD_ENV),
                System.getProperty(ADMIN_PASSWORD_SYSTEM_PROPERTY));
    }

    /**
     * 环境变量优先于系统属性，两者都不可用时抛错。
     *
     * <p>为什么空白也要拒：{@code docker-compose.yml} 里 {@code ${MCP_SECURITY_ADMIN_PASSWORD:-}}
     * 这类写法在变量未设时会传入空串，{@code .env} 里写一行 {@code MCP_SECURITY_ADMIN_PASSWORD=}
     * 也一样。只判 null 的话服务会带着「admin + 空密码」正常启动并对外提供鉴权，比起不来糟得多。
     *
     * <p>空白的环境变量会继续回退到系统属性，而不是直接失败：环境变量被容器编排设成空串是常见的
     * "没配"，此时 {@code -D} 是更明确的意图表达。
     */
    static String requireNonBlankPassword(String fromEnvironment, String fromSystemProperty) {
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        if (fromSystemProperty != null && !fromSystemProperty.isBlank()) {
            return fromSystemProperty;
        }
        throw new IllegalStateException(
            "MCP security is enabled but no admin password is configured. Set the "
            + ADMIN_PASSWORD_ENV + " environment variable (or the "
            + ADMIN_PASSWORD_SYSTEM_PROPERTY + " system property) to a non-blank value, "
            + "or set entropy.mcp.security.enabled=false to run without authentication.");
    }

    @Bean
    @ConditionalOnProperty(name = "entropy.mcp.security.enabled", havingValue = "true",
            matchIfMissing = true)
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}
