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

import org.springframework.aop.support.AopUtils;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 已注册 MCP 工具的运行期索引：工具名 → 所属分组、一句话摘要、完整描述、标签。
 *
 * <p>索引完全由反射得出，数据源是容器里真实存在的 {@link McpToolBase} bean 及其
 * {@code @McpTool} 方法，因此不存在需要人工维护的工具名清单——新增、改名、条件化注册
 * （如 {@code EtlTools} 受 {@code entropy.mcp.gateway.enabled} 控制）都会自动反映进来。
 *
 * <p>历史教训：上一版意图路由把 126 个工具名硬编码在静态 Map 里，很快与真实方法名漂移
 * （如 {@code getEtlJobStatus} vs 实际的 {@code getJobStatus}），一旦启用就会大面积误判。
 * 任何需要"按工具名做决策"的功能都应当读这个索引，不要再复制清单。
 *
 * <h2>分组规则</h2>
 * 分组名由声明类的类名推导：去掉尾部的 {@code Tools}/{@code Tool} 后转 kebab-case，
 * 例如 {@code DatabaseHealthTools → database-health}、{@code McpSessionDispatchTool →
 * mcp-session-dispatch}。这是纯函数，没有额外映射表可漂移。
 *
 * <h2>摘要与标签</h2>
 * 从工具描述里解析，依赖既有的中文描述模板（{@code 【工具中文名】一句话说明。} 与
 * {@code 标签：[read, query]}）。描述不合模板时摘要退化为首行截断、标签为空，不会报错。
 *
 * <h2>摘要与完整描述是两个字段，用途不同</h2>
 * {@code summary} 是<b>截断到 160 字</b>的一句话，给表格与 {@code suggestTools} 用；
 * {@code description} 是 {@code @McpTool} 注解里的原文，一个字不删，给
 * {@code ToolPromptGenerator} 那种"要把工具描述整段喂给模型"的场景用。两者不能互相替代：
 * 表格里塞一段几百字的描述没法读，而提示词里只给一句摘要等于把「前置条件 / 不要用于」
 * 这些真正能阻止误用的信息扔掉。
 *
 * <p>索引惰性构建：本类被 {@link IntentRouter}（自身也是一个工具 bean）依赖，构造期解析
 * bean 列表会形成循环依赖，故通过 {@link ObjectProvider} 延迟到首次使用。
 */
@Component
public class ToolCatalog {

    /** 工具描述模板里的中文名，形如 {@code 【清空查询缓存】}。 */
    private static final Pattern TITLE = Pattern.compile("【([^】]+)】");

    /** 工具描述模板里的标签行，形如 {@code 标签：[read, cache]}。 */
    private static final Pattern TAGS = Pattern.compile("标签[：:]\\s*\\[([^\\]]*)\\]");

    private static final int SUMMARY_MAX_LENGTH = 160;

    private final ObjectProvider<McpToolBase> toolBeans;

    private volatile Map<String, ToolDescriptor> index;

    /**
     * 实际暴露给客户端的工具名；{@code null} 表示未经裁剪，索引即全部暴露。
     * 由 {@link #restrictTo} 写入，见那里的说明。
     */
    private volatile Set<String> exposed;

    public ToolCatalog(ObjectProvider<McpToolBase> toolBeans) {
        this.toolBeans = toolBeans;
    }

