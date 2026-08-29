package com.entropy.database.mcp.exception;

/**
 * Thrown when a security or access-control violation occurs.
 *
 * <p>Map to error codes: {@link ErrorCode#SECURITY_VIOLATION},
 * {@link ErrorCode#SQL_OPERATION_NOT_ALLOWED}.</p>
 */
public class McpSecurityException extends McpToolException {

    public McpSecurityException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public McpSecurityException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
