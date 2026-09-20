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

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;

/**
 * 带类型的调用者身份。
 *
 * <p>Spring 自带的 {@code User} 只有用户名、口令和权限三样，装不下<b>主体类型</b>。
 * 而 MCP 的调用方经常不是人——可能是 AI agent，也可能是定时任务，这个区分之后要进授权判定：
 * "所有登录用户可读"和"所有 agent 可读"是两条不同的策略。把类型放进 {@link UserDetails}，
 * 工具层就能从 {@code SecurityContextHolder} 一次拿到完整主体，不必再去查一遍用户清单。
 *
 * <p><b>为什么类型不做成一个 authority。</b>塞成 {@code ROLE_AGENT} 之类能省掉这个类，
 * 但那会让"类型"和"权限"混进同一个集合里：之后任何一处 {@code hasRole(...)} 都可能意外匹配到
 * 一个类型标记，而这类错误的症状是"某个类型的调用者莫名拿到了额外权限"。
 *
 * @param username  登录名，也是授权判定里的主体 id
 * @param type      主体类型：{@code user} / {@code agent} / {@code service}
 * @param password  bcrypt 哈希
 * @param authorities 授予的权限。<b>从凭据文件加载的身份一律为空</b>；状态库里的身份取自
 *                    {@code mcp_user.roles} 列——两者都见 {@link SecurityConfig#userDetailsService}
 */
public record McpPrincipal(String username,
                           String type,
                           String password,
                           Set<String> authorities) implements UserDetails {

    public McpPrincipal {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("主体 id 不能为空");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("主体类型不能为空");
        }
        authorities = Set.copyOf(authorities);
    }

    /** 无任何权限的身份。凭据文件里的调用者走这条路。 */
    static McpPrincipal of(String username, String type, String password) {
        return new McpPrincipal(username, type, password, Set.of());
    }

    /**
     * 授权判定里的主体标识，形如 {@code user:zhangsan} / {@code agent:claude-x}。
     *
     * <p>放在这里而不是让每个调用点自己拼：拼错的后果是策略查不到这个主体，而"查不到"表现为
     * 一次正常的拒绝，不会有任何报错。
     */
    public String subjectRef() {
        return type + ':' + username;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities.stream().map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast).toList();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    /** 口令哈希不进 {@code toString}——它会出现在日志与异常里。 */
    @Override
    public String toString() {
        return "McpPrincipal[" + subjectRef() + ", authorities=" + authorities + ']';
    }
}
