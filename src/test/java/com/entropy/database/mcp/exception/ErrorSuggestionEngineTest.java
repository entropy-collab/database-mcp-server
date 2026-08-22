package com.entropy.database.mcp.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ErrorSuggestionEngine.
 */
class ErrorSuggestionEngineTest {

    private final ErrorSuggestionEngine engine = new ErrorSuggestionEngine();

    @Test
    @DisplayName("Should provide suggestions for connection errors")
    void testConnectionErrorSuggestions() {
        Exception ex = new RuntimeException("Connection is required");
        List<String> suggestions = engine.analyze(ex, "executeQuery");

        assertNotNull(suggestions);
        assertFalse(suggestions.isEmpty());
        // Check for Chinese content which indicates suggestions were generated
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("连接") || s.contains("connectionName")));
    }

    @Test
    @DisplayName("Should provide suggestions for SQL grammar errors")
    void testSqlGrammarErrorSuggestions() {
        Exception ex = new RuntimeException("PreparedStatementCallback; bad SQL grammar");
        List<String> suggestions = engine.analyze(ex, "executeQuery");

        assertNotNull(suggestions);
        assertFalse(suggestions.isEmpty());
        // Check for SQL error content
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("SQL") || s.contains("语法")));
    }

    @Test
    @DisplayName("Should provide suggestions for Oracle connection refused")
    void testOracleConnectionRefused() {
        Exception ex = new RuntimeException("ORA-12170: TNS:Connect timeout occurred");
        List<String> suggestions = engine.analyze(ex, "executeQuery");

        assertNotNull(suggestions);
        assertFalse(suggestions.isEmpty());
        // Check for Oracle content
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("ORA-12170") || s.contains("Oracle")));
    }

    @Test
    @DisplayName("Should provide suggestions for connection not found")
    void testConnectionNotFound() {
        Exception ex = new RuntimeException("Connection not found: mydb");
        List<String> suggestions = engine.analyze(ex, "listTables");

        assertNotNull(suggestions);
        assertFalse(suggestions.isEmpty());
        // Check for connection-related content
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("listConnections") || s.contains("连接")));
    }

    @Test
    @DisplayName("Should provide empty suggestions for unknown errors")
    void testUnknownErrorNoSuggestions() {
        Exception ex = new RuntimeException("Some unknown error");
        List<String> suggestions = engine.analyze(ex, "executeQuery");

        assertNotNull(suggestions);
        assertTrue(suggestions.isEmpty());
    }

    @Test
    @DisplayName("Should handle null exception")
    void testNullException() {
        List<String> suggestions = engine.analyze(null, "executeQuery");

        assertNotNull(suggestions);
        assertTrue(suggestions.isEmpty());
    }

    @Test
    @DisplayName("Should handle null message")
    void testNullMessage() {
        Exception ex = new NullPointerException();
        List<String> suggestions = engine.analyze(ex, "executeQuery");

        assertNotNull(suggestions);
        assertTrue(suggestions.isEmpty());
    }

    @Test
    @DisplayName("Should provide suggestions for SQL timeout")
    void testSqlTimeout() {
        Exception ex = new RuntimeException("Query execution timeout after 30000ms");
        List<String> suggestions = engine.analyze(ex, "executeQuery");

        assertNotNull(suggestions);
        assertFalse(suggestions.isEmpty());
        // Check for timeout-related content
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("timeout") || s.contains("超时")));
    }

    @Test
    @DisplayName("Should provide suggestions for table not found")
    void testTableNotFound() {
        Exception ex = new RuntimeException("ORA-00942: table or view does not exist");
        List<String> suggestions = engine.analyze(ex, "describeTable");

        assertNotNull(suggestions);
        assertFalse(suggestions.isEmpty());
        // Check for Oracle content
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("ORA-00942") || s.contains("表")));
    }

    @Test
    @DisplayName("Should provide suggestions for lock conflict")
    void testLockConflict() {
        Exception ex = new RuntimeException("ORA-00054: resource busy and acquire requested");
        List<String> suggestions = engine.analyze(ex, "insertData");

        assertNotNull(suggestions);
        assertFalse(suggestions.isEmpty());
        // Check for lock-related content
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("ORA-00054") || s.contains("锁")));
    }
}
