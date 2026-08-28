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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code entropy.mcp.database.masking.column-patterns} 必须真的绑得上。
 *
 * <p>为什么值得单独一条用例：{@link DataMaskingServiceImpl} 带 {@code @ConfigurationProperties}
 * 但曾经没有任何 setter。Boot 的 JavaBean 绑定在这种情况下一个键都不绑，并且**不报错**——
 * 配置写了也只是静默失效。这类缺陷没法靠读代码发现（注解看着是对的），只能靠断言"配进去的
 * 值确实改变了行为"。所以这里走 {@link ApplicationContextRunner}，用真正的绑定链路而不是
 * 直接调 setter。
 */
class DataMaskingColumnPatternsBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(MaskingServiceConfiguration.class);

    @Test
    @DisplayName("column-patterns 配了就生效，没配就用内置默认值")
    void bindsColumnPatternsFromConfiguration() {
        runner.withPropertyValues("entropy.mcp.database.masking.column-patterns=email,phone")
                .run(context -> {
                    DataMaskingServiceImpl service = context.getBean(DataMaskingServiceImpl.class);
                    assertThat(service.getColumnPatterns()).containsExactly("email", "phone");

                    // salary 在内置默认列表里、不在这份配置里，所以必须不再被识别为敏感列
                    assertThat(service.getMaskColumnsForSchema(List.of("email", "salary")))
                            .containsExactly("email");
                });

        runner.run(context -> assertThat(context.getBean(DataMaskingServiceImpl.class).getColumnPatterns())
                .contains("email", "salary", "bank_account"));
    }

    @Test
    @DisplayName("绑定生效后，脱敏结果随配置改变")
    void maskingFollowsTheBoundPatterns() {
        runner.withPropertyValues("entropy.mcp.database.masking.column-patterns=email")
                .run(context -> {
                    DataMaskingServiceImpl service = context.getBean(DataMaskingServiceImpl.class);
                    List<Map<String, Object>> masked = service.maskResults(
                            List.of(Map.of("email", "alice@example.com", "salary", "1234567")), null);

                    assertThat(masked.getFirst().get("email")).isNotEqualTo("alice@example.com");
                    assertThat(masked.getFirst().get("salary")).isEqualTo("1234567");
                });
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class MaskingServiceConfiguration {

        @org.springframework.context.annotation.Bean
        DataMaskingServiceImpl dataMaskingService() {
            return new DataMaskingServiceImpl();
        }
    }
}
