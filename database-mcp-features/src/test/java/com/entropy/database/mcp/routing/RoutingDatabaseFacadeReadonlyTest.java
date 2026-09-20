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

import com.entropy.database.mcp.backup.DatabaseBackupService;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * readonly 闸门必须钉在「解析出来的连接」上，而不是「调用方传了什么参数」上。
 *
 * <p>{@code McpToolExceptionAspect} 那道闸在 {@code connection} 为空时直接放行，而 {@code connection} 在多数
 * MCP 工具上是 {@code required = false}；同时 {@code resolveContext} 在只注册了一条连接时会自动补上它。两件事
 * 合起来的结果是：省掉 connection 参数就能在 readonly 连接上写库，而单连接正是最常见的部署形态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoutingDatabaseFacadeReadonlyTest {

    @Mock
    private DynamicDataSourceManager dynamicDataSourceManager;

    @Mock
    private DatabaseBackupService backupService;

    private RoutingDatabaseFacade facade() {
        return new RoutingDatabaseFacade(dynamicDataSourceManager, backupService);
    }

    private ByokDataSourceContext contextNamed(String key) {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        when(context.getKey()).thenReturn(key);
        when(context.getDialect()).thenReturn(dialect);
        return context;
    }

    /** 只注册了一条 readonly 连接时，省掉 connection 参数不能换来一次写入。 */
    @Test
    void omittedConnectionStillHitsTheReadonlyGate() {
        ByokDataSourceContext only = contextNamed("only");
        when(dynamicDataSourceManager.listConnectionKeys()).thenReturn(List.of("only"));
        when(dynamicDataSourceManager.acquire("only")).thenReturn(only);
        when(dynamicDataSourceManager.isReadonly("only")).thenReturn(true);

        assertThatThrownBy(() -> facade().executeUpdate("DELETE FROM employees WHERE id = 1", null))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONNECTION_READONLY);
    }

    @Test
    void explicitReadonlyConnectionIsRejected() {
        ByokDataSourceContext ro = contextNamed("ro");
        when(dynamicDataSourceManager.acquire("ro")).thenReturn(ro);
        when(dynamicDataSourceManager.isReadonly("ro")).thenReturn(true);

        assertThatThrownBy(() -> facade().executeDdl("DROP TABLE employees", "ro"))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONNECTION_READONLY);
    }

    /**
     * 别名与规范名各有一条登记，{@code readonly} 取自各自注册时的入参，所以两侧可以不一致——而它们共享同一个
     * 物理池。任意一侧是只读就必须拦，否则其中一个名字就是绕开只读保护的写入后门。
     */
    @Test
    void eitherSideOfAnAliasBeingReadonlyIsEnough() {
        ByokDataSourceContext shared = contextNamed("canonical");
        when(dynamicDataSourceManager.acquire("alias")).thenReturn(shared);

        when(dynamicDataSourceManager.isReadonly("canonical")).thenReturn(true);
        when(dynamicDataSourceManager.isReadonly("alias")).thenReturn(false);
        assertThatThrownBy(() -> facade().executeUpdate("DELETE FROM employees", "alias"))
                .isInstanceOf(McpToolException.class);

        when(dynamicDataSourceManager.isReadonly("canonical")).thenReturn(false);
        when(dynamicDataSourceManager.isReadonly("alias")).thenReturn(true);
        assertThatThrownBy(() -> facade().executeUpdate("DELETE FROM employees", "alias"))
                .isInstanceOf(McpToolException.class);
    }

    /** 闸门只管写入路径：读操作在 readonly 连接上本来就该通。 */
    @Test
    void readsAreNotGated() {
        ByokDataSourceContext ro = contextNamed("ro");
        when(dynamicDataSourceManager.acquire("ro")).thenReturn(ro);
        when(dynamicDataSourceManager.isReadonly("ro")).thenReturn(true);

        assertThat(facade().getDialect("ro")).isNotNull();
    }
}
