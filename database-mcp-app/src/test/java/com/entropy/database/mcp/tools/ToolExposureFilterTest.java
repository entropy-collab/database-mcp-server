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

import com.entropy.database.mcp.properties.ToolExposureProperties;
import com.entropy.database.mcp.tools.SampleTools.HealthLikeTools;
import com.entropy.database.mcp.tools.SampleTools.QueryLikeTools;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 暴露面裁剪发生在 spec 列表交给 MCP server 之前，粒度到单个工具名。
 *
 * <p>另一条同样重要的断言：配置里写了不存在的工具名或分组时必须启动失败。静默忽略会让
 * "工具改名后配置失效"这类问题拖到线上才被发现。
 */
class ToolExposureFilterTest {

    private static final String SPEC_BEAN_NAME = "toolSpecs";

    private final ToolCatalog catalog = new ToolCatalog(
            FixedObjectProvider.of(new QueryLikeTools(), new HealthLikeTools()));

    private static final ToolExposureProperties NO_FILTER = props(Set.of(), Set.of(), Set.of());

    /** 绝大多数用例与 plane 无关，走默认值 {@code all}。 */
    private static ToolExposureProperties props(
            Set<String> groups, Set<String> include, Set<String> exclude) {
        return new ToolExposureProperties(ToolExposureProperties.PLANE_ALL, groups, include, exclude);
    }

    private static SyncToolSpecification spec(String name) {
        // 裁剪只看 tool().name()，调用处理器在本测试里不会被触发
        return new SyncToolSpecification(
                McpSchema.Tool.builder().name(name).description("d").build(),
                (context, request) -> null);
    }

    private final List<SyncToolSpecification> allSpecs = List.of(
            spec("executeSample"), spec("checkSampleHealth"),
            spec("flashbackSample"), spec("untemplatedSample"));

    private ToolExposureFilter filterWith(ToolExposureProperties properties) {
        return new ToolExposureFilter(FixedObjectProvider.of(properties), FixedObjectProvider.of(catalog));
    }

    @SuppressWarnings("unchecked")
    private List<String> keptNames(ToolExposureProperties properties) {
        Object result = filterWith(properties).postProcessAfterInitialization(allSpecs, SPEC_BEAN_NAME);
        return ((List<SyncToolSpecification>) result).stream().map(s -> s.tool().name()).toList();
    }

    @Test
    void returnsListUnchangedWhenNoFilterConfigured() {
        Object result = filterWith(NO_FILTER)
                .postProcessAfterInitialization(allSpecs, SPEC_BEAN_NAME);

        assertThat(result).isSameAs(allSpecs);
    }

    @Test
    void keepsOnlyConfiguredGroups() {
        assertThat(keptNames(props(Set.of("query-like"), Set.of(), Set.of())))
                .containsExactly("executeSample");
    }

    @Test
    void includeAddsToolsOutsideTheConfiguredGroups() {
        assertThat(keptNames(props(
                Set.of("query-like"), Set.of("checkSampleHealth"), Set.of())))
                .containsExactly("executeSample", "checkSampleHealth");
    }

    @Test
    void excludeWinsOverGroupsAndInclude() {
        assertThat(keptNames(props(
                Set.of("query-like", "health-like"),
                Set.of("flashbackSample"),
                Set.of("flashbackSample", "untemplatedSample"))))
                .containsExactly("executeSample", "checkSampleHealth");
    }

    @Test
    void excludeAloneKeepsEverythingElse() {
        assertThat(keptNames(props(Set.of(), Set.of(), Set.of("flashbackSample"))))
                .containsExactly("executeSample", "checkSampleHealth", "untemplatedSample");
    }

