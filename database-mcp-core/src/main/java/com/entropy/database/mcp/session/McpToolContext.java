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
package com.entropy.database.mcp.session;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-local MCP tool context providing per-request isolation.
 *
 * <p>This class maintains request-scoped metadata for each thread, enabling:
 * <ul>
 *   <li><b>Parallel query isolation</b> — each concurrent tool call has its own context</li>
 *   <li><b>Request metadata</b> — store correlation IDs, timestamps, and audit info</li>
 *   <li><b>Resource lifecycle</b> — attach cleanup hooks per request</li>
 * </ul>
 *
 * <p>The context is automatically cleaned up when {@link #close()} is called, typically
 * via AOP interception or explicit lifecycle management.
 *
 * <p>Usage in tool methods:
 * <pre>{@code
 * public Map<String, Object> myTool(String connection) {
 *     McpToolContext.current().ifPresent(ctx -> ctx.putMeta("tool", "myTool"));
 *     return safeExecute(() -> { ... });
 * }
 * }</pre>
 *
 * <h2>Why {@link #sessionId()} is not an MCP session</h2>
 * <p>This server runs {@code spring.ai.mcp.server.protocol: STATELESS} on
 * {@code spring-ai-starter-mcp-server-webmvc}. In that mode the MCP protocol has no session:
 * {@code HttpServletStatelessServerTransport} never issues or reads an {@code Mcp-Session-Id},
 * the stateless server handler has no {@code McpSyncServerExchange}, and the only per-request
 * object reaching application code is {@link io.modelcontextprotocol.common.McpTransportContext},
 * an opaque key/value bag filled from the HTTP request by a {@code McpTransportContextExtractor}.
 * There is therefore nothing at the protocol layer to derive a stable, cross-call session identity
 * from.
 *
 * <p>So {@code sessionId} is deliberately <em>call-scoped</em>: it is derived from this
 * invocation's correlation ID and changes on every tool call. It used to be
 * {@code System.currentTimeMillis()}, which was worse in both directions — two calls of one client
 * got different values (so {@link MultiSessionContext} never found what a previous call wrote), and
 * two calls of <em>different</em> clients landing in the same millisecond got the same value (so
 * one tenant could read another tenant's namespace). A clock or a random number cannot manufacture
 * a session that the transport does not have; the honest options are a caller-supplied scope key
 * ({@link #create(String, String)}) or single-call scope, and single-call scope is the default.
 */
public class McpToolContext {

    private static final Logger log = LoggerFactory.getLogger(McpToolContext.class);
    private static final ThreadLocal<McpToolContext> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_ID_HOLDER = new ThreadLocal<>();

    /** Prefix marking a scope id that covers exactly one tool invocation. */
    private static final String CALL_SCOPE_PREFIX = "call:";

    /** Correlation ID for tracking requests across tools. */
    private final String correlationId;
    /**
     * Scope key shared with {@link MultiSessionContext}. Call-scoped unless the caller supplied
     * one explicitly — see the class javadoc.
     */
    private final String sessionId;
    /** Tool name being executed. */
    private String toolName;
    /** Connection being used. */
    private String connection;
    /** Start timestamp in milliseconds. */
    private final long startTime;
    /** Extra metadata attached by tools or aspects. */
    private final ConcurrentHashMap<String, Object> metadata = new ConcurrentHashMap<>();
    /** Cleanup hooks registered by tools. */
    private final java.util.LinkedList<Runnable> onCloseHooks = new java.util.LinkedList<>();

    private McpToolContext(String correlationId, String sessionId) {
        this.correlationId = correlationId;
        this.sessionId = sessionId;
        this.startTime = System.currentTimeMillis();
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    public static McpToolContext create() {
        return create(UUID.randomUUID().toString());
    }

    /**
     * Create a context whose session scope covers this invocation only.
     */
    public static McpToolContext create(String correlationId) {
        return create(correlationId, callScopeId(correlationId));
    }

    /**
     * Create a context bound to an explicitly supplied scope key.
     *
     * <p>The only way to get cross-call sharing out of {@link MultiSessionContext} on a stateless
     * transport: the key has to come from the caller (an MCP tool parameter, or a header lifted
     * into the transport context by a {@code McpTransportContextExtractor}), because the protocol
     * does not provide one. A blank key falls back to call scope rather than silently pooling
     * every anonymous caller into one namespace.
     */
    public static McpToolContext create(String correlationId, String sessionId) {
        String scope = (sessionId == null || sessionId.isBlank())
                ? callScopeId(correlationId)
                : sessionId;
        McpToolContext ctx = new McpToolContext(correlationId, scope);
        HOLDER.set(ctx);
        SESSION_ID_HOLDER.set(scope);
        return ctx;
    }

    private static String callScopeId(String correlationId) {
        return CALL_SCOPE_PREFIX + (correlationId == null ? UUID.randomUUID() : correlationId);
    }

    /**
     * Initialize the session scope key for the current thread (used by
     * {@link MultiSessionContext} when no context has been created).
     */
    public static void initSession(String sessionId) {
        SESSION_ID_HOLDER.set(sessionId);
    }

    /**
     * Whether {@code sessionId} denotes a single tool invocation rather than a caller-supplied
     * session. Lets callers tell "this store will not survive my next call" from "it will".
     */
    public static boolean isCallScoped(String sessionId) {
        return sessionId != null && sessionId.startsWith(CALL_SCOPE_PREFIX);
    }

    /**
     * Clean up the current thread's context. Must be called to prevent memory leaks.
     */
    public void close() {
        onCloseHooks.forEach(hook -> {
            try {
                hook.run();
            } catch (Exception e) {
                log.warn("Close hook failed for context", e);
            }
        });
        onCloseHooks.clear();
        HOLDER.remove();
        SESSION_ID_HOLDER.remove();
    }

    // ─── Accessors ──────────────────────────────────────────────────────────

    public static Optional<McpToolContext> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static Optional<String> currentSessionId() {
        return Optional.ofNullable(SESSION_ID_HOLDER.get());
    }

    public String correlationId() {
        return correlationId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String toolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String connection() {
        return connection;
    }

    public void setConnection(String connection) {
        this.connection = connection;
    }

    public long startTime() {
        return startTime;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startTime;
    }

    // ─── Metadata ───────────────────────────────────────────────────────────

    public McpToolContext putMeta(String key, Object value) {
        metadata.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getMeta(String key) {
        return (T) metadata.get(key);
    }

    public boolean hasMeta(String key) {
        return metadata.containsKey(key);
    }

    public McpToolContext withMeta(String key, Object value) {
        metadata.put(key, value);
        return this;
    }

    // ─── Lifecycle Hooks ────────────────────────────────────────────────────

    public McpToolContext onClose(Runnable hook) {
        onCloseHooks.add(hook);
        return this;
    }

    public McpToolContext withCleanup(Runnable hook) {
        onCloseHooks.add(hook);
        return this;
    }
}
