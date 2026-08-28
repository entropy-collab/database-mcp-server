package com.entropy.database.mcp.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpToolExceptionTest {
    @Test
    void testToErrorResponse() {
        McpSqlValidationException ex = new McpSqlValidationException("SELECT * FROM users", "Invalid SQL");
        var response = ex.toErrorResponse();
        assertEquals("VAL001", response.get("code"));
        assertEquals("Invalid SQL", response.get("error"));
        assertTrue((boolean) response.get("isAgentError"));
        assertNotNull(response.get("status"));
        assertEquals("error", response.get("status"));
    }

    @Test
    void testToErrorResponseNonAgentError() {
        McpSystemException ex = new McpSystemException(ErrorCode.INTERNAL_ERROR, "Database connection lost");
        var response = ex.toErrorResponse();
        assertFalse((boolean) response.get("isAgentError"));
    }

    @Test
    void testErrorCode() {
        assertEquals("VAL001", ErrorCode.SQL_VALIDATION_FAILED.getCode());
        assertEquals("Query timeout", ErrorCode.QUERY_TIMEOUT.getDescription());
    }
}
