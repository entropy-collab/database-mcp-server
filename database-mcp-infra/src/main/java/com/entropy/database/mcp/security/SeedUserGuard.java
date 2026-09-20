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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 拦住"演示种子被带进生产"。
 *
 * <p>{@code db/users-seed.sql} 里的十个身份共用一个<b>写在仓库里的公开口令</b>。那是种子数据该有的
 * 样子——它的用途是"起来就能点"。但同一个 jar 同一个状态库跑到生产上时，那十个身份就是十个
 * 任何读过仓库的人都能用的入口，而且它们看起来和真实身份一模一样，不会有任何报错。
 *
 * <p>所以这里在 production profile 下把它变成<b>启动失败</b>。理由与 {@code SecurityConfig} 对
 * "生产裸跑"的处理相同：漏判的代价（生产上有十个公开凭据）远大于误判的代价（被迫先清一下种子）。
 *
 * <h2>判据是用户名前缀，不是一份名单</h2>
 * <p>前缀 {@code demo-} 是数据自带的标记。把十个名字写死在这里会变成两处真相——改了 SQL 忘了改
 * 代码，这道检查就静默失效，而"静默失效的安全检查"比没有检查更糟：它让人以为有人在看着。
 *
 * <h2>为什么不比对口令哈希</h2>
 * <p>bcrypt 带盐，同一个口令每次生成的哈希都不同，没法拿仓库里那串去匹配。就算能匹配，
 * 改掉种子口令的人也会绕过检查——而他改口令恰恰说明他知道自己在干什么。前缀检查针对的是
 * "原封不动地带上去"这个真实场景。
 */
@Component
@ConditionalOnProperty(name = "spring.datasource.url")
class SeedUserGuard {

    private static final Logger log = LoggerFactory.getLogger(SeedUserGuard.class);

    /** 种子身份的用户名前缀。与 {@code db/users-seed.sql} 里的前缀必须一致。 */
    static final String SEED_PREFIX = "demo-";

    /** 与 {@code SecurityConfig.PRODUCTION_PROFILES} 同一组名字，理由见那里。 */
    private static final Set<String> PRODUCTION_PROFILES = Set.of("production", "prod");

    private final JdbcUserStore store;
    private final Environment environment;
    private final String adminUsername;

    SeedUserGuard(JdbcUserStore store,
                  Environment environment,
                  @Value("${entropy.mcp.security.admin-username:admin}") String adminUsername) {
        this.store = store;
        this.environment = environment;
        this.adminUsername = adminUsername;
    }

    @PostConstruct
    void check() {
        var names = store.list().stream().map(JdbcUserStore.Row::username).toList();
        requireNoShadowedAdmin(names, adminUsername);

        var seeded = names.stream().filter(name -> name.startsWith(SEED_PREFIX)).toList();
        if (seeded.isEmpty()) {
            return;
        }
        String productionProfile = activeProductionProfile();
        if (productionProfile != null) {
            throw new IllegalStateException(describe(seeded, productionProfile));
        }
        // 非生产也要说一声：种子口令是公开的，这台机器不该暴露在别人能访问的网络上
        log.warn("""
                ================================================================
                状态库里有 {} 个演示身份（{} 前缀），它们共用一个写在仓库里的公开口令。
                任何读过这个仓库的人都能用它们登录并调用全部工具。
                只在别人访问不到的主机上这样跑。清理：
                  DELETE FROM mcp_user WHERE username LIKE '{}%';
                ================================================================""",
                seeded.size(), SEED_PREFIX, SEED_PREFIX);
    }

    /**
     * 表里不能有与管理员同名的记录。
     *
     * <p>{@code UserAdminService.create} 已经拒绝这件事，但<b>直接写 SQL 绕得过去</b>——而种子脚本
     * 恰恰就是直接写 SQL。绕过去之后症状是安静的：{@code UserStoreUserDetailsService} 先比管理员、
     * 命中就直接返回，表里那一行永远不会被读到。于是"我明明改了 admin 的口令，为什么还是旧的"
     * 会变成一个无从下手的问题——没有报错，没有日志，只有一行什么都不做的记录。
     *
     * <p>所以在启动期变成硬失败，而且不分 profile：这不是"演示环境可以将就"的取舍，
     * 而是一条永远对不上的配置。
     */
    static void requireNoShadowedAdmin(List<String> usernames, String adminUsername) {
        if (usernames.contains(adminUsername)) {
            throw new IllegalStateException(
                    "Refusing to start: 状态库的 mcp_user 表里有一行 username='" + adminUsername
                    + "'，与 entropy.mcp.security.admin-username 同名。管理员口令来自环境变量 "
                    + "MCP_SECURITY_ADMIN_PASSWORD、不走这张表，所以这一行永远不会生效——"
                    + "它不会报错，只会让人以为改了管理员口令。"
                    + "请删掉它（DELETE FROM mcp_user WHERE username = '" + adminUsername + "'），"
                    + "或者把 entropy.mcp.security.admin-username 改成别的名字。");
        }
    }

    /** 抽出来好让失败信息能被直接断言，而不必真的启一个 production profile 的容器。 */
    static String describe(List<String> seeded, String productionProfile) {
        return "Refusing to start: 状态库里存在演示种子身份 " + seeded
                + "，而当前激活的是 '" + productionProfile + "' profile。"
                + "这些身份的口令是写在仓库里的公开值（见 db/users-seed.sql），"
                + "留在生产上等于留下 " + seeded.size() + " 个任何人都能用的入口。"
                + "清理后再启动：DELETE FROM mcp_user WHERE username LIKE '" + SEED_PREFIX + "%';"
                + "（只想停用：UPDATE mcp_user SET enabled = 0 WHERE ... —— 注意停用不够，"
                + "这道检查看的是记录是否存在，因为停用可以被任何有 /api/users 权限的人改回去）";
    }

    /** 只看 {@code getActiveProfiles()}：default profile 不该被当成生产。 */
    private String activeProductionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (profile != null && PRODUCTION_PROFILES.contains(profile.trim().toLowerCase(Locale.ROOT))) {
                return profile;
            }
        }
        return null;
    }
}
