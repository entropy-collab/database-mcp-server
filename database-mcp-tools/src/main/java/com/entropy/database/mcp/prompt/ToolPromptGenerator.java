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
import com.entropy.database.mcp.tools.ToolCatalog;
import com.entropy.database.mcp.tools.ToolToggleRegistry;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 按<b>当前真正在 {@code tools/list} 里的工具</b>生成一份可直接粘给模型的提示词。
 *
 * <h2>为什么在后端生成，而不是让前端拼</h2>
 * <p>提示词里最有价值的一段是"当前有哪些约束"：哪些连接是只读的、DDL 开关是开还是关、
 * 联邦网关有没有起来、连接级授权有没有打开、有多少工具被运行期停掉了。这些全都只在服务端成立。
 * 让前端拼等于把这套判断复制一份到 JS 里，而两份判断一定会漂移——漂移的表现是提示词说"可以执行
 * DDL"而服务端一律拒绝，或者反过来。所以整段文本在这里生成，前端只负责展示、复制与下载。
 *
 * <h2>只列真正调得到的工具</h2>
 * <p>清单来源是 {@link ToolToggleRegistry#exposedToolNames()} 减去
 * {@link ToolToggleRegistry#disabledToolNames()}，也就是 {@code tools/list} 此刻的内容：
 * <ul>
 *   <li>被部署期 {@code entropy.mcp.tools} 裁掉的工具不在暴露集快照里，自然不会出现；</li>
 *   <li>被运行期停用的工具从清单里减掉。</li>
 * </ul>
 * 一个不在 {@code tools/list} 里的工具写进提示词，就是让模型去调一个不存在的工具——它换回来的是
 * "工具不存在"这类错误，而模型通常会把这当成自己参数写错，改一改再试一次。这比少列一个工具糟得多。
 *
 * <p>快照里有、{@link ToolCatalog} 里没有的工具（扩展注册进来的，目录只覆盖
 * {@code McpToolBase} 子类）<b>照样列出来</b>，只是描述位置写一句"没有可用描述"。
 * 它们真的能调到，漏掉它们同样是在说谎；而 {@code toolCount} 也因此恒等于
 * {@link ToolToggleRegistry#remainingExposed()}。
 *
 * <h2>为什么不在 {@code tools} 包里</h2>
 * <p>本类需要 {@link DynamicDataSourceManager#isReadonly} —— 那是"这条连接是不是只读"唯一的
 * 现成入口（{@code ConnectionAdminTools.listConnections} 不报这个字段）。而 ArchUnit 的 R2
 * 规则禁止 {@code com.entropy.database.mcp.tools..} 直接依赖 BYOK 注册表，基线是 0，
 * 两处已有的例外都是逐方法列白名单的。本类不是一个 MCP 工具、也不该去申请第三条例外，
 * 所以它住在自己的包里；{@code ToolAdminController} 从这里取文本。
 */
@Component
public class ToolPromptGenerator {

    /** {@code format} 的合法取值。 */
    public enum PromptFormat {

        /** 只有工具清单段落，Markdown，按分组分节。 */
        LIST,

        /** 一整段可直接用的系统提示词：角色与目标 + 当前生效的约束 + 工具清单 + 快照声明。 */
        SYSTEM;

        /** 查询串里写的名字，小写。 */
        public String queryName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * 解析 {@code ?format=}。缺省与空白都是 {@link #SYSTEM}。
         *
         * <p>空白也走缺省而不是报错：控制器的 {@code defaultValue} 只在参数<b>完全缺失</b>时生效，
         * {@code ?format=} 会解析成空串，把它判成非法取值等于让一个语义上"没传"的请求拿到 400。
         *
         * @throws IllegalArgumentException 取值未知。消息里列出可选值，口径与
         *                                  {@code ToolExposureFilter.validate} 一致——这个仓库对
         *                                  "写错了"的一贯处理是把可选值直接摆在错误信息里，
         *                                  让人不必去翻源码
         */
        public static PromptFormat parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return SYSTEM;
            }
            String name = raw.strip().toLowerCase(Locale.ROOT);
            for (PromptFormat format : values()) {
                if (format.queryName().equals(name)) {
                    return format;
                }
            }
            throw new IllegalArgumentException("未知的 format='" + raw + "'；可选值是 "
                    + List.of(LIST.queryName(), SYSTEM.queryName()));
        }
    }

    /**
     * 一次生成的结果。
     *
     * @param format      实际使用的格式名（回显解析后的值，不是入参原文）
     * @param generatedAt 生成时刻。它同时被写进 {@code text} 里，两处必须是同一个瞬间，
     *                    所以由本类给出而不是让调用方再取一次 {@code Instant.now()}
     * @param toolCount   写进提示词的工具数，等于此刻 {@code tools/list} 的长度
     * @param text        可直接粘贴的纯文本
     */
    public record ToolPrompt(String format, Instant generatedAt, int toolCount, String text) {
    }

    /**
     * DDL 关闭时必须出现的那句话。
     *
     * <p>抽成常量是因为它是这个功能存在的理由之一：模型看不到服务端配置，只能从提示词里知道
     * "试了也白试"。{@code ToolPromptGeneratorTest} 在两个方向上各钉一次（关闭时在、开启时不在）。
     */
    static final String DDL_DISABLED_WARNING = "DDL 类工具会被拒，不要尝试";

    /** 一个工具都没有时的正文。不抛异常：运行期 kill switch 把全部工具停掉是合法运维动作。 */
    static final String NO_TOOLS_TEXT = "当前没有可用工具：tools/list 是空的。"
            + "这通常是运行期开关把暴露集全部停用了（合法的 kill switch），"
            + "也可能是部署期 entropy.mcp.tools 把工具裁光。在有工具之前这份提示词没有内容可写。";

    /** 目录里没有元数据的工具（扩展注册进来的）在描述位置写这一句。 */
    static final String NO_DESCRIPTION_TEXT = "（这个工具没有可用的描述：它不是 McpToolBase 子类上的"
            + " @McpTool 方法，运行期索引里没有它的元数据。调用前请以 tools/list 返回的 schema 为准。）";

    /**
     * {@code group} 为 {@code null} 的工具归到这一节。
     *
     * <p>用全角括号开头顺带解决了排序：分组名用 {@link TreeMap} 按字典序排，而全角
     * {@code （} 的码位在全部 ASCII 之后，于是这一节稳定落在最后一个。分组顺序必须稳定
     * ——同一份状态下重新生成两次得到不同文本，运维就没法拿 diff 判断"清单变了没有"。
     */
    static final String UNGROUPED = "（未分组）";

    private static final String DDL_PROPERTY = "entropy.mcp.database.ddl.allowed";
    private static final String GATEWAY_PROPERTY = "entropy.mcp.gateway.enabled";
    private static final String AUTHZ_PROPERTY = "entropy.mcp.authz.enabled";

    private final ToolCatalog catalog;
    private final ToolToggleRegistry toggles;

    /**
     * 只用到 {@code listConnectionKeys} 与 {@code isReadonly}。
     *
     * <p>必填注入而不是 {@code ObjectProvider}：这个 bean 挂在
     * {@code entropy.mcp.database.enabled}（{@code matchIfMissing=true}）上，而
     * {@code ConnectionAdminTools} 已经无条件地要求它——真的缺了它，整个上下文起不来，
     * 不会走到本类这里。为一个不可能的分支写降级代码只会多一条测不到的路径。
     */
    private final DynamicDataSourceManager dataSources;

    /**
     * 三个开关都读<b>生效值</b>，不是代码里的默认值。
     *
     * <p>统一走 {@link Environment} 而不是 {@code @Value}：这三个键没有
     * {@code @ConfigurationProperties} 类，而仓库里既有的读法就是
     * {@code environment.getProperty(键, 兜底)} + {@code Boolean.parseBoolean}
     * （{@code DdlExecutionTools}、{@code CrossDatabaseTools}、{@code WebUiController} 都这么读）。
     * 跟着读同一个 {@code Environment}，本类报的就一定和那些工具的实际行为一致。
     *
     * <p>注意 {@code gateway} 的代码兜底是 {@code false} 而 {@code application.yml} 配的是
     * {@code true}：照代码默认值猜会在默认部署上说反。
     */
    private final Environment environment;

    public ToolPromptGenerator(ToolCatalog catalog,
                               ToolToggleRegistry toggles,
                               DynamicDataSourceManager dataSources,
                               Environment environment) {
        this.catalog = catalog;
        this.toggles = toggles;
        this.dataSources = dataSources;
        this.environment = environment;
    }

    /** 按当前服务端状态生成一份提示词。 */
    public ToolPrompt generate(PromptFormat format) {
        Instant generatedAt = Instant.now();
        List<ToolCatalog.ToolDescriptor> tools = listedTools();
        String text = format == PromptFormat.LIST
                ? toolCatalogSection(tools)
                : systemPrompt(tools, generatedAt);
        return new ToolPrompt(format.queryName(), generatedAt, tools.size(), text);
    }

    /**
     * 此刻真正在 {@code tools/list} 里的工具，按分组、再按工具名排序。
     *
     * <p>以开关快照为准而不是 {@link ToolCatalog#exposedDescriptors()}：后者不知道运行期停用，
     * 而且只覆盖目录里有的那些。两者对"部署期裁剪"的判断是一致的（都由
     * {@code ToolExposureFilter} 喂进来），差别只在停用与扩展工具这两件事上。
     */
    private List<ToolCatalog.ToolDescriptor> listedTools() {
        Set<String> disabled = toggles.disabledToolNames();
        List<ToolCatalog.ToolDescriptor> tools = new ArrayList<>();
        for (String name : toggles.exposedToolNames()) {
            if (disabled.contains(name)) {
                continue;
            }
            ToolCatalog.ToolDescriptor descriptor = catalog.describe(name);
            tools.add(descriptor != null ? descriptor
                    : new ToolCatalog.ToolDescriptor(name, null, "", "", List.of()));
        }
        return tools;
    }

    /**
     * 工具清单段落，也就是 {@code format=list} 的全部内容。
     *
     * <p>{@link TreeMap} 而不是 {@code LinkedHashMap}：这里要的是按分组名字典序，
     * 而入参的顺序是按工具名的。节内的工具顺序由 {@code exposedToolNames()} 的字典序保证。
     */
    private static String toolCatalogSection(Collection<ToolCatalog.ToolDescriptor> tools) {
        if (tools.isEmpty()) {
            return NO_TOOLS_TEXT;
        }
        Map<String, List<ToolCatalog.ToolDescriptor>> byGroup = new TreeMap<>();
        for (ToolCatalog.ToolDescriptor tool : tools) {
            String group = tool.group() == null || tool.group().isBlank() ? UNGROUPED : tool.group();
            byGroup.computeIfAbsent(group, key -> new ArrayList<>()).add(tool);
        }

        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<ToolCatalog.ToolDescriptor>> entry : byGroup.entrySet()) {
            text.append("## ").append(entry.getKey()).append('\n');
            for (ToolCatalog.ToolDescriptor tool : entry.getValue()) {
                text.append("### ").append(tool.name()).append('\n');
                text.append(tool.description().isEmpty() ? NO_DESCRIPTION_TEXT : tool.description())
                        .append('\n');
                if (!tool.tags().isEmpty()) {
                    // 合模板的描述里本来就有一行 "标签：[read, query]"，所以这一行对它们是重复的。
                    // 仍然写出来：这里给的是解析归一化后的标签（小写、去空），位置固定在工具节末尾，
                    // 而描述里那一行的位置与写法取决于作者。要按标签筛工具的人（"哪些是只读的"）
                    // 需要一个每个工具都在同一位置的锚点，包括描述不合模板的那些。
                    text.append("标签：").append(String.join(", ", tool.tags())).append('\n');
                }
                text.append('\n');
            }
        }
        return text.toString().stripTrailing();
    }

    /**
     * 完整系统提示词。
     *
     * <p>语气刻意跟着仓库里 {@code @McpTool} 描述那种"前置条件 / 使用场景 / 不要用于"的实用风格：
     * 每一条都说清一个具体动作或一个具体后果，不写"请谨慎操作"这种模型读完也不知道该做什么的话。
     */
    private String systemPrompt(List<ToolCatalog.ToolDescriptor> tools, Instant generatedAt) {
        return """
                # 你是这台数据库 MCP 服务的调用方

                你通过 MCP 工具操作真实数据库。下面写的是这台服务器此刻的真实状态，不是文档默认值。

                ## 怎么干活
                - 先确认再动手：不知道有哪些连接就先调 listConnections，不知道表结构就先调 describeTable。不要按猜出来的连接名、表名或列名写 SQL。
                - 每个工具的描述里都有「前置条件」「使用场景」「不要用于」三段，照着读。前置条件不满足时调用会被拒绝，那是预期行为而不是服务故障，换个工具重试同一件事只会再被拒一次。
                - 被拒绝时先读错误消息：本服务的拒绝消息里写了原因和下一步该做什么（该改哪个参数、该先调哪个工具、还是该让运维改配置）。
                - 写入、DDL、删除这类会改变数据的操作，执行前先把「改哪个连接的哪张表、影响多少行、能不能回滚」说清楚并等用户确认。
                - 下面清单之外的工具在这台服务器上不存在。不要尝试调用清单里没有的名字。
                - 返回的行数可能被服务端截断，拿到结果后按返回体里的计数字段判断，不要假设你看到的就是全部。

                ## 当前生效的约束
                %s

                ## 可用工具（%d 个）
                %s

                ## 这份提示词是一份快照
                生成时刻：%s。工具可以在运行期被启用或停用，连接也可以随时注册、过期或被改成只读，所以这份清单会过时。发现清单里的工具调不到、或者出现了清单里没有的工具时，让运维重新生成一份，不要靠猜去补。"""
                .formatted(constraintsSection(), tools.size(), toolCatalogSection(tools),
                        generatedAt);
    }

    /**
     * "当前生效的约束"那一段：逐条从服务端真实状态取。
     *
     * <p>这是整个功能的价值所在，所以每一条都必须是读出来的而不是写死的。写死之后这份提示词就只是
     * 一份会过期的文档，而模型会照着它去试一件服务端一定拒绝的事。
     */
    private String constraintsSection() {
        List<String> lines = new ArrayList<>();
        lines.add("- 已注册连接：" + connectionsLine());
        lines.add("- DDL 执行：" + ddlLine());
        lines.add("- 联邦网关：" + gatewayLine());
        lines.add("- 连接级授权：" + authzLine());
        lines.add("- 运行期开关：" + disabledLine());
        return String.join("\n", lines);
    }

    /**
     * 连接名与它的 {@code readonly} 标记。
     *
     * <p>只列名字与只读标记，不列 JDBC URL、账号或方言：提示词会被粘进对话历史，而连接的
     * 那些细节模型用不上——它按名字引用连接，需要更多信息时有 describeConnection。
     */
    private String connectionsLine() {
        Collection<String> keys = new TreeSet<>(dataSources.listConnectionKeys());
        if (keys.isEmpty()) {
            return "一条都没有。任何需要 connection 参数的工具现在都会失败；"
                    + "先让用户或运维注册连接（createNamedConnection / createSealedConnection），"
                    + "或用 listConnections 再确认一次——它是这件事的权威来源。";
        }
        List<String> described = new ArrayList<>();
        for (String key : keys) {
            described.add(key + (dataSources.isReadonly(key) ? "（只读）" : "（可写）"));
        }
        return String.join("、", described)
                + "。标着「只读」的连接会拒绝一切改数据的工具，包括 DDL，不管 DDL 开关是什么状态。";
    }

    private String ddlLine() {
        if (booleanProperty(DDL_PROPERTY, false)) {
            return "已开启（" + DDL_PROPERTY + "=true）。executeDdl 一类工具可以执行，"
                    + "但仍要过 DDL 校验器、连接的只读标记和库账号自己的权限。"
                    + "Oracle/MySQL 的 DDL 隐式提交、出错无法回滚，执行前必须先让用户确认。";
        }
        return "已关闭（" + DDL_PROPERTY + "=false）。" + DDL_DISABLED_WARNING
                + "：executeDdl、executeDdlBatch、executeDdlRemote、createMirrorTable 一律被服务端拒绝，"
                + "换写法或换连接都不行，只有运维改配置重启才会变。"
                + "validateDdl 例外——它只做静态校验、不连库，DDL 关着时仍可用于预检。";
    }

    private String gatewayLine() {
        if (booleanProperty(GATEWAY_PROPERTY, false)) {
            return "已开启（" + GATEWAY_PROPERTY + "=true）。ETL 与跨库工具已注册，"
                    + "具体哪些可用以下面的清单为准。";
        }
        return "已关闭（" + GATEWAY_PROPERTY + "=false）。ETL 相关工具在这台服务器上根本没有注册，"
                + "下面的清单里也不会有它们；需要搬数据时只能用查询加写入的工具组合，"
                + "或者让运维打开这个开关。";
    }

    private String authzLine() {
        if (booleanProperty(AUTHZ_PROPERTY, false)) {
            return "已开启（" + AUTHZ_PROPERTY + "=true）。连接与表的访问按调用者逐条授权，"
                    + "没有被授权覆盖的组合一律拒绝（fail closed）。"
                    + "同一个工具在一条连接上可用、在另一条上被拒是正常的，不要因此反复重试。";
        }
        return "已关闭（" + AUTHZ_PROPERTY + "=false）。不做按调用者的授权，"
                + "起作用的只有连接自身的只读标记和库账号的权限。";
    }

    /**
     * 被运行期停用的工具<b>只报数量</b>。
     *
     * <p>刻意不列名字：那些工具已经不在清单里了，把名字写出来只会让模型去试一个调不到的工具，
     * 而它换回来的是"工具不存在"——和工具名写错完全一样的错误，于是它会改一改再试。
     * 数量有用（它解释了"为什么这台服务器比文档里少几个工具"），名字有害。
     */
    private String disabledLine() {
        int disabled = toggles.disabledToolNames().size();
        if (disabled == 0) {
            return "没有工具被运行期停用，下面的清单就是这台服务器暴露的全部工具。";
        }
        return "有 " + disabled + " 个工具被管理员在运行期停用，已经从下面的清单里去掉了。"
                + "清单里没有的工具就是调不到，不要去猜被停的是哪些。";
    }

    private boolean booleanProperty(String key, boolean fallback) {
        return Boolean.parseBoolean(environment.getProperty(key, Boolean.toString(fallback)));
    }
}
