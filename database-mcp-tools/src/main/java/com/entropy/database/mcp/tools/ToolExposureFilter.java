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
 * 汇总成一个 {@code List} bean 后一次性灌进 MCP server。工具清单只在启动时确定一次，
 * 且服务以 {@code tools(listChanged=false)} 声明能力——协议层不支持运行期变更通知。
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
 */
@Component
public class ToolExposureFilter implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ToolExposureFilter.class);

    private final ObjectProvider<ToolExposureProperties> properties;
    private final ObjectProvider<ToolCatalog> catalog;

    public ToolExposureFilter(ObjectProvider<ToolExposureProperties> properties,
                              ObjectProvider<ToolCatalog> catalog) {
        this.properties = properties;
        this.catalog = catalog;
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
            return bean;
        }

        ToolCatalog toolCatalog = catalog.getObject();
        validate(config, specs, toolCatalog);

        List<SyncToolSpecification> kept = specs.stream()
                .filter(spec -> exposed(spec.tool().name(), config, toolCatalog))
                .toList();

        if (kept.isEmpty()) {
            throw new IllegalStateException(
                    "entropy.mcp.tools filtered out every tool — the MCP server would expose nothing. "
                            + "Check groups/include/exclude. Available groups: " + toolCatalog.groups());
        }

        log.info("MCP tool exposure: {} of {} tools registered, {} filtered out. Kept per group: {}",
                kept.size(), specs.size(), specs.size() - kept.size(), countByGroup(kept, toolCatalog));
        if (log.isDebugEnabled()) {
            log.debug("MCP tool exposure: filtered out {}", specs.stream()
                    .map(spec -> spec.tool().name())
                    .filter(name -> !exposed(name, config, toolCatalog))
                    .sorted()
                    .toList());
        }
        return kept;
    }

    private static boolean exposed(String toolName, ToolExposureProperties config, ToolCatalog catalog) {
        if (config.exclude().contains(toolName)) {
            return false;
        }
        if (config.include().contains(toolName)) {
            return true;
        }
        if (config.groups().isEmpty()) {
            return true;
        }
        String group = catalog.groupOf(toolName);
        if (group == null) {
            // 目录只覆盖 McpToolBase 子类；其他来源的工具宁可多暴露也不静默丢掉
            log.warn("MCP tool exposure: tool '{}' has no known group, keeping it", toolName);
            return true;
        }
        return config.groups().contains(group);
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
