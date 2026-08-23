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

import java.util.Map;

/**
 * Common annotation parameter values and error messages shared across all MCP tool classes.
 * <p>
 * Centralises {@code @McpToolParam} descriptions, user-facing error messages, and
 * exception cause names so they only need to be maintained in one place.
 */
public final class ToolParams {

    private ToolParams() {
    }

    /**
     * Description used for every BYOK connection name parameter.
     * Marked {@code required = false} because callers should supply it
     * when they know the connection; omit it when the AI can infer it.
     */
    public static final String CONNECTION_DESCRIPTION = "BYOK connection name";

    /**
     * Standard SQL templates shared across tool classes.
     */
    public static final Map<String, String> TEMPLATES = Map.of(
            "select_sql", "SELECT * FROM {table} WHERE {condition} LIMIT {limit}",
            "tables_sql", "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = '{schema}'",
            "table_detail_sql", "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = '{table}' AND table_schema = '{schema}' ORDER BY ordinal_position"
    );

    // ─── Error messages ─────────────────────────────────────────────────────

    public static final String DDL_DISABLED_MSG =
            "DDL execution is disabled. Set entropy.mcp.database.ddl.allowed=true to enable.";

    public static final String GATEWAY_NOT_ENABLED_MSG = "Cross-database gateway is not enabled";

    public static final String FEDERATED_GATEWAY_NOT_ENABLED_MSG = "Federated gateway is not enabled";

    public static final String CONNECTION_NOT_FOUND = "Connection not found: ";

    // ─── Exception cause names ──────────────────────────────────────────────

    public static final String CAUSE_DISABLED = "DisabledException";
    public static final String CAUSE_VALIDATION = "ValidationException";
    public static final String CAUSE_NOT_FOUND = "NotFoundException";
    public static final String CAUSE_SECURITY = "SecurityException";
    public static final String CAUSE_CONFIGURATION = "ConfigurationException";
}
