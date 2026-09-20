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

import java.util.Locale;
import java.util.Set;

/**
 * 调用者凭据的格式规则。
 *
 * <p>抽出来是因为它有<b>两个</b>使用者：进程外的凭据文件（{@link UserFile}）与数据库里的用户表
 * （{@link JdbcUserStore}）。规则写两份的后果是其中一条路能塞进原文口令或一个拼错的主体类型，
 * 而这个项目已经为"两处 DDL 不同源"付过一次代价（见 {@code AuditLogInitializer} 类注释）。
 */
final class Credentials {

    /** bcrypt 哈希的三种前缀。Spring Security 的 {@code BCryptPasswordEncoder} 都能校验。 */
    private static final Set<String> BCRYPT_PREFIXES = Set.of("$2a$", "$2b$", "$2y$");

    /**
     * 允许的主体类型。
     *
     * <p>收成白名单而不是任意字符串：类型会进入授权判定，写错一个字母的后果是"策略里给
     * {@code agent} 的权限，这个拼成 {@code agnet} 的调用者一条都拿不到"——而它照样能通过认证，
     * 症状是"能登录但什么都干不了"，很难反推到一个拼写错误。
     */
    static final Set<String> PRINCIPAL_TYPES = Set.of("user", "agent", "service");

    private Credentials() {
    }

    static boolean isBcrypt(String hash) {
        return hash != null && BCRYPT_PREFIXES.stream().anyMatch(hash::startsWith);
    }

    /** 归一化并校验主体类型；大小写不敏感，未知类型抛错。 */
    static String requireKnownType(String type, String context) {
        String normalized = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
        if (!PRINCIPAL_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(context
                    + "：主体类型 '" + type + "' 不在允许范围内；可选值 " + PRINCIPAL_TYPES);
        }
        return normalized;
    }

    /**
     * 校验口令是 bcrypt 哈希。
     *
     * <p><b>异常信息里不放哈希本身</b>——它会进日志。
     */
    static String requireBcrypt(String hash, String context) {
        if (!isBcrypt(hash)) {
            throw new IllegalArgumentException(context
                    + "：口令必须是 bcrypt 哈希（以 $2a$ / $2b$ / $2y$ 开头），不接受原文");
        }
        return hash;
    }
}
