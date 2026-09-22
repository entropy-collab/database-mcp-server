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
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * 调用者身份清单的文件路径，默认空——此时行为与引入这个开关之前<b>完全一致</b>，只有一个管理员。
     *
     * <p>为什么是文件路径而不是 yml 里的一段 list：口令（即便是 bcrypt 哈希）不该进版本控制，
     * 这是 {@link #resolveAdminPassword()} 已经定过的调子。格式与校验见 {@link UserFile}。
     *
     * <p>为什么这里仍然用 {@code @Value} 而不是新建一个 {@code SecurityProperties}：
     * 它只是一个字符串，而 {@code entropy.mcp.security.*} 一直由本类直读。真正需要
     * {@code @ConfigurationProperties} 的是"一个用户列表"，而列表被刻意放到了进程外的文件里，
     * 所以那个理由在这里不成立。
     */
    @Value("${entropy.mcp.security.users-file:}")
    private String usersFile = "";

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
     * <p>放通配的代价是可控的：{@code /assets/**} 下只有 Vite 的构建产物，这个目录里不会出现任何数据端点。
     *
     * <h2>0.6.0 起这三个路径是 {@code permitAll}（破坏性变更，与上一版注释相反）</h2>
     * <p>上一版把它们和 {@code /api/**} 放在同一档（{@code ROLE_ADMIN}），理由是避免"页面能开、
     * 表格全 401"。改成 {@code permitAll} 是为了让登录界面成为 SPA 自己的一个视图，而不是一张
     * 手写的自包含 HTML——登录界面必须匿名可读，而 SPA 的登录视图要加载 {@code /assets/**} 里的
     * bundle 才能渲染。
     *
     * <p><b>判据变了：要保护的是"数据"而不是"代码"。</b>放通之后匿名可读的东西只有 HTML 外壳与
     * JS/CSS，而它们里面没有任何业务数据——审计流水、慢查询原文、连接清单全部来自 {@code /api/**}，
     * 那一档仍然是 {@code ROLE_ADMIN}。上一版担心的"页面能开、表格全 401"现在恰恰是<b>期望行为</b>：
     * 面板发现 {@code /api/ui/config} 是 401 就渲染登录视图，而不是显示一堆空表格。
     *
     * <p>代价（明码标价）：JS/CSS 与 HTML 结构对任何能连上端口的人可读，等于把前端代码与
     * 端点路径清单公开。换来的是登录界面与面板同一套设计系统、同一份构建产物，以及登录后
     * <b>整页不刷新</b>（location.hash 里的现场不丢）。主流 SPA 项目就是这个取舍。
     *
     * <h2>第四项 {@code /favicon.ico}：不是装饰，是在修一个弹窗</h2>
     * <p>{@code index.html} 没有声明 favicon，浏览器就会按约定<b>自己</b>去取 {@code /favicon.ico}——
     * 每次打开面板都发，而且这个请求不受前端代码控制。不放通它的后果是一条很难反推的链路：
     * <ol>
     *   <li>路径不在本清单里 → 落到 {@code anyRequest().denyAll()}；</li>
     *   <li>匿名请求上的 {@code AccessDeniedException} 会被 {@code ExceptionTranslationFilter}
     *       转成"去认证"，于是调用入口点；</li>
     *   <li>favicon 请求的 {@code Accept} 是 {@code image/*}，匹配不上入口点里"纯 401"那一档
     *       （那一档只认 {@code application/json}），落到 {@code BasicAuthenticationEntryPoint}；</li>
     *   <li>于是响应带 {@code WWW-Authenticate: Basic} → <b>浏览器在面板上弹出一个原生 Basic
     *       认证对话框</b>，和 SPA 自己的登录表单同时出现。</li>
     * </ol>
     * 症状看起来像"登录功能坏了"，而成因和 {@code /api/**}、和登录流程都没有关系。
     * {@code SecurityConfigTest#faviconDoesNotTriggerTheNativeBasicDialog} 钉住这条。
     *
     * <p>放通它不扩大数据暴露面：判据与上面那三项逐字相同（保护数据而不是装饰）。仓库里目前
     * 并没有这个文件，所以实际结果是 404 —— 这是可以接受的，要消掉的是 401 上那个挑战头。
     *
     * <p><b>只放通这一条路径并不够。</b>文件不存在 → 静态资源 404 → 容器把请求转发到
     * {@code /error}，那是同一个请求第二次进过滤器链（{@code DispatcherType=ERROR}），
     * 又一次落到 {@code denyAll} 上，于是挑战头照样出现。两件事必须同时做：本清单放通路径，
     * 加上 {@code webSecurityFilterChain} 里对 ERROR 派发的 {@code permitAll}（那里有完整说明）。
     * 这也是 2026-09-21 长春上线 {@code 0.6.0-authz2} 时踩到的：只改了这一行，线上弹窗依旧。
     */
    private static final String[] WEB_UI_RESOURCES = {
        "/", "/index.html", "/assets/**", "/favicon.ico"};

    /** 表单登录与退出的动作端点。两者都要 {@code permitAll}，否则 {@code denyAll} 会把它们收走。 */
    private static final String LOGIN_PROCESSING_URL = "/login";
    private static final String LOGOUT_URL = "/logout";

    /** MCP 传输端点。只有 HTTP {@code /mcp}，没有 stdio，也没有 SSE 的第二个路径。 */
    private static final String[] MCP_ENDPOINTS = {"/mcp", "/mcp/**"};

    /**
     * MCP 端点的过滤器链。<b>这条链的行为与引入登录页之前逐字相同</b>，改动时要格外小心。
     *
     * <p>把它单独拆出来，是"给浏览器加表单登录"这件事能不影响 MCP 客户端的<b>唯一</b>办法。
     * 如果在同一条链上加 {@code formLogin}，会同时坏掉两件事：
     * <ol>
     *   <li>CSRF 一旦打开，{@code POST /mcp} 会变成 403——MCP 的每一次工具调用都是 POST；</li>
     *   <li>{@code formLogin} 会把未认证请求的入口点换成"302 跳登录页"，于是 MCP 客户端收到的是
     *       302 + 一段 HTML 而不是 401。{@code mcp-remote} 那边的表现是"连不上但看不出为什么"，
     *       因为它在等一个带 {@code WWW-Authenticate} 的 401。</li>
     * </ol>
     *
     * <p>{@code @Order(1)}：它必须排在 UI 链之前，否则 {@code /mcp} 会被 UI 链的
     * {@code securityMatcher}（其余全部路径）先吃掉。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain mcpSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverter) throws Exception {
        http
            .securityMatcher(MCP_ENDPOINTS)
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                if (securityEnabled) {
                    auth.anyRequest().authenticated();
                } else {
                    auth.anyRequest().permitAll();
                }
            });

        if (securityEnabled) {
            http.httpBasic(basic -> basic.realmName("database-mcp-server"));
            configureJwtIfAvailable(http, jwtAuthenticationConverter);
        }
        return http.build();
    }

    /**
     * 浏览器面板与运维 API 的过滤器链：表单登录 + 会话 cookie，同时<b>保留</b> HTTP Basic。
     *
     * <h2>为什么 Basic 不能删</h2>
     * <p>{@code /api/**} 不只被浏览器调：运维脚本、{@code curl -u}、以及本仓库的全部 MockMvc 用例都用
     * Basic。删掉它等于让"加一个工具开关"这种事只能开浏览器做。两种机制并存的代价是要显式决定
     * 未认证请求收到什么，见下面的入口点。
     *
     * <h2>入口点按 Accept 分流（这是浏览器能看到登录页的关键）</h2>
     * <ul>
     *   <li>{@code Accept: text/html}（浏览器地址栏导航）→ 302 跳 {@value #LOGIN_PAGE}；</li>
     *   <li>其余（{@code fetch} 带的 {@code application/json}、{@code curl} 的 {@code *&#47;*}、
     *       MockMvc 不带 Accept）→ {@code BasicAuthenticationEntryPoint} 的 401。</li>
     * </ul>
     * 分流而不是一律 302：SPA 的 {@code fetch} 拿到 302 会跟着去取登录页的 HTML，然后在
     * {@code JSON.parse} 上炸掉，报错信息指向一个不存在的解析问题。而 401 是前端已经在处理的东西
     * （{@code api.js} 收到 401 会自己跳登录页并把当前 hash 带上）。
     *
     * <h2>CSRF：打开，但带一条例外</h2>
     * <p>有了 cookie 会话就有了 CSRF 面（浏览器会自动带上 cookie），所以这条链上 CSRF 必须开——
     * 这与原先"全局 csrf.disable()"相反，而原先那样是对的：Basic 凭据不会被浏览器自动附加。
     * 例外是<b>带 {@code Authorization} 头的请求</b>：那种请求的凭据是调用方显式放上去的，
     * 不存在"受害者浏览器替攻击者发请求"这条路径，而要求脚本先去取一个 CSRF token 只会把
     * {@code curl -u ... -X PUT} 这件事变成三步。
     * <p><b>这条链刻意不带 {@code @Order}。</b>它没有 {@code securityMatcher}，所以匹配"任何请求"，
     * 而 Spring Security 要求这种链<b>排在最后</b>（否则抛 {@code UnreachableFilterChainException}）。
     * 不加注解等于取 {@code LOWEST_PRECEDENCE}，自然落在最后；加一个 {@code @Order(2)} 反而会把它
     * 排到测试里那条同样匹配 {@code /**} 的 {@code TestSecurityConfig#testSecurityFilterChain} 之前，
     * 结果是<b>整个测试套件的上下文起不来</b>。/mcp 那条链靠 {@code @Order(1)} 排在前面就够了。
     */
    @Bean
    public SecurityFilterChain webSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverter,
            QueryAuditLogger auditLogger) throws Exception {
        http
            .authorizeHttpRequests(auth -> {
                // 存活/就绪探针拿不到凭证，所以 health 必须匿名可达；但「可达」只该给出 UP/DOWN。
                // 组件明细里会带上 db 组件的驱动异常（含 JDBC URL）等信息，因此明细由
                // management.endpoint.health.show-details=when-authorized 收口，而不是靠这里的路径规则。
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/info").permitAll();
                if (securityEnabled) {
                    /*
                     * 容器内部的 ERROR 派发必须先放通，否则匿名请求上的每一个 404 都会换回一个带
                     * Basic 挑战的 401 —— 这是上面 /favicon.ico 那条规则单独放通<b>不够</b>的原因：
                     * 仓库里没有这个文件，静态资源 404 会被 Tomcat 转发到 /error，那是同一个请求
                     * 第二次进过滤器链（DispatcherType=ERROR），按 anyRequest().denyAll() 又被拒一次，
                     * 于是照样走到 BasicAuthenticationEntryPoint 并带上 WWW-Authenticate。
                     *
                     * Spring Security 6 起授权规则默认对所有 DispatcherType 生效（此前 ERROR/ASYNC
                     * 是隐式放过的），放通 ERROR 派发是官方给的解法。不放大暴露面：ERROR 派发只能由
                     * 容器内部触发，浏览器打不出这种请求；未认证的 /api/** 在第一次（REQUEST）派发
                     * 就已经 401，走不到这一行。直接 GET /error 也仍然被 denyAll 拒掉。
                     */
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    // 面板的 HTML 与构建产物匿名可读，登录动作端点也一样：登录界面是 SPA 的一个视图，
                    // 而 SPA 要先能加载才谈得上渲染登录表单。判据与代价见 WEB_UI_RESOURCES 的注释。
                    auth.requestMatchers(WEB_UI_RESOURCES).permitAll()
                        .requestMatchers(LOGIN_PROCESSING_URL, LOGOUT_URL).permitAll();
                    // Audit history replays raw SQL, which can contain inlined credentials;
                    // keep it and the remaining actuator surface behind an authenticated admin.
                    auth.requestMatchers("/api/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").authenticated()
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
                    auth.anyRequest().permitAll();
                }
            });

        if (!securityEnabled) {
            /*
             * 鉴权关闭时这条链回到原样：无会话、无 CSRF、无登录页。
             *
             * 这不是偷懒——挂一个"谁都能过"的登录表单比没有登录页更糟：它会让人以为服务有门。
             * 前端那边同理，永远收不到 401，所以也永远不会跳到登录页去。
             */
            return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
        }

        var basicEntryPoint = new BasicAuthenticationEntryPoint();
        basicEntryPoint.setRealmName("database-mcp-server");
        basicEntryPoint.afterPropertiesSet();

        /*
         * 未认证时的响应：一律 401，<b>不做 302</b>。
         *
         * 页面（/、/index.html、/assets/**）现在是 permitAll，所以"跳去哪儿"这件事不存在了——
         * 浏览器拿到的就是面板本身，面板发现 /api/ui/config 是 401 就自己渲染登录视图。
         *
         * 分两档的原因只有一个：<b>WWW-Authenticate 头</b>。
         * - fetch（Accept: application/json）→ 纯 401，不带那个头。带上它会让某些浏览器在
         *   XHR/fetch 的 401 上弹出原生 Basic 对话框，而那个框和我们的登录表单会同时出现；
         * - 其余（curl 默认的通配 Accept、MockMvc 不带 Accept）→ BasicAuthenticationEntryPoint，
         *   带 realm 的标准 401，`curl -u` 的体验与既有用例都靠它。
         * ignoredMediaTypes(ALL) 是第一档成立的前提：不带 Accept 的请求会被解析成通配的
         * MediaType.ALL，不忽略它就会连 curl 一起吃进来。
         */
        var jsonRequest = new MediaTypeRequestMatcher(MediaType.APPLICATION_JSON);
        jsonRequest.setIgnoredMediaTypes(Set.of(MediaType.ALL));

        var unauthorizedEntryPoint = new DelegatingAuthenticationEntryPoint(
            new LinkedHashMap<>(Map.of(jsonRequest, new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))));
        unauthorizedEntryPoint.setDefaultEntryPoint(basicEntryPoint);

        http
            /*
             * CSRF：用 Spring Security 7 的 spa() 预设，不再手写。
             *
             * 它一次做完三件原先要自己拼的事：CookieCsrfTokenRepository.withHttpOnlyFalse()、
             * SpaCsrfTokenRequestHandler（请求头里来的原始 token 照常识别，渲染进响应体的那份仍然
             * 带 BREACH 防护的 Xor 编码）、以及把延迟加载的 token 落成 cookie。
             *
             * 手写版曾经用 CsrfTokenRequestAttributeHandler + 一个自己的 OncePerRequestFilter，
             * 那是 Security 6 时代的配方，代价是<b>显式放弃了 BREACH 防护</b>。换成 spa() 是净删代码。
             *
             * 例外仍然保留：带 Authorization 头的请求不校验 CSRF。CSRF 防的是浏览器自动附加的凭据，
             * 而 Basic / Bearer 是调用方显式放上去的；否则 curl -u ... -X PUT 要先去取一个 token。
             */
            .csrf(csrf -> csrf
                .spa()
                .ignoringRequestMatchers(request -> request.getHeader(HttpHeaders.AUTHORIZATION) != null))
            .formLogin(form -> form
                /*
                 * 登录界面是 SPA 自己的一个视图（见前端 LoginView），所以这里把 loginPage 指到 "/"：
                 * 它的唯一作用是关掉 Spring 自动生成的那张登录页，避免 GET /login 冒出一张
                 * 与面板毫无关系的默认表单。真正的未认证响应由下面的 exceptionHandling 决定。
                 */
                .loginPage("/")
                .loginProcessingUrl(LOGIN_PROCESSING_URL)
                /*
                 * 成功/失败都只给状态码，不做 302。
                 *
                 * 登录表单在 SPA 里用 fetch 提交：204 之后它重新拉一次 /api/ui/config 就切到面板，
                 * <b>整页不刷新</b>，所以 location.hash 里的现场（view / limit / 选中行）天然保住了——
                 * 这也是把登录做进 SPA 之后，redirect 参数那套东西可以整体删掉的原因。
                 */
                .successHandler((request, response, authentication) -> {
                    // 表单登录成功记审计。不用 AuthenticationSuccessEvent 的理由：Basic 是每请求认证，
                    // 那个事件会每次 API 调用都发一条，审计表会被刷满（见 AuthenticationAuditListener 的注释）。
                    // 用 logWithPrincipal 显式传入用户名：这个时刻 SecurityContext 尚未写入，
                    // currentPrincipal() 拿不到值。
                    String who = authentication.getName();
                    auditLogger.logWithPrincipal("auth:login", "LOGIN " + who, 0, 0L, true, null, null, who);
                    response.setStatus(HttpStatus.NO_CONTENT.value());
                })
                .failureHandler((request, response, exception) ->
                    response.setStatus(HttpStatus.UNAUTHORIZED.value())))
            .logout(logout -> logout
                .logoutUrl(LOGOUT_URL)
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> {
                    if (authentication != null) {
                        // 同上用 logWithPrincipal：退出成功时 SecurityContext 已经被清掉了。
                        String who = authentication.getName();
                        auditLogger.logWithPrincipal("auth:logout", "LOGOUT " + who,
                                0, 0L, true, null, null, who);
                    }
                    response.setStatus(HttpStatus.NO_CONTENT.value());
                }))
            .httpBasic(basic -> basic.authenticationEntryPoint(basicEntryPoint))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint));

        configureJwtIfAvailable(http, jwtAuthenticationConverter);
        return http.build();
    }

    /**
     * 配了 {@code spring.security.oauth2.resourceserver.jwt.*} 才挂 JWT 校验，两条链各挂一次。
     *
     * <p>不能无条件挂：一个都没配时容器里没有 {@code JwtDecoder}，{@code oauth2ResourceServer}
     * 会让上下文启动失败。
     */
    private void configureJwtIfAvailable(
            HttpSecurity http,
            ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverter) throws Exception {
        if (!jwtResourceServerConfigured(environment)) {
            log.debug("JWT disabled (no spring.security.oauth2.resourceserver.jwt.* configured).");
            return;
        }
        JwtAuthenticationConverter converter = jwtAuthenticationConverter.getIfAvailable();
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
            if (converter != null) {
                jwt.jwtAuthenticationConverter(converter);
            }
        }));
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
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder,
                                                ObjectProvider<JdbcUserStore> userStore) {
        String adminPassword = resolveAdminPassword();
        var admin = new McpPrincipal(adminUsername, "user",
                passwordEncoder.encode(adminPassword), Set.of("ROLE_ADMIN", "ROLE_DBA"));

        JdbcUserStore store = userStore.getIfAvailable();
        if (store != null && !usersFile.isBlank()) {
            // 两个来源就是两处真相：同一个用户名在两边不一致时，"哪个生效"取决于装配顺序
            throw new IllegalStateException(
                    "entropy.mcp.security.users-file 与 spring.datasource.url 只能配一个："
                    + "身份必须有唯一来源。配了状态库就用 /api/users 管理身份，"
                    + "只有凭据文件时身份在启动时从文件加载。");
        }
        if (store != null) {
            return new UserStoreUserDetailsService(admin, store);
        }
        if (!usersFile.isBlank()) {
            return new InMemoryUserDetailsManager(fromFile(admin));
        }
        return new InMemoryUserDetailsManager(List.of(admin));
    }

    /**
     * 数据库为来源时的查找逻辑。
     *
     * <p>不用 {@link InMemoryUserDetailsManager}：那会在启动时把表整个读进内存，"不重启加用户"
     * 就又失效了——而那正是选数据库的唯一理由。管理员仍然只在内存里，不进表。
     *
     * <p>包级可见以便直接断言"表里的 roles 真的变成了 authority"，不必为此启一整个容器。
     */
    record UserStoreUserDetailsService(McpPrincipal admin, JdbcUserStore store)
            implements UserDetailsService {

        @Override
        public UserDetails loadUserByUsername(String username) {
            if (admin.username().equals(username)) {
                return admin;
            }
            var row = store.find(username)
                    .orElseThrow(() -> new UsernameNotFoundException("未知调用者: " + username));
            if (!row.enabled()) {
                // 停用与不存在都报同一个异常：区分开会让这个接口变成一个"用户名是否存在"的探测器
                throw new UsernameNotFoundException("未知调用者: " + username);
            }
            // 权限来自表里的 roles 列，不是 McpPrincipal.of 的空集：否则库里的身份永远拿不到
            // /api/** 与只读面板（两者都要 ROLE_ADMIN），症状是"能调 /mcp 但打开页面一律 403"。
            // 凭据文件那条路仍然走 McpPrincipal.of，语义不变（文件里的身份一律无权限）。
            return new McpPrincipal(row.username(), row.type(), row.passwordHash(), row.roles());
        }
    }

    /** 凭据文件为来源时，把整份清单读进内存。文件本身是静态的，所以全量加载没有代价。 */
    private List<UserDetails> fromFile(McpPrincipal admin) {
        var principals = new ArrayList<UserDetails>();
        principals.add(admin);
        var entries = UserFile.load(Path.of(usersFile));
        for (var entry : entries) {
            if (entry.username().equals(adminUsername)) {
                // 同名会让"哪一条生效"取决于装配顺序，而那不该是安全行为的决定因素
                throw new IllegalStateException("用户凭据文件里的 '" + entry.username()
                        + "' 与 entropy.mcp.security.admin-username 同名，请改掉其中一个");
            }
            principals.add(McpPrincipal.of(entry.username(), entry.type(), entry.passwordHash()));
        }
        warnIdentitiesHaveNoToolLevelPolicy(usersFile, entries.size());
        return principals;
    }

    /**
     * 身份加载完成后的告警。
     *
     * <p>这一步<b>扩大了攻击面</b>而不是缩小：在此之前只有掌握管理员口令的人能到 {@code /mcp}，
     * 之后每个身份都能。而 {@code /mcp} 目前只要求 {@code authenticated}，工具级授权尚未就位。
     * 这条告警存在的意义就是让这个中间状态没法被忘掉。
     */
    static void warnIdentitiesHaveNoToolLevelPolicy(String source, int count) {
        log.warn("""
                ================================================================
                已从 {} 加载 {} 个调用者身份。
                这些身份【没有任何权限】，因此拿不到 /api/** 与只读运维页面；
                但 /mcp 目前只要求 authenticated，所以它们可以调用全部工具，
                包括 executeDdl / insertData / backupData / killSession。
                在工具级授权就位之前，不要往这里加不该有 DDL 权限的人。
                ================================================================""", source, count);
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
