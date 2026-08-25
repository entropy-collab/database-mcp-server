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

import com.entropy.database.mcp.facade.DatabaseOperations;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Cache administration tools.
 */
@Component
public class CacheAdminTools extends McpToolBase {

    private final DatabaseOperations routingFacade;

    public CacheAdminTools(DatabaseOperations routingFacade) {
        this.routingFacade = routingFacade;
    }

    @McpTool(description = """
            【清空查询缓存】清空该连接对应的查询结果缓存与元数据缓存中的全部条目。
            破坏性影响：缓存条目被立即全部失效且不可恢复；后续查询与元数据探查会全部回源到数据库，短时间内延迟升高、数据库压力上升。不会修改或删除数据库中的任何数据。
            前置条件：无。connection 只用于定位到哪个数据源上下文，命中的上下文内查询缓存与元数据缓存会一并清空，不能只清其中一类。
            使用场景：库表结构或数据在服务外部被改动，缓存中的旧结果已失真，需要强制刷新。
            返回字段：status（固定文本，表示已清空）。
            不要用于：只想看缓存命中率与占用（用 getStatistics，只读不清空）；清理 MCP 会话键值（用 sessionRemove 或 sessionPurge）。
            标签：[write, cache, admin, invalidate]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> clearCache(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        routingFacade.clearCache(connection);
        return success(Map.of("status", "Cache cleared successfully"));
    }

    @McpTool(description = """
            【查看查询与缓存统计】返回该连接上的查询执行统计与缓存命中统计，只读不改变缓存内容。
            前置条件：无；统计随服务进程存活，重启后归零。
            使用场景：评估缓存是否有效、判断是否值得调整缓存容量或 TTL、确认查询错误率。
            返回字段：queryStats（一行摘要文本，含查询总数、平均耗时、返回行数、错误率、距上次查询的时间）、cacheStats（含 totalSize、queryCacheSize、metadataCacheSize、maxSize、queryTTL、metadataTTL；查询缓存启用时另含 queryHits、queryMisses、queryHitRate、queryEvictions；元数据缓存启用时另含 metadataHits、metadataMisses、metadataHitRate、metadataEvictions）。
            注意：queryStats 是格式化文本而不是结构化字段，不要按对象取子字段。
            不要用于：清空缓存（用 clearCache）；查看连接池指标（用 getPoolStats）；查看审计与慢查询（用 getAuditMetrics 或 getSlowQueries）。
            标签：[read, cache, statistics, monitor]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getStatistics(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.getStatistics(connection);
    }
}
