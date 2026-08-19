package com.entropy.database.mcp.exception;
public class SqlValidationException extends DatabaseMcpException {
    private final String sql;
    public SqlValidationException(String sql) { super(ErrorCode.SQL_VALIDATION_FAILED, "SQL validation failed: " + sql); this.sql = sql; }
    public SqlValidationException(String sql, String reason) { super(ErrorCode.SQL_VALIDATION_FAILED, reason); this.sql = sql; }
    public SqlValidationException(String sql, String reason, Throwable cause) { super(ErrorCode.SQL_VALIDATION_FAILED, reason, cause); this.sql = sql; }
    public String getSql() { return sql; }
}
