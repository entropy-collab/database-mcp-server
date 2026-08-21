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
 * Unified response wrapper for MCP tool results.
 *
 * @param <T> the type of the payload data
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String error,
        String cause
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> error(String error, String cause) {
        return new ApiResponse<>(false, null, error, cause);
    }

    public static <T> ApiResponse<T> error(Map<String, Object> context, String error, String cause) {
        Map<String, Object> merged = new LinkedHashMap<>(context);
        merged.put("success", false);
        merged.put("error", error);
        merged.put("cause", cause);
        //noinspection unchecked
        return new ApiResponse<>(false, (T) merged, error, cause);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) (Map<?, ?>) map);
            result.put("success", success);
            return result;
        }
        if (success) {
            return Collections.singletonMap("success", true);
        }
        return Map.of(
                "success", false,
                "error", error != null ? error : "Unknown error",
                "cause", cause != null ? cause : ""
        );
    }
}
