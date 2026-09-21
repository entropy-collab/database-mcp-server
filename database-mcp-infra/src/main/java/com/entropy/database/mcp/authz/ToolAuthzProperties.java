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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 授权策略的配置形态。
 *
 * <p>配置里出现的是运维在想的词——"谁、在哪条连接上、是什么角色"——而不是底层的授权元组。
 * 三元组到元组的翻译只发生在 {@link ToolPolicy} 一处。
 *
 * <pre>
 * entropy.mcp.authz:
 *   enabled: true
 *   grants:
 *     - subject: user:zhangsan          # 整条连接
 *       connection: prod
 *       role: reader
 *     - subject: agent:claude-x         # 只有这一张表
 *       connection: prod
 *       table: audit_log
 *       role: writer
 * </pre>
 *
 * <p><b>{@code table} 的有无决定授权的粒度</b>，两者是并集而不是交集：不带 {@code table} 的
 * grant 覆盖该连接上的全部对象；带 {@code table} 的 grant <em>只</em>覆盖那一张表，不附带
 * 任何连接级权限。所以"只能写 audit_log"写一条就够，不需要再配一条连接级的。</p>
 *
 * <p><b>为什么策略进 yml，而身份不进。</b>身份里有口令，进版本控制就是泄漏；策略里没有秘密，
 * 而且让它进版本控制是<em>好事</em>——授权变更因此要走一次 code review，事后也查得到是哪个提交
 * 放开的。这一点与 {@code SecurityConfig} 把口令挡在 yml 之外并不矛盾，两者的判据是"是不是秘密"。
 *
 * <p><b>默认关闭。</b>打开之后是 fail-closed：没有 grant 的组合一律拒绝。这是个破坏性的开关，
 * 必须由部署方明确决定。
 */
@ConfigurationProperties(prefix = "entropy.mcp.authz")
public class ToolAuthzProperties {

    /**
     * 允许的角色名。收成白名单，理由与主体类型相同：拼错一个字母的症状是"配了但不生效"。
     *
     * <p>取值从 {@link AuthzRole} 推导，<b>不在这里再写一份</b>：这里的白名单与
     * {@code ToolPolicy} 里那两条读写规则必须覆盖同一组角色，各写一份的漂移症状是
     * "配置合法、启动正常、那个人写入时被拒"。
     */
    static final Set<String> ROLES = AuthzRole.configNames();

    private boolean enabled;
    private List<Grant> grants = new ArrayList<>();

    /**
     * 一条授权。
     *
     * @param subject    主体，形如 {@code user:zhangsan} / {@code agent:claude-x}，
     *                   与 {@code McpPrincipal.subjectRef()} 的格式一致
     * @param connection 连接名
     * @param role       {@code reader} / {@code writer} / {@code admin}
     * @param table      表名，{@code null} 表示这条 grant 作用于整条连接。给了表名就<em>只</em>
     *                   作用于那张表；大小写无关（统一折成大写，与从 SQL 里取出的对象名一致）。
     *                   可以带 schema 前缀（{@code app.users}）——带前缀时匹配更精确，见
     *                   {@code ConnectionAuthorizer} 关于"不带前缀的 grant 覆盖该连接上同名的
     *                   每一张表"的说明。
     */
    public record Grant(String subject, String connection, String role, String table) {

        public Grant {
            subject = require(subject, "subject");
            connection = require(connection, "connection");
            role = require(role, "role").toLowerCase(Locale.ROOT);
            // 只有 table 允许缺省，所以它单独处理；空白字符串按缺省处理，不按"表名是空串"
            table = table == null || table.isBlank() ? null : table.strip().toUpperCase(Locale.ROOT);
            if (!subject.contains(":")) {
                throw new IllegalArgumentException(
                        "grant 的 subject 必须是 '类型:id' 形式（如 user:zhangsan），实际是 '" + subject + "'");
            }
            if (!ROLES.contains(role)) {
                throw new IllegalArgumentException(
                        "grant 的 role '" + role + "' 不在允许范围内；可选值 " + ROLES);
            }
        }

        private static String require(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("grant 的 " + field + " 不能为空");
            }
            return value.strip();
        }

        /** 主体类型，{@code user} / {@code agent} / {@code service}。 */
        public String subjectType() {
            return subject.substring(0, subject.indexOf(':'));
        }

        /** 主体 id。 */
        public String subjectId() {
            return subject.substring(subject.indexOf(':') + 1);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Grant> getGrants() {
        return grants;
    }

    public void setGrants(List<Grant> grants) {
        this.grants = grants == null ? new ArrayList<>() : grants;
    }
}
