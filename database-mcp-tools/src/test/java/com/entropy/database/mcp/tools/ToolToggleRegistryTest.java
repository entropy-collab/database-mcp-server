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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.entropy.database.mcp.toggle.ToolToggleRepository;
import com.entropy.database.mcp.tools.SampleTools.HealthLikeTools;
import com.entropy.database.mcp.tools.SampleTools.QueryLikeTools;
import com.entropy.database.mcp.tools.SampleTools.SampleDispatchTool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 运行期启用/停用 MCP 工具：停用必须真的让工具从 {@code tools/list} 消失，也就是真的
 * {@code removeTool}，而启用必须把<b>启动期那个 spec 实例</b>放回去。
 *
 * <h2>夹具形状</h2>
 * <p>目录里有 5 个工具（{@link SampleTools} 的三个类），但只有 3 个进暴露集快照：
 * <ul>
 *   <li>{@code executeSample}（query-like）、{@code checkSampleHealth} 与 {@code flashbackSample}
 *       （health-like）是「已暴露」的，运行期开关动得了它们；</li>
 *   <li>{@code untemplatedSample}（health-like）在目录里但<b>不</b>在快照里，扮演「被
 *       {@code entropy.mcp.tools} 裁掉的工具」；</li>
 *   <li>{@code dispatchSample}（sample-dispatch）同理，用来构造「分组存在但下面一个暴露的都没有」。</li>
 * </ul>
 * 这两种「在目录里、不在快照里」的形状是本类几条最要紧的断言的前提，见
 * {@link #refusesToEnableAToolThatDeploymentTimeConfigFilteredOut()}。
 */
class ToolToggleRegistryTest {

    private final McpStatelessSyncServer server = mock(McpStatelessSyncServer.class);

    private final ToolCatalog catalog = new ToolCatalog(FixedObjectProvider.of(
            new QueryLikeTools(), new HealthLikeTools(), new SampleDispatchTool()));

    /** 暴露集快照：顺序要稳定，值都非空，用 LinkedHashMap 而不是 {@code Map.of}。 */
    private final Map<String, SyncToolSpecification> exposedSpecs = new LinkedHashMap<>();

    /** WARN 是本功能好几条行为的唯一外部可见结果（kill switch、陈旧记录），所以直接抓日志。 */
    private ListAppender<ILoggingEvent> logs;

    private ch.qos.logback.classic.Logger registryLogger;

    @BeforeEach
    void setUp() {
        for (String name : List.of("executeSample", "checkSampleHealth", "flashbackSample")) {
            exposedSpecs.put(name, spec(name));
        }
        this.logs = new ListAppender<>();
        this.logs.start();
        this.registryLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ToolToggleRegistry.class);
        this.registryLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        registryLogger.detachAppender(logs);
        logs.stop();
        // currentActor 读 SecurityContextHolder，它是 ThreadLocal，surefire 复用线程
        SecurityContextHolder.clearContext();
    }

    private static SyncToolSpecification spec(String name) {
        // 开关只按名字索引，调用处理器在本测试里不会被触发；实例同一性才是被断言的东西
        return new SyncToolSpecification(
                McpSchema.Tool.builder().name(name).description("d").build(),
                (context, request) -> null);
    }

    /** 装好暴露集快照的注册表；{@code repository} 为 {@code null} 表示没配库（内存模式）。 */
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

    private ToolToggleRegistry registry() {
        return registryWith(null);
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ─── 单个工具 ──────────────────────────────────────────────────────────

    @Test
    void disablingAToolRemovesItFromTheMcpServer() {
        ToolToggleRegistry registry = registry();

        ToolToggleRegistry.ToggleResult result = registry.setDisabled("executeSample", true);

        verify(server).removeTool("executeSample");
        assertThat(registry.disabledToolNames()).containsExactly("executeSample");
        assertThat(registry.isDisabled("executeSample")).isTrue();
        assertThat(result.affected()).containsExactly("executeSample");
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.remainingExposed()).isEqualTo(2);
    }

    /**
     * 启用放回去的必须是启动期快照里<b>那一个</b> spec 实例。
     *
     * <p>只断言工具名相同是不够的：spec 里带着 inputSchema 与调用处理器，重建一个同名的 spec
     * 会得到一个「列在 tools/list 里但调用时行为不同」的工具，而那种坏法在 tools/list 上看不出来。
     */
    @Test
    void enablingAToolPutsBackTheExactSpecInstanceFromTheStartupSnapshot() {
        ToolToggleRegistry registry = registry();
        registry.setDisabled("flashbackSample", true);

        ToolToggleRegistry.ToggleResult result = registry.setDisabled("flashbackSample", false);

        ArgumentCaptor<SyncToolSpecification> restored =
                ArgumentCaptor.forClass(SyncToolSpecification.class);
        verify(server).addTool(restored.capture());
        assertThat(restored.getValue())
                .as("放回 server 的必须是启动期那个 spec 实例，不是一个同名的新对象")
                .isSameAs(exposedSpecs.get("flashbackSample"));
        assertThat(registry.disabledToolNames()).isEmpty();
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.remainingExposed()).isEqualTo(3);
    }

    /** 幂等：已停用的再停用不报错，但返回值要能让调用方看出「什么都没发生」。 */
    @Test
    void disablingAnAlreadyDisabledToolReportsNoChange() {
        ToolToggleRegistry registry = registry();
        registry.setDisabled("executeSample", true);

        ToolToggleRegistry.ToggleResult repeated = registry.setDisabled("executeSample", true);

        assertThat(repeated.changed()).isZero();
        assertThat(repeated.affected()).containsExactly("executeSample");
        assertThat(repeated.remainingExposed()).isEqualTo(2);
        // 第二次不该再碰 server：重复 removeTool 本身无害，但它意味着状态判断没生效
        verify(server).removeTool("executeSample");
        verifyNoMoreInteractions(server);
    }

    @Test
    void enablingAnAlreadyEnabledToolReportsNoChange() {
        ToolToggleRegistry registry = registry();

        ToolToggleRegistry.ToggleResult result = registry.setDisabled("executeSample", false);

        assertThat(result.changed()).isZero();
        assertThat(result.remainingExposed()).isEqualTo(3);
        verifyNoMoreInteractions(server);
    }

    /** 工具名拼错了就得改请求，所以错误信息里直接摆出可操作的工具名。 */
    @Test
    void rejectsAnUnknownToolNameAndListsTheOperableOnes() {
        ToolToggleRegistry registry = registry();

        assertThatThrownBy(() -> registry.setDisabled("noSuchTool", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知工具名")
                .hasMessageContaining("noSuchTool")
                .hasMessageContaining("executeSample");
        verifyNoMoreInteractions(server);
    }

    /**
     * 被 {@code entropy.mcp.tools} 裁掉的工具，运行期<b>不能</b>被启用回来。
     *
     * <p>这条钉的是一个刻意的取舍，不是实现限制：部署期的暴露策略是部署方的决定，允许值班人员
     * 用一次 HTTP 调用把一个被摘掉的工具加回来，等于这套配置形同虚设。所以这里除了断言被拒，
     * 还断言错误信息说清了「只能改配置重启」——否则运维只会以为是自己拼错了名字，反复重试。
     *
     * <p>{@code untemplatedSample} 在目录里（{@code catalog.contains} 为真）但不在快照里，
     * 正是这条分支与「未知工具名」那条分支的分界。
     */
    @Test
    void refusesToEnableAToolThatDeploymentTimeConfigFilteredOut() {
        ToolToggleRegistry registry = registry();
        assertThat(catalog.contains("untemplatedSample")).isTrue();
        assertThat(registry.isExposed("untemplatedSample")).isFalse();

        assertThatThrownBy(() -> registry.setDisabled("untemplatedSample", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("untemplatedSample")
                .hasMessageContaining("entropy.mcp.tools")
                .hasMessageContaining("改配置并重启");
        // 停用方向也一样拒：它本来就不在 tools/list 里，"停用"这个动作没有意义
        assertThatThrownBy(() -> registry.setDisabled("untemplatedSample", true))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(server);
    }

    // ─── 分组批量 ──────────────────────────────────────────────────────────

    /**
     * {@code affected} 是这条命令作用到的范围（该分组下全部已暴露的工具），不只是状态真的变了的那些。
     *
     * <p>{@code untemplatedSample} 同属 health-like 却不在 affected 里：分组操作也以暴露集为界。
     */
    @Test
    void disablingAGroupAffectsEveryExposedToolInThatGroup() {
        ToolToggleRegistry registry = registry();

        ToolToggleRegistry.ToggleResult result = registry.setGroupDisabled("health-like", true);

        assertThat(result.affected()).containsExactly("checkSampleHealth", "flashbackSample");
        assertThat(result.changed()).isEqualTo(2);
        assertThat(result.remainingExposed()).isEqualTo(1);
        verify(server).removeTool("checkSampleHealth");
        verify(server).removeTool("flashbackSample");
        assertThat(registry.disabledToolNames())
                .containsExactly("checkSampleHealth", "flashbackSample");
    }

    /** 重复执行同一条分组命令：affected 不变、changed 归零。 */
    @Test
    void repeatingAGroupCommandKeepsAffectedButZeroesChanged() {
        ToolToggleRegistry registry = registry();
        registry.setGroupDisabled("health-like", true);

        ToolToggleRegistry.ToggleResult repeated = registry.setGroupDisabled("health-like", true);

        assertThat(repeated.affected()).containsExactly("checkSampleHealth", "flashbackSample");
        assertThat(repeated.changed()).isZero();
    }

    @Test
    void enablingAGroupPutsEveryToolInItBack() {
        ToolToggleRegistry registry = registry();
        registry.setGroupDisabled("health-like", true);

        ToolToggleRegistry.ToggleResult result = registry.setGroupDisabled("health-like", false);

        assertThat(result.changed()).isEqualTo(2);
        assertThat(registry.disabledToolNames()).isEmpty();
        verify(server).addTool(exposedSpecs.get("checkSampleHealth"));
        verify(server).addTool(exposedSpecs.get("flashbackSample"));
    }

    @Test
    void rejectsAnUnknownGroup() {
        ToolToggleRegistry registry = registry();

        assertThatThrownBy(() -> registry.setGroupDisabled("no-such-group", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-group")
                .hasMessageContaining("可操作的分组");
        verifyNoMoreInteractions(server);
    }

    /**
     * 分组存在、但它下面一个已暴露的工具都没有时同样被拒。
     *
     * <p>成功执行一条"什么都没做"的命令，会让运维以为自己停掉了一批工具。
     */
    @Test
    void rejectsAGroupWithoutAnyExposedTool() {
        ToolToggleRegistry registry = registry();
        assertThat(catalog.groups()).contains("sample-dispatch");

        assertThatThrownBy(() -> registry.setGroupDisabled("sample-dispatch", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sample-dispatch");
        verifyNoMoreInteractions(server);
    }

    // ─── kill switch ───────────────────────────────────────────────────────

    /**
     * 把暴露集全部停掉是<b>允许</b>的，只打 WARN。
     *
     * <p>注意这与 {@link ToolExposureFilter} 的行为刻意相反：那边在配置把工具全裁光时直接
     * <b>启动失败</b>（见 {@code ToolExposureFilterTest.failsFastWhenEverythingWouldBeFilteredOut}）。
     * 两处不一致不是 bug——启动期全裁光是一次配置错误，运行期全停是值班人员按下的 kill switch，
     * 而按下它的人正需要它生效。谁把这条改成抛异常，就等于在最需要止血的时候挡住止血。
     */
    @Test
    void disablingEveryExposedToolIsAllowedAsARuntimeKillSwitch() {
        ToolToggleRegistry registry = registry();

        assertThatCode(() -> {
            registry.setDisabled("executeSample", true);
            registry.setGroupDisabled("health-like", true);
        }).doesNotThrowAnyException();

        assertThat(registry.remainingExposed()).isZero();
        assertThat(registry.disabledToolNames()).hasSize(3);
        // 暴露集本身没变，只是全被停了：放回来还是这三个
        assertThat(registry.exposedToolNames()).hasSize(3);
        assertThat(warnings())
                .as("全停必须留下一条 WARN：tools/list 空数组在客户端那边通常表现为'服务没接上'")
                .anySatisfy(message -> assertThat(message).contains("全部停用"));
    }

    // ─── 操作者 ────────────────────────────────────────────────────────────

    /**
     * 取不到身份时记 {@code unknown}，而不是拒绝操作。
     *
     * <p>{@code entropy.mcp.security.enabled=false} 是受支持的部署形态，那种部署下压根没有
     * principal。「因为拿不到操作者就不让停用工具」会在最需要止血的时候挡住值班人员。
     */
    @Test
    void actorFallsBackToUnknownWithoutASecurityContext() {
        SecurityContextHolder.clearContext();

        assertThat(ToolToggleRegistry.currentActor()).isEqualTo(ToolToggleRegistry.UNKNOWN_ACTOR);
        assertThat(ToolToggleRegistry.UNKNOWN_ACTOR).isEqualTo("unknown");
    }

    @Test
    void actorIsTheAuthenticatedPrincipalName() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("zhangsan", "n/a", List.of()));

        assertThat(ToolToggleRegistry.currentActor()).isEqualTo("zhangsan");
    }

    // ─── 落库与内存模式 ────────────────────────────────────────────────────

    @Test
    void reportsThatChangesAreNotPersistedWithoutARepository() {
        ToolToggleRegistry registry = registry();

        assertThat(registry.persistent()).isFalse();
        // 没有仓储也必须照常工作：内存模式是默认部署形态，不是故障
        assertThat(registry.setDisabled("executeSample", true).changed()).isEqualTo(1);
    }

    @Test
    void writesEveryRealChangeToTheRepository() {
        ToolToggleRepository store = mock(ToolToggleRepository.class);
        ToolToggleRegistry registry = registryWith(store);

        registry.setDisabled("executeSample", true);
        registry.setDisabled("executeSample", false);

        assertThat(registry.persistent()).isTrue();
        verify(store).save("executeSample", true, ToolToggleRegistry.UNKNOWN_ACTOR);
        verify(store).save("executeSample", false, ToolToggleRegistry.UNKNOWN_ACTOR);
    }

    /** 幂等操作不写库：一次"本来就是这个状态"的请求不该改写"谁停用的"这条线索。 */
    @Test
    void doesNotWriteToTheRepositoryWhenNothingChanged() {
        ToolToggleRepository store = mock(ToolToggleRepository.class);
        ToolToggleRegistry registry = registryWith(store);

        registry.setDisabled("executeSample", false);

        verify(store, never()).save(anyString(), anyBoolean(), anyString());
    }

    // ─── 启动回放 ──────────────────────────────────────────────────────────

    /**
     * 库里的陈旧工具名（当前暴露集里没有）：跳过、WARN、<b>不抛异常</b>，其余记录照常生效。
     *
     * <p>这条是刻意的容错：{@code entropy.mcp.tools} 收窄、或者版本升级删了某个工具，都会在
     * {@code mcp_tool_toggle} 里留下这样的记录。让一条陈旧记录把服务拦在启动阶段，是比
     * 「少停一个工具」严重得多的故障——前者整台服务不可用，后者只是一个工具还露着。
     * WARN 里带上表名是为了让运维知道去哪儿删那一行。
     */
    @Test
    void replaySkipsStaleToolNamesWithoutFailingStartup() {
        ToolToggleRepository store = mock(ToolToggleRepository.class);
        when(store.disabledToolNames())
                .thenReturn(new LinkedHashSet<>(List.of("executeSample", "toolFromAnOlderVersion")));
        ToolToggleRegistry registry = registryWith(store);

        assertThatCode(registry::replayPersistedToggles).doesNotThrowAnyException();

        assertThat(registry.disabledToolNames()).containsExactly("executeSample");
        verify(server).removeTool("executeSample");
        verify(server, never()).removeTool("toolFromAnOlderVersion");
        assertThat(warnings()).anySatisfy(message -> assertThat(message)
                .contains("toolFromAnOlderVersion")
                .contains(ToolToggleRepository.TABLE_NAME));
    }

    /**
     * 回放不写库。
     *
     * <p>实现里刻意不走 {@code disableOne}：那条路会把刚读出来的状态再写回去，等于每次启动都刷新
     * 一次 {@code updated_at}/{@code updated_by}，把「谁在什么时候停用的」这条唯一线索冲掉。
     * 真实数据库上的同一条断言见 {@link ReplayAgainstARealStateStore}。
     */
    @Test
    void replayDoesNotWriteBackToTheRepository() {
        ToolToggleRepository store = mock(ToolToggleRepository.class);
        when(store.disabledToolNames()).thenReturn(new LinkedHashSet<>(List.of("executeSample")));
        ToolToggleRegistry registry = registryWith(store);

        registry.replayPersistedToggles();

        verify(store, never()).save(anyString(), anyBoolean(), anyString());
    }

    /** 没配库时回放只是打那条"开关只活在内存里"的 WARN，不能抛。 */
    @Test
    void replayWithoutARepositoryOnlyWarns() {
        ToolToggleRegistry registry = registry();

        assertThatCode(registry::replayPersistedToggles).doesNotThrowAnyException();

        assertThat(registry.disabledToolNames()).isEmpty();
        assertThat(warnings()).anySatisfy(message -> assertThat(message).contains("内存"));
        verify(server, never()).removeTool(any());
    }

    /**
     * 回放跑在真实的 H2 上，为的是钉住「回放不改写 {@code updated_at}/{@code updated_by}」。
     *
     * <p>用 mock 只能证明 {@code save} 没被调用；这里证明的是那两列的值真的一个字节都没变——
     * 哪天有人把回放改成走 {@code disableOne}，mock 那条会红，而这条会红得更直白。
     */
    @Nested
    class ReplayAgainstARealStateStore {

        private DataSource dataSource;
        private JdbcTemplate jdbc;
        private ToolToggleRepository store;

        @BeforeEach
        void openDatabase() {
            // 每个用例一个独立库名，否则用例之间会互相看到对方的行
            String url = "jdbc:h2:mem:toggle_replay_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
            this.dataSource = DataSourceBuilder.create().url(url).build();
            this.jdbc = new JdbcTemplate(dataSource);
            this.store = new ToolToggleRepository(jdbc, url);
            store.initialize();
        }

        @AfterEach
        void closeDatabase() {
            if (dataSource instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // 测试收尾，关不掉也不该盖住真正的失败
                }
            }
        }

        @Test
        void appliesPersistedTogglesToTheServer() {
            store.save("executeSample", true, "zhangsan");

            ToolToggleRegistry registry = registryWith(store);
            registry.replayPersistedToggles();

            assertThat(registry.disabledToolNames()).containsExactly("executeSample");
            assertThat(registry.remainingExposed()).isEqualTo(2);
            verify(server).removeTool("executeSample");
        }

        @Test
        void leavesUpdatedAtAndUpdatedByUntouched() {
            store.save("executeSample", true, "zhangsan");
            // 把时间戳压到明显的过去，这样"被回放刷新过"和"没动过"一眼可分
            Timestamp planted = Timestamp.from(
                    Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS));
            jdbc.update("UPDATE mcp_tool_toggle SET updated_at = ? WHERE tool_name = ?",
                    planted, "executeSample");

            ToolToggleRegistry registry = registryWith(store);
            registry.replayPersistedToggles();

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT updated_at, updated_by FROM mcp_tool_toggle WHERE tool_name = ?",
                    "executeSample");
            assertThat(row.get("UPDATED_BY"))
                    .as("回放不能把'谁停用的'改成本次启动的操作者")
                    .isEqualTo("zhangsan");
            assertThat(((Timestamp) row.get("UPDATED_AT")).toInstant())
                    .as("回放不能刷新 updated_at，否则每次重启都冲掉一次停用时间")
                    .isEqualTo(planted.toInstant());
        }

        /** 文件库形态下同样跑得通——这是正式环境真正会用的那种库。 */
        @Test
        void replaysFromAFileDatabase(@TempDir Path dir) {
            String url = "jdbc:h2:file:" + dir.resolve("toggles");
            DataSource fileSource = DataSourceBuilder.create().url(url).build();
            try {
                ToolToggleRepository fileStore = new ToolToggleRepository(
                        new JdbcTemplate(fileSource), url);
                fileStore.initialize();
                fileStore.save("checkSampleHealth", true, "zhangsan");

                ToolToggleRegistry registry = registryWith(fileStore);
                registry.replayPersistedToggles();

                assertThat(registry.disabledToolNames()).containsExactly("checkSampleHealth");
            } finally {
                if (fileSource instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {
                        // 同上
                    }
                }
            }
        }
    }
}
