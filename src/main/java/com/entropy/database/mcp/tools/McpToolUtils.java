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
 * Unified exception handling utilities for MCP tools.
 */
public final class McpToolUtils {

    private McpToolUtils() {
    }

    /**
     * Create a standardized error response map.
     */
    public static Map<String, Object> errorResponse(String error, String cause) {
        return ApiResponse.error(
                Map.of("error", error, "cause", cause),
                error,
                cause
        ).toMap();
    }

    /**
     * Create a standardized error response map with additional context fields.
     */
    public static Map<String, Object> errorResponse(Map<String, Object> context, String error, String cause) {
        return ApiResponse.error(context, error, cause).toMap();
    }

    /**
     * Create a standardized success response map.
     */
    public static Map<String, Object> successResponse(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put("success", true);
        return result;
    }

    /**
     * Create a standardized success response map with a single key-value pair.
     */
    public static Map<String, Object> successResponse(String key, Object value) {
        return Map.of("success", true, key, value);
    }

    /**
     * Create a standardized success response map with message.
     */
    public static Map<String, Object> successResponse(String message, int rowCount) {
        return Map.of("success", true, "message", message, "rowCount", rowCount);
    }
}
