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
package com.entropy.database.mcp.gateway;

import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.exception.McpFederatedException;
import com.entropy.database.mcp.properties.QueryConfig;
import com.entropy.database.mcp.properties.ThreadPoolProperties;
import com.entropy.database.mcp.security.SqlValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards parameter binding in {@link FederatedQueryGateway} against a real H2 database.
 *
 * <p>The bug being pinned: the gateway used to flatten the caller's parameter {@code Map} with
 * {@code params.values().toArray()} and bind it positionally. Map iteration order has no relation
 * to the order of the placeholders in the SQL — for a {@code HashMap} it is a hash artefact — so
 * the query silently read the wrong rows. Only a real database can show that, hence H2 rather than
 * a mocked template: a mock would happily accept any array.
 *
 * <p>这些用例测的都是<b>网关行为</b>（路由到哪个注册项、限行、fan-out 的部分结果与过载失败），所以
 * 迁移后一个都没搬走：它们只经由网关的公开方法，不碰 {@code RemoteJdbcClient}。远端客户端<b>自身</b>
 * 的契约（命名绑定、语句超时下达到语句、不暴露任何 spring-jdbc 类型）在
 * {@code com.entropy.database.mcp.byok.RemoteJdbcClientTest} 里，与实现同模块。
 */
class FederatedQueryGatewayTest {

    private static final String THREE_PARAM_QUERY =
            "SELECT ID FROM PEOPLE WHERE CITY = :city AND ROLE = :role AND DEPT = :dept";

    private static JdbcTemplate jdbcTemplate;
    private static org.h2.jdbcx.JdbcDataSource dataSource;

    private FederatedQueryGateway gateway;

    @BeforeAll
    static void createSchema() {
        dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:federated;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS PEOPLE");
        jdbcTemplate.execute("CREATE TABLE PEOPLE (ID INT PRIMARY KEY, CITY VARCHAR(20), "
                + "ROLE VARCHAR(20), DEPT VARCHAR(20))");
        // Row 1 is the only match for (city=beijing, role=admin, dept=sales). Rows 2 and 3 are the
        // rows a mis-ordered binding would hit: they hold the same three values, permuted.
        jdbcTemplate.update("INSERT INTO PEOPLE VALUES (1, 'beijing', 'admin', 'sales')");
        jdbcTemplate.update("INSERT INTO PEOPLE VALUES (2, 'admin', 'sales', 'beijing')");
        jdbcTemplate.update("INSERT INTO PEOPLE VALUES (3, 'sales', 'beijing', 'admin')");
    }

    @BeforeEach
    void createGateway() {
        gateway = new FederatedQueryGateway(
                new DialectResolver(),
                mock(SqlValidator.class),
                new QueryConfig(100, 10_000, 100, 500, 30),
                ThreadPoolProperties.defaults());
        gateway.registerClient("h2", dataSource);
    }

    @AfterEach
    void shutdownGateway() {
        gateway.shutdown();
    }

