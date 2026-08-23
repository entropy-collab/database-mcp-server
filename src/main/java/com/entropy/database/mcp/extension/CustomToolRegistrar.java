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
package com.entropy.database.mcp.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Custom tool registration extension point for the MCP server.
 *
 * <p>This class provides a programmatic way to register additional MCP tools at runtime,
 * complementing the annotation-based {@code @McpTool} registration. It follows the
 * Spring {@code BeanDefinitionRegistryPostProcessor} pattern for extensible tool discovery.
 *
 * <p>Usage patterns:
 * <pre>{@code
 * // 1. Programmatic registration
 * registrar.register("myTool", (args) -> {
 *     String name = (String) args.get("name");
 *     return Map.of("success", true, "greeting", "Hello " + name);
 * });
 *
 * // 2. Register a lambda tool (no Spring bean needed)
 * registrar.registerLambda("ping", Map.of("name", String.class), () -> Map.of("pong", true));
 *
 * // 3. Scan a package for @McpTool annotated beans
 * registrar.scanPackage("com.example.mytools");
 *
 * // 4. List all registered tools
 * Set<String> tools = registrar.listTools();
 * }</pre>
 *
 * <p>This is particularly useful for:
 * <ul>
 *   <li>Plugins that need to add tools dynamically</li>
 *   <li>Conditional tools based on feature flags</li>
 *   <li>Testing with mock tools</li>
 *   <li>Runtime tool hot-reloading</li>
 * </ul>
 *
 * <p>Registered tools are exposed through the {@code listTools} query method and
 * can be invoked via the standard MCP tool call protocol.
 *
 * <p>Implementation note: This registers tools as a separate Spring bean class that
 * wraps the custom tools and exposes them via {@code @McpTool} annotation on a single
 * dispatch method. This works with the existing Spring AI MCP framework without
 * requiring framework-level changes.
 */
@Component
public class CustomToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(CustomToolRegistrar.class);

    /** Tool definition returned to the MCP framework. */
    public record ToolDefinition(
            String name,
            String description,
            Map<String, Object> parameters,
            ToolHandler handler
    ) {
        @FunctionalInterface
        public interface ToolHandler {
            Map<String, Object> handle(Map<String, Object> args);
        }
    }

    private final Map<String, ToolDefinition> registeredTools = new ConcurrentHashMap<>();
    private final ApplicationContext applicationContext;

    public CustomToolRegistrar(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        log.info("CustomToolRegistrar initialized");
    }

    /**
     * Register a tool with a custom handler.
     *
     * @param name         unique tool name
     * @param description  tool description (shown to the AI model)
     * @param parameters   JSON Schema-like parameter definitions
     * @param handler      the tool implementation
     */
    public void register(String name, String description, Map<String, Object> parameters,
                         ToolDefinition.ToolHandler handler) {
        registeredTools.put(name, new ToolDefinition(name, description, parameters, handler));
        log.info("Registered custom tool: {}", name);
    }

    /**
     * Register a simple lambda tool (no parameters).
     */
    public void registerLambda(String name, String description, ToolDefinition.ToolHandler handler) {
        register(name, description, Map.of(), handler);
    }

    /**
     * Register a parameterized lambda tool.
     */
    public void registerLambda(String name, String description,
                               Map<String, Class<?>> paramTypes, ToolDefinition.ToolHandler handler) {
        Map<String, Object> params = new LinkedHashMap<>();
        paramTypes.forEach((k, v) -> params.put(k, Map.of("type", v.getSimpleName(), "description", "")));
        register(name, description, params, handler);
    }

    /**
     * Check if a tool is registered.
     */
    public boolean hasTool(String name) {
        return registeredTools.containsKey(name);
    }

    /**
     * List all registered custom tool names.
     */
    public Set<String> listTools() {
        return Collections.unmodifiableSet(registeredTools.keySet());
    }

    /**
     * Get a tool's definition.
     */
    public Optional<ToolDefinition> getTool(String name) {
        return Optional.ofNullable(registeredTools.get(name));
    }

    /**
     * Remove a registered tool.
     */
    public void unregister(String name) {
        registeredTools.remove(name);
        log.info("Unregistered custom tool: {}", name);
    }

    /**
     * Scan a package for classes annotated with {@code @McpTool} and register them.
     *
     * <p>This uses Spring's {@code ClassPathScanningCandidateComponentProvider} to
     * discover tool classes and reflects over their methods to extract tool definitions.
     *
     * @param basePackage the package to scan (e.g., "com.example.mytools")
     */
    public void scanPackage(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(McpTool.class));

        Set<BeanDefinition> candidates = scanner.findCandidateComponents(basePackage);
        int registered = 0;

        for (BeanDefinition bd : candidates) {
            try {
                Class<?> clazz = Class.forName(bd.getBeanClassName());
                Object instance = applicationContext.getBean(clazz);
                for (Method method : clazz.getDeclaredMethods()) {
                    McpTool toolAnn = method.getAnnotation(McpTool.class);
                    if (toolAnn == null) continue;

                    String toolName = toolAnn.name().isEmpty() ? method.getName() : toolAnn.name();
                    String description = toolAnn.description();

                    register(toolName, description, Map.of(), args -> {
                        try {
                            // Simple reflection-based dispatch for discovered tools
                            Object[] params = extractArgs(method, args);
                            Object result = method.invoke(instance, params);
                            if (result instanceof Map<?, ?> map) {
                                java.util.Map<String, Object> mapped = new java.util.LinkedHashMap<>();
                                map.forEach((k, v) -> mapped.put(k.toString(), v));
                                return mapped;
                            }
                            return Map.of("result", result, "success", true);
                        } catch (Exception e) {
                            return Map.of("success", false, "error", e.getMessage(), "cause", e.getClass().getSimpleName());
                        }
                    });
                    registered++;
                }
            } catch (Exception e) {
                log.warn("Failed to register tool from class {}: {}", bd.getBeanClassName(), e.getMessage());
            }
        }

        log.info("Scanned package '{}': registered {} custom tools", basePackage, registered);
    }

    /**
     * Invoke a custom tool by name with the given arguments.
     * This is the entry point used by the dispatch tool.
     */
    public Map<String, Object> invoke(String toolName, Map<String, Object> args) {
        ToolDefinition def = registeredTools.get(toolName);
        if (def == null) {
            return Map.of("success", false, "error", "Unknown tool: " + toolName,
                    "cause", "NoSuchToolException", "availableTools", new ArrayList<>(registeredTools.keySet()));
        }
        try {
            return def.handler().handle(args);
        } catch (Exception e) {
            log.warn("Custom tool '{}' execution failed: {}", toolName, e.getMessage(), e);
            return Map.of("success", false, "error", e.getMessage(), "cause", e.getClass().getSimpleName());
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    private Object[] extractArgs(Method method, Map<String, Object> args) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        Object[] result = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String paramName = params[i].getName();
            result[i] = args.get(paramName);
        }
        return result;
    }
}
