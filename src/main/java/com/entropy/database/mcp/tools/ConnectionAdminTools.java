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

    @McpTool(description = "List all registered datasource connections",
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

    @McpTool(description = "Get detailed metadata for a specific connection",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> describeConnection(
            @McpToolParam(description = "Connection key") String connectionName) {
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

    @McpTool(description = "Get connection count summary (active and total registered)",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getConnectionCount() {
        return safeExecute(() -> success(Map.of(
                "activeConnections", dataSourceManager.getActiveConnectionCount(),
                "totalRegistered", dataSourceManager.getConnectionCount())));
    }
}
