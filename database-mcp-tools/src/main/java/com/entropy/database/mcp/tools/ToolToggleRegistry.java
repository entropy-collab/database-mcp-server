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

import com.entropy.database.mcp.toggle.ToolToggleRepository;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 运行期启用/停用 MCP 工具：<b>停用 = 工具从 {@code tools/list} 消失</b>。
 *
 * <h2>为什么是增删而不是"留在清单里、调用时报错"</h2>
 * <p>工具描述本身就是成本：{@code tools/list} 里多一个工具就是多一段进模型上下文的 token 和多一个
 * 选型干扰项。调用时拦截（上一代 {@code ToolWhitelistAspect} 的思路，已在 0.1.4 移除）省不下这些，
 * 也不能让一个正在被误用的工具真的消失。所以这里做的是真删：
 * {@link McpStatelessSyncServer#removeTool(String)} / {@link McpStatelessSyncServer#addTool}。
 * SDK 内部是 {@code CopyOnWriteArrayList}，{@code tools/list} 每次请求现算，因此运行期增删立刻生效。
 *
 * <h2>已知代价：客户端不会被通知</h2>
 * <p>stateless 传输<b>没有 server → client 的推送通道</b>，{@code McpStatelessSyncServer} 也因此
 * <b>没有</b> {@code notifyToolsListChanged}（那是 stateful 的 {@code McpSyncServer} 才有的方法）。
 * 也就是说：客户端只有在下一次主动 {@code tools/list} 时才知道清单变了。已经缓存了旧清单的客户端
 * 调用一个刚被停用的工具，拿到的是"工具不存在"这一类错误，而不是"工具被管理员停用了"。
 * 这是本方案的已知代价，不是缺陷——协议层给不出更好的东西。运维在停用一个高频工具之前应当知道
 * 会看到一批这样的报错。
 *
 * <h2>快照只覆盖"暴露集"，因此运行期不能绕过部署期策略</h2>
 * <p>可增删的工具集来自 {@link ToolExposureFilter}：它在把裁剪后的 spec 列表交给 MCP server 的同时
 * 调用 {@link #registerExposed}。被部署期配置（{@code entropy.mcp.tools.plane/groups/include/exclude}）
 * 裁掉的工具<b>不在快照里</b>，运行期也就<b>无法</b>通过 API 启用——那个裁剪是部署方的决定，
 * 不该让值班人员用一次 HTTP 调用绕开。请求这类工具名会被拒（见 {@link #requireExposed}）。
 *
 * <h2>与启动期"全裁光即启动失败"的差异</h2>
 * <p>{@link ToolExposureFilter} 在配置把工具全部裁掉时是<b>直接启动失败</b>的——那是一次配置错误，
 * 一个什么都不暴露的 MCP 服务没有意义。而本类<b>允许</b>把暴露集全部停掉：运行期的 kill switch
 * （"先全停，再一个一个放回来"）是合法运维动作，值班人员按下它的时候正需要它生效。两处行为相反
 * 不是 bug，区别在于「配错了」与「有意为之」。全停时打一条 WARN，并在返回体里带上剩余工具数。
 *
 * <h2>为什么用 ObjectProvider 拿 server</h2>
 * <p>{@code McpStatelessSyncServer} 这个 bean 是自动配置建的，它的入参就是 spec 列表，而
 * {@link ToolExposureFilter}（BeanPostProcessor）又在处理那个列表、并在处理过程中调用本类的
 * {@link #registerExposed}。构造期直接注入 server 会把这条链绞成循环依赖；{@link ObjectProvider}
 * 把解析推迟到第一次真正要调用 add/removeTool 的时候，那时容器早已就绪。仓储同理（它还可能不存在）。
 *
 * <h2>为什么变更不写 audit_log</h2>
 * <p>{@code audit_log} 的 schema 是 SQL 审计流水（{@code sql_text} / {@code row_count} /
 * {@code event_time}）。把"管理员停用了一个工具"塞进去，要么这三列全是空，要么得编一段假 SQL——
 * 两种都会污染那张表的语义，而它是合规报告的数据源。管理动作只打 INFO 日志，带上操作者。
 */
@Component
public class ToolToggleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolToggleRegistry.class);

    /**
     * SecurityContext 整个为空时记入日志与 {@code updated_by} 的占位值。
     *
     * <p>注意这<b>不是</b>"鉴权关闭时"的取值：{@code entropy.mcp.security.enabled=false} 下过滤器链
     * 仍然装着匿名认证，{@code getName()} 返回 {@code anonymousUser}，落库的就是那个值。
     * 这个常量只在没有过滤器链的线程里出现——单测，以及将来任何非 HTTP 的调用入口。
     */
    static final String UNKNOWN_ACTOR = "unknown";

    private final ObjectProvider<McpStatelessSyncServer> server;
    private final ObjectProvider<ToolCatalog> catalog;
    private final ObjectProvider<ToolToggleRepository> repository;

    /**
     * 暴露集快照：工具名 → 交给 server 的那个 spec。启用一个工具时必须把<b>同一个</b> spec 放回去，
     * 所以这里存的是 spec 而不只是工具名——spec 里带着 inputSchema 与调用处理器，重建不出来。
     *
     * <p>由 {@link #registerExposed} 写入，读写都在 {@code synchronized} 里。进程内存是权威副本：
     * 读操作（列清单、判断是否停用）一律不查库，库只是为了活过重启。
     */
    private final Map<String, SyncToolSpecification> exposed = new LinkedHashMap<>();

    /** 当前被停用的工具名。{@code exposed} 的子集，永远如此——启用/停用都先过 {@link #requireExposed}。 */
    private final Set<String> disabled = new LinkedHashSet<>();

    public ToolToggleRegistry(ObjectProvider<McpStatelessSyncServer> server,
                              ObjectProvider<ToolCatalog> catalog,
                              ObjectProvider<ToolToggleRepository> repository) {
        this.server = server;
        this.catalog = catalog;
        this.repository = repository;
    }

    /**
     * 一次开关操作的结果。
     *
     * @param affected         这次操作作用到的工具名（分组操作下是该分组里全部已暴露的工具，
     *                         不只是状态真的变了的那些——运维要能看出这条命令的作用范围）
     * @param changed          其中状态真的发生变化的个数；幂等操作返回 0
     * @param remainingExposed 操作后仍然出现在 {@code tools/list} 里的工具数
     */
    public record ToggleResult(List<String> affected, int changed, int remainingExposed) {
    }

    /**
     * 记下裁剪后真正交给 MCP server 的 spec 列表，这是运行期可操作的全集。
     *
     * <p>唯一的合法调用点是 {@link ToolExposureFilter#postProcessAfterInitialization}。之所以是
     * {@code public}：容器里存在多个 {@code List<SyncToolSpecification>} bean，BeanPostProcessor 会被
     * 逐个调用，所以这里<b>合并</b>而不是覆盖（同名不覆盖，先到的那个 spec 才是真正注册进去的那个），
     * 与 {@link ToolCatalog#restrictTo} 的并集语义一致。
     */
    public synchronized void registerExposed(Collection<SyncToolSpecification> specs) {
        for (SyncToolSpecification spec : specs) {
            exposed.putIfAbsent(spec.tool().name(), spec);
        }
    }

    /** 运行期可操作的工具名（即部署期暴露集），字典序。 */
    public synchronized Set<String> exposedToolNames() {
        return new TreeSet<>(exposed.keySet());
    }

    /** 当前被停用、已从 {@code tools/list} 移除的工具名，字典序。 */
    public synchronized Set<String> disabledToolNames() {
        return new TreeSet<>(disabled);
    }

    /** 工具是否在部署期暴露集里；{@code false} 表示它被 {@code entropy.mcp.tools} 裁掉了或根本不存在。 */
    public synchronized boolean isExposed(String toolName) {
        return exposed.containsKey(toolName);
    }

    public synchronized boolean isDisabled(String toolName) {
        return disabled.contains(toolName);
    }

    /** 仍然出现在 {@code tools/list} 里的工具数。 */
    public synchronized int remainingExposed() {
        return exposed.size() - disabled.size();
    }

    /**
     * 开关变更是否会活过重启。
     *
     * <p>{@code false} 表示没配 {@code spring.datasource.url}，仓储 bean 不存在，改动只在内存里。
     * 这个值必须出现在接口返回体里：否则运维会以为停用能活过重启，而它不能。
     */
    public boolean persistent() {
        return repository.getIfAvailable() != null;
    }

    /**
     * 停用或启用单个工具。
     *
     * @throws IllegalArgumentException 工具名未知，或它被 {@code entropy.mcp.tools} 裁掉了
     */
    public synchronized ToggleResult setDisabled(String toolName, boolean disable) {
        requireExposed(toolName);
        boolean changed = disable ? disableOne(toolName) : enableOne(toolName);
        String actor = currentActor();
        if (changed) {
            log.info("MCP 工具开关：{} 把工具 '{}' {}，当前仍暴露 {} 个工具（落库：{}）",
                    actor, toolName, disable ? "停用" : "启用", remainingExposed(), persistent());
        } else {
            log.info("MCP 工具开关：{} 请求把工具 '{}' {}，但它本来就是这个状态，未变更",
                    actor, toolName, disable ? "停用" : "启用");
        }
        warnIfNothingLeft(disable);
        return new ToggleResult(List.of(toolName), changed ? 1 : 0, remainingExposed());
    }

    /**
     * 按分组批量停用或启用。
     *
     * <p>分组 → 工具名一律从 {@link ToolCatalog#exposedDescriptors()} 取，不在这里复制第二份清单：
     * {@code ToolCatalog} 的类注释里写明了「任何按工具名做决策的功能都读这个索引，不要再复制清单」，
     * 上一代意图路由正是因为把工具名硬编码在静态 Map 里而与真实方法名漂移。
     *
     * @throws IllegalArgumentException 分组名未知，或该分组下没有任何已暴露的工具
     */
    public synchronized ToggleResult setGroupDisabled(String group, boolean disable) {
        List<String> targets = requireGroup(group);
        int changed = 0;
        for (String toolName : targets) {
            if (disable ? disableOne(toolName) : enableOne(toolName)) {
                changed++;
            }
        }
        log.info("MCP 工具开关：{} 把分组 '{}' 的 {} 个工具{}，其中 {} 个状态发生变化，"
                        + "当前仍暴露 {} 个工具（落库：{}）",
                currentActor(), group, targets.size(), disable ? "停用" : "启用", changed,
                remainingExposed(), persistent());
        warnIfNothingLeft(disable);
        return new ToggleResult(targets, changed, remainingExposed());
    }

    /**
     * 把库里的停用集合回放到 MCP server 上。
     *
     * <p>挂在 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：要 {@code removeTool}
     * 就得先有 server bean，而 server bean 的入参是 spec 列表，本类在那条链上（见类注释）。
     * ready 事件是"容器完全就绪"的第一个安全点。
     *
     * <p>库里的工具名已经不在暴露集里时<b>跳过并 WARN</b>，不让启动挂掉：配置改了
     * （{@code entropy.mcp.tools} 收窄）、或者版本升级删了某个工具，都会留下这样的陈旧记录，
     * 而"一条陈旧记录让服务起不来"是比"少停一个工具"严重得多的故障。
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void replayPersistedToggles() {
        ToolToggleRepository store = repository.getIfAvailable();
        if (store == null) {
            log.warn("""
                    ================================================================
                    MCP 工具开关只存在于【内存】：没有配置 spring.datasource.url，
                    仓储 bean 不装配。通过 /api/tools 停用的工具在重启后全部恢复暴露，
                    回到 entropy.mcp.tools 配置声明的状态，且没有任何报错——
                    症状就是"明明停掉了的工具又回到 tools/list 里"。
                    要让停用活过重启，请配上服务端状态库（与审计流水、调用者身份同一个键）。
                    ================================================================""");
            return;
        }
        List<String> applied = new ArrayList<>();
        for (String toolName : store.disabledToolNames()) {
            if (!exposed.containsKey(toolName)) {
                log.warn("MCP 工具开关：库里记着工具 '{}' 被停用，但它不在当前暴露集里，跳过。"
                        + "通常是 entropy.mcp.tools 收窄了暴露面，或者这个版本已经没有这个工具了。"
                        + "这条记录会一直留在 {} 表里，直到有人删掉它", toolName, ToolToggleRepository.TABLE_NAME);
                continue;
            }
            if (disabled.add(toolName)) {
                // 回放刻意不走 disableOne：那条路会把刚读出来的状态再写回库里，等于每次启动都改一次
                // updated_at / updated_by，把"谁停用的"这条唯一线索冲掉
                server.getObject().removeTool(toolName);
                applied.add(toolName);
            }
        }
        log.info("MCP 工具开关：{} 个工具在库里被标记为停用，已从清单移除{}；当前仍暴露 {} 个工具",
                applied.size(), applied.isEmpty() ? "" : "（" + applied + "）", remainingExposed());
        warnIfNothingLeft(true);
    }

    /**
     * 操作者名。
     *
     * <p>取不到就是 {@code unknown}，不抛也不拒：鉴权关闭（{@code entropy.mcp.security.enabled=false}）
     * 是受支持的部署形态，那种部署下压根没有 principal，而"因为拿不到操作者就不让停用工具"会在
     * 最需要止血的时候挡住值班人员。
     *
     * <p>包级可见以便直接断言。
     */
    static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String name = authentication == null ? null : authentication.getName();
        return name == null || name.isBlank() ? UNKNOWN_ACTOR : name;
    }

    /** @return 状态是否真的变了；已停用的再停用返回 {@code false} 而不是报错 */
    private boolean disableOne(String toolName) {
        if (!disabled.add(toolName)) {
            return false;
        }
        server.getObject().removeTool(toolName);
        persist(toolName, true);
        return true;
    }

    /** @return 状态是否真的变了；未停用的再启用返回 {@code false} 而不是报错 */
    private boolean enableOne(String toolName) {
        if (!disabled.remove(toolName)) {
            return false;
        }
        // 放回去的必须是快照里那个 spec：schema 与调用处理器都在里面，重建不出来
        server.getObject().addTool(exposed.get(toolName));
        persist(toolName, false);
        return true;
    }

    private void persist(String toolName, boolean disable) {
        ToolToggleRepository store = repository.getIfAvailable();
        if (store != null) {
            store.save(toolName, disable, currentActor());
        }
    }

    /**
     * 工具必须在部署期暴露集里。
     *
     * <p>两种拒绝分开说，因为运维的下一步动作完全不同：工具名拼错了改请求，被配置裁掉了得改配置重启。
     * 消息里列出可选值，口径与 {@code ToolExposureFilter.validate} 一致——这个仓库对"配错了"的一贯
     * 处理是把可选值直接摆在错误信息里，让人不必去翻源码。
     */
    private void requireExposed(String toolName) {
        if (exposed.containsKey(toolName)) {
            return;
        }
        if (catalog.getObject().contains(toolName)) {
            throw new IllegalArgumentException("工具 '" + toolName + "' 存在，但不在暴露集里："
                    + "它已被部署期配置 entropy.mcp.tools（plane/groups/include/exclude）裁掉。"
                    + "运行期刻意不允许把它加回来——那等于用一次 HTTP 调用绕过部署方的暴露策略。"
                    + "要启用它请改配置并重启。");
        }
        throw new IllegalArgumentException("未知工具名 '" + toolName + "'；可操作的工具有 "
                + exposedToolNames());
    }

    /** @return 该分组下全部已暴露的工具名，字典序 */
    private List<String> requireGroup(String group) {
        List<String> targets = catalog.getObject().exposedDescriptors().stream()
                .filter(descriptor -> group.equals(descriptor.group()))
                // 以快照为准：目录的暴露视图与本快照理论上一致，但目录只覆盖 McpToolBase 子类，
                // 两者不同源，真正能 add/removeTool 的只有快照里的那些
                .map(ToolCatalog.ToolDescriptor::name)
                .filter(exposed::containsKey)
                .sorted()
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("分组 '" + group + "' 未知，或它下面没有任何已暴露的工具；"
                    + "可操作的分组有 " + operableGroups());
        }
        return targets;
    }

    /** 至少有一个已暴露工具的分组，字典序。全量分组见 {@link ToolCatalog#groups()}。 */
    private Set<String> operableGroups() {
        return catalog.getObject().exposedDescriptors().stream()
                .map(ToolCatalog.ToolDescriptor::group)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * 暴露集被全部停掉时告警。
     *
     * <p>这不是错误（见类注释里与启动期行为的差异），但它意味着这台服务器现在对任何客户端都
     * 一个工具都不提供，而 {@code tools/list} 返回空数组在客户端那边通常表现为"服务没接上"。
     */
    private void warnIfNothingLeft(boolean disable) {
        if (disable && remainingExposed() == 0 && !exposed.isEmpty()) {
            log.warn("MCP 工具开关：暴露集已被全部停用，tools/list 现在是空的。"
                    + "这是运行期 kill switch 的预期行为（与启动期「配置把工具全裁光即启动失败」不同），"
                    + "但客户端看到的是一台没有任何工具的服务器。用 PUT /api/tools/<工具名> 逐个放回来。");
        }
    }
}
