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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Abstract base class for all MCP tool implementations.
 *
 * <p>Design philosophy (Spring AI MCP 2.0 native):
 * <ul>
 *   <li>Business errors → throw {@link McpToolException} (SDK maps to {@code isError: true})</li>
 *   <li>System errors → thrown as {@link RuntimeException}, SDK maps to JSON-RPC error {@code -32603}</li>
 *   <li>Success → raw data map (no wrapper)</li>
 * </ul>
 *
 * <p>{@link #safeExecute} wraps execution and re-throws {@link McpToolException} unchanged.
 * Other exceptions are wrapped as {@link ErrorCode#SYSTEM_ERROR} to preserve business error semantics.
 *
 * <p>Subclasses inherit:
 * <ul>
 *   <li>{@link #safeExecute(Supplier)} — executes business logic with uniform error wrapping</li>
 *   <li>{@link #validateRequired(String, String)} — null/blank parameter validation</li>
 *   <li>{@link #success(Map)} — response builder</li>
 * </ul>
 */
public abstract class McpToolBase {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    // ─── Execution Template ───────────────────────────────────────────────

    /**
     * Executes business logic. Any {@link McpToolException} is re-thrown unchanged (SDK maps to {@code isError: true}).
     * Other exceptions are wrapped as {@link ErrorCode#SYSTEM_ERROR}.
     */
    protected final Map<String, Object> safeExecute(CheckedSupplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (McpToolException e) {
            log.warn("Tool validation error: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.warn("Tool execution failed: {}", e.getClass().getSimpleName(), e);
            throw new McpToolException(ErrorCode.SYSTEM_ERROR, "An unexpected error occurred. Check server logs for details.", e);
        }
    }

    @FunctionalInterface
    protected interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    // ─── Response Builders ────────────────────────────────────────────────

    /** Success response — data map returned directly (no wrapper). */
    protected Map<String, Object> success(Map<String, Object> data) {
        return McpToolUtils.success(data);
    }

    /** Success response with connection injected. */
    protected Map<String, Object> success(Map<String, Object> data, String connection) {
        return McpToolUtils.success(data, connection);
    }

    /** Success response with a single key-value pair. */
    protected Map<String, Object> success(String key, Object value) {
        return McpToolUtils.success(key, value);
    }

    /** Success response with message and row count. */
    protected Map<String, Object> success(String message, int rowCount) {
        return McpToolUtils.success(message, rowCount);
    }

    // ─── Validation Helpers ────────────────────────────────────────────────

    protected final void validateRequired(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new McpToolException(
                    com.entropy.database.mcp.exception.ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " cannot be blank");
        }
    }

    protected Map<String, Object> emptyResult() {
        return success(Map.of("message", "No rows returned", "rowCount", 0));
    }

    protected Map<String, Object> context() {
        return McpToolUtils.context();
    }

    protected Map<String, Object> context(Object... pairs) {
        return McpToolUtils.context(pairs);
    }
}
