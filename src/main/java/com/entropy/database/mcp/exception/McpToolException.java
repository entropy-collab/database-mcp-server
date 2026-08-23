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
        r.put("error", errorCode.getCode());
        r.put("message", getMessage());
        if (connection != null && !connection.isBlank()) {
            r.put("connection", connection);
        }
        return r;
    }
}
