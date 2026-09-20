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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.security.McpPrincipal;
import com.entropy.database.mcp.security.SqlTables;
import facet.core.eval.Ctx;
import facet.core.eval.Explains;
import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Locale;
import java.util.Set;

/**
 * 按调用者的授权：这个人能不能在这条连接、这张表上读 / 写。
 *
 * <h2>它替掉了什么</h2>
 * <p>{@code readonly} 是<b>每连接一个布尔</b>——对所有人一视同仁。这里让它变成"张三在 prod 上只读，
 * 李四可写，某个 agent 只能写 audit_log"。两者并存：{@code readonly} 仍然是连接自身的属性
 * （一条声明为只读的连接，谁都写不了），而这里加的是"就算连接可写，也得看是谁、动的是哪张表"。
 *
 * <h2>两个粒度怎么合起来判</h2>
 * <p>先判连接级，判过就放行；判不过再<b>逐表</b>判，每一张都得过。顺序是短路优化也是语义：
 * 连接级授权本来就覆盖该连接上的全部对象。这条包含关系为什么不在 schema 里，见 {@link ToolPolicy}。
 *
 * <p>取不到对象名的语句（解析失败）只判连接级。这个回落方向是安全的：它比逐表判更严，
 * 只有表级授权的主体会因此被拒，不存在"解析失败所以放行"。
 *
 * <p><b>不带 schema 前缀的表级 grant 是宽的。</b>{@code table: users} 会覆盖该连接上
 * {@code APP.USERS}、{@code OTHER.USERS}……每一张叫 USERS 的表，因为服务端不解析"当前 schema"是哪个，
 * 猜一个出来会让判定依赖连接的会话状态。要精确就把前缀写上：{@code table: app.users}。
 * 这与 {@code entropy.mcp.database.security.allowed-tables} 的匹配规则一致——同一个名字在两处
 * 应该框住同一批表。
 *
 * <h2>Fail closed</h2>
 * <p>开关打开之后：
 * <ul>
 *   <li>拿不到 {@link McpPrincipal} 就拒绝。JWT 主体、或者 {@code entropy.mcp.security.enabled=false}
 *       裸跑时都会走到这里——两种情况下"谁在调用"都不成立，而不成立时放行等于这道闸不存在。</li>
 *   <li>没有任何 grant 命中就拒绝。不存在"没配就放开"。</li>
 * </ul>
 *
 * <p><b>关着的时候完全不参与。</b>默认 {@code enabled=false}，此时行为与引入这个类之前逐字节相同。
 * 这是个破坏性开关：打开它，所有没写进 grants 的访问组合都会开始失败。
 *
 * <p><b>覆盖范围。</b>判定挂在带 SQL 或带表名的操作上（查询、DDL、批量写入）。只读元数据
 * （{@code listTables} / {@code describeTable} / {@code listIndexes} …）不判——它们暴露的是结构而不是
 * 数据，而 MCP 的调用方需要先看到结构才能写出 SQL。要连结构也管住，只能在连接级挡住整条连接。
 *
 * <h2>为什么拒绝信息里带 explain</h2>
 * <p>{@code Explains.render} 输出的是判定树。"为什么我被拒了"如果只能得到一句"无权限"，
 * 运维只能去猜是漏了哪条 grant；判定树直接说出它查了哪些关系、在哪一步落空。
 * 这段只进服务端日志，不回给调用方——判定树会暴露策略结构。
 *
 * <p><b>为什么不是 {@code @Component}。</b>它由 {@code DatabaseConfig} 显式声明成 {@code @Bean}，
 * 和 {@code RoutingDatabaseFacade} 放在一起。靠组件扫描的话，任何只导入 {@code DatabaseConfig}
 * 的切片测试都会因为扫不到这个包而起不来——而"授权组件恰好不在这个测试的扫描范围里"是个
 * 与被测行为无关的失败原因。
 */
