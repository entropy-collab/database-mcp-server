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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 应用入口。
 *
 * <p>{@link ConfigurationPropertiesScan} 取代了原先散在 {@code DatabaseConfig} 和
 * {@code AsyncConfig} 上的两处 {@code @EnableConfigurationProperties} 列表。两者注册出来的
 * bean 集合完全一致——{@code properties} 包里带 {@code @ConfigurationProperties} 的类正好
 * 11 个，原先的显式列表登记的也正好是这 11 个。换成扫描是为了去掉"新加一个 properties
 * 类、忘了往列表里补一行"这种静默失效，{@code ConfigurationPropertiesRegisteredTest}
 * 把这条不变式钉住了。
 *
 * <p>扫描范围刻意收窄到 {@code properties} 包，而不是默认的整个应用包。带
 * {@code @Component} 的持有者（{@code DataMaskingServiceImpl}）本来就会被
 * {@code ConfigurationPropertiesScanRegistrar} 跳过，不存在重复注册；限定包只是让
 * "配置类都在这里"这件事在代码里可读。
 */
@SpringBootApplication
@EnableScheduling
@EnableAspectJAutoProxy
@ConfigurationPropertiesScan("com.entropy.database.mcp.properties")
public class DatabaseMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatabaseMcpApplication.class, args);
    }
}
