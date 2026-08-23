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
package com.entropy.database.mcp.aop;

import com.entropy.database.mcp.tools.IntentRouter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AOP aspect that filters tool availability based on intent routing.
 *
 * <p>When {@code entropy.mcp.intent.filter.enabled=true}, this aspect intercepts every
 * {@code @McpTool} call, routes the incoming request through {@link IntentRouter}, and if the
 * matched intent's recommended tool list does <b>not</b> include the current tool, it throws
 * a {@code McpToolException} with {@link com.entropy.database.mcp.exception.ErrorCode#TOOL_FILTERED}.
 *
 * <p>This implements the "intent-driven tool exposure" pattern: the LLM sees a filtered set of
 * available tools per intent, reducing noise and improving routing accuracy.
 */
@Aspect
@Order(100)
@Component
public class ToolWhitelistAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolWhitelistAspect.class);
    private static final String CONFIG_KEY = "entropy.mcp.intent.filter.enabled";

    private final IntentRouter intentRouter;
    private final boolean enabled;

    public ToolWhitelistAspect(IntentRouter intentRouter, org.springframework.core.env.Environment env) {
        this.intentRouter = intentRouter;
        this.enabled = Boolean.parseBoolean(env.getProperty(CONFIG_KEY, "false"));
    }

    @Around("@annotation(mcpTool)")
    public Object filterByIntent(ProceedingJoinPoint pjp, McpTool mcpTool) throws Throwable {
        if (!enabled) {
            return pjp.proceed();
        }

        // Extract the user input from method arguments for intent routing
        String userInput = extractUserInput(pjp);
        if (userInput == null || userInput.isBlank()) {
            log.debug("No user input detected for tool '{}', skipping intent filter",
                    pjp.getSignature().getName());
            return pjp.proceed();
        }
        // Reject SQL-like strings as user input to prevent false intent matches
        if (isSqlLike(userInput)) {
            log.debug("Argument appears to be SQL, not natural language input — skipping intent filter for '{}'",
                    pjp.getSignature().getName());
            return pjp.proceed();
        }

        // Route intent and get recommended tools
        var result = intentRouter.route(userInput);
        @SuppressWarnings("unchecked")
        List<String> recommendedTools = (List<String>) result.get("recommendedTools");
        String confidence = (String) result.get("confidence");

        String toolName = pjp.getSignature().getName();
        boolean allowed = recommendedTools.contains(toolName);

        if (!allowed) {
            log.debug("Tool '{}' filtered out by intent router (intent={}, confidence={})",
                    toolName, result.get("intent"), confidence);
            throw new com.entropy.database.mcp.exception.McpToolException(
                    com.entropy.database.mcp.exception.ErrorCode.SYSTEM_ERROR,
                    "Tool '" + toolName + "' is not recommended for this intent. "
                            + "Recommended tools: " + recommendedTools
                            + ". Consider using one of: " + String.join(", ", recommendedTools));
        }

        return pjp.proceed();
    }

    /**
     * Extracts the most likely user input string from method arguments.
     * Looks for String parameters named "input", "query", "sql", "userInput", "search", etc.
     */
    private String extractUserInput(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Parameter[] params = sig.getMethod().getParameters();
        Object[] args = pjp.getArgs();

        // Priority order for user input detection
        String[] priorityNames = {"userInput", "input", "query", "search", "text", "message"};

        for (int i = 0; i < params.length && i < args.length; i++) {
            String paramName = params[i].getName();
            if (args[i] instanceof String str && !str.isBlank()) {
                for (String target : priorityNames) {
                    if (paramName.equalsIgnoreCase(target)) {
                        return str;
                    }
                }
            }
        }

        // Note: we do NOT fall back to first non-empty String arg here.
        // SQL strings like "SELECT * FROM ..." should never be routed as natural language input.
        return null;
    }

    /**
     * Heuristic check: is this string more likely SQL than natural language?
     * Matches strings starting with SQL keywords or containing typical SQL patterns.
     */
    private static boolean isSqlLike(String s) {
        String upper = s.toUpperCase().trim();
        return upper.startsWith("SELECT ") || upper.startsWith("INSERT ")
                || upper.startsWith("UPDATE ") || upper.startsWith("DELETE ")
                || upper.startsWith("CREATE ") || upper.startsWith("DROP ")
                || upper.startsWith("ALTER ") || upper.startsWith("EXPLAIN ")
                || upper.startsWith("DESCRIBE ") || upper.startsWith("DESC ")
                || upper.startsWith("SHOW ") || upper.startsWith("TRUNCATE ");
    }
}
