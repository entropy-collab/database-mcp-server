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
package com.entropy.database.mcp.routing;

import com.entropy.database.mcp.authz.ConnectionAuthorizer;
import com.entropy.database.mcp.authz.ToolAuthzProperties;
import com.entropy.database.mcp.backup.DatabaseBackupService;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.security.McpPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 表级授权真的挂在了 facade 的解析路径上。
 *
 * <p>{@code ConnectionAuthorizerTest} 只证明判定本身对；这里证明的是<b>接线</b>：判定发生在连接解析
 * <em>之后</em>、用的是规范名、而且表名来自这次调用真正会碰的对象。接线出过三次问题
 * （SecurityContext 没传下来、bean 没注册、构造器选错），每一次都不会被判定层的测试发现。
 *
 * <p>只断言拒绝这一侧：放行之后会继续往下走到真正的 JDBC 调用，而这里的 context 是 mock，
 * "没被拒绝"没法在不铺一整套 JDBC 桩的前提下干净地观察。拒绝一侧已经覆盖了接线的全部分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoutingDatabaseFacadeAuthzTest {

    @Mock
    private DynamicDataSourceManager dynamicDataSourceManager;

    @Mock
    private DatabaseBackupService backupService;

    @BeforeEach
    void callerIsClaudeX() {
        var principal = new McpPrincipal("claude-x", "agent", "$2a$10$irrelevant", Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** DDL / DML 的对象名取自 SQL：没被授权的表写不了。 */
    @Test
    void sqlWriteIsJudgedPerTable() {
        ByokDataSourceContext prod = contextNamed("prod");
        when(dynamicDataSourceManager.acquire("prod")).thenReturn(prod);

        assertThatThrownBy(() -> facade(tableGrant("prod", "audit_log", "writer"))
                .executeUpdate("DELETE FROM orders WHERE id = 1", "prod"))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECURITY_VIOLATION);
    }

    /** 批量写入不带 SQL，表名是参数——这条路径必须用那个参数判，而不是退回连接级放行。 */
    @Test
    void batchInsertIsJudgedOnTheTableArgument() {
        ByokDataSourceContext prod = contextNamed("prod");
        when(dynamicDataSourceManager.acquire("prod")).thenReturn(prod);

        assertThatThrownBy(() -> facade(tableGrant("prod", "audit_log", "writer"))
                .batchInsert("orders", List.of("id"), List.of(List.of(1)), 100, "prod"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("ORDERS");
    }

    /** 读也被判：查一张没授权的表要被拒，而不是"读操作一律放行"。 */
    @Test
    void sqlReadIsJudgedPerTable() {
        ByokDataSourceContext prod = contextNamed("prod");
        when(dynamicDataSourceManager.acquire("prod")).thenReturn(prod);

        assertThatThrownBy(() -> facade(tableGrant("prod", "audit_log", "reader"))
                .executeQuery("SELECT * FROM salaries", 10, null, "prod"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("SALARIES");
    }

    /**
     * 判定用规范名，别名不是另一条授权路径。
     *
     * <p>别名与规范名共享同一个物理池；如果策略按调用方传进来的名字匹配，那么给别名配一条 grant
     * 就等于给规范名也配了一条，而运维只以为自己放开了一个别名。
     */
    @Test
    void theCanonicalKeyIsWhatGetsJudged() {
        ByokDataSourceContext shared = contextNamed("canonical");
        when(dynamicDataSourceManager.acquire("alias")).thenReturn(shared);

        // grant 写在别名上，调用也用别名——但判定落在 canonical 上，所以不命中
        assertThatThrownBy(() -> facade(tableGrant("alias", "orders", "writer"))
                .executeUpdate("DELETE FROM orders WHERE id = 1", "alias"))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECURITY_VIOLATION);
    }

    /**
     * 省掉 connection 参数同样被判。
     *
     * <p>与 readonly 那道闸同一个理由：{@code connection} 在多数 MCP 工具上是可选的，而只注册一条连接时
     * {@code resolveContext} 会把它补上。闸门放在解析之后，就不存在"没传参数所以不知道该拦谁"。
     */
    @Test
    void omittedConnectionStillGetsJudged() {
        ByokDataSourceContext only = contextNamed("only");
        when(dynamicDataSourceManager.listConnectionKeys()).thenReturn(List.of("only"));
        when(dynamicDataSourceManager.acquire("only")).thenReturn(only);

        assertThatThrownBy(() -> facade(tableGrant("only", "audit_log", "writer"))
                .executeUpdate("DELETE FROM orders WHERE id = 1", null))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECURITY_VIOLATION);
    }

    private RoutingDatabaseFacade facade(ToolAuthzProperties.Grant... grants) {
        var properties = new ToolAuthzProperties();
        properties.setEnabled(true);
        properties.setGrants(List.of(grants));
        return new RoutingDatabaseFacade(
                dynamicDataSourceManager, backupService, new ConnectionAuthorizer(properties));
    }

    private static ToolAuthzProperties.Grant tableGrant(String connection, String table, String role) {
        return new ToolAuthzProperties.Grant("agent:claude-x", connection, role, table);
    }

    private ByokDataSourceContext contextNamed(String key) {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        when(context.getKey()).thenReturn(key);
        when(context.getDialect()).thenReturn(dialect);
        return context;
    }
}
