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
 *   <li><b>Session tracking</b> — correlate multiple tool calls to the same MCP session</li>
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
 * <p>Session IDs are shared between {@link McpToolContext} and {@link MultiSessionContext}
 * to enable cross-session data sharing.
 */
public class McpToolContext {

    private static final Logger log = LoggerFactory.getLogger(McpToolContext.class);
    private static final ThreadLocal<McpToolContext> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Long> SESSION_ID_HOLDER = new ThreadLocal<>();

    /** Correlation ID for tracking requests across tools. */
    private final String correlationId;
    /** Session ID shared with {@link MultiSessionContext}. */
    private final long sessionId;
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

    private McpToolContext(String correlationId, long sessionId) {
        this.correlationId = correlationId;
        this.sessionId = sessionId;
        this.startTime = System.currentTimeMillis();
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    public static McpToolContext create() {
        return create(UUID.randomUUID().toString());
    }

    public static McpToolContext create(String correlationId) {
        return create(correlationId, System.currentTimeMillis());
    }

    public static McpToolContext create(String correlationId, long sessionId) {
        McpToolContext ctx = new McpToolContext(correlationId, sessionId);
        HOLDER.set(ctx);
        SESSION_ID_HOLDER.set(sessionId);
        return ctx;
    }

    /**
     * Initialize session ID for current thread (used by {@link MultiSessionContext}).
     */
    public static void initSession(long sessionId) {
        SESSION_ID_HOLDER.set(sessionId);
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

    public static Optional<Long> currentSessionId() {
        return Optional.ofNullable(SESSION_ID_HOLDER.get());
    }

    public String correlationId() {
        return correlationId;
    }

    public long sessionId() {
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
