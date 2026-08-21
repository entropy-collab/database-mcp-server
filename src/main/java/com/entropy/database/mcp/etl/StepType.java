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
package com.entropy.database.mcp.etl;

/**
 * Supported step types in an ETL job.
 */
public enum StepType {
    /**
     * Execute a query and insert results into a target table.
     */
    QUERY_TO_TABLE("query_to_table"),

    /**
     * Execute a query and return results as JSON.
     */
    QUERY_TO_JSON("query_to_json"),

    /**
     * Transform data from source table to target table with column mapping.
     */
    TRANSFORM("transform"),

    /**
     * Execute DDL statements.
     */
    DDL("ddl"),

    /**
     * Upsert (insert or update) data into a table.
     */
    UPSERT("upsert"),

    /**
     * Export query results to a file or external system.
     */
    EXPORT("export");

    private final String value;

    StepType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static StepType from(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase()) {
            case "query_to_table" -> QUERY_TO_TABLE;
            case "query_to_json" -> QUERY_TO_JSON;
            case "transform" -> TRANSFORM;
            case "ddl" -> DDL;
            case "upsert" -> UPSERT;
            case "export" -> EXPORT;
            default -> throw new IllegalArgumentException("Unknown step type: " + value);
        };
    }
}
