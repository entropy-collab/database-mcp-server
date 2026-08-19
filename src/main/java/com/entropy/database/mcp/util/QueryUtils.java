/*
 * Copyright 2024-2026 Entropy Pty Ltd.
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

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Utility class for common conversions and formatting operations.
 */
public final class QueryUtils {

    private QueryUtils() {
        // Utility class
    }

    /**
     * Convert database values to JSON-serializable standard Java types.
     */
    public static Object convertToSerializable(Object value) {
        if (value == null) {
            return null;
        }
        // Handle Oracle-specific types by converting to String
        String className = value.getClass().getName();
        if (className.startsWith("oracle.") || className.startsWith("java.sql")) {
            return value.toString();
        }
        // Handle java.time types
        if (value instanceof LocalDate ld) {
            return ld.toString();
        }
        if (value instanceof LocalTime lt) {
            return lt.toString();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.toString();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (value instanceof Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof Time t) {
            return t.toLocalTime().toString();
        }
        return value;
    }

    /**
     * Format rows as CSV string.
     */
    public static String toCsv(List<Map<String, Object>> rows, List<String> columns) {
        if (rows.isEmpty()) {
            return String.join(",", columns) + "\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", columns)).append("\n");

        for (Map<String, Object> row : rows) {
            List<String> values = columns.stream()
                .map(col -> formatCsvValue(row.get(col)))
                .toList();
            sb.append(String.join(",", values)).append("\n");
        }

        return sb.toString();
    }

    private static String formatCsvValue(Object value) {
        if (value == null) {
            return "";
        }
        String str = value.toString();
        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }
}
