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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.tools.SampleTools.HealthLikeTools;
import com.entropy.database.mcp.tools.SampleTools.QueryLikeTools;
import com.entropy.database.mcp.tools.SampleTools.SampleDispatchTool;
import com.entropy.database.mcp.tools.ToolCatalog.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 索引必须完全由反射得出：分组是类名的纯函数，工具名以 {@code @McpTool.name()} 为准。
 *
 * <p>这几条断言的意义在于防止索引退化成"又一份需要人工维护的工具清单"——上一代意图路由
 * 就是因为静态清单与真实方法名漂移而失效。
 */
class ToolCatalogTest {

    private final Map<String, ToolDescriptor> index = ToolCatalog.build(
            List.of(new QueryLikeTools(), new HealthLikeTools(), new SampleDispatchTool()));

    @Test
    void indexesEveryAnnotatedMethod() {
        assertThat(index.keySet()).containsExactly(
                "checkSampleHealth", "dispatchSample", "executeSample", "flashbackSample", "untemplatedSample");
    }

    @Test
    void explicitToolNameWinsOverMethodName() {
        // HealthLikeTools.healthMethodName() 标注了 name = "checkSampleHealth"
        assertThat(index).containsKey("checkSampleHealth");
        assertThat(index).doesNotContainKey("healthMethodName");
    }

    @Test
    void derivesGroupFromDeclaringClassName() {
        assertThat(index.get("executeSample").group()).isEqualTo("query-like");
        assertThat(index.get("flashbackSample").group()).isEqualTo("health-like");
        assertThat(index.get("dispatchSample").group()).isEqualTo("sample-dispatch");
    }

    @Test
    void extractsSummaryAndTagsFromDescriptionTemplate() {
        ToolDescriptor descriptor = index.get("executeSample");
        assertThat(descriptor.summary()).isEqualTo("执行示例查询：在示例连接上执行一条只读查询并返回结果行");
        assertThat(descriptor.tags()).containsExactly("read", "query", "select");
    }

    @Test
    void degradesGracefullyWhenDescriptionDoesNotFollowTemplate() {
        ToolDescriptor descriptor = index.get("untemplatedSample");
        assertThat(descriptor.summary()).isEqualTo("plain description without the Chinese template");
        assertThat(descriptor.tags()).isEmpty();
    }

    @Test
    void exposesGroupLookupAndSize() {
        ToolCatalog catalog = new ToolCatalog(FixedObjectProvider.of(
                new QueryLikeTools(), new HealthLikeTools(), new SampleDispatchTool()));

        assertThat(catalog.size()).isEqualTo(5);
        assertThat(catalog.groups()).containsExactly("health-like", "query-like", "sample-dispatch");
        assertThat(catalog.groupOf("executeSample")).isEqualTo("query-like");
        assertThat(catalog.groupOf("noSuchTool")).isNull();
        assertThat(catalog.contains("executeSample")).isTrue();
        assertThat(catalog.contains("noSuchTool")).isFalse();
    }

    @Test
    void searchableTextCoversNameGroupSummaryAndTags() {
        String text = index.get("flashbackSample").searchableText();
        assertThat(text).contains("flashbacksample", "health-like", "闪回", "oracle");
    }
}
