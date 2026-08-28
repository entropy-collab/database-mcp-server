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

@ConfigurationProperties(prefix = "entropy.mcp.database.optimizer")
public record OptimizerProperties(
    boolean enabled,
    int maxSuggestionsPerQuery,
    int maxIndexRecommendations,
    boolean enableCompositeIndexAnalysis
) {
    public static final int DEFAULT_MAX_SUGGESTIONS_PER_QUERY = 10;
    public static final int DEFAULT_MAX_INDEX_RECOMMENDATIONS = 5;

    /** 归一化理由同 {@link CatalogProperties}：非正数按"未配置"处理，而不是启动失败。 */
    public OptimizerProperties {
        maxSuggestionsPerQuery = maxSuggestionsPerQuery > 0
                ? maxSuggestionsPerQuery : DEFAULT_MAX_SUGGESTIONS_PER_QUERY;
        maxIndexRecommendations = maxIndexRecommendations > 0
                ? maxIndexRecommendations : DEFAULT_MAX_INDEX_RECOMMENDATIONS;
    }

    public OptimizerProperties() {
        this(true, DEFAULT_MAX_SUGGESTIONS_PER_QUERY, DEFAULT_MAX_INDEX_RECOMMENDATIONS, true);
    }
}
