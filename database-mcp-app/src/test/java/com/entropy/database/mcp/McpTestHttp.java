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

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP client for the {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} suites.
 *
 * <p>Exists for one reason: {@code new RestTemplate()} has <strong>no timeouts at all</strong>. These
 * tests drive a real MCP endpoint over a real socket, so a request that never gets answered — a
 * deadlocked pool, a lost SSE response, a JDBC call that hangs — parks the JUnit thread forever. On CI
 * that shows up as the whole job timing out with no failing test to point at, which is far harder to
 * diagnose than an assertion error.
 *
 * <p>The read timeout is deliberately generous: some tools legitimately take seconds against H2 with
 * the test dataset. It only has to be shorter than the CI job limit to turn a hang into a failure.
 */
final class McpTestHttp {

    /** Connect to a local, already-started server: anything slower than this is broken, not busy. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Upper bound for a single tool call. Turns "hangs forever" into "fails with a stack trace". */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private McpTestHttp() {
    }

    static RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
    }
}
