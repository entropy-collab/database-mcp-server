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
package com.entropy.database.mcp.controller;

import com.entropy.database.mcp.authz.AuthzRole;
import com.entropy.database.mcp.authz.ToolAuthzProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按调用者的连接级/表级授权，摊平成人能读的一张表。
 *
 * <p>挂在 {@code /api/**} 之下，因此由 {@code SecurityConfig} 强制 {@code ROLE_ADMIN}。
 * <b>只读</b>：这里没有任何写入口，改授权只能改 {@code entropy.mcp.authz.grants} 并重启——
 * 那是刻意的，策略进版本控制才能走 code review、事后查得到是哪个提交放开的
 * （理由见 {@link ToolAuthzProperties} 的类注释）。
 *
 * <h2>为什么是正向展开配置，而不是问一遍判定引擎</h2>
 * <p>"某个身份能读写哪些表"这个方向，facet 的 schema 上<b>没有</b>声明 {@code listable}
 * （见 {@code ToolPolicy} 的类注释），所以引擎答不了反向枚举。但规则里只有直接授权与角色包含、
 * 没有任何继承或递归，于是 {@code grants} 这份配置本身就是完整答案：把每条 grant 按
 * {@link AuthzRole} 的读写包含关系展开即可，结论与 {@code ConnectionAuthorizer} 逐次判定的结果一致。
 * <b>读写的包含关系不在这里重写一遍</b>，直接取 {@code AuthzRole}——那是它唯一的定义处。
 *
 * <h2>这个视图说不了的三件事（页面必须一起说出来）</h2>
 * <ol>
 *   <li><b>{@code enabled=false} 时这张表与实际行为无关</b>。总开关关着，
 *       {@code ConnectionAuthorizer.require} 第一行就返回，等于全放行——此时"谁能读写什么"
 *       的答案是"所有人都能"，而不是这张表里列出的那些；</li>
 *   <li><b>连接自身的 {@code readonly} 标记是另一道闸，而且在授权之前执行</b>
 *       （{@code RoutingDatabaseFacade.rejectIfReadonly}）。所以这里的"能写"只意味着授权允许，
 *       不意味着那条连接允许；</li>
 *   <li><b>只读元数据不判授权</b>（{@code listTables} / {@code describeTable} / {@code listIndexes}
 *       走不判定的那条路径）。表结构对任何能调 {@code /mcp} 的身份都可见，与这张表无关。</li>
 * </ol>
 *
 * <p>另外两条容易踩的语义，也在返回体里如实体现：{@code connection} <b>大小写敏感且必须是规范名</b>
 * （别名不生效）；{@code table} 为 {@code null} 的 grant 覆盖整条连接，给了表名的 grant
 * <b>只</b>覆盖那张表、不附带连接级权限。
 *
 * <p><b>刻意不做成 MCP 工具</b>，与 {@code UserAdminController} / {@code ToolAdminController} 同一条理由：
 * 工具级授权还没就位，一个能调 {@code /mcp} 的 agent 不该顺手拿到"谁能碰哪张表"的全景图——
 * 那是一份现成的横向移动地图。
 */
@RestController
@RequestMapping("/api/authz")
public class AuthzViewController {

    /**
     * 配置永远在（{@code DatabaseConfig} 用 {@code @EnableConfigurationProperties} 显式带上了它），
     * 所以这里不需要 {@code @Nullable} + 503 那一套——那是给"没配库就没有这个 bean"的
     * {@code UserAdminController} 用的。授权没开是 {@code enabled=false}，不是端点不可用。
     */
    private final ToolAuthzProperties properties;

    public AuthzViewController(ToolAuthzProperties properties) {
        this.properties = properties;
    }

    /**
     * 一条展开后的授权。
     *
     * @param subject     配置里的原文，{@code 类型:id}
     * @param subjectType {@code user} / {@code agent} / {@code service}
     * @param subjectId   主体 id，与 {@code /api/users} 里的 username 同一个值域
     * @param connection  连接的规范名（大小写敏感）
     * @param table       表名（已折成大写），{@code null} 表示这条 grant 作用于整条连接
     * @param scope       {@code connection} / {@code table}，由 table 有无推出；派生成字符串是为了
     *                    让前端能直接拿它做枚举过滤与排序，而不是对 null 做判断
     * @param role        {@code reader} / {@code writer} / {@code admin}
     * @param canRead     该角色是否蕴含读
     * @param canWrite    该角色是否蕴含写
     */
    public record Access(String subject, String subjectType, String subjectId, String connection,
                         String table, String scope, String role, boolean canRead, boolean canWrite) {}

    /** 角色能力的图例。前端不写第二份"reader 能不能写"，一律照这里显示。 */
    public record RoleView(String role, boolean canRead, boolean canWrite) {}

    /**
     * GET /api/authz →
     * {@code {enabled, grantCount, subjectCount, connectionCount, roles: [...], grants: [...]}}
     *
     * <p>排序固定为 subject → connection → table：两次请求的结果 diff 起来才有意义
     * （同 {@code UserAdminService.sorted} 的理由）。{@code table} 为 null 的连接级 grant 排在
     * 同一条连接的表级 grant 之前——读的时候"整条连接"是前提，表级是它的例外。
     */
    @GetMapping
    public Map<String, Object> view() {
        List<Access> grants = properties.getGrants().stream()
                .map(AuthzViewController::expand)
                .sorted(Comparator.comparing(Access::subject)
                        .thenComparing(Access::connection)
                        // nullsFirst：连接级 grant（table=null）排在表级之前
                        .thenComparing(Access::table, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("grantCount", grants.size());
        result.put("subjectCount", grants.stream().map(Access::subject).distinct().count());
        result.put("connectionCount", grants.stream().map(Access::connection).distinct().count());
        result.put("roles", roleViews());
        result.put("grants", grants);
        return result;
    }

    private static List<RoleView> roleViews() {
        return Arrays.stream(AuthzRole.values())
                .map(role -> new RoleView(role.configName(), role.grantsRead(), role.grantsWrite()))
                .toList();
    }

    private static Access expand(ToolAuthzProperties.Grant grant) {
        AuthzRole role = AuthzRole.of(grant.role());
        return new Access(
                grant.subject(),
                grant.subjectType(),
                grant.subjectId(),
                grant.connection(),
                grant.table(),
                grant.table() == null ? "connection" : "table",
                role.configName(),
                role.grantsRead(),
                role.grantsWrite());
    }
}
