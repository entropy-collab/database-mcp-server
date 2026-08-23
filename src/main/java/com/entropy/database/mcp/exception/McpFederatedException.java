package com.entropy.database.mcp.exception;

/**
 * Thrown when a federated/cross-database query fails.
 *
 * <p>Map to error codes: {@link ErrorCode#FEDERATED_QUERY_FAILED},
 * {@link ErrorCode#FEDERATED_GATEWAY_UNAVAILABLE}, {@link ErrorCode#REMOTE_DATABASE_NOT_FOUND}.</p>
 */
public class McpFederatedException extends McpToolException {

    public McpFederatedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public McpFederatedException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
