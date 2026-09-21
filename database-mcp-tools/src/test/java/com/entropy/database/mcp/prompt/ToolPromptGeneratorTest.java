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
package com.entropy.database.mcp.prompt;

import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.prompt.ToolPromptGenerator.PromptFormat;
import com.entropy.database.mcp.prompt.ToolPromptGenerator.ToolPrompt;
import com.entropy.database.mcp.toggle.ToolToggleRepository;
import com.entropy.database.mcp.tools.FixedObjectProvider;
import com.entropy.database.mcp.tools.McpToolBase;
import com.entropy.database.mcp.tools.ToolCatalog;
import com.entropy.database.mcp.tools.ToolToggleRegistry;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 提示词生成器：清单口径、两种格式的关键段落、约束段落逐条取自服务端状态。
 *
 * <h2>这里最值得测的是"清单里不该有什么"</h2>
 * <p>一个被停用或被部署期裁掉的工具出现在提示词里，就是让模型去调一个不存在的工具——它换回来的是
 * "工具不存在"，和参数写错完全一样的错误，于是它会改一改再试。所以每条清单相关的用例都同时断言
 * 正面（该在的在）与反面（不该在的不在）。
 *
 * <h2>夹具形状</h2>
 * <p>暴露集 3 个，目录里 2 个：
 * <ul>
 *   <li>{@code executeSample}（分组 {@code sample-query}）、{@code adminSample}
 *       （分组 {@code sample-admin}）：目录里有，描述合模板；</li>
 *   <li>{@code extensionSample}：已暴露但<b>不在</b>目录里（目录只覆盖 {@code McpToolBase} 子类），
 *       扮演扩展注册进来的工具——它照样得出现在提示词里，否则 {@code toolCount} 和
 *       {@code remainingExposed()} 会对不上，而运维会以为这台服务器少了一个工具。</li>
 * </ul>
 */
class ToolPromptGeneratorTest {

    /** 分组名应推导为 {@code sample-query}。描述严格按仓库的中文模板写，用来验证原文整段进提示词。 */
    static class SampleQueryTools extends McpToolBase {

        /*
         * 描述里刻意<b>不提</b> adminSample 这个名字。本类有多条用例断言"被停用的工具名一个字都不许
         * 出现在提示词里"，而真实工具的描述里到处是「不要用于：……（用 xxx）」的交叉引用——夹具里
         * 只要有一条这样的引用，那些反面断言就会因为一句无关的文案而红，而它红的原因和被测行为无关。
         */
        @McpTool(description = """
                【执行示例查询】在示例连接上执行一条只读查询并返回结果行。
                前置条件：连接已用 createNamedConnection 注册。
                使用场景：只需要读一批行时。
                返回字段：rows、rowCount。
                不要用于：任何写入操作。
                标签：[read, query]
                """)
        public Map<String, Object> executeSample() {
            return success("rows", List.of());
        }
    }

    /** 分组名应推导为 {@code sample-admin}。 */
    static class SampleAdminTools extends McpToolBase {

        @McpTool(description = """
                【示例管理】改一改示例的服务端状态。
                前置条件：无。
                标签：[write, admin]
                """)
        public Map<String, Object> adminSample() {
            return success("ok", true);
        }
    }

    /** 描述里的一整句，用来断言进提示词的是<b>原文</b>而不是 160 字摘要。 */
    private static final String PRECONDITION_LINE = "前置条件：连接已用 createNamedConnection 注册。";

    private static final String EXTENSION_TOOL = "extensionSample";

