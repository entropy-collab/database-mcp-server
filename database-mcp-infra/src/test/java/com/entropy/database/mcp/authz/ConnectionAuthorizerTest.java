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

import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.security.McpPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 连接级授权的判定。
 *
 * <p>最要紧的三条：
 * <ul>
 *   <li><b>关着的时候完全不参与</b>——这是这个开关能安全引入的前提；</li>
 *   <li><b>开着而拿不到身份时拒绝</b>——JWT 主体或裸跑时"谁在调用"不成立，放行等于这道闸不存在；</li>
 *   <li><b>writer 自动能读</b>——角色的包含关系写在 schema 规则里，而不是靠配置的人记得展开。
 *       靠人展开的话，漏展开不会报错，只会在某天变成一次莫名的拒绝。</li>
 * </ul>
 */
class ConnectionAuthorizerTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void disabledAuthorizerLetsEverythingThrough() {
        var authorizer = authorizer(false);
        // 连身份都没设：关着的时候连主体都不该去取
        assertThatCode(() -> authorizer.requireRead("prod")).doesNotThrowAnyException();
        assertThatCode(() -> authorizer.requireWrite("prod")).doesNotThrowAnyException();
    }

    @Test
    void readerCanReadButNotWrite() {
        var authorizer = authorizer(true, grant("user:zhangsan", "prod", "reader"));
        callerIs("zhangsan", "user");

        assertThatCode(() -> authorizer.requireRead("prod")).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.requireWrite("prod"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("无权")
                .hasMessageContaining("写入");
    }

    /** writer 能读——包含关系在 schema 里，配置只写一条。 */
    @Test
    void writerCanAlsoRead() {
        var authorizer = authorizer(true, grant("user:zhangsan", "prod", "writer"));
        callerIs("zhangsan", "user");

        assertThatCode(() -> authorizer.requireRead("prod")).doesNotThrowAnyException();
        assertThatCode(() -> authorizer.requireWrite("prod")).doesNotThrowAnyException();
    }

    @Test
    void adminCanDoBoth() {
        var authorizer = authorizer(true, grant("user:zhangsan", "prod", "admin"));
        callerIs("zhangsan", "user");

        assertThatCode(() -> authorizer.requireRead("prod")).doesNotThrowAnyException();
        assertThatCode(() -> authorizer.requireWrite("prod")).doesNotThrowAnyException();
    }

    /** 没有任何 grant 命中就拒绝。不存在"没配就放开"。 */
    @Test
    void noGrantMeansDenied() {
        var authorizer = authorizer(true);
        callerIs("zhangsan", "user");

        assertThatThrownBy(() -> authorizer.requireRead("prod"))
                .isInstanceOf(McpToolException.class);
    }

    /** grant 不跨连接：prod 上的 writer 在 dev 上什么都不是。 */
    @Test
    void grantsDoNotLeakAcrossConnections() {
        var authorizer = authorizer(true, grant("user:zhangsan", "prod", "writer"));
        callerIs("zhangsan", "user");

        assertThatCode(() -> authorizer.requireWrite("prod")).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.requireWrite("dev"))
                .isInstanceOf(McpToolException.class);
    }

    /**
     * 主体类型是判定的一部分：{@code user:zhangsan} 的授权不会落到 {@code agent:zhangsan} 上。
     *
     * <p>这一条守的是"同名不同类"。MCP 的调用方混着人和 agent，让类型不参与匹配等于把
     * "给张三的权限"送给任何叫 zhangsan 的 agent。
     */
    @Test
    void principalTypeIsPartOfTheDecision() {
        var authorizer = authorizer(true, grant("user:zhangsan", "prod", "writer"));
        callerIs("zhangsan", "agent");

        assertThatThrownBy(() -> authorizer.requireWrite("prod"))
                .isInstanceOf(McpToolException.class);
    }

    /** 开着但拿不到身份时拒绝，而不是放行。 */
    @Test
    void missingIdentityIsDeniedNotAllowed() {
        var authorizer = authorizer(true, grant("user:zhangsan", "prod", "writer"));
        // 不设 SecurityContext

        assertThatThrownBy(() -> authorizer.requireRead("prod"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("没有可识别的调用者身份");
    }

    /** 身份存在但不是 McpPrincipal（例如 JWT 主体）时同样拒绝。 */
    @Test
    void unknownPrincipalTypeIsDenied() {
        var authorizer = authorizer(true, grant("user:zhangsan", "prod", "writer"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("zhangsan", null, List.of()));

        assertThatThrownBy(() -> authorizer.requireRead("prod"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("没有可识别的调用者身份");
    }

    /** 策略配错必须在构造期失败，而不是等到某天有人打开开关。 */
    @Test
    void badGrantsFailFastEvenWhileDisabled() {
        assertThatThrownBy(() -> grant("zhangsan", "prod", "reader"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类型:id");

        assertThatThrownBy(() -> grant("user:zhangsan", "prod", "superuser"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");

        assertThatThrownBy(() -> grant("user:zhangsan", "  ", "reader"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection");
    }

    @Test
    void roleIsCaseInsensitive() {
        var authorizer = authorizer(true, grant("user:zhangsan", "prod", "WRITER"));
        callerIs("zhangsan", "user");

        assertThatCode(() -> authorizer.requireWrite("prod")).doesNotThrowAnyException();
    }

    /** 拒绝信息要指向可操作的下一步——运维看到它就知道该改哪里。 */
    @Test
    void denialMessagePointsAtTheConfiguration() {
        var authorizer = authorizer(true);
        callerIs("zhangsan", "user");

        assertThatThrownBy(() -> authorizer.requireRead("prod"))
                .hasMessageContaining("entropy.mcp.authz.grants");
    }

    @Test
    void multipleGrantsForOneSubjectAcrossConnections() {
        var authorizer = authorizer(true,
                grant("user:zhangsan", "prod", "reader"),
                grant("user:zhangsan", "dev", "writer"));
        callerIs("zhangsan", "user");

        assertThatCode(() -> authorizer.requireRead("prod")).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.requireWrite("prod")).isInstanceOf(McpToolException.class);
        assertThatCode(() -> authorizer.requireWrite("dev")).doesNotThrowAnyException();
    }

    // ─── 表级 ────────────────────────────────────────────────────────────

    /**
     * 表级 grant <b>不</b>附带连接级权限。
     *
     * <p>这是整个表级授权的语义核心：如果表级 grant 顺带给了连接级权限，那"只能写 audit_log"
     * 就等于"能写整条连接"，配置的人不会得到任何提示。
     */
    @Test
    void tableGrantConfersNothingAtConnectionLevel() {
        var authorizer = authorizer(true, tableGrant("agent:claude-x", "prod", "audit_log", "writer"));
        callerIs("claude-x", "agent");

        assertThatCode(() -> authorizer.requireSqlWrite("prod", "INSERT INTO audit_log (id) VALUES (1)"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.requireSqlWrite("prod", "INSERT INTO orders (id) VALUES (1)"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("ORDERS");
        // 拿不到表名的写入路径（inTransaction）只能判连接级，表级 grant 在那里什么都不是
        assertThatThrownBy(() -> authorizer.requireWrite("prod"))
                .isInstanceOf(McpToolException.class);
    }

    /** 连接级 grant 覆盖该连接上的每张表——包含关系在调用侧的短路里，见 ToolPolicy 的说明。 */
    @Test
    void connectionGrantCoversEveryTable() {
        var authorizer = authorizer(true, grant("user:zhangsan", "prod", "writer"));
        callerIs("zhangsan", "user");

        assertThatCode(() -> authorizer.requireSqlWrite("prod", "INSERT INTO anything (id) VALUES (1)"))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorizer.requireTableWrite("prod", "anything"))
                .doesNotThrowAnyException();
    }

    /**
     * 语句里的<b>每一张</b>表都要过，而不是只看被写的那张。
     *
     * <p>{@code INSERT INTO 授权表 SELECT ... FROM 未授权表} 是最容易漏的形状：目标表在白名单里，
     * 而数据实际是从另一张表搬出来的。
     */
    @Test
    void everyTableInTheStatementMustPass() {
        var authorizer = authorizer(true, tableGrant("agent:claude-x", "prod", "audit_log", "writer"));
        callerIs("claude-x", "agent");

        assertThatThrownBy(() -> authorizer.requireSqlWrite(
                "prod", "INSERT INTO audit_log (id) SELECT id FROM orders"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("ORDERS");
    }

    /** 堆叠语句不能让第二条藏起来：取不到"唯一一条语句"就回落到连接级，于是被拒。 */
    @Test
    void stackedStatementsFallBackToConnectionLevel() {
        var authorizer = authorizer(true, tableGrant("agent:claude-x", "prod", "audit_log", "writer"));
        callerIs("claude-x", "agent");

        assertThatThrownBy(() -> authorizer.requireSqlWrite(
                "prod", "INSERT INTO audit_log (id) VALUES (1); DROP TABLE orders"))
                .isInstanceOf(McpToolException.class);
    }

    /** 解析失败同样回落到连接级——更严，而不是放行。 */
    @Test
    void unparseableSqlFallsBackToConnectionLevel() {
        var tableOnly = authorizer(true, tableGrant("agent:claude-x", "prod", "audit_log", "writer"));
        callerIs("claude-x", "agent");
        assertThatThrownBy(() -> tableOnly.requireSqlWrite("prod", "this is not sql at all"))
                .isInstanceOf(McpToolException.class);

        var connectionWide = authorizer(true, grant("agent:claude-x", "prod", "writer"));
        assertThatCode(() -> connectionWide.requireSqlWrite("prod", "this is not sql at all"))
                .doesNotThrowAnyException();
    }

    @Test
    void tableNamesAreCaseInsensitive() {
        var authorizer = authorizer(true, tableGrant("user:zhangsan", "prod", "Audit_Log", "writer"));
        callerIs("zhangsan", "user");

        assertThatCode(() -> authorizer.requireSqlWrite("prod", "insert into AUDIT_LOG (id) values (1)"))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorizer.requireTableWrite("prod", "audit_log"))
                .doesNotThrowAnyException();
    }

    /**
     * 不带 schema 前缀的 grant 覆盖带前缀的引用；带前缀的 grant 不覆盖别的 schema。
     *
     * <p>规则与 {@code entropy.mcp.database.security.allowed-tables} 一致——同一个名字在两处应该
     * 框住同一批表，否则运维得记住两套匹配规则。
     */
    @Test
    void schemaQualificationMatchesTheWhitelistRule() {
        var unqualified = authorizer(true, tableGrant("user:zhangsan", "prod", "audit_log", "writer"));
        callerIs("zhangsan", "user");
        assertThatCode(() -> unqualified.requireSqlWrite("prod", "INSERT INTO app.audit_log (id) VALUES (1)"))
                .doesNotThrowAnyException();

        var qualified = authorizer(true, tableGrant("user:zhangsan", "prod", "app.audit_log", "writer"));
        assertThatCode(() -> qualified.requireSqlWrite("prod", "INSERT INTO app.audit_log (id) VALUES (1)"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> qualified.requireSqlWrite("prod", "INSERT INTO other.audit_log (id) VALUES (1)"))
                .isInstanceOf(McpToolException.class);
        // 不带前缀的引用也不命中带前缀的 grant：服务端不猜"当前 schema"是哪个
        assertThatThrownBy(() -> qualified.requireSqlWrite("prod", "INSERT INTO audit_log (id) VALUES (1)"))
                .isInstanceOf(McpToolException.class);
    }

    /** 表级 grant 不跨连接：prod 的 audit_log 授权在 dev 的同名表上不成立。 */
    @Test
    void tableGrantsDoNotLeakAcrossConnections() {
        var authorizer = authorizer(true, tableGrant("user:zhangsan", "prod", "audit_log", "writer"));
        callerIs("zhangsan", "user");

        assertThatCode(() -> authorizer.requireSqlWrite("prod", "INSERT INTO audit_log (id) VALUES (1)"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.requireSqlWrite("dev", "INSERT INTO audit_log (id) VALUES (1)"))
                .isInstanceOf(McpToolException.class);
    }

    /** 角色的包含关系在表这一级同样成立：writer 能读，reader 不能写。 */
    @Test
    void roleContainmentHoldsAtTableLevel() {
        var writer = authorizer(true, tableGrant("user:zhangsan", "prod", "audit_log", "writer"));
        callerIs("zhangsan", "user");
        assertThatCode(() -> writer.requireSqlRead("prod", "SELECT * FROM audit_log"))
                .doesNotThrowAnyException();

        var reader = authorizer(true, tableGrant("user:zhangsan", "prod", "audit_log", "reader"));
        assertThatCode(() -> reader.requireSqlRead("prod", "SELECT * FROM audit_log"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> reader.requireSqlWrite("prod", "INSERT INTO audit_log (id) VALUES (1)"))
                .isInstanceOf(McpToolException.class);
    }

    /** 读也按表判：没被授权的表读不了。 */
    @Test
    void readIsCheckedPerTableToo() {
        var authorizer = authorizer(true, tableGrant("user:zhangsan", "prod", "audit_log", "reader"));
        callerIs("zhangsan", "user");

        assertThatThrownBy(() -> authorizer.requireSqlRead("prod", "SELECT * FROM salaries"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("SALARIES");
        // JOIN 的另一侧同样要过
        assertThatThrownBy(() -> authorizer.requireSqlRead(
                "prod", "SELECT * FROM audit_log a JOIN salaries s ON a.id = s.id"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("SALARIES");
    }

    /** 关着的时候一次解析都不做——连非法 SQL 也不该在这里炸。 */
    @Test
    void disabledAuthorizerDoesNotEvenParse() {
        var authorizer = authorizer(false, tableGrant("user:zhangsan", "prod", "audit_log", "writer"));

        assertThatCode(() -> authorizer.requireSqlWrite("prod", "this is not sql at all"))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorizer.requireSqlRead("prod", null))
                .doesNotThrowAnyException();
    }

    private static ToolAuthzProperties.Grant grant(String subject, String connection, String role) {
        return new ToolAuthzProperties.Grant(subject, connection, role, null);
    }

    /** 表级 grant：只覆盖那一张表，不附带任何连接级权限。 */
    private static ToolAuthzProperties.Grant tableGrant(
            String subject, String connection, String table, String role) {
        return new ToolAuthzProperties.Grant(subject, connection, role, table);
    }

    private static ConnectionAuthorizer authorizer(boolean enabled,
                                                   ToolAuthzProperties.Grant... grants) {
        var properties = new ToolAuthzProperties();
        properties.setEnabled(enabled);
        properties.setGrants(List.of(grants));
        return new ConnectionAuthorizer(properties);
    }

    private static void callerIs(String username, String type) {
        // 走公共构造器而不是包级的 of(...)：这个测试在 authz 包里，看不到 security 包的包级成员。
        // 权限给空集，正是凭据文件/用户表里那些身份的形态
        var principal = new McpPrincipal(username, type, "$2a$10$irrelevant", Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        assertThat(principal.subjectRef()).isEqualTo(type + ':' + username);
    }
}
