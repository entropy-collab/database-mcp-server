package com.entropy.database.mcp.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base exception for all MCP tool failures. Replaces {@code DatabaseMcpException}.
 *
 * <p>All application exceptions extend this class, enabling a single catch clause in
 * {@code McpToolExceptionAspect} and {@code McpToolBase.safeExecute()} while preserving
 * domain-specific type information for programmatic handling.</p>
 *
 * <pre>
 * McpToolException
 *   ├── McpValidationException
 *   │     └── McpSqlValidationException  (replaces SqlValidationException)
 *   ├── McpConnectionException
 *   │     ├── McpNoSuchConnectionException  (replaces NoSuchConnectionException)
 *   │     └── McpLeaseExpiredException      (replaces LeaseExpiredException)
 *   ├── McpQueryException
 *   ├── McpSecurityException
 *   ├── McpFederatedException
 *   └── McpSystemException
 * </pre>
 */
public class McpToolException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String connection;

    public McpToolException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.connection = null;
    }

    public McpToolException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.connection = null;
    }

    public McpToolException(ErrorCode errorCode, String message, String connection) {
        super(message);
        this.errorCode = errorCode;
        this.connection = connection;
    }

    public McpToolException(ErrorCode errorCode, String message, Throwable cause, String connection) {
        super(message, cause);
        this.errorCode = errorCode;
        this.connection = connection;
    }

    public ErrorCode getErrorCode() { return errorCode; }
    public String getConnection() { return connection; }

    /**
     * The message exactly as supplied at construction, without the {@code connection=...}
     * prefix that {@link #getMessage()} adds. Use this when rebuilding an exception so the
     * prefix is not applied twice.
     */
    public String getRawMessage() {
        return super.getMessage();
    }

    @Override
    public String getMessage() {
        if (connection != null && !connection.isBlank()) {
            return "connection=" + connection + " | " + super.getMessage();
        }
        return super.getMessage();
    }

    /**
     * Returns a minimal error response map compatible with the legacy
     * {@link McpToolException#getErrorCode()} consumer pattern.
     */
    public Map<String, Object> toErrorResponse() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("code", errorCode.getCode());
        r.put("error", getMessage());
        // isAgentError=true means the LLM can self-correct (e.g. fix SQL syntax, provide missing params)
        // isAgentError=false means a server-side issue that requires human intervention
        r.put("isAgentError", isAgentError());
        if (connection != null && !connection.isBlank()) {
            r.put("connection", connection);
        }
        return r;
    }

    /**
     * Returns true if this error is agent-correctable (parameter validation, SQL syntax, etc.).
     * Agent-correctable errors allow the LLM to retry with adjusted parameters.
     */
    public boolean isAgentError() {
        return errorCode == ErrorCode.SQL_VALIDATION_FAILED
                || errorCode == ErrorCode.PARAMETER_VALIDATION_FAILED
                || errorCode == ErrorCode.DATA_VALIDATION_FAILED
                || errorCode == ErrorCode.CONNECTION_NOT_FOUND
                || errorCode == ErrorCode.CONNECTION_READONLY
                || errorCode == ErrorCode.TOOL_FILTERED
                || errorCode == ErrorCode.SQL_OPERATION_NOT_ALLOWED;
    }
}