    private final McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);

    private final ToolCatalog catalog = new ToolCatalog(
            FixedObjectProvider.of(new SampleQueryTools(), new SampleAdminTools()));

    private final DynamicDataSourceManager dataSources = mock(DynamicDataSourceManager.class);

    /** 三个开关都不设：本仓库的默认部署下 ddl.allowed=false、authz.enabled=false。 */
    private final MockEnvironment environment = new MockEnvironment();

    private ToolToggleRegistry toggles;
    private ToolPromptGenerator generator;

    @BeforeEach
    void setUp() {
        this.toggles = registry("executeSample", "adminSample", EXTENSION_TOOL);
        this.generator = new ToolPromptGenerator(catalog, toggles, dataSources, environment);
    }

    /** {@code repository} 不存在 = 没配 {@code spring.datasource.url}（内存模式），与本类无关。 */
    private ToolToggleRegistry registry(String... exposedToolNames) {
        ToolToggleRegistry registry = new ToolToggleRegistry(
                FixedObjectProvider.of(server),
                FixedObjectProvider.of(catalog),
                FixedObjectProvider.<ToolToggleRepository>of());
        for (String name : exposedToolNames) {
            registry.registerExposed(List.of(spec(name)));
        }
        return registry;
    }

    private static SyncToolSpecification spec(String name) {
        return new SyncToolSpecification(
                McpSchema.Tool.builder().name(name).description("d").build(),
                (context, request) -> null);
    }

    private String textOf(PromptFormat format) {
        return generator.generate(format).text();
    }

    // ─── 清单口径 ──────────────────────────────────────────────────────────

    /**
     * 只列此刻真正在 {@code tools/list} 里的工具，两种格式都一样。
     *
     * <p>反面断言（停用的工具名一个字都不许出现）比正面重要：写出来就是让模型去调它。
     */
    @Test
    void listsOnlyToolsThatAreStillInToolsList() {
        toggles.setDisabled("adminSample", true);

        for (PromptFormat format : PromptFormat.values()) {
            ToolPrompt prompt = generator.generate(format);
            assertThat(prompt.toolCount())
                    .as("%s：工具数必须等于 tools/list 的长度", format)
                    .isEqualTo(toggles.remainingExposed())
                    .isEqualTo(2);
            assertThat(prompt.text())
                    .as("%s：仍然启用的工具必须在", format)
                    .contains("executeSample", EXTENSION_TOOL);
            assertThat(prompt.text())
                    .as("%s：被停用的工具绝不能出现——写出来就是让模型去调一个不存在的工具", format)
                    .doesNotContain("adminSample");
        }
    }

    /**
     * 扩展注册的工具（目录里没有元数据）照样列出来，只是描述位置写一句说明。
     *
     * <p>漏掉它会让 {@code toolCount} 与 {@code remainingExposed()} 对不上，
     * 也会让提示词声称这台服务器没有一个真的能调到的工具。
     */
    @Test
    void includesToolsThatHaveNoCatalogMetadata() {
        String text = textOf(PromptFormat.LIST);

        assertThat(text).contains("### " + EXTENSION_TOOL);
        assertThat(text).contains(ToolPromptGenerator.NO_DESCRIPTION_TEXT);
        assertThat(text).contains("## " + ToolPromptGenerator.UNGROUPED);
    }

    /** 一个工具都没有时不抛，而是返回一段说明——运行期 kill switch 把全部工具停掉是合法动作。 */
    @Test
    void doesNotThrowWhenThereIsNoToolAtAll() {
        ToolPromptGenerator empty = new ToolPromptGenerator(
                catalog, registry(), dataSources, environment);

        for (PromptFormat format : PromptFormat.values()) {
            ToolPrompt prompt = empty.generate(format);
            assertThat(prompt.toolCount()).as("%s", format).isZero();
            assertThat(prompt.text()).as("%s", format).contains(ToolPromptGenerator.NO_TOOLS_TEXT);
        }
    }

    /**
     * 分组顺序稳定：分组名按字典序，未分组那一节落在最后。
     *
     * <p>同一份服务端状态下生成两次得到不同文本的话，运维就没法拿 diff 判断"清单变了没有"。
     */
    @Test
    void groupOrderIsStable() {
        String text = textOf(PromptFormat.LIST);

        assertThat(text.indexOf("## sample-admin"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(text.indexOf("## sample-query"));
        assertThat(text.indexOf("## sample-query"))
                .isLessThan(text.indexOf("## " + ToolPromptGenerator.UNGROUPED));
        assertThat(textOf(PromptFormat.LIST))
                .as("同一份状态下重新生成必须逐字节相同")
                .isEqualTo(text);
    }

    // ─── 两种格式 ──────────────────────────────────────────────────────────

    /** {@code list} 只有清单段落：分组标题、工具标题、完整描述原文、标签行。 */
    @Test
    void listFormatIsOnlyTheToolCatalogSection() {
        String text = textOf(PromptFormat.LIST);

        assertThat(text).contains("## sample-query");
        assertThat(text).contains("### executeSample");
        assertThat(text)
                .as("进提示词的必须是 @McpTool 描述原文，不是 ToolCatalog 那个 160 字摘要")
                .contains(PRECONDITION_LINE)
                .contains("不要用于：任何写入操作。");
        assertThat(text).contains("标签：read, query");
        assertThat(text)
                .as("list 格式刻意不带系统提示词的那几段")
                .doesNotContain("## 当前生效的约束", "## 这份提示词是一份快照");
    }

    /** {@code system} 四段全在，而且第三段就是 {@code list} 的内容。 */
    @Test
    void systemFormatCarriesAllFourSections() {
        String text = textOf(PromptFormat.SYSTEM);

        assertThat(text).contains("## 怎么干活");
        assertThat(text).contains("## 当前生效的约束");
        assertThat(text).contains("## 可用工具（3 个）");
        assertThat(text).contains("## 这份提示词是一份快照");
        assertThat(text)
                .as("工具清单段落两种格式共用同一段代码，system 里必须原样包含它")
                .contains(textOf(PromptFormat.LIST));
    }

    /** {@code generatedAt} 同时出现在返回体与正文里，两处必须是同一个瞬间。 */
    @Test
    void theGeneratedInstantAppearsInTheTextItself() {
        ToolPrompt prompt = generator.generate(PromptFormat.SYSTEM);

        assertThat(prompt.text()).contains(prompt.generatedAt().toString());
        assertThat(prompt.format()).isEqualTo("system");
    }

    // ─── 约束段落：逐条来自服务端状态 ──────────────────────────────────────

    /** DDL 关着（默认）时那句警告必须在——模型看不到服务端配置，只能从提示词里知道试了也白试。 */
    @Test
    void systemFormatWarnsWhenDdlIsDisabled() {
        assertThat(textOf(PromptFormat.SYSTEM))
                .contains(ToolPromptGenerator.DDL_DISABLED_WARNING)
                .contains("entropy.mcp.database.ddl.allowed=false");
    }

    /** 反面：DDL 开着时那句警告不许出现，否则模型会拒绝去做一件它其实能做的事。 */
    @Test
    void systemFormatDropsTheDdlWarningWhenDdlIsAllowed() {
        environment.setProperty("entropy.mcp.database.ddl.allowed", "true");

        assertThat(textOf(PromptFormat.SYSTEM))
                .doesNotContain(ToolPromptGenerator.DDL_DISABLED_WARNING)
                .contains("entropy.mcp.database.ddl.allowed=true");
    }

    /** gateway 关着时要写明 ETL 工具根本没注册，而不是"暂时不可用"。 */
    @Test
    void systemFormatReportsTheGatewaySwitch() {
        assertThat(textOf(PromptFormat.SYSTEM))
                .contains("entropy.mcp.gateway.enabled=false")
                .contains("ETL 相关工具在这台服务器上根本没有注册");

        environment.setProperty("entropy.mcp.gateway.enabled", "true");
        assertThat(textOf(PromptFormat.SYSTEM)).contains("entropy.mcp.gateway.enabled=true");
    }

    @Test
    void systemFormatReportsTheAuthzSwitch() {
        assertThat(textOf(PromptFormat.SYSTEM)).contains("entropy.mcp.authz.enabled=false");

        environment.setProperty("entropy.mcp.authz.enabled", "true");
        assertThat(textOf(PromptFormat.SYSTEM)).contains("entropy.mcp.authz.enabled=true");
    }

    /** 连接名与 readonly 标记逐条取自注册表，不是写死的。 */
    @Test
    void systemFormatListsRegisteredConnectionsWithTheirReadonlyFlag() {
        when(dataSources.listConnectionKeys()).thenReturn(List.of("reporting", "staging"));
        when(dataSources.isReadonly("reporting")).thenReturn(true);
        when(dataSources.isReadonly("staging")).thenReturn(false);

        assertThat(textOf(PromptFormat.SYSTEM))
                .contains("reporting（只读）")
                .contains("staging（可写）");
    }

    /** 一条连接都没有时说清后果：任何需要 connection 的工具现在都会失败。 */
    @Test
    void systemFormatSaysSoWhenThereIsNoConnection() {
        assertThat(textOf(PromptFormat.SYSTEM))
                .contains("一条都没有")
                .contains("createNamedConnection");
    }

    /**
     * 被停用的工具<b>只报数量，不报名字</b>。
     *
     * <p>名字有害：那些工具已经不在清单里了，写出来只会让模型去试一个调不到的工具。
     */
    @Test
    void systemFormatReportsTheDisabledCountButNotTheNames() {
        toggles.setDisabled("adminSample", true);

        String text = textOf(PromptFormat.SYSTEM);
        assertThat(text).contains("有 1 个工具被管理员在运行期停用");
        assertThat(text).doesNotContain("adminSample");
    }

    @Test
    void systemFormatSaysSoWhenNothingIsDisabled() {
        assertThat(textOf(PromptFormat.SYSTEM)).contains("没有工具被运行期停用");
    }

    // ─── format 解析 ───────────────────────────────────────────────────────

    @Test
    void formatParsingDefaultsToSystemAndIsCaseInsensitive() {
        assertThat(PromptFormat.parse(null)).isEqualTo(PromptFormat.SYSTEM);
        assertThat(PromptFormat.parse("")).isEqualTo(PromptFormat.SYSTEM);
        assertThat(PromptFormat.parse("  ")).isEqualTo(PromptFormat.SYSTEM);
        assertThat(PromptFormat.parse(" LIST ")).isEqualTo(PromptFormat.LIST);
        assertThat(PromptFormat.parse("system")).isEqualTo(PromptFormat.SYSTEM);
    }

    /** 非法取值的消息里必须列出可选值：这个仓库对"写错了"的一贯处理。 */
    @Test
    void unknownFormatIsRejectedWithTheValidValuesInTheMessage() {
        assertThatThrownBy(() -> PromptFormat.parse("markdown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("markdown")
                .hasMessageContaining("list")
                .hasMessageContaining("system");
    }
}
