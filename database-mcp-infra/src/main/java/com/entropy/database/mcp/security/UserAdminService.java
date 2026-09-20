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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 调用者身份的运维入口：不重启增删用户。
 *
 * <p>存在的理由是 {@link JdbcUserStore} 是包级私有的，而控制器在 {@code tools} 模块。这一层同时
 * 承担三件只该做一次的事：口令哈希、管理员保护、以及把内部行形态翻译成对外的视图。
 *
 * <h2>为什么这里接受口令原文，而凭据文件不接受</h2>
 * <p>看起来矛盾，其实是两种不同的暴露面。凭据文件<b>长期躺在磁盘上</b>，一旦泄漏就是全部凭据，
 * 所以只接受哈希；而这里的原文只存在于一次请求体里，落库前就变成哈希。要求运维先自己跑一遍
 * {@code htpasswd} 才能加一个用户，等于把"不重启加用户"这件事又变回一道手工流程。
 *
 * <p>代价必须说清：原文会经过 HTTP。<b>这个接口必须跑在 HTTPS 上</b>，并且不要把请求体写进
 * 访问日志。{@code /api/**} 已经要求 {@code ROLE_ADMIN}，但传输层的保护不在这一层。
 *
 * <h2>为什么管理员不能被这个接口碰</h2>
 * <p>管理员口令来自环境变量、不进库（见 {@link SecurityConfig#resolveAdminPassword}）。允许在库里
 * 插一条同名记录，就会出现"两处定义、谁生效取决于查找顺序"；允许停用它，则是把自己锁在门外之后
 * 连改回来的入口都没了。两者都在这一层拒绝。
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class UserAdminService {

    private final JdbcUserStore store;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;

    UserAdminService(JdbcUserStore store,
                     PasswordEncoder passwordEncoder,
                     @Value("${entropy.mcp.security.admin-username:admin}") String adminUsername) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
    }

    /**
     * 一条身份的对外视图。<b>没有口令字段</b>——这个记录会被序列化进 HTTP 响应。
     *
     * @param username 登录名，也是授权判定里的主体 id
     * @param type     主体类型：{@code user} / {@code agent} / {@code service}
     * @param enabled  是否可登录
     * @param roles    授予的权限，排序后输出。带出来是刚需：不然运维在列表里看不出谁有 {@code ROLE_ADMIN}，
     *                 而"谁能打开面板、谁只能调 /mcp"正是这个接口最常被问到的问题
     */
    public record View(String username, String type, boolean enabled, List<String> roles) {}

    /** 全部身份，按用户名排序。不含管理员——它不在库里。 */
    public List<View> list() {
        return store.list().stream()
                .map(row -> new View(row.username(), row.type(), row.enabled(), sorted(row.roles())))
                .toList();
    }

    /**
     * 新增一个身份。
     *
     * @param password 口令<b>原文</b>，落库前哈希
     * @param roles    授予的权限，{@code null} 或空表示不授予（与升级前的行为一致）
     * @throws IllegalArgumentException 用户名为空或与管理员同名、类型未知、口令为空、权限不在白名单、
     *                                  用户名已存在
     */
    public View create(String username, String type, String password, List<String> roles) {
        String name = username == null ? "" : username.strip();
        if (name.equals(adminUsername)) {
            throw new IllegalArgumentException(
                    "'" + name + "' 是管理员，它的口令来自环境变量、不在库里；"
                    + "在库里再插一条同名记录会让两处定义同时存在");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("口令不能为空");
        }
        // 归一化由 store 做，这里只负责把原文变成哈希
        String normalizedType = Credentials.requireKnownType(type, "新增 '" + name + "'");
        // 权限在这里先归一一次，只为了让返回的 View 说的是真的落库的那份（大写、去重、去空白）。
        // store 仍然会自己再校验一遍——它是包级可见的，种子与测试都直接调它，校验不能只在这一层。
        var granted = JdbcUserStore.requireGrantableRoles(roles, "新增 '" + name + "'");
        store.create(name, normalizedType, passwordEncoder.encode(password), granted);
        return new View(name, normalizedType, true, sorted(granted));
    }

    /**
     * 停用一个身份。
     *
     * @return 是否真的改到了一行；{@code false} 表示这个用户名不存在
     * @throws IllegalArgumentException 试图停用管理员
     */
    public boolean disable(String username) {
        if (adminUsername.equals(username)) {
            throw new IllegalArgumentException(
                    "不能停用管理员：它是最后一个入口，停掉之后连改回来的接口都进不去");
        }
        return store.disable(username);
    }

    /** 权限排序输出：JSON 里字段顺序不稳定的话，两次 {@code /api/users} 的结果 diff 起来全是噪音。 */
    private static List<String> sorted(Collection<String> roles) {
        return roles.stream().sorted().toList();
    }
}
