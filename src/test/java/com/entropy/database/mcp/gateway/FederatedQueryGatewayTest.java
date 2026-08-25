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
import com.entropy.database.mcp.security.SqlValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Guards parameter binding in {@link FederatedQueryGateway} against a real H2 database.
 *
 * <p>The bug being pinned: the gateway used to flatten the caller's parameter {@code Map} with
 * {@code params.values().toArray()} and bind it positionally. Map iteration order has no relation
 * to the order of the placeholders in the SQL — for a {@code HashMap} it is a hash artefact — so
 * the query silently read the wrong rows. Only a real database can show that, hence H2 rather than
 * a mocked template: a mock would happily accept any array.
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
                new QueryConfig(100, 10_000, 100, 500, 30));
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

    @Test
    void rejectsRegistrationWithoutAClientId() {
        assertThatThrownBy(() -> gateway.registerClient("  ", dataSource))
                .isInstanceOf(McpFederatedException.class);
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
}