    /**
     * 单个工具的检索元数据。
     *
     * @param name        MCP 工具名（{@code @McpTool.name()} 优先，缺省为方法名）
     * @param group       分组名，由声明类名推导
     * @param summary     一句话摘要，取描述里的中文名与首句，<b>会被截断</b>（见 {@code summarize}）
     * @param description {@code @McpTool.description()} 的原文，只做首尾去空白，<b>不截断</b>；
     *                    没有描述时是空串而不是 {@code null}
     * @param tags        描述里 {@code 标签：[...]} 的英文小写关键字
     */
    public record ToolDescriptor(String name, String group, String summary, String description,
                                 List<String> tags) {

        /**
         * 供关键词匹配用的小写检索文本，包含工具名、分组、摘要与标签。
         *
         * <p><b>刻意不含 {@link #description}。</b>这是一个取舍，不是遗漏：完整描述里有
         * 「前置条件 / 使用场景 / 返回字段 / 不要用于」这一套模板套话，把它加进来之后几乎任何
         * 关键词都能在几乎任何工具上命中（"连接"、"表"、"查询"、"错误" 每段描述里都有），
         * {@code IntentRouter.suggestTools} 的弱命中权重会给所有工具都加上分，排序随之退化成
         * 按工具名字典序——推荐功能形同废掉。要让描述参与匹配得先重做打分（比如按词频归一化），
         * 那是另一件事。{@code ToolCatalogTest} 有一条断言钉住这里不含描述正文。
         */
        public String searchableText() {
            return (name + ' ' + group + ' ' + summary + ' ' + String.join(" ", tags)).toLowerCase();
        }
    }

    /** 索引里的全部工具，按工具名字典序；<b>不</b>受暴露面裁剪影响。 */
    public Collection<ToolDescriptor> descriptors() {
        return index().values();
    }

    /** 索引里的工具总数；<b>不</b>受暴露面裁剪影响。 */
    public int size() {
        return index().size();
    }

    /**
     * 实际交给客户端的工具，按工具名字典序；{@link #restrictTo} 未被调用时等于 {@link #descriptors()}。
     *
     * <p>面向模型的工具（目前是 {@code suggestTools}）必须用这个而不是 {@link #descriptors()}：
     * 推荐一个已被摘掉、根本调不到的工具，比不推荐更糟。
     */
    public Collection<ToolDescriptor> exposedDescriptors() {
        Set<String> visible = this.exposed;
        if (visible == null) {
            return descriptors();
        }
        return index().values().stream()
                .filter(descriptor -> visible.contains(descriptor.name()))
                .toList();
    }

    /** 实际交给客户端的工具总数。 */
    public int exposedSize() {
        return exposedDescriptors().size();
    }

    /**
     * 记录 {@link ToolExposureFilter} 裁剪后真正交给客户端的工具名。
     *
     * <p>为什么需要这一步：本索引是从容器里的 {@link McpToolBase} bean 反射出来的，而
     * {@code plane}/{@code groups}/{@code include}/{@code exclude} 裁剪的是下游的
     * {@code SyncToolSpecification} 列表。两者不同源，不做这一步的话，配了预设（如
     * {@code tools-explore}）之后 {@code suggestTools} 会把绝大多数已被摘掉、根本调不到的工具
     * 推荐给模型，{@code totalTools} 也照报全量。这里刻意不举具体数字：工具增删后快照数字就变成
     * 谎报，真正钉住这条不变量的是 {@code ToolExposureFilterTest} 的断言。
     *
     * <p>类级 {@code @ConditionalOnProperty}（如 {@code EtlTools}）不需要这一步：bean 本身
     * 不存在，索引里自然没有。
     *
     * <p>并集合并而非覆盖：容器里存在多个 {@code List<SyncToolSpecification>} bean，
     * {@link ToolExposureFilter} 作为 BeanPostProcessor 会被逐个调用。
     *
     * <p>刻意只影响 {@link #exposedDescriptors()} 与 {@link #exposedSize()}，不改
     * {@link #descriptors()}/{@link #size()}/{@link #describe}/{@link #groups} 的语义：
     * 裁剪器自己要靠后者做判定与校验，不能依赖自己的输出；"X of Y" 里的 Y 也得是完整总数。
     */
    synchronized void restrictTo(Collection<String> exposedToolNames) {
        Set<String> merged = new TreeSet<>(exposedToolNames);
        if (this.exposed != null) {
            merged.addAll(this.exposed);
        }
        this.exposed = Set.copyOf(merged);
    }

    /** 全部分组名，字典序。 */
    public Set<String> groups() {
        return new TreeSet<>(index().values().stream().map(ToolDescriptor::group).toList());
    }

