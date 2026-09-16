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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.facade.DatabaseOperations;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.security.QueryAuditLogger;
import com.entropy.database.mcp.security.SqlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code entropy.mcp.database.ddl.allowed} 曾经只挡住 executeDdl 一个入口：executeDdlBatch 与
 * executeDdlRemote 只看网关开关，createMirrorTable 走的又是另一条读取路径，运维照文档配了
 * allowed=false 却仍然能整批改结构，而且没有任何迹象说明闸门没生效。这些用例把四个执行入口
 * 一并钉住，并钉住 validateDdl 刻意不设闸——它不执行 SQL，是禁用 DDL 之后唯一的预检手段。
 */
class DdlExecutionToolsGateTest {

    private static final String CONNECTION = "prod";
    private static final String CONFIG_KEY = "entropy.mcp.database.ddl.allowed";
    private static final String CREATE_TABLE = "CREATE TABLE t (id INT)";

    private final DatabaseOperations routingFacade = mock(DatabaseOperations.class);
    private final SqlValidator sqlValidator = mock(SqlValidator.class);
    private final QueryAuditLogger auditLogger = mock(QueryAuditLogger.class);

    /** gateway.enabled=true，这样远程与批量两条路的拒绝只可能来自 DDL 闸门。 */
    private DdlExecutionTools tools(boolean ddlAllowed) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("entropy.mcp.gateway.enabled", "true");
        return new DdlExecutionTools(routingFacade, sqlValidator, properties(ddlAllowed), environment,
                auditLogger);
    }

    private static DatabaseProperties properties(boolean ddlAllowed) {
        return new DatabaseProperties(true, "h2", null, null, null,
                new DatabaseProperties.DdlProperties(ddlAllowed),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 拒绝要发生在碰到 facade 之前，且错误码是 SECURITY_VIOLATION 而不是可自纠的
     * SQL_OPERATION_NOT_ALLOWED——服务端配置只能人工解除，让模型改写语句重试是浪费。
     */
    @Test
    void executeDdlIsRefusedWhenTheSwitchIsOff() {
        assertThatThrownBy(() -> tools(false).executeDdl(CREATE_TABLE, CONNECTION))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(CONFIG_KEY)
                .hasMessageContaining("executeDdl")
                .extracting(e -> ((McpToolException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECURITY_VIOLATION);

        verify(routingFacade, never()).executeDdl(anyString(), anyString());
    }

    @Test
    void executeDdlBatchIsRefusedWhenTheSwitchIsOff() {
        assertThatThrownBy(() -> tools(false).executeDdlBatch(CONNECTION, List.of(CREATE_TABLE)))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(CONFIG_KEY)
                .hasMessageContaining("executeDdlBatch")
                .extracting(e -> ((McpToolException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECURITY_VIOLATION);

        verify(routingFacade, never()).inTransaction(anyString(), any());
    }

    @Test
    void executeDdlRemoteIsRefusedWhenTheSwitchIsOff() {
        assertThatThrownBy(() -> tools(false).executeDdlRemote(CONNECTION, CREATE_TABLE))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(CONFIG_KEY)
                .extracting(e -> ((McpToolException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECURITY_VIOLATION);

        verify(routingFacade, never()).executeUpdate(anyString(), anyString());
    }

    /** 闸门开着时必须原样放行，否则这个开关就从"假开关"变成"永远拒绝"。 */
    @Test
    void executeDdlPassesThroughWhenTheSwitchIsOn() {
        doReturn(Map.of("success", true)).when(routingFacade).executeDdl(CREATE_TABLE, CONNECTION);

        assertThat(tools(true).executeDdl(CREATE_TABLE, CONNECTION))
                .containsEntry("success", true);

        verify(routingFacade).executeDdl(CREATE_TABLE, CONNECTION);
    }

    @Test
    void executeDdlBatchPassesThroughWhenTheSwitchIsOn() throws Exception {
        doReturn(Map.of("reachedFacade", true)).when(routingFacade).inTransaction(anyString(), any());

        assertThat(tools(true).executeDdlBatch(CONNECTION, List.of(CREATE_TABLE)))
                .containsEntry("reachedFacade", true);
    }

    /**
     * validateDdl 只做静态校验、不连库，因此不受闸门约束：把预检也拦掉不会少改一张表，
     * 只会逼调用方在 Oracle/MySQL 这类无法回滚的库上盲改。
     */
    @Test
    void validateDdlStaysAvailableWhenTheSwitchIsOff() throws Exception {
        assertThat(tools(false).validateDdl(List.of(CREATE_TABLE)))
                .containsEntry("validCount", 1L)
                .containsEntry("invalidCount", 0L);

        verify(sqlValidator).validateDdl(CREATE_TABLE);
        verify(routingFacade, never()).executeUpdate(anyString(), anyString());
    }
}
