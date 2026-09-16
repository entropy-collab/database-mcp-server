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
package com.entropy.database.mcp.byok;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the remote federated client that {@code FederatedQueryGateway} registers per target
 * database: named binding, and the statement ceiling actually reaching the statements.
 *
 * <p>Runs against a real H2 database rather than a mocked template, for the same reason the gateway
 * test does: a mock accepts any binding, so it cannot show a mis-bound query reading the wrong rows.
 *
 * <p>该测什么留在哪：本类测<b>远端客户端本身</b>（绑定方式、超时下达到语句、不泄露模板）。fan-out 的
 * 超时/过载、方言探测缓存、注册表驱逐这些<b>网关行为</b>仍留在 features 的
 * {@code FederatedQueryGatewayTest}——那些用例只经由网关的公开方法，不碰这里的类型。
 */
class RemoteJdbcClientTest {

    private static final String THREE_PARAM_QUERY =
            "SELECT ID FROM PEOPLE WHERE CITY = :city AND ROLE = :role AND DEPT = :dept";

    private static org.h2.jdbcx.JdbcDataSource dataSource;

    @BeforeAll
    static void createSchema() {
        dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:remotejdbcclient;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcTemplate setup = new JdbcTemplate(dataSource);
        setup.execute("DROP TABLE IF EXISTS PEOPLE");
        setup.execute("CREATE TABLE PEOPLE (ID INT PRIMARY KEY, CITY VARCHAR(20), "
                + "ROLE VARCHAR(20), DEPT VARCHAR(20))");
        // Row 1 is the only match for (city=beijing, role=admin, dept=sales). Rows 2 and 3 hold the
        // same three values, permuted: they are what a positionally mis-bound query would return.
        setup.update("INSERT INTO PEOPLE VALUES (1, 'beijing', 'admin', 'sales')");
        setup.update("INSERT INTO PEOPLE VALUES (2, 'admin', 'sales', 'beijing')");
        setup.update("INSERT INTO PEOPLE VALUES (3, 'sales', 'beijing', 'admin')");
    }

    @Test
    @DisplayName("a parameterless query runs on the positional template")
    void runsAQueryWithoutParameters() {
        RemoteJdbcClient client = RemoteJdbcClient.over(dataSource, 30);

        assertThat(client.queryForList("SELECT ID FROM PEOPLE ORDER BY ID")).hasSize(3);
    }

    @Test
    @DisplayName("named parameters bind by name, not by map order")
    void bindsNamedParametersByNameNotByMapOrder() {
        // Insertion order deliberately unrelated to the placeholder order in the SQL.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dept", "sales");
        params.put("city", "beijing");
        params.put("role", "admin");

        List<Map<String, Object>> rows =
                RemoteJdbcClient.over(dataSource, 30).queryForList(THREE_PARAM_QUERY, params);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("ID")).isEqualTo(1);
    }

    @Test
    @DisplayName("a HashMap whose order is an implementation detail binds correctly too")
    void bindsNamedParametersOutOfAHashMap() {
        Map<String, Object> params = new HashMap<>();
        params.put("role", "admin");
        params.put("dept", "sales");
        params.put("city", "beijing");

        List<Map<String, Object>> rows =
                RemoteJdbcClient.over(dataSource, 30).queryForList(THREE_PARAM_QUERY, params);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("ID")).isEqualTo(1);
    }

    /**
     * The ceiling has to reach the statement, on <em>both</em> paths: a fan-out waits on every
     * database it targets, so an unbounded remote statement pins a gateway thread and the request
     * thread joining on it. Configuring only the positional template is how the named path silently
     * ends up unbounded — hence the named template wraps the positional one instead of the
     * datasource.
     */
    @Test
    @DisplayName("the statement ceiling reaches the statements on both the positional and named paths")
    void appliesTheStatementTimeoutOnBothPaths() throws SQLException {
        List<Integer> timeouts = new ArrayList<>();
        RemoteJdbcClient client = RemoteJdbcClient.over(recording(timeouts), 30);

        client.queryForList("SELECT ID FROM PEOPLE");
        assertThat(timeouts).containsExactly(30);

        client.queryForList(THREE_PARAM_QUERY, Map.of("city", "beijing", "role", "admin", "dept", "sales"));
        assertThat(timeouts).containsExactly(30, 30);
        assertThat(client.queryTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("a non-positive ceiling leaves the driver default in place")
    void nonPositiveTimeoutIsNotApplied() throws SQLException {
        List<Integer> timeouts = new ArrayList<>();

        RemoteJdbcClient client = RemoteJdbcClient.over(recording(timeouts), 0);
        client.queryForList("SELECT ID FROM PEOPLE");

        assertThat(timeouts).isEmpty();
        assertThat(client.queryTimeoutSeconds()).isZero();
    }

    /**
     * The gateway probes the dialect and connection validity through this accessor, so it must hand
     * back the very datasource it was registered with — the client never wraps or owns it.
     */
    @Test
    @DisplayName("the registered datasource is handed back as-is")
    void exposesTheRegisteredDataSource() {
        assertThat(RemoteJdbcClient.over(dataSource, 30).dataSource()).isSameAs(dataSource);
    }

    /**
     * The whole point of this class living in {@code infra}: the gateway must not be able to get a
     * template out of it. A {@code jdbc()} / {@code named()} style accessor would move the ArchUnit
     * R6 violations rather than remove them, so the API surface is asserted here instead of waiting
     * for the arch test in another module to notice.
     */
    @Test
    @DisplayName("no public member exposes a Spring JDBC type")
    void exposesNoSpringJdbcTypes() {
        List<String> leaks = Arrays.stream(RemoteJdbcClient.class.getMethods())
                .filter(method -> method.getDeclaringClass() == RemoteJdbcClient.class)
                .filter(method -> Stream.concat(Stream.of(method.getReturnType()),
                                Arrays.stream(method.getParameterTypes()))
                        .anyMatch(type -> type.getName().startsWith("org.springframework.jdbc")))
                .map(Method::toGenericString)
                .toList();

        assertThat(leaks).isEmpty();
    }

    /**
     * The H2 datasource, with every statement it creates recording the timeout that was pushed onto
     * it. A {@code JdbcTemplate}'s {@code queryTimeout} is only visible on the statement, so this is
     * the only place the ceiling can be observed without asserting on the template itself.
     */
    private static DataSource recording(List<Integer> timeouts) throws SQLException {
        DataSource recording = mock(DataSource.class);
        when(recording.getConnection()).thenAnswer(invocation -> proxy(Connection.class,
                dataSource.getConnection(), timeouts));
        return recording;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> contract, Object target, List<Integer> timeouts) {
        return (T) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxyInstance, method, args) -> {
                    if ("setQueryTimeout".equals(method.getName())) {
                        timeouts.add((Integer) args[0]);
                    }
                    Object result = invoke(target, method, args);
                    // Wrap the statements too, otherwise the setter above is never seen; the declared
                    // return type is the exact JDBC contract to proxy (Statement / PreparedStatement).
                    if (result instanceof Statement) {
                        return proxy(method.getReturnType(), result, timeouts);
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
