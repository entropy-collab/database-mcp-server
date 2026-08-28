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
import com.entropy.database.mcp.byok.ConnectionMetadata;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.session.McpToolContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 *   <li>Enhance BadSqlGrammarException with dialect hint for LLM guidance</li>
 * </ul>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class McpToolExceptionAspect {

    private static final Logger log = LoggerFactory.getLogger(McpToolExceptionAspect.class);

    /** Prefix of the classification suffix appended to tool error messages. */
    private static final String CLASSIFICATION_MARKER = " [code=";

    /**
     * Tools whose only side-effect is server-side state (caches, session key/value store,
     * in-memory registries). They never write to the database, so the per-connection
     * {@code readonly} flag must not block them.
     *
     * <p>Every other non-{@code readOnlyHint} tool is treated as a database mutation. The
     * read-only classification itself lives on each tool's {@code @McpTool.McpAnnotations},
     * so it is declared exactly once.</p>
     */
    private static final Set<String> SERVER_STATE_ONLY_TOOLS = Set.of(
            "clearCache",
            "sessionStore", "sessionRemove", "sessionPurge",
            "scanCustomTools",
            "registerSubscription", "unregisterSubscription");

    /**
     * Error codes that can legitimately mean "the connection argument was not usable". Anything
     * else — a query failure, a pool outage, a lease expiry — describes work that had already
     * started and must keep its own classification.
     */
    private static final Set<ErrorCode> MISSING_CONNECTION_CODES = Set.of(
            ErrorCode.PARAMETER_VALIDATION_FAILED,
            ErrorCode.CONNECTION_NOT_FOUND);

    private final DynamicDataSourceManager dataSourceManager;

    public McpToolExceptionAspect(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

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
            rejectIfReadonlyConnection(pjp, toolName, context.connection());
            Object result = pjp.proceed();
            injectConnection(result, pjp);
            log.debug("MCP tool exit: tool={}, elapsed={}ms", toolName, context.elapsedMillis());
            return result;
        } catch (Throwable t) {
            Throwable enhanced = enhanceWithDialectHint(t, context.connection());
            if (isMissingConnectionArgument(pjp, enhanced)) {
                enhanced = missingConnectionHint(enhanced);
            }
            // Surface the error classification in the message so the LLM can distinguish an
            // input mistake it can fix from a server-side failure it cannot. The exception is
            // still thrown: the Spring AI SDK converts it to isError=true, which is the
            // correct MCP shape for a tool execution error.
            if (enhanced instanceof McpToolException mcpEx) {
                enhanced = annotateErrorClassification(mcpEx);
            }
            throw enhanced;
        } finally {
            context.close();
        }
    }

    /**
     * Reject database-mutating tools on connections registered with {@code readonly=true}.
     *
     * <p>The read-only classification is read from the tool's own
     * {@code @McpTool.McpAnnotations(readOnlyHint = ...)}, so it is declared in exactly one
     * place and cannot drift from what {@code tools/list} advertises.</p>
     *
     * <p>Two exemptions: {@code createNamedConnection} registers or re-registers the
     * connection itself, so gating it on the flag it sets would make a read-only connection
     * impossible to update; and {@link #SERVER_STATE_ONLY_TOOLS} never touch the database.</p>
     */
    private void rejectIfReadonlyConnection(ProceedingJoinPoint pjp, String toolName, String connection) {
        if (connection == null || connection.isBlank()) return;
        if ("createNamedConnection".equals(toolName)) return;
        if (SERVER_STATE_ONLY_TOOLS.contains(toolName)) return;
        if (isDeclaredReadOnly(pjp)) return;
        if (!dataSourceManager.isReadonly(connection)) return;
        throw new McpToolException(
                ErrorCode.CONNECTION_READONLY,
                "Tool '" + toolName + "' modifies state, but connection '" + connection
                        + "' is registered as read-only. Use a read-only tool, or a connection "
                        + "registered without readonly=true.",
                connection);
    }

    /**
     * Append a machine-readable classification suffix to the exception message so the LLM can
     * tell a self-correctable input error from a server-side failure. Returns the original
     * exception unchanged if it is already annotated.
     */
    private McpToolException annotateErrorClassification(McpToolException ex) {
        String rawMessage = ex.getRawMessage();
        if (rawMessage == null || rawMessage.contains(CLASSIFICATION_MARKER)) return ex;
        String suffix = CLASSIFICATION_MARKER + ex.getErrorCode().getCode()
                + " retryable=" + ex.isAgentError() + "]";
        return new McpToolException(ex.getErrorCode(), rawMessage + suffix, ex.getCause(), ex.getConnection());
    }

    private Throwable enhanceWithDialectHint(Throwable t, String connection) {
        if (connection == null || connection.isBlank()) return t;
        Throwable cause = findRootCause(t);
        if (!(cause instanceof BadSqlGrammarException)) return t;
        ConnectionMetadata meta = dataSourceManager.getConnectionMetadata(connection);
        if (meta == null || meta.dialect() == null) return t;
        String dialect = meta.dialect();
        String hint = " [数据库类型: " + dialect + " — 请将 SQL 改写为兼容 " + dialect + " 的语法]";
        if (t instanceof McpToolException mcpEx && mcpEx.getRawMessage() != null) {
            return new McpToolException(mcpEx.getErrorCode(), mcpEx.getRawMessage() + hint, t.getCause(), connection);
        }
        if (cause instanceof BadSqlGrammarException bsge && bsge.getMessage() != null) {
            String enhancedMsg = bsge.getMessage() + hint;
            return new BadSqlGrammarException(enhancedMsg, bsge.getSql(), bsge.getSQLException());
        }
        return t;
    }

    /**
     * Whether this failure really is "the caller omitted the connection argument".
     *
     * <p>Two structural conditions, both required. First, the tool must declare a
     * {@code connection}/{@code connectionName} parameter that this invocation left null or blank —
     * a tool that was handed a name cannot be missing one. Second, the failure must be a
     * connection-resolution or parameter-validation failure by <em>type</em>, not by wording.
     *
     * <p>The message text is deliberately never consulted. "HikariDataSource has been closed",
     * "connection refused" and "Failed to acquire connection" all contain the word while describing
     * a server-side fault; rewriting them as {@code PARAMETER_VALIDATION_FAILED} told the LLM to
     * retry with a different parameter and hid the real outage.
     */
    private boolean isMissingConnectionArgument(ProceedingJoinPoint pjp, Throwable t) {
        if (!ConnectionArgExtractor.isConnectionArgMissing(
                pjp.getArgs(), (MethodSignature) pjp.getSignature())) {
            return false;
        }
        if (t instanceof McpToolException mcpEx) {
            return MISSING_CONNECTION_CODES.contains(mcpEx.getErrorCode());
        }
        // The routing facade reports an unresolvable or unsupplied connection name as a plain
        // IllegalArgumentException before any datasource is touched; pool and driver failures
        // arrive as DataAccessException/SQLException instead and must not be caught here.
        return t instanceof IllegalArgumentException;
    }

    /**
     * Replace a connection-resolution failure with the list of names the caller could have passed.
     */
    private Throwable missingConnectionHint(Throwable t) {
        Collection<String> registered = dataSourceManager.listConnectionKeys();
        String connectionHint;
        if (registered.isEmpty()) {
            connectionHint = "No connections registered. Call createNamedConnection first.";
        } else {
            String connectionList = registered.stream()
                    .map(name -> "  - " + name)
                    .collect(Collectors.joining("\n"));
            connectionHint = String.format("Available connections:\n%s\nUse one of these names as the connection parameter.", connectionList);
        }
        return new McpToolException(
                ErrorCode.PARAMETER_VALIDATION_FAILED,
                "参数 'connection' 未提供。\n" + connectionHint,
                t);
    }

    private Throwable findRootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private void injectConnection(Object result, ProceedingJoinPoint pjp) {
        if (!(result instanceof Map<?, ?> resultMap)) return;
        ConnectionArgExtractor.extractConnectionName(pjp.getArgs(), (MethodSignature) pjp.getSignature())
                .ifPresent(conn -> {
                    try { ((Map<String, Object>) result).putIfAbsent("connection", conn); }
                    catch (Exception e) { log.debug("Could not inject connection hint into result", e); }
                });
    }

    /**
     * Read {@code readOnlyHint} from the intercepted tool's own {@code @McpTool} declaration.
     * Falls back to false (treat as mutating) when the annotation cannot be resolved.
     */
    private boolean isDeclaredReadOnly(ProceedingJoinPoint pjp) {
        try {
            Method method = AopUtils.getMostSpecificMethod(
                    ((MethodSignature) pjp.getSignature()).getMethod(), pjp.getTarget().getClass());
            McpTool annotation = method.getAnnotation(McpTool.class);
            return annotation != null && annotation.annotations().readOnlyHint();
        } catch (Exception e) {
            log.debug("Could not resolve @McpTool annotations for {}", pjp.getSignature().getName(), e);
            return false;
        }
    }
}
