package com.entropy.database.mcp.exception;

/**
 * Thrown when SQL syntax, structure, or allowed-operations validation fails.
 * Replaces {@code SqlValidationException}.
 */
public class McpSqlValidationException extends McpValidationException {

    private final String sql;

    public McpSqlValidationException(String sql) {
        super(ErrorCode.SQL_VALIDATION_FAILED, "SQL validation failed: " + sql);
        this.sql = sql;
    }

    public McpSqlValidationException(String sql, String reason) {
        super(ErrorCode.SQL_VALIDATION_FAILED, reason);
        this.sql = sql;
    }

    public McpSqlValidationException(String sql, String reason, Throwable cause) {
        super(ErrorCode.SQL_VALIDATION_FAILED, reason, cause);
        this.sql = sql;
    }

    public String getSql() { return sql; }
}
