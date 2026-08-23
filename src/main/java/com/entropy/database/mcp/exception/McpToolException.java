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

    public McpToolException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public McpToolException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }

    /**
     * Returns a minimal error response map compatible with the legacy
     * {@link McpToolException#getErrorCode()} consumer pattern.
     */
    public Map<String, Object> toErrorResponse() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", errorCode.getCode());
        r.put("message", getMessage());
        return r;
    }
}
