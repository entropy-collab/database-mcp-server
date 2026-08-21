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

import com.entropy.database.mcp.byok.ConnectionMetadata;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.stream.Collectors;

import static com.entropy.database.mcp.tools.McpToolUtils.errorResponse;
import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;
import static com.entropy.database.mcp.util.ValidationUtils.requireNotBlank;

/**
 * Connection administration tools.
 */
@Configuration
public class ConnectionAdminTools {

    private static final Logger log = LoggerFactory.getLogger(ConnectionAdminTools.class);

    private final DynamicDataSourceManager dataSourceManager;

    public ConnectionAdminTools(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @McpTool(description = "List all registered datasource connections")
    public Map<String, Object> listConnections() {
        try {
            Collection<String> keys = dataSourceManager.listConnectionKeys();
            List<Map<String, Object>> connections = keys.stream()
                    .map(key -> {
                        ConnectionMetadata meta = dataSourceManager.getConnectionMetadata(key);
                        if (meta == null) {
                            return Map.<String, Object>of("key", key, "status", "UNKNOWN");
                        }
                        return Map.<String, Object>of(
                                "key", meta.key(),
                                "dialect", meta.dialect(),
                                "jdbcUrlMasked", meta.jdbcUrlMasked(),
                                "owner", meta.owner(),
                                "isPrimary", Boolean.toString(meta.isPrimary()),
                                "status", meta.getStatus(),
                                "createdAt", meta.createdAt().toString(),
                                "leaseExpiry", meta.getLeaseExpiry().toString(),
                                "maxLifetimeExpiry", meta.getMaxLifetimeExpiry().toString(),
                                "poolSize", meta.poolSize()
                        );
                    })
                    .sorted(Comparator.comparing(m -> (String) m.get("key")))
                    .collect(Collectors.toList());

            return successResponse(Map.of(
                    "totalConnections", connections.size(),
                    "activeConnections", dataSourceManager.getActiveConnectionCount(),
                    "connections", connections
            ));
        } catch (Exception e) {
            return McpToolUtils.errorResponse(Map.of(), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Get detailed metadata for a specific connection")
    public Map<String, Object> describeConnection(
            @McpToolParam(description = "Connection key (e.g. 'primary' or BYOK key)") String connectionName) {
        try {
            ConnectionMetadata meta = dataSourceManager.getConnectionMetadata(connectionName);
            if (meta == null) {
                return errorResponse(Map.of("connectionName", connectionName), "Connection not found: " + connectionName, "NotFoundException");
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("key", meta.key());
            detail.put("dialect", meta.dialect());
            detail.put("jdbcUrlMasked", meta.jdbcUrlMasked());
            detail.put("owner", meta.owner());
            detail.put("isPrimary", Boolean.toString(meta.isPrimary()));
            detail.put("status", meta.getStatus());
            detail.put("createdAt", meta.createdAt().toString());
            detail.put("leaseTtl", meta.leaseTtl().toString());
            detail.put("leaseExpiry", meta.getLeaseExpiry().toString());
            detail.put("maxLifetime", meta.maxLifetime().toString());
            detail.put("maxLifetimeExpiry", meta.getMaxLifetimeExpiry().toString());
            detail.put("poolSize", meta.poolSize());
            detail.put("activeConnections", meta.activeConnections());

            return successResponse(Map.of("connection", detail));
        } catch (Exception e) {
            return errorResponse(Map.of("connectionName", connectionName), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Get the current number of active BYOK connections")
    public Map<String, Object> getConnectionCount() {
        try {
            int activeCount = dataSourceManager.getActiveConnectionCount();
            int totalRegistered = dataSourceManager.getConnectionCount();
            return successResponse(Map.of(
                    "activeConnections", activeCount,
                    "totalRegistered", totalRegistered
            ));
        } catch (Exception e) {
            return McpToolUtils.errorResponse(Map.of(), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(name = "getActiveConnectionCount", description = "Get the current number of active BYOK connections (alias)")
    public Map<String, Object> getActiveConnectionCount() {
        return getConnectionCount();
    }
}
