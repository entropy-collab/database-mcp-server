package com.entropy.database.mcp.exception;

public enum ErrorCode {
    SQL_VALIDATION_FAILED("SQL001", "SQL validation failed"),
    QUERY_TIMEOUT("QRY001", "Query timeout"),
    QUERY_ERROR("QRY002", "Query execution failed"),
    SECURITY_VIOLATION("SEC001", "Security violation"),
    DB_NOT_FOUND("DB004", "Database not found"),
    DB_CONNECTION_FAILED("DB005", "Database connection failed"),
    FEDERATED_QUERY_FAILED("FED001", "Federated query failed"),
    QUERY_RESULT_TOO_LARGE("QRY003", "Query result exceeds maximum allowed rows"),
    PARAMETER_VALIDATION_FAILED("VAL001", "Parameter validation failed"),
    DATA_VALIDATION_FAILED("VAL002", "Data validation failed"),
    INVALID_ENUM_VALUE("ENM001", "Invalid enum value"),
    SESSION_NOT_FOUND("SES001", "Session not found"),
    ETL_EXECUTION_FAILED("ETL001", "ETL execution failed");

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }
}
