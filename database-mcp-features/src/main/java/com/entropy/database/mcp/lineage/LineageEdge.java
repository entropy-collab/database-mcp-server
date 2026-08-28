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
 * A single edge in the data lineage graph.
 *
 * @param sourceTable  upstream table (producer)
 * @param targetTable  downstream table (consumer)
 * @param sourceColumn upstream column (null = table-level)
 * @param targetColumn downstream column (null = table-level)
 * @param type         lineage relationship type
 * @param connection   BYOK connection name
 * @param schema       schema name (nullable)
 */
public record LineageEdge(
    String sourceTable,
    String targetTable,
    String sourceColumn,
    String targetColumn,
    LineageType type,
    String connection,
    String schema
) {
    public LineageEdge {
        if (sourceColumn == null) sourceColumn = "*";
        if (targetColumn == null) targetColumn = "*";
        if (schema == null) schema = "";
    }
}
