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
package com.entropy.database.mcp.controller;

import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.prompt.ToolPromptGenerator;
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
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/tools} 的 HTTP 契约：返回体字段、幂等语义，以及四种"入参不对"到 400 的翻译
 * （缺 {@code disabled}、未知工具名、未知分组、未知 {@code format}）。
 *
 * <p>{@code GET /api/tools/prompt} 在这里只验 HTTP 契约（字段齐全、缺省值、非法值到 400）；
 * 文本内容本身在 {@code ToolPromptGeneratorTest} 里钉——那边不必起 MockMvc。
 *
 * <h2>为什么是 standaloneSetup 而不是 @SpringBootTest</h2>
 * <p>本模块没有 {@code @SpringBootConfiguration}（它在 app 模块），而这里要钉的是控制器自己的
 * 返回体与状态码，用不着整个应用上下文。上下文真的起得来那一条另有其人：
 * {@code WebUiTest.ToolToggleAdminApi}（那里才有 autoconfig 建出来的 {@code McpStatelessSyncServer}）。
 *
 * <h2>夹具形状</h2>
 * <p>目录里 3 个工具，暴露集 3 个，但两者不是同一批：
 * <ul>
 *   <li>{@code countSamples}/{@code listSamples}：目录里有、也已暴露，开关动得了；</li>
 *   <li>{@code guardedSample}：目录里有、<b>未</b>暴露，扮演被 {@code entropy.mcp.tools} 裁掉的工具；</li>
 *   <li>{@code extensionSample}：已暴露但<b>不在</b>目录里（目录只覆盖 {@code McpToolBase} 子类），
 *       扮演扩展注册进来的工具——它必须照样出现在清单里，否则 {@code exposed} 这个计数和
 *       {@code tools} 列表会对不上。</li>
 * </ul>
 */
class ToolAdminControllerTest {

    /** 分组名应推导为 {@code query-sample}。 */
    static class QuerySampleTools extends McpToolBase {

        @McpTool(description = """
                【列出示例】列出示例对象。
                标签：[read, query]
                """)
        public Map<String, Object> listSamples() {
            return success("rows", List.of());
        }

        @McpTool(description = """
                【统计示例】返回示例对象的数量。
                标签：[read, query]
                """)
        public Map<String, Object> countSamples() {
            return success("count", 0);
        }
    }

    /** 分组名应推导为 {@code guarded-sample}；这个工具刻意不进暴露集。 */
    static class GuardedSampleTools extends McpToolBase {

        @McpTool(description = """
                【受控示例】一个被部署期配置裁掉的工具。
                标签：[write, admin]
                """)
        public Map<String, Object> guardedSample() {
            return success("ok", true);
        }
    }

