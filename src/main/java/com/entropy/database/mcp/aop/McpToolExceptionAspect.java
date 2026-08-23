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

import com.entropy.database.mcp.aop.ConnectionArgExtractor;
import com.entropy.database.mcp.session.McpToolContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AOP aspect for connection injection and session context management in MCP tools.
 *
 * <p>Exception handling is delegated to Spring AI SDK (AbstractSyncMcpToolMethodCallback),
 * which automatically converts exceptions to isError=true CallToolResult.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Manage {@link McpToolContext} lifecycle (create/close per tool invocation)</li>
 *   <li>Inject connection name into success response maps</li>
 * </ul>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class McpToolExceptionAspect {

    private static final Logger log = LoggerFactory.getLogger(McpToolExceptionAspect.class);

    @Pointcut("@annotation(org.springframework.ai.mcp.annotation.McpTool)")
    public void mcpToolMethod() {}

    @Around("mcpToolMethod()")
    public Object handleMcPException(ProceedingJoinPoint pjp) throws Throwable {
        String toolName = pjp.getSignature().getName();
        McpToolContext context = McpToolContext.create();
        context.setToolName(toolName);
        ConnectionArgExtractor.extractConnectionName(pjp.getArgs(), (MethodSignature) pjp.getSignature())
                .ifPresent(context::setConnection);

        log.debug("MCP tool entry: tool={}, correlationId={}, sessionId={}",
                toolName, context.correlationId(), context.sessionId());

        try {
            Object result = pjp.proceed();
            injectConnection(result, pjp);
            log.debug("MCP tool exit: tool={}, elapsed={}ms", toolName, context.elapsedMillis());
            return result;
        } finally {
            context.close();
        }
    }

    private void injectConnection(Object result, ProceedingJoinPoint pjp) {
        if (!(result instanceof Map<?, ?> resultMap)) return;
        ConnectionArgExtractor.extractConnectionName(pjp.getArgs(), (MethodSignature) pjp.getSignature())
                .ifPresent(conn -> {
                    try { ((Map<String, Object>) result).putIfAbsent("connection", conn); }
                    catch (Exception ignored) {}
                });
    }
}
