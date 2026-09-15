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
package com.entropy.database.mcp.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理员密码解析的边界。
 *
 * <p>0.4.0 起 {@code entropy.mcp.security.enabled} 默认为 {@code true}，这段逻辑从"只有显式打开
 * 鉴权的人才会走到"变成了"默认路径"，所以它的失败方式必须钉住。
 *
 * <p>空白输入是真实踩点而非假想：{@code docker-compose.yml} 里 {@code ${VAR:-}} 与 {@code .env} 里
 * 一行 {@code VAR=} 都会传进空串。只判 null 会让服务带着「admin + 空密码」正常启动并对外提供鉴权，
 * 那比起不来危险得多。
 */
class SecurityConfigPasswordTest {

    @Test
    void prefersTheEnvironmentVariable() {
        assertThat(SecurityConfig.requireNonBlankPassword("from-env", "from-property"))
                .isEqualTo("from-env");
    }

    @Test
    void fallsBackToTheSystemPropertyWhenTheEnvironmentVariableIsAbsentOrBlank() {
        assertThat(SecurityConfig.requireNonBlankPassword(null, "from-property"))
                .isEqualTo("from-property");
        assertThat(SecurityConfig.requireNonBlankPassword("", "from-property"))
                .isEqualTo("from-property");
        assertThat(SecurityConfig.requireNonBlankPassword("   ", "from-property"))
                .isEqualTo("from-property");
    }

    @Test
    void rejectsMissingAndBlankPasswords() {
        for (String[] candidates : new String[][]{
                {null, null}, {"", ""}, {"   ", null}, {null, "\t"}, {"", "  "}}) {
            assertThatThrownBy(() ->
                    SecurityConfig.requireNonBlankPassword(candidates[0], candidates[1]))
                    .as("env=%s property=%s", candidates[0], candidates[1])
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MCP_SECURITY_ADMIN_PASSWORD")
                    // 报错必须给出退路，否则本地开发只能靠翻源码
                    .hasMessageContaining("entropy.mcp.security.enabled=false");
        }
    }
}