    @Test
    void bindsNamedParametersByNameNotByMapOrder() {
        // Insertion order deliberately unrelated to the placeholder order in the SQL.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dept", "sales");
        params.put("city", "beijing");
        params.put("role", "admin");

        List<Map<String, Object>> rows = gateway.executeQuery("h2", THREE_PARAM_QUERY, 10, params);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("ID")).isEqualTo(1);
    }

    @Test
    void bindsNamedParametersOutOfAHashMapWhoseOrderIsAnImplementationDetail() {
        Map<String, Object> params = new HashMap<>();
        params.put("role", "admin");
        params.put("dept", "sales");
        params.put("city", "beijing");

        List<Map<String, Object>> rows = gateway.executeQuery("h2", THREE_PARAM_QUERY, 10, params);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("ID")).isEqualTo(1);
    }

    @Test
    void appliesTheRowLimitFromTheDialect() {
        List<Map<String, Object>> rows =
                gateway.executeQuery("h2", "SELECT ID FROM PEOPLE ORDER BY ID", 2, null);

        assertThat(rows).hasSize(2);
    }

    @Test
    void fallsBackToTheConfiguredMaxRowsWhenNoneIsGiven() {
        List<Map<String, Object>> rows =
                gateway.executeQuery("h2", "SELECT ID FROM PEOPLE ORDER BY ID", null, null);

        assertThat(rows).hasSize(3);
    }

    @Test
    void rejectsAnUnknownDatabase() {
        assertThatThrownBy(() -> gateway.executeQuery("nope", "SELECT 1", 1, null))
                .isInstanceOf(McpFederatedException.class)
                .hasMessageContaining("nope");
    }

    /**
     * 注册必须是全成或全不成：拒掉的注册不能在表里留下一个半初始化的 entry，否则后续查询会拿到一个
     * 没有模板（也就没有语句超时）的客户端。
     */
    @Test
    void rejectsRegistrationWithoutAClientId() {
        assertThatThrownBy(() -> gateway.registerClient("  ", dataSource))
                .isInstanceOf(McpFederatedException.class);
        assertThatThrownBy(() -> gateway.registerClient("nulls", null))
                .isInstanceOf(McpFederatedException.class);

        // 只剩 @BeforeEach 注册的那一个，两次被拒的注册都没留下痕迹。
        assertThat(gateway.getClientCount()).isEqualTo(1);
        assertThat(gateway.getDatabaseInfo("nulls")).containsEntry("status", "not_found");
    }

    @Test
    void federatedQueryRunsOnTheGatewaysOwnExecutor() {
        Map<String, Object> result =
                gateway.executeFederatedQuery("SELECT ID FROM PEOPLE ORDER BY ID", List.of("h2"), 3);

        assertThat(result.get("successCount")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> perDatabase = (Map<String, Object>) result.get("results");
        @SuppressWarnings("unchecked")
        Map<String, Object> h2Result = (Map<String, Object>) perDatabase.get("h2");
        assertThat(h2Result.get("rowCount")).isEqualTo(3);
        // The pool the fan-out actually submits to is the one getQueryStats() reports.
        assertThat(gateway.getQueryStats()).containsEntry("registeredClients", 1);
    }

    @Test
    void selectiveQueryReportsPerDatabaseFailuresWithoutAborting() {
        Map<String, Object> result = gateway.executeSelectiveQuery(new LinkedHashMap<>(Map.of(
                "h2", "SELECT ID FROM PEOPLE",
                "missing", "SELECT 1")));

        assertThat(result.get("successCount")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> perDatabase = (Map<String, Object>) result.get("results");
        assertThat(perDatabase).containsOnlyKeys("h2", "missing");
    }

    @Test
    void unregisteringRemovesTheClient() {
        gateway.unregisterClient("h2");

        assertThat(gateway.getClientCount()).isZero();
        assertThat(gateway.getDatabaseInfo("h2")).containsEntry("status", "not_found");
    }

    /**
     * 之前 fan-out 用 {@code join()} 无限等：一个卡住的库会把 MCP 请求线程一起挂住，而 statement 级
     * {@code queryTimeout} 管不到建连阶段。现在等待带超时，超时的库按单库失败记录，已经跑完的库的结果
     * 照常返回。
     */
    @Test
    void federatedQueryReturnsPartialResultsWhenOneDatabaseHangs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FederatedQueryGateway hangingGateway = gatewayWithFanOutTimeout(1, 2);
        try {
            hangingGateway.registerClient("h2", dataSource);
            hangingGateway.registerClient("stuck", blockingDataSource(release));

            Map<String, Object> result = hangingGateway.executeFederatedQuery(
                    "SELECT ID FROM PEOPLE ORDER BY ID", List.of("h2", "stuck"), 3);

            assertThat(result.get("successCount")).isEqualTo(1L);
            @SuppressWarnings("unchecked")
            Map<String, Object> perDatabase = (Map<String, Object>) result.get("results");
            assertThat(perDatabase).containsOnlyKeys("h2", "stuck");
            @SuppressWarnings("unchecked")
            Map<String, Object> stuckResult = (Map<String, Object>) perDatabase.get("stuck");
            assertThat(stuckResult).containsEntry("status", "error");
            assertThat(String.valueOf(stuckResult.get("error"))).contains("timed out");
        } finally {
            release.countDown();
            hangingGateway.shutdown();
        }
    }

    /**
     * 池的队列现在是显式有界 + AbortPolicy：过载时提交处立刻抛错，而不是攒一队迟早都会超时的任务。
     * 池宽 1、队列深 {@code 1 × 4}，所以第 6 个目标库必被拒。
     */
    @Test
    void saturatedPoolFailsFastInsteadOfQueueingUnbounded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FederatedQueryGateway narrowGateway = gatewayWithFanOutTimeout(30, 1);
        try {
            List<String> targets = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String id = "stuck" + i;
                narrowGateway.registerClient(id, blockingDataSource(release));
                targets.add(id);
            }

            assertThatThrownBy(() -> narrowGateway.executeFederatedQuery("SELECT 1", targets, 1))
                    .isInstanceOf(McpFederatedException.class)
                    .hasMessageContaining("saturated");
        } finally {
            release.countDown();
            narrowGateway.shutdown();
        }
    }

    private FederatedQueryGateway gatewayWithFanOutTimeout(int fanOutTimeoutSeconds, int poolSize) {
        return new FederatedQueryGateway(
                new DialectResolver(),
                mock(SqlValidator.class),
                new QueryConfig(100, 10_000, 100, 500, 30),
                new ThreadPoolProperties(0, 0, 0, 0, poolSize, 0, 0),
                fanOutTimeoutSeconds);
    }

    /**
     * 一个永远拿不到连接的 DataSource：方言探测就会卡在 {@code getConnection()} 上，正是超时与过载要
     * 覆盖的形状。每个库用独立 mock，避免多线程共用一个 mock 时 Mockito 自身的同步把并发压平。
     */
    private static DataSource blockingDataSource(CountDownLatch release) throws Exception {
        DataSource blocking = mock(DataSource.class);
        when(blocking.getConnection()).thenAnswer(invocation -> {
            release.await(30, TimeUnit.SECONDS);
            throw new SQLException("released without connecting");
        });
        return blocking;
    }
}
