package com.entropy.database.mcp.exception;

public enum ErrorCode {
    SQL_VALIDATION_FAILED("SQL001", "SQL validation failed"),
    QUERY_TIMEOUT("QRY001", "Query timeout"),
    SECURITY_VIOLATION("SEC001", "Security violation"),
    DB_NOT_FOUND("DB004", "Database not found"),
    DB_CONNECTION_FAILED("DB005", "Database connection failed"),
    FEDERATED_QUERY_FAILED("FED001", "Federated query failed"),
    QUERY_RESULT_TOO_LARGE("QRY002", "Query result exceeds maximum allowed rows");

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }
}
