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

    /**
     * {@code description} 是注解原文，只做首尾去空白。
     *
     * <p>摘要那一条已经在上面钉住了；这里钉的是"描述<b>没有</b>被摘要化"：模板里首句之后的
     * 每一行（前置条件、返回字段、标签行）都得在。提示词生成器要的就是这几行——只给一句摘要
     * 等于把真正能阻止误用的信息扔掉。
     */
    @Test
    void keepsTheFullAnnotationDescription() {
        ToolDescriptor descriptor = index.get("executeSample");

        assertThat(descriptor.description())
                .startsWith("【执行示例查询】")
                .contains("前置条件：无。")
                .contains("返回字段：rows、rowCount。")
                .contains("标签：[read, query, select]")
                .as("首尾去空白：文本块尾部那个换行会在 Markdown 里变成一个空段落")
                .doesNotEndWith("\n");
        assertThat(descriptor.description())
                .as("描述比摘要长得多，这正是两个字段并存的理由")
                .hasSizeGreaterThan(descriptor.summary().length());
    }

    /** 没有描述时是空串而不是 null：这个字段会进 JSON 与 Markdown，两处都不该出现 "null"。 */
    @Test
    void missingDescriptionBecomesAnEmptyStringNotNull() {
        assertThat(ToolCatalog.fullDescription(null)).isEmpty();
        assertThat(ToolCatalog.fullDescription("   \n  ")).isEmpty();
    }

    /**
     * <b>{@code searchableText()} 刻意不含 description。</b>
     *
     * <p>这一条钉的是一个取舍，不是实现细节：完整描述里有「前置条件 / 使用场景 / 返回字段 /
     * 不要用于」这套模板套话，把它加进检索文本之后几乎任何关键词都能在几乎任何工具上命中，
     * {@code IntentRouter.suggestTools} 的弱命中权重会给所有工具都加分，排序退化成按工具名
     * 字典序——推荐功能形同废掉。哪天有人"顺手"把 description 加进去，这条会红。
     */
    @Test
    void searchableTextDeliberatelyExcludesTheFullDescription() {
        ToolDescriptor descriptor = index.get("executeSample");

        assertThat(descriptor.description())
                .as("前提：这个夹具的描述里确实有摘要里没有的内容")
                .contains("返回字段");
        assertThat(descriptor.searchableText())
                .doesNotContain("返回字段")
                .doesNotContain("前置条件");
    }
}
