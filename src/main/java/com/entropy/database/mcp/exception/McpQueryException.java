package com.entropy.database.mcp.exception;

/**
 * Thrown when a query execution fails (timeout, result limit, etc.).
 *
 * <p>Map to error codes: {@link ErrorCode#QUERY_TIMEOUT},
 * {@link ErrorCode#QUERY_EXECUTION_FAILED}, {@link ErrorCode#QUERY_RESULT_TOO_LARGE}.</p>
 */
public class McpQueryException extends McpToolException {

    public McpQueryException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public McpQueryException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
