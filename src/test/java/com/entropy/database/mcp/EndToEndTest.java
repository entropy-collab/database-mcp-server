/*
 * Copyright 2024-2026 Entropy Pty Ltd.
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
package com.entropy.database.mcp;

import com.entropy.database.mcp.config.TestSecurityConfig;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the Database MCP Server.
 * Runs against an H2 in-memory database with test data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"entropy.mcp.security.enabled=false",
                  "entropy.mcp.security.test-mode=true",
                  "entropy.mcp.database.dialect=h2",
                  "entropy.mcp.gateway.enabled=false"})
@TestPropertySource(properties = {
    "entropy.mcp.database.security.enabled=false",
    "entropy.mcp.database.cache.max-size=100"
})
@Import(TestSecurityConfig.class)
class EndToEndTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Setup ────────────────────────────────────────────────────────────

    @BeforeAll
    static void setUp(@Autowired DynamicDataSourceManager dataSourceManager,
                      @Autowired DialectResolver dialectResolver) throws Exception {
        // Create H2 datasource for tests
        org.h2.Driver.load();
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:e2edb;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        // Register as BYOK connection
        dataSourceManager.registerExisting("primary", dataSource, new H2Dialect());

        // Create test data
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE employees ("
            + "ID INT PRIMARY KEY, NAME VARCHAR(100), DEPARTMENT VARCHAR(50), "
            + "SALARY DECIMAL(10,2), EMAIL VARCHAR(100))");
        jdbc.execute("INSERT INTO employees VALUES (1, 'Alice', 'Engineering', 95000, 'alice@example.com')");
        jdbc.execute("INSERT INTO employees VALUES (2, 'Bob', 'Marketing', 72000, 'bob@example.com')");
        jdbc.execute("INSERT INTO employees VALUES (3, 'Charlie', 'Engineering', 88000, 'charlie@example.com')");
        jdbc.execute("INSERT INTO employees VALUES (4, 'Diana', 'Sales', 65000, 'diana@example.com')");
        jdbc.execute("INSERT INTO employees VALUES (5, 'Eve', 'Engineering', 102000, 'eve@example.com')");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(
            MediaType.parseMediaType("application/json"),
            MediaType.parseMediaType("text/event-stream")));
        return headers;
    }

    private String postMcp(String method, Map<String, Object> arguments) {
        Map<String, Object> req = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", method,
            "params", Map.of("name", method.equals("tools/list") ? "tools/list" : "executeQuery",
                            "arguments", arguments != null ? arguments : Map.of()));
        if (method.equals("tools/list")) {
            req = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of());
        }
        HttpEntity<String> entity = new HttpEntity<>(asJson(req), buildHeaders());
        return restTemplate.postForObject("http://localhost:" + port + "/mcp", entity, String.class);
    }

    private String postToolCall(String toolName, Map<String, Object> arguments) {
        Map<String, Object> req = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "tools/call",
            "params", Map.of("name", toolName, "arguments", arguments != null ? arguments : Map.of()));
        HttpEntity<String> entity = new HttpEntity<>(asJson(req), buildHeaders());
        return restTemplate.postForObject("http://localhost:" + port + "/mcp", entity, String.class);
    }

    private String asJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode parseResult(String response) {
        try {
            return mapper.readTree(response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse: " + response, e);
        }
    }

    private String getToolText(String response) {
        JsonNode node = parseResult(response);
        JsonNode content = node.get("result").get("content");
        return content.get(0).get("text").asText();
    }

    private List<String> jsonNodeArrayToList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
    }

    // ─── MCP Protocol Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("MCP initialize should return server capabilities")
    void testInitialize() throws Exception {
        String response = postMcp("initialize", null);

        JsonNode node = parseResult(response);
        assertThat(node.get("id").asInt()).isEqualTo(1);
        assertThat(node.has("result")).isTrue();
        assertThat(node.get("result").get("serverInfo").get("name").asText())
            .isEqualTo("database-mcp-server");
        assertThat(node.get("result").get("capabilities")).isNotNull();
    }

    @Test
    @DisplayName("tools/list should return registered tools")
    void testToolsList() throws Exception {
        String response = postMcp("tools/list", null);
        JsonNode node = parseResult(response);

        JsonNode tools = node.get("result").get("tools");
        assertThat(tools).isNotNull();
        assertThat(tools.isArray()).isTrue();
        List<String> nameFields = new ArrayList<>();
        tools.forEach(t -> nameFields.add(t.get("name").asText()));
        assertThat(nameFields).contains("listTables", "describeTable", "executeQuery",
            "getDatabaseInfo", "batchQuery");
    }

    @Test
    @DisplayName("tools/list should advertise MCP tool annotations")
    void testToolsListAnnotations() throws Exception {
        String response = postMcp("tools/list", null);
        JsonNode tools = parseResult(response).get("result").get("tools");

        JsonNode readOnlyTool = findTool(tools, "executeQuery");
        assertThat(readOnlyTool.get("annotations")).isNotNull();
        assertThat(readOnlyTool.get("annotations").get("readOnlyHint").asBoolean()).isTrue();

        JsonNode mutatingTool = findTool(tools, "executeDdl");
        assertThat(mutatingTool.get("annotations")).isNotNull();
        assertThat(mutatingTool.get("annotations").get("readOnlyHint").asBoolean()).isFalse();
        assertThat(mutatingTool.get("annotations").get("destructiveHint").asBoolean()).isTrue();
    }

    private JsonNode findTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.get("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("Tool not found in tools/list: " + name);
    }

    @Test
    @DisplayName("tools/call listTables should return test tables")
    void testListTables() throws Exception {
        String response = postToolCall("listTables", Map.of("schema", "PUBLIC", "connection", "primary"));
        String text = getToolText(response);
        assertThat(text.toLowerCase()).contains("employees");
    }

    @Test
    @DisplayName("tools/call executeQuery should return employee data")
    void testExecuteQuery() throws Exception {
        String response = postToolCall("executeQuery", Map.of(
            "sql", "SELECT ID, NAME, DEPARTMENT, SALARY FROM employees ORDER BY ID",
            "maxRows", 10,
            "continuationToken", "",
            "connection", "primary"));

        String text = getToolText(response);
        JsonNode result = mapper.readTree(text);
        assertThat(result.get("rowCount").asInt()).isEqualTo(5);
        assertThat(result.get("columns").isArray());
        List<String> columns = jsonNodeArrayToList(result.get("columns"));
        assertThat(columns).containsExactly("ID", "NAME", "DEPARTMENT", "SALARY");
        assertThat(result.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("tools/call executeQuery with WHERE clause filters correctly")
    void testExecuteQueryWithFilter() throws Exception {
        String response = postToolCall("executeQuery", Map.of(
            "sql", "SELECT NAME, SALARY FROM employees WHERE DEPARTMENT = 'Engineering' ORDER BY SALARY DESC",
            "maxRows", 10,
            "continuationToken", "",
            "connection", "primary"));

        String text = getToolText(response);
        JsonNode result = mapper.readTree(text);

        assertThat(result.get("rowCount").asInt()).isEqualTo(3);
        List<String> names = new ArrayList<>();
        result.get("rows").forEach(r -> names.add(r.get("NAME").asText()));
        assertThat(names).containsExactly("Eve", "Alice", "Charlie");
    }

    @Test
    @DisplayName("tools/call executeQuery with pagination returns correct hasMore flag")
    void testExecuteQueryPagination() throws Exception {
        // Page 1: limit 2 rows
        String response = postToolCall("executeQuery", Map.of(
            "sql", "SELECT ID, NAME FROM employees ORDER BY ID",
            "maxRows", 2,
            "continuationToken", "",
            "connection", "primary"));

        String text = getToolText(response);
        JsonNode result = mapper.readTree(text);

        assertThat(result.get("rowCount").asInt()).isEqualTo(2);
        assertThat(result.get("hasMore").asBoolean()).isTrue();
        assertThat(result.get("continuationToken").asText()).isNotEmpty();

        // Page 2: using continuation token
        String token = result.get("continuationToken").asText();
        String response2 = postToolCall("executeQuery", Map.of(
            "sql", "SELECT ID, NAME FROM employees ORDER BY ID",
            "maxRows", 2,
            "continuationToken", token,
            "connection", "primary"));

        String text2 = getToolText(response2);
        JsonNode result2 = mapper.readTree(text2);

        assertThat(result2.get("rowCount").asInt()).isEqualTo(2);
        assertThat(result2.get("hasMore").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("tools/call describeTable should return column metadata")
    void testDescribeTable() throws Exception {
        String response = postToolCall("describeTable", Map.of(
            "table", "employees",
            "schema", "PUBLIC",
            "connection", "primary"));
        String text = getToolText(response);
        assertThat(text).contains("employees");
        assertThat(text).contains("columnCount");
    }

    @Test
    @DisplayName("tools/call getDatabaseInfo should return product info")
    void testGetDatabaseInfo() throws Exception {
        String response = postToolCall("getDatabaseInfo", Map.of("connection", "primary"));
        String text = getToolText(response);
        JsonNode info = mapper.readTree(text);
        assertThat(info.has("productName")).isTrue();
        assertThat(info.get("productName").asText()).isEqualTo("H2");
    }

    @Test
    @DisplayName("tools/call executeQuery with SELECT * should respect maxRows limit")
    void testExecuteQueryMaxRowsLimit() throws Exception {
        String response = postToolCall("executeQuery", Map.of(
            "sql", "SELECT * FROM employees",
            "maxRows", 2,
            "continuationToken", "",
            "connection", "primary"));

        String text = getToolText(response);
        JsonNode result = mapper.readTree(text);

        assertThat(result.get("rowCount").asInt()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("HTTP 400 returned when Accept header is missing")
    void testMissingAcceptHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", headers);

        try {
            restTemplate.postForObject(
                "http://localhost:" + port + "/mcp", entity, String.class);
            assertThat(false).as("Should have thrown").isTrue();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(400);
        }
    }
}
