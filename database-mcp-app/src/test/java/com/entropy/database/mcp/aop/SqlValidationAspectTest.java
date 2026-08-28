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

import com.entropy.database.mcp.config.TestSecurityConfig;
import com.entropy.database.mcp.exception.McpSqlValidationException;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the SQL validation pointcut is actually woven, not merely declared.
 *
 * <p>Runs against the real application context on purpose: the previous expression parsed but
 * matched nothing, and only a container-resolved proxy can tell the difference. No connection is
 * registered — validation runs before {@code proceed()}, so a rejected statement never reaches a
 * datasource.
 */
@SpringBootTest(properties = {
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.security.enabled=false",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.database.ddl.allowed=false"
})
@Import(TestSecurityConfig.class)
class SqlValidationAspectTest {

    private static final String UNKNOWN_CONNECTION = "no-such-connection-for-aspect-test";

    @Autowired
    private RoutingDatabaseFacade routingFacade;

    @Test
    void routingFacadeIsProxiedSoAdviceCanApply() {
        assertThat(AopUtils.isAopProxy(routingFacade)).isTrue();
    }

    @Test
    void queryRowsRejectsDelete() {
        assertThatThrownBy(() -> routingFacade.queryRows(
                "DELETE FROM employees WHERE id = 1", UNKNOWN_CONNECTION))
                .isInstanceOf(McpSqlValidationException.class)
                .hasMessageContaining("Operation not allowed");
    }

    @Test
    void queryRowsRejectsDrop() {
        assertThatThrownBy(() -> routingFacade.queryRows(
                "DROP TABLE employees", UNKNOWN_CONNECTION))
                .isInstanceOf(McpSqlValidationException.class)
                .hasMessageContaining("Operation not allowed");
    }

    @Test
    void queryRowsRejectsUnparseableSql() {
        assertThatThrownBy(() -> routingFacade.queryRows("not sql at all", UNKNOWN_CONNECTION))
                .isInstanceOf(McpSqlValidationException.class);
    }

    @Test
    void executeQueryRejectsDelete() {
        assertThatThrownBy(() -> routingFacade.executeQuery(
                "DELETE FROM employees", 10, null, UNKNOWN_CONNECTION))
                .isInstanceOf(McpSqlValidationException.class)
                .hasMessageContaining("Operation not allowed");
    }

    /**
     * executeUpdate must take the DDL/write path: a DELETE is legitimate input there, so it has to
     * get past validation and fail later on connection resolution instead.
     */
    @Test
    void executeUpdateTakesWriteValidationPath() {
        assertThatThrownBy(() -> routingFacade.executeUpdate(
                "DELETE FROM employees WHERE id = 1", UNKNOWN_CONNECTION))
                .isNotInstanceOf(McpSqlValidationException.class);
    }

    @Test
    void executeUpdateStillRejectsUnparseableSql() {
        assertThatThrownBy(() -> routingFacade.executeUpdate("gibberish ;; not sql", UNKNOWN_CONNECTION))
                .isInstanceOf(McpSqlValidationException.class);
    }
}
