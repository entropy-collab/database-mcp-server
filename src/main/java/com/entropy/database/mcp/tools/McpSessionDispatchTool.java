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

import com.entropy.database.mcp.extension.CustomToolRegistrar;
import com.entropy.database.mcp.session.MultiSessionContext;
import com.entropy.database.mcp.session.McpToolContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session management and custom tool dispatch tools.
 *
 * <p>Provides three key capabilities:
 * <ol>
 *   <li><b>Session context management</b> — view and manipulate the current MCP session state</li>
 *   <li><b>Custom tool invocation</b> — dispatch calls to dynamically registered tools</li>
 *   <li><b>Cross-tool data sharing</b> — store and retrieve data via {@link MultiSessionContext}</li>
 * </ol>
 *
 * <p>These tools enable the AI model to:
 * <ul>
 *   <li>Save query results to session storage for later use</li>
 *   <li>Invoke custom plugins or extended tools</li>
 *   <li>Check session state and available tools</li>
 * </ul>
 */
@Component
public class McpSessionDispatchTool extends McpToolBase {

    private final CustomToolRegistrar customToolRegistrar;
    private final MultiSessionContext multiSessionContext;

    public McpSessionDispatchTool(CustomToolRegistrar customToolRegistrar,
                                  MultiSessionContext multiSessionContext) {
        this.customToolRegistrar = customToolRegistrar;
        this.multiSessionContext = multiSessionContext;
    }

    @McpTool(description = "Store data in the current MCP session context for cross-tool sharing")
    public Map<String, Object> sessionStore(
            @McpToolParam(description = "Namespace: 'queries', 'etl', 'preferences', or 'scratch'") String namespace,
            @McpToolParam(description = "Key within the namespace") String key,
            @McpToolParam(description = "Value to store (any serializable object)") Object value) {
        return safeExecute(() -> {
            multiSessionContext.set(namespace, key, value);
            log.debug("Stored '{}' in session namespace '{}' (session={})", key, namespace, multiSessionContext.currentSessionId());
            return success(context("namespace", namespace, "key", key, "stored", true));
        });
    }

    @McpTool(description = "Retrieve data from the current MCP session context")
    public Map<String, Object> sessionGet(
            @McpToolParam(description = "Namespace to query") String namespace,
            @McpToolParam(description = "Key to retrieve") String key) {
        return safeExecute(() -> {
            Object value = multiSessionContext.get(namespace, key);
            if (value == null) {
                return success(Map.of("namespace", namespace, "key", key, "found", false, "value", null));
            }
            return success(Map.of("namespace", namespace, "key", key, "found", true, "value", value));
        });
    }

    @McpTool(description = "List all keys in a session namespace")
    public Map<String, Object> sessionKeys(
            @McpToolParam(description = "Namespace to list keys for") String namespace) {
        return safeExecute(() -> {
            Set<String> keys = multiSessionContext.keys(namespace);
            return success(context("namespace", namespace, "keys", keys, "count", keys.size()));
        });
    }

    @McpTool(description = "Remove a value from the session context")
    public Map<String, Object> sessionRemove(
            @McpToolParam(description = "Namespace") String namespace,
            @McpToolParam(description = "Key to remove") String key) {
        return safeExecute(() -> {
            multiSessionContext.remove(namespace, key);
            return success(context("namespace", namespace, "key", key, "removed", true));
        });
    }

    @McpTool(description = "Purge expired entries from all session namespaces")
    public Map<String, Object> sessionPurge() {
        return safeExecute(() -> {
            multiSessionContext.purgeExpired();
            return success(context("purged", true, "activeSessions", multiSessionContext.sessionCount()));
        });
    }

    @McpTool(description = "Invoke a custom tool registered via CustomToolRegistrar")
    public Map<String, Object> invokeCustomTool(
            @McpToolParam(description = "Name of the custom tool to invoke") String toolName,
            @McpToolParam(description = "Tool arguments as JSON object", required = false) Map<String, Object> args) {
        return safeExecute(() -> {
            Map<String, Object> result = customToolRegistrar.invoke(toolName, args != null ? args : Map.of());
            McpToolContext.current().ifPresent(ctx -> ctx.putMeta("lastCustomTool", toolName));
            return result;
        });
    }

    @McpTool(description = "List all registered custom tools")
    public Map<String, Object> listCustomTools() {
        return safeExecute(() -> {
            Set<String> tools = customToolRegistrar.listTools();
            List<Map<String, Object>> details = tools.stream()
                    .map(name -> customToolRegistrar.getTool(name)
                            .map(def -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("name", def.name());
                                m.put("description", def.description());
                                return m;
                            })
                            .orElse(Map.of("name", name)))
                    .toList();
            return success(context("tools", details, "count", tools.size()));
        });
    }

    @McpTool(description = "Scan a package for @McpTool annotated classes and register them as custom tools")
    public Map<String, Object> scanCustomTools(
            @McpToolParam(description = "Java package to scan (e.g., 'com.example.mytools')") String basePackage) {
        return safeExecute(() -> {
            int before = customToolRegistrar.listTools().size();
            customToolRegistrar.scanPackage(basePackage);
            int after = customToolRegistrar.listTools().size();
            return success(context("package", basePackage, "registered", after - before, "totalTools", after));
        });
    }

    @McpTool(description = "Get current session metadata (correlation ID, elapsed time, active tools)")
    public Map<String, Object> getSessionInfo() {
        return safeExecute(() -> {
            McpToolContext ctx = McpToolContext.current().orElse(null);
            if (ctx == null) {
                return success(Map.of("sessionId", multiSessionContext.currentSessionId(),
                        "hasSession", false, "activeTools", customToolRegistrar.listTools().size()));
            }
            return success(context(
                    "sessionId", multiSessionContext.currentSessionId(),
                    "correlationId", ctx.correlationId(),
                    "toolName", ctx.toolName(),
                    "connection", ctx.connection(),
                    "elapsedMillis", ctx.elapsedMillis(),
                    "activeTools", customToolRegistrar.listTools().size(),
                    "hasMeta", ctx.hasMeta("lastCustomTool")));
        });
    }
}
