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
package com.entropy.database.mcp.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * MCP 工具暴露面配置：控制启动时把哪些工具写进 {@code tools/list}。
 *
 * <p>三个开关全为空（默认）时不做任何裁剪，行为与历史版本一致。生效顺序：
 * <ol>
 *   <li>{@code groups} 非空 → 基集合收敛为这些分组下的工具；为空 → 基集合是全部工具</li>
 *   <li>{@code include} 里的工具名并入基集合（用于"整组关掉但保留个别工具"）</li>
 *   <li>{@code exclude} 里的工具名从结果中移除，优先级最高</li>
 * </ol>
 *
 * <p>分组名由工具类名推导（{@code DatabaseHealthTools → database-health}），可用值见
 * {@code ToolCatalog#groups()}；启动日志会打印裁剪结果。
 *
 * <p>{@code include}/{@code exclude}/{@code groups} 里出现未知名称会直接启动失败，而不是
 * 静默忽略——这是刻意的：上一代意图白名单正是因为工具名清单与真实方法名静默漂移而失效。
 *
 * @param groups  只暴露这些分组；空集表示不按分组过滤
 * @param include 无论分组如何都要暴露的工具名
 * @param exclude 一律不暴露的工具名，优先级高于 groups 与 include
 */
@ConfigurationProperties(prefix = "entropy.mcp.tools")
public record ToolExposureProperties(
    Set<String> groups,
    Set<String> include,
    Set<String> exclude
) {
    public ToolExposureProperties {
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        include = include == null ? Set.of() : Set.copyOf(include);
        exclude = exclude == null ? Set.of() : Set.copyOf(exclude);
    }

    /** 三个开关都为空时无需裁剪。 */
    public boolean isNoop() {
        return groups.isEmpty() && include.isEmpty() && exclude.isEmpty();
    }
}
