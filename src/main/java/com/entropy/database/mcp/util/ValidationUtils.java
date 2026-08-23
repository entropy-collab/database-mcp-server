/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.entropy.database.mcp.util;

import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.exception.ErrorCode;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Validation utilities for database operations.
 * Centralizes validation logic to avoid duplication across tool classes.
 */
public final class ValidationUtils {

    private ValidationUtils() {
    }

    public static void validateIdentifier(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " cannot be blank");
        }
        if (!value.matches("[A-Za-z][A-Za-z0-9_$#]*")) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " contains invalid characters: " + value);
        }
    }

    public static void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "host cannot be blank");
        }
        if (!host.matches("[A-Za-z0-9._-]+")) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "host contains invalid characters: " + host);
        }
    }

    public static void validatePort(String port) {
        if (port == null || port.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "port cannot be blank");
        }
        try {
            int p = Integer.parseInt(port);
            if (p < 1 || p > 65535) {
                throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "port out of range: " + p);
            }
        } catch (NumberFormatException e) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "port must be a number: " + port, e);
        }
    }

    public static void validateServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "serviceName cannot be blank");
        }
        if (!serviceName.matches("[A-Za-z0-9._]+")) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "serviceName contains invalid characters: " + serviceName);
        }
    }

    public static void requireNotBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " cannot be blank");
        }
    }

    public static void requireNotNull(Object value, String paramName) {
        if (value == null) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " cannot be null");
        }
    }

    public static void requireNotEmpty(Collection<?> collection, String paramName) {
        if (collection == null || collection.isEmpty()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " cannot be empty");
        }
    }

    public static void requirePositive(int value, String paramName) {
        if (value <= 0) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " must be positive");
        }
    }

    public static void requireMinLength(String value, int minLength, String paramName) {
        if (value == null || value.length() < minLength) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " must be at least " + minLength + " characters");
        }
    }

    public static void requireMaxLength(String value, int maxLength, String paramName) {
        if (value != null && value.length() > maxLength) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " must be at most " + maxLength + " characters");
        }
    }

    public static void requireInRange(int value, int min, int max, String paramName) {
        if (value < min || value > max) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " must be between " + min + " and " + max);
        }
    }

    // Pre-compiled patterns for performance
    private static final Pattern WHERE_CLAUSE_PATTERN = Pattern.compile("[A-Za-z0-9_\\s.,'\"=<>!()+\\-*/%&|^~]+");
    // Dangerous substrings that indicate SQL injection
    private static final String[] DANGEROUS_PATTERNS = {";", "--", "/*", "*/", "(", ")", "SELECT ", "INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "CREATE "};

    /**
     * Validate WHERE clause for SQL injection safety.
     * Only allows simple column comparisons: col = value, col > value, etc.
     * Rejects subqueries, multi-statement attacks, and dangerous keywords.
     */
    public static void validateWhereClause(String whereClause, String paramName) {
        if (whereClause == null || whereClause.isBlank()) {
            return;
        }
        String lower = whereClause.toLowerCase().trim();
        // Reject subqueries and multi-statement patterns
        for (String dangerous : DANGEROUS_PATTERNS) {
            if (lower.contains(dangerous) && dangerous.contains(" ")) {
                // Allow comparison operators like "= ", "> ", "< " but reject keywords
                if (!isComparisonOperator(dangerous.trim())) {
                    throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                            paramName + " contains dangerous SQL pattern: " + dangerous.trim());
                }
            }
        }
        // Reject subqueries: any parenthesis containing SELECT
        if (lower.contains("select") && lower.contains("(")) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " contains disallowed subquery pattern");
        }
        // Only allow safe characters: letters, numbers, spaces, operators, quotes, parentheses
        if (!WHERE_CLAUSE_PATTERN.matcher(whereClause).matches()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " contains invalid characters. Only alphanumeric, spaces, and basic operators are allowed");
        }
    }

    private static boolean isComparisonOperator(String op) {
        return op.equals("=") || op.equals(">") || op.equals("<") || op.equals("!")
                || op.equals("<>") || op.equals("<=") || op.equals(">=") || op.equals("<!");
    }
}
