package com.entropy.database.mcp.exception;

/**
 * Error codes organized by domain layer, following Spring's exception hierarchy conventions.
 *
 * <p>Code format: {DOMAIN}{NNN} — e.g. VAL001, CON002, QRY003</p>
 */
public enum ErrorCode {

    // ── Validation (输入/参数校验) ──────────────────────────────────────────
    SQL_VALIDATION_FAILED("VAL001", "SQL validation failed"),
    PARAMETER_VALIDATION_FAILED("VAL002", "Parameter validation failed"),
    DATA_VALIDATION_FAILED("VAL003", "Data validation failed"),

    // ── Connection (连接管理) ───────────────────────────────────────────────
    CONNECTION_NOT_FOUND("CON001", "Connection not found"),
    CONNECTION_FAILED("CON002", "Database connection failed"),
    LEASE_EXPIRED("CON003", "Connection lease expired"),
    CONNECTION_GATEWAY_DISABLED("CON004", "Gateway is not enabled"),

    // ── Query (查询执行) ────────────────────────────────────────────────────
    QUERY_TIMEOUT("QRY001", "Query timeout"),
    QUERY_EXECUTION_FAILED("QRY002", "Query execution failed"),
    QUERY_RESULT_TOO_LARGE("QRY003", "Query result exceeds maximum allowed rows"),

    // ── Security (安全/权限) ───────────────────────────────────────────────
    SECURITY_VIOLATION("SEC001", "Security violation"),
    SQL_OPERATION_NOT_ALLOWED("SEC002", "SQL operation not allowed"),

    // ── Federated (跨库/网关) ──────────────────────────────────────────────
    FEDERATED_QUERY_FAILED("FED001", "Federated query failed"),
    FEDERATED_GATEWAY_UNAVAILABLE("FED002", "Federated gateway unavailable"),
    REMOTE_DATABASE_NOT_FOUND("FED003", "Remote database not found"),

    // ── Session (会话) ─────────────────────────────────────────────────────
    SESSION_NOT_FOUND("SES001", "Session not found"),

    // ── System (系统级兜底) ────────────────────────────────────────────────
    ETL_EXECUTION_FAILED("SYS001", "ETL execution failed"),
    EXPLAIN_NOT_SUPPORTED("SYS002", "EXPLAIN PLAN not supported for this dialect"),
    KILL_SESSION_NOT_SUPPORTED("SYS003", "Kill session is not supported for dialect"),
    UPSERT_NOT_SUPPORTED("SYS004", "UPSERT not supported for dialect"),
    NOT_FOUND("NOT_FOUND", "Not found"),
    INTERNAL_ERROR("SYS999", "Internal error"),
    SYSTEM_ERROR("SYS998", "System error"),
    ;

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }
}
