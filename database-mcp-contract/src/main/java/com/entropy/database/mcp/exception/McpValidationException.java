package com.entropy.database.mcp.exception;

/**
 * Base class for all validation-related MCP tool failures.
 * Covers SQL syntax violations, parameter checks, and data quality issues.
 *
 * <p>Map to error codes: {@link ErrorCode#SQL_VALIDATION_FAILED},
 * {@link ErrorCode#PARAMETER_VALIDATION_FAILED}, {@link ErrorCode#DATA_VALIDATION_FAILED}.</p>
 */
public class McpValidationException extends McpToolException {

    public McpValidationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public McpValidationException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
