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

import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.monitor.HikariPoolStats;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 汇总口径的测试：内容相同的连接共用一个 Hikari 池，注册表里却是多个名字指向它，
 * 所以 {@code getPoolStats()} 的数量必须按物理池去重，而按名字查询必须仍然认识别名。
 */
class PoolMonitorToolsTest {

    private static HikariPoolStats stats(String connectionName, String canonicalName, boolean healthy) {
        return new HikariPoolStats(
                connectionName,
                canonicalName,
                "MySqlDialect",
                "jdbc:mysql://host:3306/db",
                2, 1, 1, 0,
                10, 2,
                30_000L, 600_000L, 1_800_000L, 0L,
                healthy,
                healthy ? List.of() : List.of("Pool near exhaustion: 9/10 connections active"));
    }

    private static PoolMonitorTools toolsReturning(Map<String, HikariPoolStats> byName) {
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.getPoolStats()).thenReturn(byName);
        return new PoolMonitorTools(manager);
    }

    /**
     * 旧实现直接用 {@code stats.size()} 和 {@code filter().count()}，两个名字共用一个池时
     * 池数量与健康池数量按别名个数放大，运维看到的资源占用是虚高的。
     */
    @Test
    @SuppressWarnings("unchecked")
    void poolCountsAreDeduplicatedByPhysicalPool() {
        Map<String, HikariPoolStats> byName = new LinkedHashMap<>();
        byName.put("orders", stats("orders", "orders", true));
        byName.put("orders-copy", stats("orders-copy", "orders", true));
        byName.put("orders-again", stats("orders-again", "orders", true));
        byName.put("reporting", stats("reporting", "reporting", false));

        Map<String, Object> result = toolsReturning(byName).getPoolStats();

        // 4 个名字，但只有 2 个物理池：orders（健康）与 reporting（降级）
        assertThat(result.get("totalConnections")).isEqualTo(2);
        assertThat(result.get("totalConnectionNames")).isEqualTo(4);
        assertThat(result.get("healthyPools")).isEqualTo(1L);
        assertThat(result.get("degradedPools")).isEqualTo(1L);
        assertThat((List<String>) result.get("aliasNames"))
                .containsExactly("orders-copy", "orders-again");

        // 明细仍然按名字逐条返回，并且能看出哪些名字是别名
        List<Map<String, Object>> pools = (List<Map<String, Object>>) result.get("pools");
        assertThat(pools).hasSize(4);
        assertThat(pools.get(0)).containsEntry("connectionName", "orders")
                .containsEntry("canonicalName", "orders")
                .containsEntry("isAlias", false);
        assertThat(pools.get(1)).containsEntry("connectionName", "orders-copy")
                .containsEntry("canonicalName", "orders")
                .containsEntry("isAlias", true);
    }

    /** 没有别名时口径不变：名字数等于池数。 */
    @Test
    @SuppressWarnings("unchecked")
    void countsAreUnchangedWhenThereAreNoAliases() {
        Map<String, HikariPoolStats> byName = new LinkedHashMap<>();
        byName.put("orders", stats("orders", "orders", true));
        byName.put("reporting", stats("reporting", "reporting", true));

        Map<String, Object> result = toolsReturning(byName).getPoolStats();

        assertThat(result.get("totalConnections")).isEqualTo(2);
        assertThat(result.get("totalConnectionNames")).isEqualTo(2);
        assertThat(result.get("healthyPools")).isEqualTo(2L);
        assertThat(result.get("degradedPools")).isEqualTo(0L);
        assertThat((List<String>) result.get("aliasNames")).isEmpty();
    }

    /**
     * 去重只发生在汇总计数里：别名条目不能从 map 中删掉，否则按别名查会退化成「连接不存在」。
     */
    @Test
    void anAliasNameIsStillResolvableByName() {
        Map<String, HikariPoolStats> byName = new LinkedHashMap<>();
        byName.put("orders", stats("orders", "orders", true));
        byName.put("orders-copy", stats("orders-copy", "orders", true));

        PoolMonitorTools tools = toolsReturning(byName);

        assertThat(tools.getPoolStatsForConnection("orders-copy"))
                .containsEntry("connectionName", "orders-copy")
                .containsEntry("canonicalName", "orders")
                .containsEntry("isAlias", true);
        assertThatThrownBy(() -> tools.getPoolStatsForConnection("never-acquired"))
                .isInstanceOf(McpToolException.class);
    }
}