    private final McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);

    private final ToolCatalog catalog = new ToolCatalog(
            FixedObjectProvider.of(new QuerySampleTools(), new GuardedSampleTools()));

    /** 提示词只用到连接名与 readonly 标记；本类不测文本内容（那在 {@code ToolPromptGeneratorTest}）。 */
    private final DynamicDataSourceManager dataSources = mock(DynamicDataSourceManager.class);

    /** 三个开关都不设 = 本仓库默认部署下的生效值。 */
    private final MockEnvironment environment = new MockEnvironment();

    /** 顺序要稳定、值都非空，所以是 LinkedHashMap 而不是 {@code Map.of}。 */
    private final Map<String, SyncToolSpecification> exposedSpecs = new LinkedHashMap<>();

    private ToolToggleRegistry toggles;
    private ToolAdminController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        for (String name : List.of("listSamples", "countSamples", "extensionSample")) {
            exposedSpecs.put(name, spec(name));
        }
        this.toggles = registryWith(null);
        this.controller = new ToolAdminController(toggles, catalog, generator(toggles));
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private ToolPromptGenerator generator(ToolToggleRegistry registry) {
        return new ToolPromptGenerator(catalog, registry, dataSources, environment);
    }

    private static SyncToolSpecification spec(String name) {
        return new SyncToolSpecification(
                McpSchema.Tool.builder().name(name).description("d").build(),
                (context, request) -> null);
    }

    /** {@code repository} 为 {@code null} 表示没配 {@code spring.datasource.url}（内存模式）。 */
    private ToolToggleRegistry registryWith(ToolToggleRepository repository) {
        ToolToggleRegistry registry = new ToolToggleRegistry(
                FixedObjectProvider.of(server),
                FixedObjectProvider.of(catalog),
                repository == null
                        ? FixedObjectProvider.<ToolToggleRepository>of()
                        : FixedObjectProvider.of(repository));
        registry.registerExposed(exposedSpecs.values());
        return registry;
    }

    private static String body(boolean disabled) {
        return "{\"disabled\": " + disabled + "}";
    }

    // ─── 清单 ──────────────────────────────────────────────────────────────

    /**
     * 清单的每个字段都必须在：少一个的表现是页面上少一列或少一行，没有任何报错。
     *
     * <p>键集合用直接调用断言（{@code containsExactly} 连顺序一起钉住），状态码与序列化后的值用
     * HTTP 断言——两者缺一都验不全：直接调用看不到 JSON，jsonPath 又看不出 {@code group} 这种
     * 值为 {@code null} 的键到底在不在返回体里。
     */
    @Test
    @SuppressWarnings("unchecked")
    void listExposesEveryFieldTheToolsPageNeeds() throws Exception {
        Map<String, Object> listed = controller.list();

        assertThat(listed.keySet())
                .as("字段顺序也钉住：返回体是 LinkedHashMap 组装的，顺序稳定是刻意的")
                .containsExactly("total", "exposed", "disabledCount", "persisted", "tools");
        assertThat(listed)
                .containsEntry("total", 4)
                .containsEntry("exposed", 3)
                .containsEntry("disabledCount", 0)
                .containsEntry("persisted", false);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) listed.get("tools");
        assertThat(tools).allSatisfy(tool -> assertThat(tool).containsOnlyKeys(
                "name", "group", "summary", "tags", "exposed", "disabled"));
        // 目录里的三个按名字排序在前，快照里那个目录没有的追加在后
        assertThat(tools).extracting(tool -> tool.get("name")).containsExactly(
                "countSamples", "guardedSample", "listSamples", "extensionSample");
        assertThat(tools.get(3).get("group"))
                .as("扩展注册的工具没有分组，这个键必须以 null 出现而不是消失")
                .isNull();

        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.exposed").value(3))
                .andExpect(jsonPath("$.disabledCount").value(0))
                .andExpect(jsonPath("$.tools[2].name").value("listSamples"))
                .andExpect(jsonPath("$.tools[2].group").value("query-sample"))
                .andExpect(jsonPath("$.tools[2].exposed").value(true))
                .andExpect(jsonPath("$.tools[2].disabled").value(false))
                .andExpect(jsonPath("$.tools[1].name").value("guardedSample"))
                .andExpect(jsonPath("$.tools[1].exposed")
                        .value(false));
    }

    @Test
    void listReflectsTheCurrentDisabledSet() throws Exception {
        toggles.setDisabled("listSamples", true);

        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabledCount").value(1))
                .andExpect(jsonPath("$.exposed")
                        .value(3))
                .andExpect(jsonPath("$.tools[2].name").value("listSamples"))
                .andExpect(jsonPath("$.tools[2].disabled").value(true));
    }

    // ─── 单个工具 ──────────────────────────────────────────────────────────

    @Test
    void disablingASingleToolReportsChangedAndRemainingExposed() throws Exception {
        mockMvc.perform(put("/api/tools/listSamples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("listSamples"))
                .andExpect(jsonPath("$.disabled").value(true))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.remainingExposed").value(2));

        assertThat(toggles.disabledToolNames()).containsExactly("listSamples");
    }

    /** 幂等不是错误：重复停用是 200 + {@code changed=false}，不该是 4xx。 */
    @Test
    void disablingAnAlreadyDisabledToolStaysOkWithChangedFalse() throws Exception {
        toggles.setDisabled("listSamples", true);

        mockMvc.perform(put("/api/tools/listSamples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.remainingExposed").value(2));
    }

    @Test
    void enablingAToolPutsItBackIntoTheCount() throws Exception {
        toggles.setDisabled("listSamples", true);

        mockMvc.perform(put("/api/tools/listSamples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled").value(false))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.remainingExposed").value(3));
    }

    /**
     * 缺 {@code disabled} 字段是 400，不是"当成 false"。
     *
     * <p>请求体是 {@code Boolean} 而不是 {@code boolean} 就是为了这条：基本类型会把 {@code {}}
     * 静默读成"停用=false"，也就是把一个写错的请求执行成一次真实的启用操作。
     */
    @Test
    void missingDisabledFlagIsRejected() throws Exception {
        mockMvc.perform(put("/api/tools/listSamples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ─── 分组批量 ──────────────────────────────────────────────────────────

    /**
     * {@code affected} 是这条命令作用到的工具（该分组下全部已暴露的工具），{@code changed} 是其中
     * 状态真的变了的个数——两者不同是正常的，所以两个字段都要出现在返回体里。
     */
    @Test
    void togglingAGroupReportsAffectedAndChanged() throws Exception {
        mockMvc.perform(put("/api/tools/groups/query-sample")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group").value("query-sample"))
                .andExpect(jsonPath("$.affected.length()").value(2))
                .andExpect(jsonPath("$.affected[0]").value("countSamples"))
                .andExpect(jsonPath("$.affected[1]").value("listSamples"))
                .andExpect(jsonPath("$.changed").value(2))
                .andExpect(jsonPath("$.remainingExposed").value(1));

        assertThat(toggles.disabledToolNames()).containsExactly("countSamples", "listSamples");
    }

    // ─── 入参不对：400 而不是 500 ──────────────────────────────────────────

    /** 未知工具名是调用方的错，500 会让它以为是服务端故障而重试，重试一万次结果相同。 */
    @Test
    void unknownToolNameIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(put("/api/tools/noSuchTool")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownGroupIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(put("/api/tools/groups/no-such-group")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true)))
                .andExpect(status().isBadRequest());
    }

    /**
     * 被部署期配置裁掉的工具名同样是 400——刻意不允许运行期把它加回来。
     *
     * <p>它在 {@code GET /api/tools} 里以 {@code exposed=false} 列出来，为的是让运维看到
     * "这台服务器上还有这个工具，但本接口动不了它"；真去动它就得到 400，而不是一次静默成功。
     * 拒绝的理由与语义在 {@code ToolToggleRegistryTest} 那一侧钉住，这里钉的是它别变成 500。
     */
    @Test
    void aToolFilteredOutAtDeploymentTimeIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(put("/api/tools/guardedSample")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false)))
                .andExpect(status().isBadRequest());
    }

    // ─── 提示词 ────────────────────────────────────────────────────────────

    /**
     * 返回体四个字段齐全、顺序稳定，{@code toolCount} 与 {@code tools/list} 的长度一致。
     *
     * <p>文本内容本身在 {@code ToolPromptGeneratorTest} 里钉；这里只钉 HTTP 契约。
     * 键集合用直接调用断言（{@code containsExactly} 连顺序一起钉），值用 jsonPath——
     * 两者缺一都验不全，理由同 {@link #listExposesEveryFieldTheToolsPageNeeds}。
     */
    @Test
    void promptExposesEveryFieldAndCountsOnlyListedTools() throws Exception {
        assertThat(controller.prompt("system").keySet())
                .containsExactly("format", "generatedAt", "toolCount", "text");

        mockMvc.perform(get("/api/tools/prompt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("system"))
                .andExpect(jsonPath("$.generatedAt").isString())
                // 暴露集 3 个、没有被停用的：被部署期裁掉的 guardedSample 不算在内
                .andExpect(jsonPath("$.toolCount").value(3))
                .andExpect(jsonPath("$.text").isString());
    }

    /** {@code format} 缺省是 {@code system}，空串也走缺省（{@code ?format=} 语义上是"没传"）。 */
    @Test
    void promptFormatDefaultsToSystem() throws Exception {
        mockMvc.perform(get("/api/tools/prompt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("system"));
        mockMvc.perform(get("/api/tools/prompt").param("format", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("system"));
    }

    @Test
    void promptHonoursTheListFormat() throws Exception {
        mockMvc.perform(get("/api/tools/prompt").param("format", "list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("list"));
    }

    /**
     * 非法 {@code format} 是 400 而不是 500，消息里带可选值。
     *
     * <p>500 会让调用方以为是服务端故障而重试，而重试一万次结果相同——与本类里其它三种
     * "入参不对"的处理一致。
     */
    @Test
    void unknownPromptFormatIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(get("/api/tools/prompt").param("format", "markdown"))
                .andExpect(status().isBadRequest());
    }

    /** 停用之后提示词里的工具数必须跟着减：它的口径就是此刻的 {@code tools/list}。 */
    @Test
    void promptDropsToolsThatWereJustDisabled() throws Exception {
        toggles.setDisabled("listSamples", true);

        mockMvc.perform(get("/api/tools/prompt").param("format", "list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolCount").value(2));
    }

    // ─── 内存模式 ──────────────────────────────────────────────────────────

    /**
     * 没配库时接口<b>照常可用</b>，只是返回体里 {@code persisted=false}。
     *
     * <p>这是与 {@code UserAdminController} 刻意不同的一处：那边没有用户库就回 503，因为"没有身份库
     * 却要建用户"无从谈起；而这里，内存模式是受支持的部署形态（默认就是它），停用照样立刻生效，
     * 只是活不过重启。回 503 等于把一个正常形态报成故障，值班人员会以为是接口坏了而放弃止血。
     * {@code persisted} 这个字段则必须在：否则运维会以为停用能活过重启，而它不能。
     */
    @Test
    void worksWithoutAStateStoreAndSaysSoInTheBody() throws Exception {
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(false));

        mockMvc.perform(put("/api/tools/listSamples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.persisted").value(false));
    }

    /** 反面：配了库时同一个字段翻成 true，它是由"仓储 bean 在不在"驱动的。 */
    @Test
    void reportsPersistedWhenAStateStoreIsConfigured() throws Exception {
        ToolToggleRegistry persistent = registryWith(mock(ToolToggleRepository.class));
        MockMvc withStore = MockMvcBuilders
                .standaloneSetup(new ToolAdminController(persistent, catalog, generator(persistent)))
                .build();

        withStore.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(true));
        withStore.perform(put("/api/tools/listSamples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(true));
    }
}