    @Test
    void failsFastOnUnknownToolName() {
        assertThatThrownBy(() -> keptNames(
                props(Set.of(), Set.of(), Set.of("getEtlJobStatus"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown tool names")
                .hasMessageContaining("getEtlJobStatus");
    }

    @Test
    void failsFastOnUnknownGroup() {
        assertThatThrownBy(() -> keptNames(
                props(Set.of("no-such-group"), Set.of(), Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown groups");
    }

    @Test
    void failsFastWhenEverythingWouldBeFilteredOut() {
        assertThatThrownBy(() -> keptNames(props(
                Set.of(), Set.of(),
                Set.of("executeSample", "checkSampleHealth", "flashbackSample", "untemplatedSample"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filtered out every tool");
    }

    @Test
    void failsFastOnUnknownPlane() {
        assertThatThrownBy(() -> keptNames(
                new ToolExposureProperties("edge", Set.of(), Set.of(), Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entropy.mcp.tools.plane");
    }

    /** {@code untemplatedSample} 没有标签行，按 fail-closed 规则不属于数据面。 */
    @Test
    void dataPlaneKeepsOnlyReadOnlyTools() {
        assertThat(keptNames(new ToolExposureProperties("data", Set.of(), Set.of(), Set.of())))
                .containsExactly("executeSample", "checkSampleHealth", "flashbackSample");
    }

    @Test
    void controlPlaneIsTheComplementOfTheDataPlane() {
        assertThat(keptNames(new ToolExposureProperties("control", Set.of(), Set.of(), Set.of())))
                .containsExactly("untemplatedSample");
    }

    /** plane 是暴露面收敛手段而非安全边界，{@code include} 必须能穿透它。 */
    @Test
    void includePunchesThroughThePlane() {
        assertThat(keptNames(new ToolExposureProperties(
                "data", Set.of(), Set.of("untemplatedSample"), Set.of())))
                .containsExactly(
                        "executeSample", "checkSampleHealth", "flashbackSample", "untemplatedSample");
    }

    @Test
    void excludeStillWinsOverThePlane() {
        assertThat(keptNames(new ToolExposureProperties(
                "data", Set.of(), Set.of(), Set.of("flashbackSample"))))
                .containsExactly("executeSample", "checkSampleHealth");
    }

    @Test
    void ignoresBeansThatAreNotToolSpecificationLists() {
        ToolExposureFilter filter = filterWith(
                props(Set.of("query-like"), Set.of(), Set.of()));

        assertThat(filter.postProcessAfterInitialization(List.of("a", "b"), "otherList"))
                .isEqualTo(List.of("a", "b"));
        assertThat(filter.postProcessAfterInitialization(List.of(), "emptyList")).isEqualTo(List.of());
        assertThat(filter.postProcessAfterInitialization("not a list", "someBean")).isEqualTo("not a list");
    }

    /**
     * 端到端确认裁剪真的作用在交给 MCP server 的那份 spec 列表上。
     *
     * <p>单测能验证过滤逻辑，但验证不了"我们挂到了正确的 bean 上"——Spring AI 版本升级若改了
     * spec 列表的产生方式，只有这个用例会红。
     */
    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.security.enabled=false",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.tools.groups=query,schema",
        "entropy.mcp.tools.include=checkHealth",
        "entropy.mcp.tools.exclude=batchQuery"
    })
    class AppliedToTheRealRegistration {

        /**
         * 容器里存在多个 {@code List<SyncToolSpecification>} bean（注解扫描一份、
         * ToolCallback 转换一份，后者通常为空），全部合起来才是最终交给 server 的工具集，
         * 所以这里用 ObjectProvider 汇总而不是按 bean 名取。
         */
        @Autowired
        private ObjectProvider<List<SyncToolSpecification>> specBeans;

        @Autowired
        private ToolCatalog catalog;

        @Autowired
        private ToolExposureProperties boundProperties;

        @Test
        void bindsConfiguredGroupsIncludeAndExclude() {
            assertThat(boundProperties.groups()).containsExactlyInAnyOrder("query", "schema");
            assertThat(boundProperties.include()).containsExactly("checkHealth");
            assertThat(boundProperties.exclude()).containsExactly("batchQuery");
        }

        @Test
        void registersOnlyConfiguredGroupsPlusIncludes() {
            List<String> names = specBeans.orderedStream()
                    .flatMap(List::stream)
                    .map(spec -> spec.tool().name())
                    .toList();

            assertThat(names).contains("executeQuery", "listTables", "describeTable", "checkHealth");
            assertThat(names).doesNotContain("batchQuery", "flashbackQuery", "createDbLink");
            assertThat(names).allSatisfy(name -> assertThat(
                    Set.of("query", "schema").contains(catalog.groupOf(name)) || "checkHealth".equals(name))
                    .isTrue());
            assertThat(names.size()).isLessThan(catalog.size());
        }
    }

    /**
     * 对着真实的 116 个工具验证按标签推导的数据面。
     *
     * <p>单测用的假工具只有 4 个，证明不了"真实描述里的标签行足以支撑切分"——这个用例才是
     * {@code plane=data} 敢用在生产上的依据。
     */
    @Nested
    @SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.security.enabled=false",
        "entropy.mcp.gateway.enabled=false",
        "entropy.mcp.tools.plane=data"
    })
    class DataPlaneOnTheRealCatalog {

        private static final Set<String> MUTATING_TAGS =
                Set.of("write", "ddl", "destructive", "admin");

        @Autowired
        private ObjectProvider<List<SyncToolSpecification>> specBeans;

        @Autowired
        private ToolCatalog catalog;

        private List<String> registeredNames() {
            return specBeans.orderedStream()
                    .flatMap(List::stream)
                    .map(spec -> spec.tool().name())
                    .toList();
        }

        @Test
        void everyRegisteredToolProvesItselfReadOnly() {
            assertThat(registeredNames()).allSatisfy(name -> {
                List<String> tags = catalog.describe(name).tags();
                assertThat(tags).as("%s 缺少 read 标签却进了数据面", name).contains("read");
                assertThat(tags).as("%s 带有改动状态的标签却进了数据面", name)
                        .doesNotContainAnyElementsOf(MUTATING_TAGS);
            });
        }

        @Test
        void dropsTheWriteSideTools() {
            assertThat(registeredNames())
                    .contains("executeQuery", "listTables", "describeTable", "checkHealth")
                    .doesNotContain("executeDdl", "insertData", "upsertData", "createDbLink",
                            "submitEtlJob", "backupTable", "killSession");
        }

        /** 切分必须真的切掉了一部分，否则这个开关等于没生效。 */
        @Test
        void narrowsTheExposedSurface() {
            assertThat(registeredNames().size())
                    .isGreaterThan(catalog.size() / 2)
                    .isLessThan(catalog.size());
        }
    }
}
