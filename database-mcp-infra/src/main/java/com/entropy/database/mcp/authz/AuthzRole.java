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
package com.entropy.database.mcp.authz;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 授权角色，以及每个角色蕴含的读写能力。
 *
 * <p>这个枚举是 <b>"reader / writer / admin 各自能干什么"这件事的唯一定义处</b>。原先它散在三处：
 * {@code ToolAuthzProperties.ROLES} 的白名单、{@link ToolPolicy} 里那两条 {@code computed} 规则、
 * 以及任何想把授权展示给人看的地方。前两处漂移的症状很安静——白名单收了一个 {@code admin}，
 * 而规则里忘了把它算进 {@code write}，于是配置合法、启动正常、那个人写入时被拒。
 *
 * <p>读写的包含关系写在这里，{@link ToolPolicy} 从它生成 facet 的 {@code computed} 规则：
 * <pre>
 * read  = reader ∪ writer ∪ admin
 * write = writer ∪ admin
 * </pre>
 *
 * <p><b>这不是 Spring Security 的 role。</b>{@code ROLE_ADMIN} / {@code ROLE_DBA} 是 HTTP 层的
 * authority（决定谁能开面板、谁能调 {@code /api/**}），与这里的三个角色<b>没有任何关系</b>——
 * 按调用者的连接级/表级判定只用 {@code McpPrincipal} 的 {@code type} 与 {@code username}，
 * 不看 authority（见 {@code ConnectionAuthorizer.currentSubject}）。两套东西同名容易混，
 * 所以这里的取值一律小写，与配置里的写法一致。
 */
public enum AuthzRole {

    /** 只能读。 */
    READER(true, false),
    /** 能写，也能读——所以配置里不需要给同一个人再补一条 reader。 */
    WRITER(true, true),
    /** 读写都能。当前与 writer 的能力相同，保留它是为了在配置里表达"这条连接的负责人"。 */
    ADMIN(true, true);

    private final boolean read;
    private final boolean write;

    AuthzRole(boolean read, boolean write) {
        this.read = read;
        this.write = write;
    }

    /** 配置与 facet 关系名里用的写法（小写）。 */
    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean grantsRead() {
        return read;
    }

    public boolean grantsWrite() {
        return write;
    }

    /** 白名单。{@code ToolAuthzProperties.Grant} 用它校验配置，错误信息里也会带上它。 */
    public static Set<String> configNames() {
        return Arrays.stream(values()).map(AuthzRole::configName).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 解析配置里的角色名。
     *
     * @throws IllegalArgumentException 不在白名单内。调用方通常已经校验过（{@code Grant} 的构造期），
     *                                  所以走到这里抛异常意味着有人绕过了那道校验
     */
    public static AuthzRole of(String role) {
        String normalized = role == null ? "" : role.strip().toLowerCase(Locale.ROOT);
        for (AuthzRole candidate : values()) {
            if (candidate.configName().equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "角色 '" + role + "' 不在允许范围内；可选值 " + configNames());
    }
}
