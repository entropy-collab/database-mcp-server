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
package com.entropy.database.mcp.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entropy.mcp.database.catalog")
public record CatalogProperties(
    boolean enabled,
    boolean autoGenerateComments,
    boolean enableSensitiveDetection,
    int maxSearchResults
) {
    public static final int DEFAULT_MAX_SEARCH_RESULTS = 100;

    /**
     * 与本包其余 8 个 properties 类一致：非正数按"未配置"处理，回落到默认值。
     * <p>这里刻意不用 {@code @Validated} + {@code @Positive}：紧凑构造器先跑，Bean Validation
     * 看到的是归一化之后的值，约束永远不会失败；而且 {@code 0} 在本包里是"用默认值"的哨兵
     * （见 {@link ThreadPoolProperties#defaults()}），把它变成启动失败会推翻这个约定。
     */
    public CatalogProperties {
        maxSearchResults = maxSearchResults > 0 ? maxSearchResults : DEFAULT_MAX_SEARCH_RESULTS;
    }

    public CatalogProperties() {
        this(true, true, true, DEFAULT_MAX_SEARCH_RESULTS);
    }
}
