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
package com.entropy.database.mcp.domain;

import java.util.List;
import java.util.Map;

/**
 * Standardized execution plan format (inspired by TiDB's plan management).
 * Unifies EXPLAIN output across different database dialects.
 */
public record StandardizedPlan(
    String id,                              // Plan node ID
    String parentIds,                       // Parent node IDs (comma-separated)
    String operation,                       // Operation type: SELECT, SCAN, JOIN, etc.
    String object,                          // Table/index name
    String operator,                        // Operator: eq, gt, lt, in, etc.
    List<PlanProperty> properties,          // Cost, rows, width, etc.
    String accessPredicates,                // Access conditions
    String filterPredicates,                // Filter conditions
    Integer projectionSize                  // Number of projected columns
) {
    /**
     * Create from Oracle DBMS_XPLAN output.
     */
    public static StandardizedPlan fromOracleExplain(Map<String, String> planRow) {
        String id = planRow.getOrDefault("ID", "0");
        String parentIds = planRow.getOrDefault("PARENT_ID", "");
        String operation = planRow.getOrDefault("OPERATION", "SELECT");
        String options = planRow.getOrDefault("OPTIONS", "");
        String objectName = planRow.getOrDefault("OBJECT_NAME", "");
        String projection = planRow.getOrDefault("PROJECTION", "");
        
        // Parse operation type
        String opType = extractOperationType(operation, options);
        
        // Extract predicates
        String accessPred = planRow.getOrDefault("ACCESS_PREDICATES", "");
        String filterPred = planRow.getOrDefault("FILTER_PREDICATES", "");
        
        // Estimate row count from row object or output
        String rowObj = planRow.getOrDefault("ROW_OBJ", "");
        int estimatedRows = parseEstimatedRows(rowObj);
        
        return new StandardizedPlan(
            id,
            parentIds,
            opType,
            objectName,
            extractOperator(options),
            List.of(
                new PlanProperty("estimated_rows", estimatedRows),
                new PlanProperty("cost", parseCost(planRow.get("_COST"))),
                new PlanProperty("cardinality", estimatedRows)
            ),
            accessPred.isBlank() ? null : accessPred,
            filterPred.isBlank() ? null : filterPred,
            estimateProjectionSize(projection)
        );
    }

    /**
     * Create from PostgreSQL EXPLAIN JSON.
     */
    public static StandardizedPlan fromPostgresExplain(Map<String, Object> node) {
        String operation = String.valueOf(node.getOrDefault("Node Type", "UNKNOWN"));
        String parentIds = "";
        
        // Extract table/index
        String object = "";
        if (node.containsKey("Relation Name")) {
            object = String.valueOf(node.get("Relation Name"));
        } else if (node.containsKey("Index Name")) {
            object = String.valueOf(node.get("Index Name"));
        }
        
        // Extract predicates
        String accessPred = node.containsKey("Filter") ? 
            String.valueOf(node.get("Filter")) : "";
        
        // Extract cost
        @SuppressWarnings("unchecked")
        Map<String, Number> cost = (Map<String, Number>) node.get("Total Cost");
        double totalCost = cost != null ? cost.getOrDefault("final", 0.0).doubleValue() : 0.0;
        
        // Extract rows
        long estimatedRows = node.containsKey("Plan Rows") ?
            ((Number) node.get("Plan Rows")).longValue() : 0;
        
        return new StandardizedPlan(
            "0",
            parentIds,
            mapPostgresOperation(operation),
            object,
            extractOperatorFromNode(node),
            List.of(
                new PlanProperty("estimated_rows", estimatedRows),
                new PlanProperty("total_cost", totalCost),
                new PlanProperty("width", node.containsKey("Plan Width") ? 
                    ((Number) node.get("Plan Width")).longValue() : 0)
            ),
            accessPred.isBlank() ? null : accessPred,
            null,
            null
        );
    }

    /**
     * Get simplified operation summary.
     */
    public String getOperationSummary() {
        if (object() == null || object().isBlank()) {
            return operation();
        }
        return operation() + " on " + object();
    }

    /**
     * Check if this is a full table scan.
     */
    public boolean isFullTableScan() {
        return "TABLE ACCESS".equals(operation()) && 
               "FULL".equalsIgnoreCase(operator());
    }

    /**
     * Get estimated rows (0 if unknown).
     */
    public long getEstimatedRows() {
        return properties().stream()
            .filter(p -> "estimated_rows".equals(p.name()))
            .mapToLong(p -> ((Number) p.value()).longValue())
            .findFirst()
            .orElse(0L);
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private static String extractOperationType(String operation, String options) {
        String combined = (operation + " " + options).toUpperCase();
        
        if (combined.contains("NESTED LOOPS")) return "NESTED_LOOP_JOIN";
        if (combined.contains("HASH JOIN")) return "HASH_JOIN";
        if (combined.contains("MERGE JOIN")) return "MERGE_JOIN";
        if (combined.contains("HASH UNIQUE")) return "HASH_UNIQUE";
        if (combined.contains("SORT")) return "SORT";
        if (combined.contains("INDEX")) return "INDEX_SCAN";
        if (combined.contains("TABLE ACCESS")) return "TABLE_ACCESS";
        if (combined.contains("VIEW")) return "VIEW";
        if (combined.contains("UNION")) return "UNION";
        
        return operation.toUpperCase();
    }

    private static String extractOperator(String options) {
        String upper = options.toUpperCase();
        if (upper.contains("FULL")) return "FULL";
        if (upper.contains("INDEX")) return "INDEX";
        if (upper.contains("RANGE")) return "RANGE";
        if (upper.contains("HASH")) return "HASH";
        if (upper.contains("NESTED")) return "NESTED";
        if (upper.contains("MERGE")) return "MERGE";
        return null;
    }

    private static int parseEstimatedRows(String rowObj) {
        try {
            // Format: "ROWNUM=1234"
            int idx = rowObj.indexOf('=');
            if (idx > 0) {
                return Integer.parseInt(rowObj.substring(idx + 1).trim());
            }
        } catch (Exception e) {
            // Ignore
        }
        return 0;
    }

    private static Double parseCost(String costStr) {
        try {
            return Double.parseDouble(costStr != null ? costStr : "0");
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static Integer estimateProjectionSize(String projection) {
        if (projection == null || projection.isBlank()) return null;
        // Count comma-separated items in projection
        return projection.split(",").length;
    }

    private static String mapPostgresOperation(String type) {
        return switch (type.toUpperCase()) {
            case "SEQ SCAN" -> "SEQUENCE_SCAN";
            case "INDEX SCAN" -> "INDEX_SCAN";
            case "INDEX ONLY SCAN" -> "INDEX_ONLY_SCAN";
            case "NESTED LOOP" -> "NESTED_LOOP_JOIN";
            case "HASH JOIN" -> "HASH_JOIN";
            case "MERGE JOIN" -> "MERGE_JOIN";
            case "SORT" -> "SORT";
            case "AGG" -> "AGGREGATE";
            case "HASH" -> "HASH";
            default -> type;
        };
    }

    private static String extractOperatorFromNode(Map<String, Object> node) {
        if (node.containsKey("Filter")) {
            String filter = String.valueOf(node.get("Filter"));
            if (filter.contains("=")) return "EQ";
            if (filter.contains(">")) return "GT";
            if (filter.contains("<")) return "LT";
            if (filter.contains("LIKE")) return "LIKE";
        }
        return null;
    }
}
