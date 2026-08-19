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
package com.entropy.database.mcp;

import com.entropy.database.mcp.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for stateless MCP Server protocol
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"entropy.mcp.security.enabled=false",
                  "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
                  "spring.datasource.primary.jdbc-url=jdbc:h2:mem:mcpdb;DB_CLOSE_DELAY=-1",
                  "spring.datasource.primary.username=sa",
                  "spring.datasource.primary.password=",
                  "entropy.mcp.database.dialect=generic",
                  "entropy.mcp.gateway.enabled=false",
                  "entropy.mcp.security.test-mode=true"})
@Import(TestSecurityConfig.class)
class McpServerIntegrationTest {

    @LocalServerPort
    private int port;

    private RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(
            MediaType.parseMediaType("application/json"),
            MediaType.parseMediaType("text/event-stream")
        ));
        return headers;
    }

    @Test
    void toolsListReturnsRegisteredTools() throws Exception {
        HttpHeaders headers = buildHeaders();
        HttpEntity<String> entity = new HttpEntity<>(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
            headers);

        String response = restTemplate.postForObject(
            "http://localhost:" + port + "/mcp",
            entity,
            String.class);

        assertThat(response).isNotNull();
        assertThat(response).contains("\"tools\"");
        assertThat(response).contains("listTables");
        assertThat(response).contains("describeTable");
        assertThat(response).contains("executeQuery");
    }

    @Test
    void initializeReturnsServerInfo() throws Exception {
        HttpHeaders headers = buildHeaders();
        String initRequest = "{"
            + "\"jsonrpc\":\"2.0\","
            + "\"id\":1,"
            + "\"method\":\"initialize\","
            + "\"params\":{"
            + "\"protocolVersion\":\"2024-11-05\","
            + "\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0\"}"
            + "}}";

        HttpEntity<String> entity = new HttpEntity<>(initRequest, headers);
        String response = restTemplate.postForObject(
            "http://localhost:" + port + "/mcp",
            entity,
            String.class);

        assertThat(response).isNotNull();
        assertThat(response).contains("\"database-mcp-server\"");
        assertThat(response).contains("\"0.1.0\"");
        assertThat(response).contains("\"capabilities\"");
    }

    @Test
    void requestWithoutAcceptHeaderReturnsError() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
            headers);

        try {
            restTemplate.postForObject(
                "http://localhost:" + port + "/mcp",
                entity,
                String.class);
            assertThat(false).as("Should throw exception").isTrue();
        } catch (Exception e) {
            // Expected - should fail due to missing Accept header
            assertThat(e.getMessage()).contains("400");
        }
    }

    @Test
    void statelessModeNoSessionRequired() throws Exception {
        // In stateless mode, each request is independent - no session needed
        HttpHeaders headers = buildHeaders();

        // First call
        HttpEntity<String> entity1 = new HttpEntity<>(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
            headers);
        String response1 = restTemplate.postForObject(
            "http://localhost:" + port + "/mcp",
            entity1,
            String.class);

        // Second call - should work without any session context
        HttpEntity<String> entity2 = new HttpEntity<>(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
            headers);
        String response2 = restTemplate.postForObject(
            "http://localhost:" + port + "/mcp",
            entity2,
            String.class);

        assertThat(response1).isNotNull();
        assertThat(response2).isNotNull();
        // Compare only the result part (ignore the id field)
        assertThat(objectMapper.readTree(response1).get("result"))
            .isEqualTo(objectMapper.readTree(response2).get("result"));
    }
}
