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

import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;

/**
 * Shared connection-argument resolver used by AOP aspects and tool classes.
 *
 * <p>Eliminates duplicated logic between {@link McpToolExceptionAspect} and
 * {@link PerformanceTimingAspect}. Resolution strategy:
 * <ol>
 *   <li>Reflection-based lookup for a parameter named "connection"</li>
 *   <li>Fallback heuristic: first short non-SQL string argument</li>
 * </ol>
 */
public final class ConnectionArgExtractor {

    private static final int MAX_NAME_LENGTH = 64;
    private static final String[] SQL_KEYWORDS = {"SELECT", "FROM", "INSERT", "CREATE", "DROP", "ALTER", "UPDATE", "DELETE"};

    private ConnectionArgExtractor() {
    }

    /**
     * Extract the connection name from method arguments.
     */
    public static Optional<String> extractConnectionName(Object[] args) {
        if (args == null || args.length == 0) {
            return Optional.empty();
        }
        for (Object arg : args) {
            if (arg instanceof String s && isLikelyConnectionName(s)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * Extract connection name with reflection support (for AOP aspects).
     */
    public static Optional<String> extractConnectionName(Object[] args, MethodSignature signature) {
        if (args == null || args.length == 0 || signature == null) {
            return extractConnectionName(args);
        }
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            Parameter param = parameters[i];
            if (param.isNamePresent() && "connection".equalsIgnoreCase(param.getName())) {
                if (args[i] instanceof String s && !s.isBlank()) {
                    return Optional.of(s);
                }
                return Optional.empty();
            }
        }
        return extractConnectionName(args);
    }

    /**
     * Whether the invoked method declares a connection-name parameter that the caller left null,
     * absent or blank.
     *
     * <p>Structural counterpart to sniffing exception messages for the word "connection": a tool
     * that never declares the parameter cannot be missing it, and a tool that was given one is not
     * missing it no matter what the failure says.
     *
     * @return false when the method declares no connection parameter at all
     */
    public static boolean isConnectionArgMissing(Object[] args, MethodSignature signature) {
        if (signature == null) {
            return false;
        }
        Parameter[] parameters = signature.getMethod().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (!isConnectionParameter(parameters[i])) {
                continue;
            }
            if (args == null || i >= args.length) {
                return true;
            }
            return !(args[i] instanceof String s) || s.isBlank();
        }
        return false;
    }

    private static boolean isConnectionParameter(Parameter param) {
        if (!param.isNamePresent()) {
            return false;
        }
        String name = param.getName();
        return "connection".equalsIgnoreCase(name) || "connectionName".equalsIgnoreCase(name);
    }

    private static boolean isLikelyConnectionName(String s) {
        if (s.isBlank() || s.length() > MAX_NAME_LENGTH) {
            return false;
        }
        if (s.startsWith("jdbc:") || s.startsWith("--") || s.startsWith("/*")) {
            return false;
        }
        for (String keyword : SQL_KEYWORDS) {
            if (s.toUpperCase().contains(keyword)) {
                return false;
            }
        }
        return s.matches("[A-Za-z][A-Za-z0-9_-]*");
    }
}
