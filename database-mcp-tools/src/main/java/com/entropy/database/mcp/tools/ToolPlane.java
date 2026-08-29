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

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 部署面预设：把 116 个工具切成"只读的数据面"与"会改状态的控制面"两个互补集合。
 *
 * <h2>为什么按标签推导，而不是列一张类名清单</h2>
 * 两个面的成员完全由 {@code @McpTool} 描述里既有的 {@code 标签：[...]} 决定，不新增任何需要
 * 人工维护的名单。{@link ToolCatalog} 的 Javadoc 已经记过教训：上一版意图路由把工具名硬编码
 * 成静态 Map，很快与真实方法名漂移。按分组切也不行——{@code cross-database} 里既有联邦查询
 * 也有 {@code createDbLink}/{@code dropDbLink}，{@code database-health} 里既有诊断也有
 * {@code gatherTableStats}，类粒度切不开。
 *
 * <h2>判据</h2>
 * 数据面要求工具<strong>正面证明自己只读</strong>：带 {@code read} 标签，且不带
 * {@link #MUTATING_TAGS} 里的任何一个。控制面是它的补集。因此：
 * <ul>
 *   <li>两个面的并集是全部工具，交集为空——没有工具会在切分中丢失，也不会两边都出现；</li>
 *   <li>标签缺失或写得不清楚的新工具会落进控制面。数据面是要多副本、暴露面最大的那一侧，
 *       对它 fail-closed；控制面单副本、可信，对它 fail-open。</li>
 * </ul>
 *
 * <p>这只是<em>暴露面</em>的切分：两个面的 bean 仍然都会被容器创建，数据面实例照样构造
 * ETL/备份/CDC 的 service。真正的安全闸在 {@code entropy.mcp.database.ddl.allowed} 与
 * {@code entropy.mcp.security.*}，不在这里。
 */
enum ToolPlane {

    /** 不按面裁剪。 */
    ALL {
        @Override
        boolean includes(ToolCatalog.ToolDescriptor descriptor) {
            return true;
        }
    },

    /** 只读查询面：高并发、短超时、可多副本。 */
    DATA {
        @Override
        boolean includes(ToolCatalog.ToolDescriptor descriptor) {
            return readOnly(descriptor);
        }
    },

    /** 连接注册、备份恢复、ETL、CDC、编目扫描、DDL：低并发、长超时、有进程内状态、单副本。 */
    CONTROL {
        @Override
        boolean includes(ToolCatalog.ToolDescriptor descriptor) {
            return !readOnly(descriptor);
        }
    };

    /** 出现任意一个就说明工具会改动状态或系统配置，因此不属于数据面。 */
    private static final Set<String> MUTATING_TAGS = Set.of("write", "ddl", "destructive", "admin");

    private static final String READ_TAG = "read";

    abstract boolean includes(ToolCatalog.ToolDescriptor descriptor);

    private static boolean readOnly(ToolCatalog.ToolDescriptor descriptor) {
        List<String> tags = descriptor.tags();
        return tags.contains(READ_TAG) && tags.stream().noneMatch(MUTATING_TAGS::contains);
    }

    /** 配置里写的名字，小写。 */
    String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析 {@code entropy.mcp.tools.plane}。
     *
     * @throws IllegalStateException 名称未知时直接让启动失败，与 groups/include/exclude 的
     *                               严格校验保持一致——静默退化成 {@code all} 会让一个本该只读的
     *                               部署悄悄暴露出全部写工具
     */
    static ToolPlane parse(String configured) {
        String name = configured == null || configured.isBlank()
                ? ToolExposureProperties.PLANE_ALL
                : configured.strip().toLowerCase(Locale.ROOT);
        for (ToolPlane plane : values()) {
            if (plane.configName().equals(name)) {
                return plane;
            }
        }
        throw new IllegalStateException("entropy.mcp.tools.plane='" + configured
                + "' is unknown. Valid values: all, data, control");
    }
}
