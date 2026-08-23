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
package com.entropy.database.mcp.lineage;

/**
 * Type of data lineage relationship.
 */
public enum LineageType {
    /** Foreign key constraint */
    FOREIGN_KEY,
    /** ETL step writes to table from query */
    ETL_WRITE,
    /** View dependency on base table */
    VIEW_DEPENDENCY,
    /** Column-level mapping in transformation */
    COLUMN_MAP,
    /** Materialized view refresh */
    MATERIALIZED_VIEW,
    /** Generic dependency */
    DEPENDENCY
}
