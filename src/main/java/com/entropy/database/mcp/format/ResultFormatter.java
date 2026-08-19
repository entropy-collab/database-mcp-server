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
package com.entropy.database.mcp.format;

import java.util.List;
import java.util.Map;

/**
 * SQL query result formatter for different output formats.
 */
public class ResultFormatter {

    /**
     * Format query results as CSV.
     */
    public static String toCsv(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }

        // Extract column names from first row
        List<String> columns = List.copyOf(rows.get(0).keySet());
        
        StringBuilder sb = new StringBuilder();
        
        // Write header
        sb.append(String.join(",", escapeCsvColumns(columns)));
        sb.append("\n");
        
        // Write data rows
        for (Map<String, Object> row : rows) {
            List<String> values = columns.stream()
                .map(col -> formatCsvValue(row.get(col)))
                .toList();
            sb.append(String.join(",", values));
            sb.append("\n");
        }
        
        return sb.toString();
    }

    /**
     * Format query results as pretty JSON.
     */
    public static String toJson(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "[]";
        }
        
        // Simple JSON formatting without external dependency
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            sb.append("  {");
            
            int idx = 0;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (idx > 0) sb.append(", ");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\": ");
                sb.append(formatJsonValue(entry.getValue()));
                idx++;
            }
            
            sb.append("}");
            if (i < rows.size() - 1) sb.append(",");
            sb.append("\n");
        }
        
        sb.append("]");
        return sb.toString();
    }

    /**
     * Format as markdown table.
     */
    public static String toMarkdown(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }

        List<String> columns = List.copyOf(rows.get(0).keySet());
        
        StringBuilder sb = new StringBuilder();
        
        // Header
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("| " + String.join(" | ", columns.stream().map(c -> "---").toList()) + " |\n");
        
        // Data rows
        for (Map<String, Object> row : rows) {
            List<String> values = columns.stream()
                .map(col -> String.valueOf(row.getOrDefault(col, "")))
                .toList();
            sb.append("| ").append(String.join(" | ", values)).append(" |\n");
        }
        
        return sb.toString();
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private static List<String> escapeCsvColumns(List<String> columns) {
        return columns.stream()
            .map(ResultFormatter::escapeCsvValue)
            .toList();
    }

    private static String escapeCsvValue(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String formatCsvValue(Object value) {
        if (value == null) return "";
        String str = String.valueOf(value);
        return escapeCsvValue(str);
    }

    private static String escapeJson(String key) {
        return key.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n");
    }

    private static String formatJsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            return "\"" + escapeJson((String) value) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }
}
