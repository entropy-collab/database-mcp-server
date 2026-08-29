package com.entropy.database.mcp.exception;

/**
 * Thrown when a leased datasource exceeds its maximum lifetime.
 * Replaces the former {@code LeaseExpiredException}.
 */
public class McpLeaseExpiredException extends McpConnectionException {

    public McpLeaseExpiredException(String message) {
        super(ErrorCode.LEASE_EXPIRED, message);
    }

    public McpLeaseExpiredException(String message, Throwable cause) {
        super(ErrorCode.LEASE_EXPIRED, message, cause);
    }
}
