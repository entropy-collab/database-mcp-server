package com.entropy.database.mcp.exception;

/**
 * Base class for connection lifecycle failures.
 * Covers missing connections, connection pool errors, and lease expiration.
 *
 * <p>Map to error codes: {@link ErrorCode#CONNECTION_NOT_FOUND},
 * {@link ErrorCode#CONNECTION_FAILED}, {@link ErrorCode#LEASE_EXPIRED}.</p>
 */
public class McpConnectionException extends McpToolException {

    public McpConnectionException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public McpConnectionException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    public McpConnectionException(ErrorCode errorCode, String message, String connection) {
        super(errorCode, message, connection);
    }

    public McpConnectionException(ErrorCode errorCode, String message, Throwable cause, String connection) {
        super(errorCode, message, cause, connection);
    }
}
