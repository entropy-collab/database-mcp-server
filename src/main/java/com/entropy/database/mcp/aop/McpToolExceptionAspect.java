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

import com.entropy.database.mcp.exception.DatabaseMcpException;
import com.entropy.database.mcp.exception.ErrorSuggestionEngine;
import com.entropy.database.mcp.tools.McpToolUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AOP aspect for unified exception handling and connection injection in MCP tools.
 * Wraps all @McpTool methods with standardized error response formatting,
 * and automatically injects the {@code connection} name into successful responses.
 *
 * <p>Uses the {@link ConnectionArgPolicy} strategy pattern to identify connection name
 * arguments, with annotation-driven detection via parameter names.
 */
@Aspect
@Component
public class McpToolExceptionAspect {

    private static final Logger log = LoggerFactory.getLogger(McpToolExceptionAspect.class);
    private static final String CONNECTION_PARAM = "connection";
    private static final String SENSITIVE_PARAM_NAMES = "sql,password,jdbcUrl,token,secret,key";

    private final ConnectionArgPolicy connectionArgPolicy;
    private final ErrorSuggestionEngine suggestionEngine;

    public McpToolExceptionAspect(ErrorSuggestionEngine suggestionEngine) {
        this.connectionArgPolicy = new AnnotatedConnectionArgPolicy();
        this.suggestionEngine = suggestionEngine;
    }

    @Around("execution(@org.springframework.ai.mcp.annotation.McpTool * *(..))")
    public Object handleMcPException(ProceedingJoinPoint pjp) throws Throwable {
        try {
            Object result = pjp.proceed();
            injectConnection(result, pjp);
            return result;
        } catch (DatabaseMcpException e) {
            String toolName = pjp.getSignature().getName();
            log.warn("{} failed: {}", toolName, e.getMessage(), e);
            return McpToolUtils.errorResponse(
                    extractContext(pjp),
                    e.getMessage(),
                    e.getErrorCode().getCode()
            );
        } catch (Exception e) {
            String toolName = pjp.getSignature().getName();
            log.warn("{} failed", toolName, e);
            return McpToolUtils.errorResponse(
                    extractContext(pjp),
                    e.getMessage(),
                    e.getClass().getSimpleName()
            );
        }
    }

    /**
     * Inject the connection name into the result when the tool was called with a connection.
     */
    private void injectConnection(Object result, ProceedingJoinPoint pjp) {
        if (!(result instanceof Map<?, ?> resultMap)) {
            return;
        }
        String connection = extractConnectionArg(pjp);
        if (connection == null) {
            return;
        }
        try {
            //noinspection unchecked
            ((Map<String, Object>) result).putIfAbsent(CONNECTION_PARAM, connection);
        } catch (Exception ignored) {
            // result is immutable; skip injection
        }
    }

    private String extractConnectionArg(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = pjp.getArgs();

        if (parameters.length == 0 || args == null) {
            return null;
        }

        // Try to find the "connection" parameter by name via reflection
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            Parameter param = parameters[i];
            if (param.isNamePresent() && "connection".equalsIgnoreCase(param.getName())) {
                if (args[i] instanceof String s && !s.isBlank()) {
                    return s;
                }
                return null;
            }
        }

        // Fallback: heuristic detection via strategy
        return connectionArgPolicy.extractConnectionName(args).orElse(null);
    }

    private Map<String, Object> extractContext(ProceedingJoinPoint pjp) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tool", pjp.getSignature().getName());
        Object[] args = pjp.getArgs();
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg != null) {
                    context.put("arg" + i, maskSensitive(arg));
                }
            }
        }
        return context;
    }

    private Object maskSensitive(Object value) {
        if (!(value instanceof String s)) {
            return value;
        }
        String lower = s.toLowerCase();
        for (String keyword : SENSITIVE_PARAM_NAMES.split(",")) {
            if (lower.contains(keyword.trim())) {
                if (s.length() <= 4) {
                    return "***";
                }
                return s.substring(0, 2) + "***" + s.substring(s.length() - 2);
            }
        }
        return s;
    }

    /**
     * Add intelligent suggestions to error responses.
     */
    private void addSuggestions(Map<String, Object> response, String toolName, Exception exception) {
        try {
            List<String> suggestions = suggestionEngine.analyze(exception, toolName);
            if (!suggestions.isEmpty()) {
                response.put("suggestions", suggestions);
            }
        } catch (Exception e) {
            // Don't let suggestion generation break the response
            log.debug("Failed to generate suggestions for {}: {}", toolName, e.getMessage());
        }
    }

    /**
     * Connection argument resolution using Java reflection parameter names.
     * Looks for a parameter named "connection" in the target method.
     */
    static class AnnotatedConnectionArgPolicy implements ConnectionArgPolicy {

        @Override
        public Optional<String> extractConnectionName(Object[] args) {
            if (args == null || args.length == 0) {
                return Optional.empty();
            }
            for (Object arg : args) {
                if (!(arg instanceof String s) || s.isBlank()) {
                    continue;
                }
                if (isLikelyConnectionName(s)) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }

        private boolean isLikelyConnectionName(String s) {
            if (s.length() > 64) return false;
            if (s.contains(" ") || s.contains("\n") || s.contains("\t")) return false;
            if (s.startsWith("jdbc:") || s.startsWith("--") || s.startsWith("/*")) return false;
            String upper = s.toUpperCase();
            if (upper.contains("SELECT") || upper.contains("FROM") || upper.contains("INSERT")
                    || upper.contains("CREATE") || upper.contains("DROP") || upper.contains("ALTER")) {
                return false;
            }
            return s.matches("[A-Za-z][A-Za-z0-9_-]*");
        }
    }
}
