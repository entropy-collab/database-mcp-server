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
package com.entropy.database.mcp.util;

import com.entropy.database.mcp.byok.ConnectionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility class for connection-related operations.
 */
public final class ConnectionUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConnectionUtils() {
        // Utility class
    }

    /**
     * Parse a JSON string into ConnectionProperties.
     *
     * @param connectionJson the JSON string (may be null or blank)
     * @return ConnectionProperties or null if input is blank
     */
    public static ConnectionProperties parseConnection(String connectionJson) {
        if (connectionJson == null || connectionJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(connectionJson, ConnectionProperties.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid connection JSON: " + e.getMessage(), e);
        }
    }
}
