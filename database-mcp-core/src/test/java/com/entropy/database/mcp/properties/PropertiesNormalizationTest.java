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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本包的约定：数值型配置项收到非正数时按"未配置"处理，回落到默认值。
 *
 * <p>{@code CatalogProperties} / {@code CdcProperties} / {@code OptimizerProperties} 原先是唯一
 * 三个没有紧凑构造器的类——{@code max-search-results: 0} 会原样绑成 0 传到下游。补齐之后这三条
 * 测试钉住它们和其余 8 个类行为一致。
 *
 * <p>这里之所以不是 {@code @Validated} + {@code @Positive}：{@code 0} 在本包里是"用默认值"的
 * 哨兵而不是错误输入（{@link ThreadPoolProperties#defaults()} 就传全 0），而且紧凑构造器先于
 * Bean Validation 执行，约束看到的永远是归一化后的正数，永远不会失败。
 */
class PropertiesNormalizationTest {

    @Test
    void catalogNormalisesNonPositiveSearchLimit() {
        assertThat(new CatalogProperties(true, true, true, 0).maxSearchResults())
                .isEqualTo(CatalogProperties.DEFAULT_MAX_SEARCH_RESULTS);
        assertThat(new CatalogProperties(true, true, true, -1).maxSearchResults())
                .isEqualTo(CatalogProperties.DEFAULT_MAX_SEARCH_RESULTS);
        assertThat(new CatalogProperties(true, true, true, 25).maxSearchResults())
                .isEqualTo(25);
    }

    @Test
    void cdcNormalisesNonPositivePollAndTaskLimits() {
        var zeroed = new CdcProperties(true, false, 0, 0L, true, 0, true);
        assertThat(zeroed.maxEventsPerPoll()).isEqualTo(CdcProperties.DEFAULT_MAX_EVENTS_PER_POLL);
        assertThat(zeroed.defaultPollIntervalMs()).isEqualTo(CdcProperties.DEFAULT_POLL_INTERVAL_MS);
        assertThat(zeroed.maxMirrorTasks()).isEqualTo(CdcProperties.DEFAULT_MAX_MIRROR_TASKS);

        var configured = new CdcProperties(true, false, 500, 250L, true, 3, true);
        assertThat(configured.maxEventsPerPoll()).isEqualTo(500);
        assertThat(configured.defaultPollIntervalMs()).isEqualTo(250L);
        assertThat(configured.maxMirrorTasks()).isEqualTo(3);
    }

    @Test
    void optimizerNormalisesNonPositiveSuggestionLimits() {
        var negative = new OptimizerProperties(true, -5, -5, true);
        assertThat(negative.maxSuggestionsPerQuery())
                .isEqualTo(OptimizerProperties.DEFAULT_MAX_SUGGESTIONS_PER_QUERY);
        assertThat(negative.maxIndexRecommendations())
                .isEqualTo(OptimizerProperties.DEFAULT_MAX_INDEX_RECOMMENDATIONS);

        var configured = new OptimizerProperties(true, 3, 2, true);
        assertThat(configured.maxSuggestionsPerQuery()).isEqualTo(3);
        assertThat(configured.maxIndexRecommendations()).isEqualTo(2);
    }

    @Test
    void noArgConstructorsMatchTheDeclaredDefaults() {
        assertThat(new CatalogProperties().maxSearchResults())
                .isEqualTo(CatalogProperties.DEFAULT_MAX_SEARCH_RESULTS);
        assertThat(new CdcProperties().maxEventsPerPoll())
                .isEqualTo(CdcProperties.DEFAULT_MAX_EVENTS_PER_POLL);
        assertThat(new OptimizerProperties().maxIndexRecommendations())
                .isEqualTo(OptimizerProperties.DEFAULT_MAX_INDEX_RECOMMENDATIONS);
    }
}
