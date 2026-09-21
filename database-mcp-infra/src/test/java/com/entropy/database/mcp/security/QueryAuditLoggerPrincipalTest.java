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

import com.entropy.database.mcp.audit.AuditLogEntity;
import com.entropy.database.mcp.audit.AuditLogRepository;
import com.entropy.database.mcp.properties.DatabaseProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 审计表 {@code principal} 列的来源。
 *
 * <p>这一列曾经恒为空：认证事件（登录成功 / 失败 / 退出）发生的那一刻 SecurityContext 要么还没写、
 * 要么刚被清掉，所以审计层自己去 {@code SecurityContextHolder} 取一定拿不到值。修法是给显式传值
 * 留一个入口，这些用例把两条路径都钉住。
 */
class QueryAuditLoggerPrincipalTest {

    private static DatabaseProperties props() {
        return new DatabaseProperties(true, "h2", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 显式传入的用户名必须落到表里，哪怕当下根本没有 SecurityContext。 */
    @Test
    void logWithPrincipalRecordsTheGivenNameWithoutASecurityContext() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        var logger = new QueryAuditLoggerImpl(repository, props(), null);

        logger.logWithPrincipal("auth:login-failed", "LOGIN alice", 0, 0L, false,
                "BadCredentialsException", null, "alice");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().principal()).isEqualTo("alice");
    }

    /** 普通工具调用不传 principal，由审计层从 SecurityContext 取，格式与 subjectRef() 一致。 */
    @Test
    void theOrdinaryLogTakesThePrincipalFromTheSecurityContext() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        var logger = new QueryAuditLoggerImpl(repository, props(), null);
        var caller = new McpPrincipal("claude-x", "agent", "hash", Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(caller, null, caller.getAuthorities()));

        logger.log("executeQuery", "SELECT 1", 1, 3L, true, null, "primary");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().principal()).isEqualTo("agent:claude-x");
    }

    /** 没有身份时 principal 是 null 而不是空串：审计表那一列可空，折成空串就丢掉了"未知"这个信息。 */
    @Test
    void anAnonymousCallLeavesThePrincipalNull() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        var logger = new QueryAuditLoggerImpl(repository, props(), null);

        logger.log("getDatabaseInfo", "SELECT 1", 1, 1L, true, null, null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().principal()).isNull();
    }
}
