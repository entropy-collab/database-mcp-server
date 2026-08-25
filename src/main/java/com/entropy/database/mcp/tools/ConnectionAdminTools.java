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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.byok.ConnectionMetadata;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

/**
 * Connection administration tools.
 */
@Component
public class ConnectionAdminTools extends McpToolBase {

    private final DynamicDataSourceManager dataSourceManager;

    public ConnectionAdminTools(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @McpTool(description = """
            【列出所有连接】列出已注册的全部数据源连接及其概要信息。无入参。
            前置条件：无；未注册任何连接时 connections 为空数组。
            使用场景：不知道有哪些可用连接名时先列一遍，再把连接名传给其他工具的 connection 参数。
            返回字段：totalConnections（已注册连接数）、activeConnections（活跃连接数）、connections（按 key 升序排列的数组，每项含 key、dialect、jdbcUrlMasked、owner、status、createdAt、leaseExpiry、maxLifetimeExpiry、poolSize；元数据缺失的连接只含 key 与 status=UNKNOWN）。status 取值 ACTIVE、EXPIRED_LEASE、EXPIRED_MAX_LIFETIME。
            不要用于：查看单个连接的完整明细，如租约 TTL 与当前活跃连接数（用 describeConnection）；只要数量（用 getConnectionCount）；查看连接池运行指标（用 getPoolStats）。
            标签：[read, connection, admin, list]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listConnections() {
        return safeExecute(() -> {
            Collection<String> keys = dataSourceManager.listConnectionKeys();
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("totalConnections", keys.size());
            result.put("activeConnections", dataSourceManager.getActiveConnectionCount());
            result.put("connections", keys.stream()
                    .map(key -> {
                        ConnectionMetadata meta = dataSourceManager.getConnectionMetadata(key);
                        if (meta == null) {
                            return Map.<String, Object>of("key", key, "status", "UNKNOWN");
                        }
                        return Map.<String, Object>of(
                                "key", meta.key(), "dialect", meta.dialect(),
                                "jdbcUrlMasked", meta.jdbcUrlMasked(), "owner", meta.owner(),
                                "status", meta.getStatus(),
                                "createdAt", meta.createdAt().toString(),
                                "leaseExpiry", meta.getLeaseExpiry().toString(),
                                "maxLifetimeExpiry", meta.getMaxLifetimeExpiry().toString(),
                                "poolSize", meta.poolSize());
                    })
                    .sorted(Comparator.comparing(m -> (String) m.get("key")))
                    .toList());
            return success(result);
        });
    }

    @McpTool(description = """
            【查看连接详情】查看单个连接的完整元数据，包括方言、脱敏后的 JDBC URL、租约与生命周期到期时间、池大小与活跃连接数。
            前置条件：连接必须已注册，否则报连接不存在。连接注册是异步的——调用 createNamedConnection 之后请用本工具确认 status 为 ACTIVE、连接已就绪，再执行查询。
            使用场景：确认连接是否可用、排查查询失败是否因租约过期、确认服务端识别到的数据库方言。
            返回字段：connection（含 key、dialect、jdbcUrlMasked、owner、status、createdAt、leaseTtl、leaseExpiry、maxLifetime、maxLifetimeExpiry、poolSize、activeConnections）。status 取值 ACTIVE、EXPIRED_LEASE、EXPIRED_MAX_LIFETIME。
            不要用于：不知道连接名时逐个试探（先用 listConnections）；查看池的实时使用率与健康告警（用 getPoolStatsForConnection）。
            标签：[read, connection, admin, metadata]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> describeConnection(
            @McpToolParam(description = "要查询的 BYOK 连接名，必填且不可省略；须与 listConnections 返回的 key 完全一致，未注册时报连接不存在") String connectionName) {
        return safeExecute(() -> {
            ConnectionMetadata meta = dataSourceManager.getConnectionMetadata(connectionName);
            if (meta == null) {
                throw new McpToolException(ErrorCode.CONNECTION_NOT_FOUND, "Connection not found: " + connectionName, connectionName);
            }
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("key", meta.key());
            detail.put("dialect", meta.dialect());
            detail.put("jdbcUrlMasked", meta.jdbcUrlMasked());
            detail.put("owner", meta.owner());
            detail.put("status", meta.getStatus());
            detail.put("createdAt", meta.createdAt().toString());
            detail.put("leaseTtl", meta.leaseTtl().toString());
            detail.put("leaseExpiry", meta.getLeaseExpiry().toString());
            detail.put("maxLifetime", meta.maxLifetime().toString());
            detail.put("maxLifetimeExpiry", meta.getMaxLifetimeExpiry().toString());
            detail.put("poolSize", meta.poolSize());
            detail.put("activeConnections", meta.activeConnections());
            return success(Map.of("connection", detail));
        });
    }

    @McpTool(description = """
            【统计连接数量】只返回连接数量的汇总数字，不返回任何连接名或明细。无入参。
            前置条件：无。
            使用场景：只需判断是否已有连接、或监控活跃连接规模，不关心具体是哪些连接。
            返回字段：activeConnections（活跃连接数）、totalRegistered（已注册连接总数）。
            不要用于：需要知道具体连接名（用 listConnections）；需要单个连接的状态与到期时间（用 describeConnection）。
            标签：[read, connection, admin, count]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getConnectionCount() {
        return safeExecute(() -> success(Map.of(
                "activeConnections", dataSourceManager.getActiveConnectionCount(),
                "totalRegistered", dataSourceManager.getConnectionCount())));
    }
}
