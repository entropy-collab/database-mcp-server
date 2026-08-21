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
package com.entropy.database.mcp.security;

import java.util.Set;
import java.util.List;

/**
 * SQL validation service interface.
 * Validates SQL queries for safety, table access, and resource limits.
 */
public interface SqlValidator {

    /**
     * Validate a SELECT query.
     */
    void validateSelect(String sql);

    /**
     * Validate a DDL statement.
     */
    void validateDdl(String sql);

    /**
     * Set the maximum number of JOINs allowed.
     */
    void setMaxJoins(int maxJoins);

    /**
     * Set the maximum subquery nesting depth allowed.
     */
    void setMaxSubqueryDepth(int maxSubqueryDepth);

    /**
     * Get the maximum rows allowed per query.
     */
    int getMaxRows();

    /**
     * Set the maximum rows allowed per query.
     */
    void setMaxRows(int maxRows);

    /**
     * Get the set of allowed tables.
     */
    Set<String> getAllowedTables();

    /**
     * Set the allowed tables.
     */
    void setAllowedTables(Set<String> allowedTables);

    /**
     * Get the set of allowed operations.
     */
    Set<String> getAllowedOperations();

    /**
     * Set the allowed operations.
     */
    void setAllowedOperations(Set<String> allowedOperations);

    /**
     * Get the list of mask columns.
     */
    List<String> getMaskColumns();

    /**
     * Set the list of mask columns.
     */
    void setMaskColumns(List<String> maskColumns);
}
