package com.entropy.database.mcp.exception;

/**
 * Thrown when a BYOK connection name cannot be resolved.
 * Replaces the former {@code NoSuchConnectionException}.
 */
public class McpNoSuchConnectionException extends McpConnectionException {

    public McpNoSuchConnectionException(String connectionName) {
        super(ErrorCode.CONNECTION_NOT_FOUND, "Connection not found: " + connectionName, connectionName);
    }

    public McpNoSuchConnectionException(String message, Throwable cause) {
        super(ErrorCode.CONNECTION_NOT_FOUND, message, cause);
    }
}
