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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility class providing factory methods for MCP tool responses.
 *
 * <p>All response maps are {@link LinkedHashMap} to preserve key ordering in JSON serialization.
 */
public final class McpToolUtils {

    private McpToolUtils() {}

    // ─── Success Responses ────────────────────────────────────────────────

    public static Map<String, Object> success(Map<String, Object> data) {
        return new LinkedHashMap<>(data);
    }

    public static Map<String, Object> success(Map<String, Object> data, String connection) {
        Map<String, Object> result = new LinkedHashMap<>(data);
        if (connection != null && !connection.isBlank()) {
            result.put("connection", connection);
        }
        return result;
    }

    public static Map<String, Object> success(String key, Object value) {
        return Map.of(key, value);
    }

    public static Map<String, Object> success(String message, int rowCount) {
        return Map.of("message", message, "rowCount", rowCount);
    }

    public static Map<String, Object> emptyResult() {
        return Map.of("message", "No rows returned", "rowCount", 0);
    }

    // ─── Context Helpers ──────────────────────────────────────────────────

    public static Map<String, Object> context() {
        return new LinkedHashMap<>();
    }

    public static Map<String, Object> context(Object... pairs) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            ctx.put((String) pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(ctx);
    }
}
