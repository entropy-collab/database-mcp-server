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

import java.util.Locale;
import java.util.Set;

/**
 * MCP 工具暴露面配置：控制启动时把哪些工具写进 {@code tools/list}。
 *
 * <p>四个开关全为默认时不做任何裁剪，行为与历史版本一致。生效顺序：
 * <ol>
 *   <li>{@code plane} 决定基集合：{@code all}（默认）为全部工具，{@code data} 只保留只读工具，
 *       {@code control} 只保留会改动状态的工具；两者互补且无交集</li>
 *   <li>{@code groups} 非空 → 在基集合内再收敛为这些分组下的工具</li>
 *   <li>{@code include} 里的工具名并入结果（用于"整组关掉但保留个别工具"）</li>
 *   <li>{@code exclude} 里的工具名从结果中移除，优先级最高</li>
 * </ol>
 *
 * <p>分组名由工具类名推导（{@code DatabaseHealthTools → database-health}），可用值见
 * {@code ToolCatalog#groups()}；启动日志会打印裁剪结果。
 *
 * <p>{@code include}/{@code exclude}/{@code groups}/{@code plane} 里出现未知名称会直接启动失败，
 * 而不是静默忽略——这是刻意的：上一代意图白名单正是因为工具名清单与真实方法名静默漂移而失效。
 *
 * <p><strong>注意 {@code include} 能穿透 {@code plane}。</strong>它就是"例外"的意思，把它做成
 * 无法覆盖 plane 会让"数据面加一个受控写工具"这种正当需求无路可走。plane 是暴露面收敛手段，
 * 不是安全边界——真正的闸在 {@code entropy.mcp.database.ddl.allowed} 与
 * {@code entropy.mcp.security.*}。
 *
 * @param plane   部署面预设，取值 {@code all} / {@code data} / {@code control}；空值等同 {@code all}
 * @param groups  只暴露这些分组；空集表示不按分组过滤
 * @param include 无论 plane 与分组如何都要暴露的工具名
 * @param exclude 一律不暴露的工具名，优先级最高
 */
@ConfigurationProperties(prefix = "entropy.mcp.tools")
public record ToolExposureProperties(
    String plane,
    Set<String> groups,
    Set<String> include,
    Set<String> exclude
) {

    /** {@code plane} 的默认值，语义为"不按面裁剪"。 */
    public static final String PLANE_ALL = "all";

    public ToolExposureProperties {
        plane = plane == null || plane.isBlank() ? PLANE_ALL : plane.strip().toLowerCase(Locale.ROOT);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        include = include == null ? Set.of() : Set.copyOf(include);
        exclude = exclude == null ? Set.of() : Set.copyOf(exclude);
    }

    /** 四个开关都是默认值时无需裁剪。 */
    public boolean isNoop() {
        return PLANE_ALL.equals(plane) && groups.isEmpty() && include.isEmpty() && exclude.isEmpty();
    }
}
