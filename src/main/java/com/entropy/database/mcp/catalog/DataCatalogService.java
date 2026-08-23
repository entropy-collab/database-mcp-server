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
package com.entropy.database.mcp.catalog;

import java.util.List;

public interface DataCatalogService {

    /**
     * Generate a full data catalog entry for the given table.
     */
    DataCatalogEntry generateCatalog(String tableName, String connection);

    /**
     * Scan all tables in the given schema and return their catalog entries.
     */
    List<DataCatalogEntry> scanSchema(String schema, String connection);

    /**
     * Search data assets by keyword across table names and comments.
     */
    List<DataCatalogEntry> searchAssets(String keyword, String connection);

    /**
     * Classify a column name into sensitivity level and category based on naming heuristics.
     */
    ClassifiedColumn classifyColumn(String columnName, String columnComment);

    /**
     * Get all sensitive columns across all tables in the given schema.
     */
    List<DataElement> getSensitiveColumns(String schema, String connection);

    /**
     * Classification result for a single column.
     */
    record ClassifiedColumn(
            String columnName,
            SensitivityLevel sensitivity,
            DataCategory category,
            String suggestion
    ) {
        public ClassifiedColumn(String columnName, SensitivityLevel sensitivity, DataCategory category) {
            this(columnName, sensitivity, category, buildSuggestion(sensitivity, category, columnName));
        }

        private static String buildSuggestion(SensitivityLevel sens, DataCategory cat, String colName) {
            if (sens.getLevel() >= 3) return "建议脱敏处理（加密/掩码），限制访问权限";
            if (sens.getLevel() >= 2) return "建议增加访问控制，审计日志记录";
            return "普通数据，按内部标准管理";
        }
    }
}
