package com.entropy.database.mcp.exception;
import java.util.Map;
import java.util.LinkedHashMap;
public class DatabaseMcpException extends RuntimeException {
    private final ErrorCode errorCode;
    public DatabaseMcpException(ErrorCode errorCode, String message) { super(message); this.errorCode = errorCode; }
    public DatabaseMcpException(ErrorCode errorCode, String message, Throwable cause) { super(message, cause); this.errorCode = errorCode; }
    public ErrorCode getErrorCode() { return errorCode; }
    public Map<String, Object> toErrorResponse() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", errorCode.getCode()); r.put("message", getMessage()); return r;
    }
}
