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
import org.springframework.beans.factory.annotation.Value;
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
                  "entropy.mcp.database.dialect=generic",
                  "entropy.mcp.gateway.enabled=false",
                  "entropy.mcp.security.test-mode=true",
                  "entropy.mcp.tools.exclude=batchQuery"})
@Import(TestSecurityConfig.class)
class McpServerIntegrationTest {

    @LocalServerPort
    private int port;

    @Value("${spring.ai.mcp.server.version}")
    private String configuredServerVersion;

    private RestTemplate restTemplate = McpTestHttp.restTemplate();
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
        assertThat(response).contains("\"" + configuredServerVersion + "\"");
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

    /**
     * 被 {@code entropy.mcp.tools.exclude} 摘掉的工具不仅要从 {@code tools/list} 消失，
     * 按名字直接 {@code tools/call} 也必须调不到。
     *
     * <p>这两件事并不等价：CVE-2026-59318 的成因正是 tools/call 在已注册清单里找不到名字时
     * 回退到全局 resolver，于是"未公布"的工具照样能被调用。裁剪如果只作用在 list 上，暴露面
     * 收敛就只是障眼法。
     */
    @Test
    void excludedToolIsNeitherListedNorCallable() throws Exception {
        HttpHeaders headers = buildHeaders();

        String listResponse = restTemplate.postForObject(
            "http://localhost:" + port + "/mcp",
            new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}", headers),
            String.class);
        assertThat(listResponse).doesNotContain("batchQuery");

        String callResponse = restTemplate.postForObject(
            "http://localhost:" + port + "/mcp",
            new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"batchQuery\",\"arguments\":{}}}", headers),
            String.class);

        JsonNode call = objectMapper.readTree(callResponse);
        assertThat(call.get("error")).as("dispatching an unexposed tool must be a protocol error: %s",
                callResponse).isNotNull();
        assertThat(call.get("result")).isNull();

        // 对照组，防止上面的断言空转：一个仍然暴露的工具、同样传空参数，走的是"执行失败"路径，
        // 回的是 result（isError=true）而不是 JSON-RPC error。两者形状不同，才说明上面测到的
        // 是"调不到"而不是"调到了但报错"。
        String exposedResponse = restTemplate.postForObject(
            "http://localhost:" + port + "/mcp",
            new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"executeQuery\",\"arguments\":{}}}", headers),
            String.class);

        JsonNode exposed = objectMapper.readTree(exposedResponse);
        assertThat(exposed.get("error")).as("executeQuery is exposed, so this must not be an "
                + "unknown-tool error: %s", exposedResponse).isNull();
        assertThat(exposed.get("result")).isNotNull();
    }
}
