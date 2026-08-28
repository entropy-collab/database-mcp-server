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
package com.entropy.database.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 {@code @ConfigurationPropertiesScan} 的价值：classpath 上每一个带
 * {@link ConfigurationProperties} 的类都必须是容器里的 bean。
 *
 * <p>这条测试是把显式 {@code @EnableConfigurationProperties} 列表换成包扫描的前提。列表方
 * 案的失效方式是静默的——新加一个 properties 类、忘了往列表里补一行，注入点会直接
 * {@code NoSuchBeanDefinitionException}，或者更糟：某处用了默认值兜底，于是配置文件里
 * 的键永远不生效而没人发现。扫描消掉了这个失效模式，这条测试保证扫描范围没写错。
 *
 * <p>断言里刻意不写死类的个数：写死了就等于把"新增类要改一处"从列表搬到测试里，什么都
 * 没解决。只用一个下界防止扫描器本身空转（basePackage 打错字 → 扫出 0 个 → 全部断言空过）。
 */
@SpringBootTest(properties = {
    "entropy.mcp.database.enabled=true",
    "entropy.mcp.database.dialect=generic",
    "entropy.mcp.security.enabled=false",
    "entropy.mcp.gateway.enabled=false"
})
class ConfigurationPropertiesRegisteredTest {

    private static final String BASE_PACKAGE = "com.entropy.database.mcp";

    /** 扫描器空转的下界。当前实际是 11 个 properties 类 + 1 个 {@code @Component} 持有者。 */
    private static final int MINIMUM_EXPECTED = 10;

    @Autowired
    private ApplicationContext context;

    @Test
    void everyConfigurationPropertiesClassIsARegisteredBean() {
        List<Class<?>> holders = findConfigurationPropertiesClasses();

        assertThat(holders)
                .as("扫描器本身空转了，basePackage 或 classpath 有问题")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_EXPECTED);

        List<String> missing = holders.stream()
                .filter(type -> context.getBeanNamesForType(type).length == 0)
                .map(Class::getName)
                .toList();

        assertThat(missing)
                .as("这些类带了 @ConfigurationProperties 但不是 bean，配置键不会生效；"
                        + "要么放进 " + BASE_PACKAGE + ".properties（由 @ConfigurationPropertiesScan 覆盖），"
                        + "要么自己加个 stereotype 注解")
                .isEmpty();
    }

    private List<Class<?>> findConfigurationPropertiesClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));

        List<Class<?>> found = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String name = candidate.getBeanClassName();
            if (name != null) {
                found.add(ClassUtils.resolveClassName(name, getClass().getClassLoader()));
            }
        }
        return found;
    }
}
