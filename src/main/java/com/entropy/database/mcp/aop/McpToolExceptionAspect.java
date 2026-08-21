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
import com.entropy.database.mcp.tools.McpToolUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AOP aspect for unified exception handling in MCP tools.
 * Wraps all @McpTool methods with standardized error response formatting.
 */
@Aspect
@Component
public class McpToolExceptionAspect {

    private static final Logger log = LoggerFactory.getLogger(McpToolExceptionAspect.class);

    @Around("execution(@org.springframework.ai.mcp.annotation.McpTool * *(..))")
    public Object handleMcPException(ProceedingJoinPoint pjp) throws Throwable {
        try {
            return pjp.proceed();
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

    private Map<String, Object> extractContext(ProceedingJoinPoint pjp) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tool", pjp.getSignature().getName());
        Object[] args = pjp.getArgs();
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg != null) {
                    context.put("arg" + i, arg.toString());
                }
            }
        }
        return context;
    }
}
