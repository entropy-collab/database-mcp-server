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
package com.entropy.database.mcp.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds structured diagnostic error responses for MCP tools.
 *
 * <p>Transforms {@link McpToolException} into a JSON-serializable map containing:
 * <ul>
 *   <li>{@code error} — machine-readable error code (e.g. "CON001")</li>
 *   <li>{@code message} — human-readable description</li>
 *   <li>{@code category} — domain category (e.g. "connection", "query")</li>
 *   <li>{@code suggestedActions} — actionable remediation steps for the LLM</li>
 * </ul>
 *
 * <p>The output is designed to be embedded in the MCP error response text so the
 * downstream LLM can take corrective action without guessing.
 */
public final class ErrorDiagnosticBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ErrorDiagnosticBuilder() {}

    /**
     * Builds a diagnostic map from a {@link McpToolException}.
     */
    public static Map<String, Object> build(McpToolException ex) {
        ErrorCode code = ex.getErrorCode();
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("error", code.getCode());
        diag.put("message", ex.getMessage() != null ? ex.getMessage() : code.getDescription());
        if (ex.getConnection() != null && !ex.getConnection().isBlank()) {
            diag.put("connection", ex.getConnection());
        }
        // Detect dialect hint from BadSqlGrammarException message
        String msg = ex.getMessage();
        if (msg != null && msg.contains("数据库类型")) {
            int start = msg.indexOf("数据库类型: ");
            int end = msg.indexOf(" — ", start);
            if (start >= 0 && end > start) {
                String dialect = msg.substring(start + "数据库类型: ".length(), end);
                diag.put("dialect", dialect);
                diag.put("hint", "The SQL syntax is incompatible with " + dialect + ". Rewrite the SQL to use " + dialect + "-compatible syntax.");
            }
        }
        diag.put("category", categorize(code));
        diag.put("suggestedActions", suggestActions(code, ex));
        return diag;
    }

    /**
     * Returns the domain category for an error code.
     */
    static String categorize(ErrorCode code) {
        String prefix = code.getCode();
        if (prefix.startsWith("VAL")) return "validation";
        if (prefix.startsWith("CON")) return "connection";
        if (prefix.startsWith("QRY")) return "query";
        if (prefix.startsWith("SEC")) return "security";
        if (prefix.startsWith("FED")) return "federated";
        if (prefix.startsWith("SES")) return "session";
        if (prefix.startsWith("SYS")) return "system";
        return "unknown";
    }

    /**
     * Returns suggested remediation actions based on the error code.
     * Designed to guide the LLM toward the right corrective action.
     */
    static List<String> suggestActions(ErrorCode code, McpToolException ex) {
        List<String> actions = new ArrayList<>();
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        switch (code) {
            case SQL_VALIDATION_FAILED:
                actions.add("Check SQL syntax: ensure proper quoting, valid column names, and correct JOIN clauses");
                actions.add("Use executeSqlTemplate for parameterized queries to avoid injection errors");
                break;
            case PARAMETER_VALIDATION_FAILED:
                actions.add("Verify all required parameters are provided and non-empty");
                actions.add("Check parameter types match the tool's expected schema");
                break;
            case CONNECTION_NOT_FOUND:
                actions.add("List available connections using listDatabases or describeConnection");
                actions.add("Create a named connection using createNamedConnection before querying");
                break;
            case CONNECTION_FAILED:
                actions.add("Verify connection string, credentials, and network reachability");
                actions.add("Check if the database host is accessible from this environment");
                break;
            case LEASE_EXPIRED:
                actions.add("Connection lease expired; the framework will auto-renew on next invocation");
                actions.add("If persistent, check connection pool configuration");
                break;
            case QUERY_TIMEOUT:
                actions.add("Add WHERE clause to reduce result set size");
                actions.add("Use pagination (continuationToken) for large result sets");
                actions.add("Consider adding LIMIT clause or breaking into smaller queries");
                break;
            case QUERY_EXECUTION_FAILED:
                actions.add("Check table/column names exist in the target schema");
                actions.add("Run listSchemas and describe to verify the target objects");
                break;
            case QUERY_RESULT_TOO_LARGE:
                actions.add("Add WHERE filter or LIMIT to reduce row count");
                actions.add("Use pagination with continuationToken for large datasets");
                break;
            case SECURITY_VIOLATION:
                actions.add("The SQL was blocked by the security policy");
                actions.add("Review allowed operations: only SELECT queries are permitted in read mode");
                break;
            case SQL_OPERATION_NOT_ALLOWED:
                actions.add("This operation requires DDL privileges not granted to this connection");
                actions.add("Contact the DBA to request additional permissions");
                break;
            case FEDERATED_QUERY_FAILED:
                actions.add("Check that the remote database link is active via listConnections");
                actions.add("Verify network connectivity to the remote database");
                break;
            case FEDERATED_GATEWAY_UNAVAILABLE:
                actions.add("The federated gateway service is down; retry after a short delay");
                actions.add("Check server logs for gateway startup issues");
                break;
            case REMOTE_DATABASE_NOT_FOUND:
                actions.add("List available remote databases via listDatabases");
                actions.add("Ensure the target database is registered in the federated gateway");
                break;
            case SESSION_NOT_FOUND:
                actions.add("Create a new session or use the default session (omit connection parameter)");
                break;
            case INTERNAL_ERROR:
            case SYSTEM_ERROR:
                actions.add("An unexpected system error occurred; check server logs for details");
                actions.add("Retry the operation; if persistent, contact the administrator");
                break;
            default:
                actions.add("Review the error message and adjust the query or parameters");
        }
        return actions;
    }

    /**
     * Serializes the diagnostic map to compact JSON using Jackson.
     */
    public static String toJsonString(Map<String, Object> diag) {
        try {
            return MAPPER.writeValueAsString(diag);
        } catch (JsonProcessingException e) {
            // Fallback: manual serialization if Jackson fails
            return fallbackToJson(diag);
        }
    }

    private static String fallbackToJson(Map<String, Object> diag) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  ");
        sb.append("\"error\": \"").append(escapeJson(diag.get("error"))).append("\",\n  ");
        sb.append("\"message\": \"").append(escapeJson(diag.get("message"))).append("\",\n  ");
        sb.append("\"category\": \"").append(escapeJson(diag.get("category"))).append("\",\n  ");
        sb.append("\"suggestedActions\": [");
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) diag.get("suggestedActions");
        if (actions != null) {
            for (int i = 0; i < actions.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\n    \"").append(escapeJson(actions.get(i))).append("\"");
            }
            sb.append("\n  ");
        }
        sb.append("]\n}");
        return sb.toString();
    }

    private static String escapeJson(Object value) {
        if (value == null) return "";
        return value.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
