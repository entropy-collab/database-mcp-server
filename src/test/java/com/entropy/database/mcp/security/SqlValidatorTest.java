package com.entropy.database.mcp.security;

import com.entropy.database.mcp.exception.SqlValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SqlValidatorTest {
    private SqlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SqlValidator();
        validator.setMaxRows(1000);
    }

    @Test
    void testValidSelect() {
        assertDoesNotThrow(() -> validator.validateSelect("SELECT * FROM users"));
    }

    @Test
    void testInvalidOperation() {
        assertThrows(SqlValidationException.class, () -> 
            validator.validateSelect("DROP TABLE users"));
    }

    @Test
    void testEmptySql() {
        assertThrows(SqlValidationException.class, () -> 
            validator.validateSelect(""));
    }

    @Test
    void testTableWhitelist() {
        validator.setAllowedTables(Set.of("USERS"));
        assertDoesNotThrow(() -> validator.validateSelect("SELECT * FROM USERS"));
        assertThrows(SqlValidationException.class, () -> 
            validator.validateSelect("SELECT * FROM admin"));
    }
}
