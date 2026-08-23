package com.entropy.database.mcp.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpToolExceptionTest {
    @Test
    void testToErrorResponse() {
        McpSqlValidationException ex = new McpSqlValidationException("SELECT * FROM users", "Invalid SQL");
        var response = ex.toErrorResponse();
        assertEquals("VAL001", response.get("error"));
        assertNotNull(response.get("message"));
    }

    @Test
    void testErrorCode() {
        assertEquals("VAL001", ErrorCode.SQL_VALIDATION_FAILED.getCode());
        assertEquals("Query timeout", ErrorCode.QUERY_TIMEOUT.getDescription());
    }
}
