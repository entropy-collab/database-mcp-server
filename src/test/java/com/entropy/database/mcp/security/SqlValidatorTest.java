package com.entropy.database.mcp.security;

import com.entropy.database.mcp.exception.SqlValidationException;
import com.entropy.database.mcp.properties.DatabaseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SqlValidatorTest {
    private SqlValidatorImpl validator;

    @BeforeEach
    void setUp(@Mock DatabaseProperties properties) {
        lenient().when(properties.query()).thenReturn(new DatabaseProperties.QueryProperties(100, 30, true, 10000, 500, 100));
        lenient().when(properties.security()).thenReturn(new DatabaseProperties.SecurityProperties(10, 5));
        validator = new SqlValidatorImpl(properties);
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
