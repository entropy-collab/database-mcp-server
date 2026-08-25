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
package com.entropy.database.mcp.aop;

import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the missing-connection hint, which used to fire on any message containing the substring
 * "connection" and so relabelled pool outages as caller mistakes.
 */
class McpToolExceptionAspectTest {

    private FailingTools tools;

    @BeforeEach
    void setUp() {
        DynamicDataSourceManager dataSourceManager = mock(DynamicDataSourceManager.class);
        when(dataSourceManager.listConnectionKeys()).thenReturn(List.of("primary", "warehouse"));

        AspectJProxyFactory factory = new AspectJProxyFactory(new FailingTools());
        factory.addAspect(new McpToolExceptionAspect(dataSourceManager));
        tools = factory.getProxy();
    }

    @Test
    void hikariClosedIsNotRelabelledAsParameterError() {
        assertThatThrownBy(() -> tools.poolOutage(null))
                .isInstanceOf(CannotGetJdbcConnectionException.class)
                .hasMessageContaining("HikariDataSource has been closed");
    }

    @Test
    void connectionRefusedIsNotRelabelledAsParameterError() {
        assertThatThrownBy(() -> tools.connectionRefused(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    void queryFailureMentioningConnectionKeepsItsOwnErrorCode() {
        assertThatThrownBy(() -> tools.queryFailure(null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("Failed to acquire connection")
                .satisfies(t -> org.assertj.core.api.Assertions
                        .assertThat(((McpToolException) t).getErrorCode())
                        .isEqualTo(ErrorCode.QUERY_EXECUTION_FAILED));
    }

    @Test
    void unresolvableConnectionArgumentStillGetsTheHint() {
        assertThatThrownBy(() -> tools.needsConnection(null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("参数 'connection' 未提供")
                .hasMessageContaining("primary");
    }

    @Test
    void hintIsNotAppliedWhenTheConnectionArgumentWasSupplied() {
        assertThatThrownBy(() -> tools.needsConnection("primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connection is required but not provided");
    }

    @Test
    void toolWithoutConnectionParameterIsNeverRewritten() {
        assertThatThrownBy(() -> tools.noConnectionParameter())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection");
    }

    /** Tool stubs whose only job is to fail in a specific, recognisable way. */
    static class FailingTools {

        @McpTool(description = "Fails because the pool was shut down")
        public Map<String, Object> poolOutage(String connection) {
            throw new CannotGetJdbcConnectionException(
                    "HikariDataSource has been closed.", new SQLException("pool closed"));
        }

        @McpTool(description = "Fails because the server refused the socket")
        public Map<String, Object> connectionRefused(String connection) {
            throw new IllegalStateException("connection refused: 10.0.0.1:1521");
        }

        @McpTool(description = "Fails mid-query with an already classified error")
        public Map<String, Object> queryFailure(String connection) {
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Failed to acquire connection for statement");
        }

        @McpTool(description = "Fails because no connection name was supplied")
        public Map<String, Object> needsConnection(String connection) {
            throw new IllegalArgumentException("Connection is required but not provided.");
        }

        @McpTool(description = "Takes no connection parameter at all")
        public Map<String, Object> noConnectionParameter() {
            throw new IllegalArgumentException("gateway connection registry is empty");
        }
    }
}
