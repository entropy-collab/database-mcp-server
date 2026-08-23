package com.entropy.database.mcp.exception;

/**
 * Thrown for system-level failures that don't fit other categories.
 * Used as a fallback for unexpected internal errors.
 *
 * <p>Map to error code: {@link ErrorCode#INTERNAL_ERROR},
 * {@link ErrorCode#ETL_EXECUTION_FAILED}.</p>
 */
public class McpSystemException extends McpToolException {

    public McpSystemException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public McpSystemException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