public class ConnectionAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(ConnectionAuthorizer.class);

    private final ToolAuthzProperties properties;
    private final ToolPolicy policy;

    public ConnectionAuthorizer(ToolAuthzProperties properties) {
        this.properties = properties;
        // 关着也构建：策略配错（角色名拼错、subject 少了类型前缀）应当在启动时就失败，
        // 而不是等到某天有人打开开关才发现配置一直是坏的
        this.policy = ToolPolicy.of(properties.getGrants());
        if (properties.isEnabled()) {
            log.info("按调用者的授权已启用，{} 条 grant；未被 grant 覆盖的访问将被拒绝",
                    properties.getGrants().size());
        } else if (!properties.getGrants().isEmpty()) {
            log.warn("配置了 {} 条 entropy.mcp.authz.grants，但 entropy.mcp.authz.enabled=false —— "
                    + "这些授权当前不生效，任何已认证的调用者都能访问全部连接",
                    properties.getGrants().size());
        }
    }

    /** 要求当前调用者对这条连接有读权限。给拿不到表名的读路径用。 */
    public void requireRead(String connection) {
        require(connection, Set.of(), ToolPolicy.READ, "读取");
    }

    /** 要求当前调用者对这条连接有写权限。给拿不到表名的写路径用（如 {@code inTransaction}）。 */
    public void requireWrite(String connection) {
        require(connection, Set.of(), ToolPolicy.WRITE, "写入");
    }

    /** 要求当前调用者能读这条 SQL 碰到的每一张表。 */
    public void requireSqlRead(String connection, String sql) {
        require(connection, tablesOf(sql), ToolPolicy.READ, "读取");
    }

    /** 要求当前调用者能写这条 SQL 碰到的每一张表。 */
    public void requireSqlWrite(String connection, String sql) {
        require(connection, tablesOf(sql), ToolPolicy.WRITE, "写入");
    }

    /** 要求当前调用者能写这张表。给直接给出表名的批量写入路径用。 */
    public void requireTableWrite(String connection, String table) {
        require(connection, tableName(table), ToolPolicy.WRITE, "写入");
    }

    /**
     * @param tables 空集合表示"取不到对象名"，此时只判连接级
     */
    private void require(String connection, Set<String> tables, Rel relation, String what) {
        if (!properties.isEnabled()) {
            return;
        }
        SubjectRef subject = currentSubject(connection, what);
        // 连接级先判：拿到连接级授权的人，这条连接上的每张表都成立，不必再逐表判
        var atConnection = check(subject, ToolPolicy.connection(connection), relation);
        if (atConnection.allowed()) {
            return;
        }
        if (tables.isEmpty()) {
            throw denied(subject, connection, null, what,
                    Explains.render(atConnection.explain()));
        }
        for (String table : tables) {
            var atTable = checkTable(subject, connection, table, relation);
            if (!atTable.allowed()) {
                // 两棵树都记：运维要看的是"连接级为什么没过、表级又为什么没过"
                throw denied(subject, connection, table, what,
                        "连接级：\n" + Explains.render(atConnection.explain())
                                + "\n表级：\n" + Explains.render(atTable.explain()));
            }
        }
    }

    /**
     * 判一张表。带 schema 前缀的名字先按全名判，不过再按去掉前缀的名字判一次——为的是让
     * {@code table: users} 这样的 grant 也能命中 {@code app.users}，规则与 allowed-tables 一致。
     * 宽窄取舍见类注释。
     */
    private facet.core.eval.Decision checkTable(SubjectRef subject, String connection,
                                                String table, Rel relation) {
        var decision = check(subject, ToolPolicy.table(connection, table), relation);
        if (decision.allowed()) {
            return decision;
        }
        int dot = table.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= table.length()) {
            return decision;
        }
        return check(subject, ToolPolicy.table(connection, table.substring(dot + 1)), relation);
    }

    private facet.core.eval.Decision check(SubjectRef subject, ObjectRef object, Rel relation) {
        return Ctx.run(Ctx.Request.of(subject), () -> policy.checker().check(object, relation));
    }

    private McpToolException denied(SubjectRef subject, String connection, String table,
                                    String what, String explain) {
        String target = table == null
                ? "连接 '" + connection + "'"
                : "连接 '" + connection + "' 上的表 '" + table + "'";
        // 判定树只进日志：它会暴露策略结构，不该回给被拒的调用方
        log.info("拒绝 {} 对{}的{}请求，判定过程：\n{}", subject, target, what, explain);
        return new McpToolException(ErrorCode.SECURITY_VIOLATION,
                "调用者无权对" + target + "执行" + what + "操作。"
                        + "请在 entropy.mcp.authz.grants 里为该主体授予相应角色。",
                connection);
    }

    /** 关着的时候一次解析都不做——这是"关着就与引入前逐字节相同"的一部分。 */
    private Set<String> tablesOf(String sql) {
        return properties.isEnabled() ? SqlTables.ofOrEmpty(sql) : Set.of();
    }

    /** 与 {@link SqlTables} 的归一化保持一致，否则同一张表在两条路径上会是两个对象。 */
    private static Set<String> tableName(String table) {
        return table == null || table.isBlank()
                ? Set.of()
                : Set.of(table.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * 当前调用者。
     *
     * <p>取不到就抛，不回落到任何默认主体——默认主体会让"没有身份"看起来像一次正常的判定。
     * 这条与 Facet 里 {@code Ctx.current()} 在上下文未绑定时直接抛异常是同一个理由。
     */
    private SubjectRef currentSubject(String connection, String what) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof McpPrincipal caller) {
            return new SubjectRef.Principal(
                    new facet.core.ir.ObjectType(caller.type()), caller.username());
        }
        log.info("拒绝对连接 '{}' 的{}请求：拿不到调用者身份（principal={}）",
                connection, what, principal == null ? "null" : principal.getClass().getName());
        throw new McpToolException(ErrorCode.SECURITY_VIOLATION,
                "按调用者的授权已启用，但这次调用没有可识别的调用者身份，因此无法判定。"
                        + "请确认请求带了凭据，且 entropy.mcp.security.enabled 未被关掉。",
                connection);
    }
}
