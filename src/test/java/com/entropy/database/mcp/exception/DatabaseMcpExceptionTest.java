package com.entropy.database.mcp.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseMcpExceptionTest {
    @Test
    void testToErrorResponse() {
        DatabaseMcpException ex = new SqlValidationException("SELECT * FROM users", "Invalid SQL");
        var response = ex.toErrorResponse();
        assertEquals("SQL001", response.get("error"));
        assertNotNull(response.get("message"));
    }

    @Test
    void testErrorCode() {
        assertEquals("SQL001", ErrorCode.SQL_VALIDATION_FAILED.getCode());
        assertEquals("Query timeout", ErrorCode.QUERY_TIMEOUT.getDescription());
    }
}