    /** 返回工具所属分组；工具名未出现在索引里时返回 {@code null}。 */
    public String groupOf(String toolName) {
        ToolDescriptor d = index().get(toolName);
        return d == null ? null : d.group();
    }

    /** 返回工具的完整元数据；工具名未出现在索引里时返回 {@code null}。 */
    public ToolDescriptor describe(String toolName) {
        return index().get(toolName);
    }

    /** 判断工具名是否存在于索引。 */
    public boolean contains(String toolName) {
        return index().containsKey(toolName);
    }

    private Map<String, ToolDescriptor> index() {
        Map<String, ToolDescriptor> local = this.index;
        if (local == null) {
            synchronized (this) {
                local = this.index;
                if (local == null) {
                    local = build(toolBeans.orderedStream().toList());
                    this.index = local;
                }
            }
        }
        return local;
    }

    static Map<String, ToolDescriptor> build(List<?> beans) {
        Map<String, ToolDescriptor> result = new TreeMap<>();
        for (Object bean : beans) {
            Class<?> type = AopUtils.getTargetClass(bean);
            String group = groupOf(type);
            for (Method method : type.getDeclaredMethods()) {
                McpTool annotation = method.getAnnotation(McpTool.class);
                if (annotation == null) {
                    continue;
                }
                String name = annotation.name().isBlank() ? method.getName() : annotation.name();
                result.put(name, new ToolDescriptor(name, group, summarize(annotation.description()),
                        fullDescription(annotation.description()), parseTags(annotation.description())));
            }
        }
        return new LinkedHashMap<>(result);
    }

    /** 由声明类名推导分组名：去掉尾部 Tools/Tool 后转 kebab-case。 */
    static String groupOf(Class<?> type) {
        String name = type.getSimpleName();
        if (name.endsWith("Tools")) {
            name = name.substring(0, name.length() - "Tools".length());
        } else if (name.endsWith("Tool")) {
            name = name.substring(0, name.length() - "Tool".length());
        }
        if (name.isEmpty()) {
            return type.getSimpleName().toLowerCase();
        }
        StringBuilder kebab = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                kebab.append('-');
            }
            kebab.append(Character.toLowerCase(c));
        }
        return kebab.toString();
    }

    /**
     * 注解里的完整描述，只做首尾去空白。
     *
     * <p>去空白而不是原样保留：描述一律是文本块，尾部必然带一个换行、首部可能带缩进，
     * 而这份文本会被直接拼进 Markdown 提示词，多出来的空行会变成段落分隔。
     * 中间的换行与缩进<b>不动</b>——描述模板的每一行（前置条件 / 使用场景 / 返回字段 /
     * 不要用于 / 标签）都是独立一行，压平会让模型读成一句长句。
     *
     * <p>{@code null} 与空白都折成空串，不返回 {@code null}：这个字段会进 JSON 与 Markdown，
     * 两处都不希望出现 "null" 字面量，而本仓库刚为同一类原因修过四处 NPE。
     */
    static String fullDescription(String description) {
        return description == null ? "" : description.strip();
    }

    /** 取描述里的 {@code 【中文名】} 与紧随的首句；不合模板时退化为首行截断。 */
    static String summarize(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String firstLine = description.strip().lines().findFirst().orElse("").strip();
        Matcher title = TITLE.matcher(firstLine);
        if (title.find()) {
            String rest = firstLine.substring(title.end());
            int stop = rest.indexOf('。');
            String sentence = stop >= 0 ? rest.substring(0, stop) : rest;
            firstLine = title.group(1) + (sentence.isBlank() ? "" : "：" + sentence.strip());
        }
        return firstLine.length() > SUMMARY_MAX_LENGTH
                ? firstLine.substring(0, SUMMARY_MAX_LENGTH) : firstLine;
    }

    /** 解析描述里的 {@code 标签：[read, query]} 行，返回英文小写关键字。 */
    static List<String> parseTags(String description) {
        if (description == null) {
            return List.of();
        }
        Matcher matcher = TAGS.matcher(description);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String raw : matcher.group(1).split(",")) {
            String tag = raw.strip().toLowerCase();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }
}
