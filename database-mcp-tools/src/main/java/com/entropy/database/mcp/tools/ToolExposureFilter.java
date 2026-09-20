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
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 在启动期按配置裁剪 MCP 工具清单，实现方法级的暴露面收敛。
 *
 * <h2>为什么必须在这一层做</h2>
 * Spring AI 的注解扫描把每个 {@code @McpTool} 方法转成一个 {@link SyncToolSpecification}，
 * 汇总成一个 {@code List} bean 后一次性灌进 MCP server。部署期的暴露面就在这一刻定下来，
 * 且服务以 {@code tools(listChanged=false)} 声明能力——协议层没有"清单变了"的推送通道。
 * 因此"少暴露"只有两个位置可做：
 * <ul>
 *   <li>类级 {@code @ConditionalOnProperty}：粒度只能到整个工具类，无法单独摘掉
 *       {@code DatabaseHealthTools} 里 14 个工具中的 {@code flashbackQuery}；</li>
 *   <li>本类：在 spec 列表交给 server 之前按工具名裁剪，粒度到单个工具。</li>
 * </ul>
 * 需要注意的是，调用时拦截（AOP 抛错）无法达到同样效果：{@code tools/list} 仍会把全部
 * 工具描述发给模型，token 一分不省，选型噪声一点不减。上一代 {@code ToolWhitelistAspect}
 * 就是这个思路，已在 0.1.4 移除。
 *
 * <h2>实现约束</h2>
 * 用 {@link BeanPostProcessor} 而非覆盖自动配置的 {@code toolSpecs} bean，是为了不与
 * Spring AI 的版本演进耦合到 bean 名上；识别目标只依赖列表元素类型，换 SDK 版本仍然成立。
 * 依赖统一走 {@link ObjectProvider} 延迟解析：BeanPostProcessor 实例化极早，构造期直接
 * 注入普通 bean 会把它们拖到"未经全部后置处理器处理"的状态。
 *
 * <h2>这里同时是运行期开关的唯一数据源</h2>
 * 裁剪后的 spec 列表交给 {@link ToolToggleRegistry#registerExposed}，那是运行期启用/停用工具
 * （{@code /api/tools}）能操作的全集。本类是整个进程里唯一看得到 spec 列表的地方，而启用一个工具
 * 需要把<b>原来那个 spec</b> 放回 server，所以快照只能在这里建。
 *
 * <p>快照刻意只覆盖裁剪后的集合：被本类裁掉的工具在运行期<b>无法</b>被启用回来。部署期的暴露策略
 * 是部署方的决定，运行期开关是值班人员的工具，后者不该能绕过前者。
 *
 * <p>另一处刻意的不一致：本类在配置把工具全裁光时<b>直接启动失败</b>（{@code kept.isEmpty()}），
 * 而 {@link ToolToggleRegistry} <b>允许</b>运行期把暴露集全部停掉。前者是配置错误，后者是
 * kill switch，理由写在那个类的注释里——看到两处行为相反时请先读那一段，这不是 bug。
 */
@Component
public class ToolExposureFilter implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ToolExposureFilter.class);

    private final ObjectProvider<ToolExposureProperties> properties;
    private final ObjectProvider<ToolCatalog> catalog;
    private final ObjectProvider<ToolToggleRegistry> toggles;

    public ToolExposureFilter(ObjectProvider<ToolExposureProperties> properties,
                              ObjectProvider<ToolCatalog> catalog,
                              ObjectProvider<ToolToggleRegistry> toggles) {
        this.properties = properties;
        this.catalog = catalog;
        this.toggles = toggles;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof List<?> list) || list.isEmpty()
                || !(list.getFirst() instanceof SyncToolSpecification)) {
            return bean;
        }

        ToolExposureProperties config = properties.getObject();
        @SuppressWarnings("unchecked")
        List<SyncToolSpecification> specs = (List<SyncToolSpecification>) list;
        if (config.isNoop()) {
            log.info("MCP tool exposure: no filter configured, all {} tools registered", specs.size());
            // 未配裁剪时"裁剪后的列表"就是全量，快照照样要建：运行期开关的可操作集合永远等于
            // 真正交给 server 的那份列表，两条路径必须都记，否则默认部署下开关会认为一个工具都没有
            toggles.getObject().registerExposed(specs);
            return bean;
        }

        ToolCatalog toolCatalog = catalog.getObject();
        ToolPlane plane = ToolPlane.parse(config.plane());
        validate(config, specs, toolCatalog);

        List<SyncToolSpecification> kept = specs.stream()
                .filter(spec -> exposed(spec.tool().name(), config, plane, toolCatalog))
                .toList();

        if (kept.isEmpty()) {
            throw new IllegalStateException(
                    "entropy.mcp.tools filtered out every tool — the MCP server would expose nothing. "
                            + "Check plane/groups/include/exclude. Available groups: " + toolCatalog.groups());
        }

        log.info("MCP tool exposure: plane={}, {} of {} tools registered, {} filtered out. Kept per group: {}",
                plane.configName(), kept.size(), specs.size(), specs.size() - kept.size(),
                countByGroup(kept, toolCatalog));
        if (log.isDebugEnabled()) {
            log.debug("MCP tool exposure: filtered out {}", specs.stream()
                    .map(spec -> spec.tool().name())
                    .filter(name -> !exposed(name, config, plane, toolCatalog))
                    .sorted()
                    .toList());
        }

        // 让 suggestTools 只推荐真正调得到的工具：目录是从 bean 反射来的，不经过这里的裁剪。
        toolCatalog.restrictTo(kept.stream().map(spec -> spec.tool().name()).toList());
        // 运行期开关只认这份裁剪后的列表：被上面裁掉的工具不进快照，也就无法在运行期被启用回来。
        // 这是刻意的——部署期的暴露策略是部署方的决定，不该让值班人员用一次 HTTP 调用绕过去。
        // 详见 ToolToggleRegistry 类注释。
        toggles.getObject().registerExposed(kept);
        return kept;
    }

    private static boolean exposed(String toolName, ToolExposureProperties config,
                                   ToolPlane plane, ToolCatalog catalog) {
        if (config.exclude().contains(toolName)) {
            return false;
        }
        // include 是"例外"，刻意能穿透 plane 与 groups：否则"数据面额外放一个受控写工具"无路可走。
        if (config.include().contains(toolName)) {
            return true;
        }
        ToolCatalog.ToolDescriptor descriptor = catalog.describe(toolName);
        if (descriptor == null) {
            // 目录只覆盖 McpToolBase 子类；其他来源的工具宁可多暴露也不静默丢掉
            log.warn("MCP tool exposure: tool '{}' has no known group, keeping it", toolName);
            return true;
        }
        if (!plane.includes(descriptor)) {
            return false;
        }
        if (config.groups().isEmpty()) {
            return true;
        }
        return config.groups().contains(descriptor.group());
    }

    /**
     * 校验配置里引用的分组名与工具名都真实存在，否则直接启动失败。
     *
     * <p>不做静默忽略：工具改名后配置悄悄失效，比启动报错难查得多。
     */
    private static void validate(ToolExposureProperties config, List<SyncToolSpecification> specs,
                                 ToolCatalog catalog) {
        Set<String> registered = specs.stream()
                .map(spec -> spec.tool().name())
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> unknownNames = new TreeSet<>(config.include());
        unknownNames.addAll(config.exclude());
        unknownNames.removeAll(registered);
        if (!unknownNames.isEmpty()) {
            throw new IllegalStateException(
                    "entropy.mcp.tools references unknown tool names: " + unknownNames
                            + ". Registered tools: " + registered);
        }

        Set<String> unknownGroups = new TreeSet<>(config.groups());
        unknownGroups.removeAll(catalog.groups());
        if (!unknownGroups.isEmpty()) {
            throw new IllegalStateException(
                    "entropy.mcp.tools.groups references unknown groups: " + unknownGroups
                            + ". Available groups: " + catalog.groups());
        }
    }

    private static Map<String, Long> countByGroup(List<SyncToolSpecification> specs, ToolCatalog catalog) {
        return specs.stream().collect(Collectors.groupingBy(
                spec -> {
                    String group = catalog.groupOf(spec.tool().name());
                    return group == null ? "unknown" : group;
                },
                TreeMap::new,
                Collectors.counting()));
    }
}
