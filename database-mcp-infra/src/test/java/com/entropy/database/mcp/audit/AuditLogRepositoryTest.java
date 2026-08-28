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
package com.entropy.database.mcp.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AuditLogRepository#insert}.
 *
 * <p>insert 过去在每条审计前都跑一次 {@code CREATE TABLE IF NOT EXISTS}，等于给每次查询搭一次
 * DDL 往返；而建表失败与插入失败共用同一个异常，运维无法从日志里判断是缺 DDL 权限还是连接抖动。
 */
class AuditLogRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private AuditLogRepository repository;

    @BeforeEach
    void setUp() {
        this.jdbcTemplate = mock(JdbcTemplate.class);
        this.repository = new AuditLogRepository(jdbcTemplate);
    }

    private static AuditLogEntity entry() {
        return new AuditLogEntity(null, "executeQuery", "SELECT 1", 1, 5L, true, null, Instant.now(), "default");
    }

    @Test
    void createsTheTableOnlyOnceAcrossInserts() {
        repository.insert(entry());
        repository.insert(entry());
        repository.insert(entry());

        verify(jdbcTemplate, times(1)).execute(startsWith("CREATE TABLE IF NOT EXISTS audit_log"));
        verify(jdbcTemplate, times(3)).update(anyString(), any(Object[].class));
    }

    @Test
    void reportsTableCreationFailureDistinctlyAndSkipsTheInsert() {
        RuntimeException noPrivilege = new BadSqlGrammarException(
                "CREATE TABLE", "CREATE TABLE IF NOT EXISTS audit_log", new SQLException("insufficient privileges"));
        doThrowOnCreateTable(noPrivilege);

        assertThatThrownBy(() -> repository.insert(entry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create or verify the audit_log table")
                .hasCause(noPrivilege);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void reportsInsertFailureDistinctlyAndRevalidatesTheTableNextTime() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("connection reset"))
                .thenReturn(1);

        assertThatThrownBy(() -> repository.insert(entry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insert into audit_log");

        // 插入失败也可能是表被删了，所以下一次调用重新确认表结构，而不是永远跳过
        assertThatCode(() -> repository.insert(entry())).doesNotThrowAnyException();
        verify(jdbcTemplate, times(2)).execute(startsWith("CREATE TABLE IF NOT EXISTS audit_log"));
    }

    @Test
    void failureMessagesTellTheTwoFailureModesApart() {
        RuntimeException ddlFailure = new BadSqlGrammarException(
                "CREATE TABLE", "CREATE TABLE IF NOT EXISTS audit_log", new SQLException("no privilege"));
        doThrowOnCreateTable(ddlFailure);
        String createMessage = catchMessage(() -> repository.insert(entry()));

        JdbcTemplate healthyCreate = mock(JdbcTemplate.class);
        when(healthyCreate.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));
        String insertMessage = catchMessage(() -> new AuditLogRepository(healthyCreate).insert(entry()));

        assertThat(createMessage).isNotEqualTo(insertMessage);
        assertThat(createMessage).contains("DDL privileges");
        assertThat(insertMessage).contains("Failed to insert into audit_log");
    }

    private void doThrowOnCreateTable(RuntimeException failure) {
        org.mockito.Mockito.doThrow(failure).when(jdbcTemplate).execute(anyString());
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected the insert to fail");
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
