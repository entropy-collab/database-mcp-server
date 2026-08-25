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

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class DataCatalogServiceImpl implements DataCatalogService {

    private static final Logger log = LoggerFactory.getLogger(DataCatalogServiceImpl.class);

    /** Keyword patterns that indicate sensitive personal/financial data. */
    private static final List<Pattern> SENSITIVITY_PATTERNS = List.of(
            Pattern.compile("(?i)^(id_card|national_id|passport|social_security|ssn)"),
            Pattern.compile("(?i)^(phone|mobile|telephone|cell_phone)"),
            Pattern.compile("(?i)^(email|mail_address)"),
            Pattern.compile("(?i)^(address|home_addr|billing_addr)"),
            Pattern.compile("(?i)^(password|passwd|pwd|secret|pin_code)"),
            Pattern.compile("(?i)^(bank_account|card_number|credit_card|acct_no)"),
            Pattern.compile("(?i)^(date_of_birth|dob|birth_date)"),
            Pattern.compile("(?i)^(salary|income|wage|compensation)"),
            Pattern.compile("(?i)^(gender|sex|ethnicity|nationality)"),
            Pattern.compile("(?i)^(medical|health|diagnosis|prescription)"),
            Pattern.compile("(?i)^(lat|lng|latitude|longitude|location)"),
            Pattern.compile("(?i)^(biometric|fingerprint|face_id)"),
            Pattern.compile("(?i)^(username|login_name|user_id)"),
            Pattern.compile("(?i)^(name|full_name|first_name|last_name|nick_name)")
    );

    /** Patterns that indicate business-critical data. */
    private static final List<Pattern> BUSINESS_PATTERNS = List.of(
            Pattern.compile("(?i)^(order_no|invoice_no|contract_no|po_no|shipment_no)"),
            Pattern.compile("(?i)^(amount|price|cost|fee|total|balance)"),
            Pattern.compile("(?i)^(created_at|updated_at|modified_time|status)"),
            Pattern.compile("(?i)^(product_id|sku|item_code|material_no)"),
            Pattern.compile("(?i)^(customer_id|supplier_id|vendor_id|partner_id)"),
            Pattern.compile("(?i)^(inventory|stock|quantity|balance)"),
            Pattern.compile("(?i)^(region|province|city|district|country)"),
            Pattern.compile("(?i)^(remark|note|comment|description)")
    );

    private final DynamicDataSourceManager dataSourceManager;

    public DataCatalogServiceImpl(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    // ─── Catalog Generation ──────────────────────────────────────────────────

    @Override
    public DataCatalogEntry generateCatalog(String tableName, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            String normalizedTable = dialect.normalizeTableName(tableName);

            // Table comments
            String tableComment = fetchTableComment(jdbc, dialect, normalizedTable);

            // Column comments
            List<DataElement> columns = fetchColumnComments(jdbc, dialect, normalizedTable);

            // Row count
            long rowCount = estimateRowCount(jdbc, dialect, normalizedTable);

            // Table size
            long sizeMb = estimateTableSize(jdbc, dialect, normalizedTable);

            // Classify
            DataCategory overallCat = inferCategory(columns, tableComment);
            SensitivityLevel maxSens = inferMaxSensitivity(columns);
            List<String> keywords = extractKeywords(columns, tableComment);
            String description = buildDescription(tableComment, rowCount, columns.size());

            return new DataCatalogEntry(connection, null, normalizedTable, tableComment,
                    rowCount, sizeMb, columns, overallCat, maxSens, keywords, description);
        } catch (Exception e) {
            log.warn("Failed to generate catalog for {}: {}", tableName, e.getMessage(), e);
            return buildErrorEntry(tableName, connection, null);
        }
    }

    @Override
    public List<DataCatalogEntry> scanSchema(String schema, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();

            // Get all tables
            List<Map<String, Object>> tables = jdbc.queryForList(dialect.tablesQuery(schema), schema);
            List<DataCatalogEntry> entries = new ArrayList<>();

            int batchSize = 20;
            for (int i = 0; i < tables.size(); i += batchSize) {
                List<Map<String, Object>> batch = tables.subList(i, Math.min(i + batchSize, tables.size()));
                batch.parallelStream().forEach(row -> {
                    String tname = String.valueOf(row.get("table_name"));
                    entries.add(generateCatalog(tname, connection));
                });
            }
            return entries;
        } catch (Exception e) {
            log.warn("Schema scan failed for {}: {}", schema, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<DataCatalogEntry> searchAssets(String keyword, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();

            String searchSql = dialect.searchTableCommentsQuery(keyword);
            if (searchSql == null) {
                // Fallback: search via standard table search
                String fallbackSql = dialect.searchTablesQuery(keyword);
                List<Map<String, Object>> rows = jdbc.queryForList(fallbackSql, keyword);
                return rows.stream()
                        .map(row -> generateCatalog((String) row.get("table_name"), connection))
                        .toList();
            }

            String kw = "%" + keyword + "%";
            List<Map<String, Object>> rows = jdbc.queryForList(searchSql, kw, kw);
            return rows.stream()
                    .map(row -> generateCatalog((String) row.get("table_name"), connection))
                    .toList();
        } catch (Exception e) {
            log.warn("Asset search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // ─── Classification ──────────────────────────────────────────────────────

    @Override
    public DataCatalogService.ClassifiedColumn classifyColumn(String columnName, String columnComment) {
        String cleanName = columnName.toLowerCase().replaceAll("[^a-z0-9_]", "");
        String commentLower = (columnComment != null ? columnComment : "").toLowerCase();
        String combined = cleanName + " " + commentLower;

        // Check sensitivity
        SensitivityLevel sensitivity = SensitivityLevel.INTERNAL;
        for (Pattern p : SENSITIVITY_PATTERNS) {
            if (p.matcher(combined).find()) {
                sensitivity = SensitivityLevel.CONFIDENTIAL;
                break;
            }
        }
        if (combined.contains("password") || combined.contains("secret")
                || combined.contains("pin") || combined.contains("biometric")) {
            sensitivity = SensitivityLevel.HIGHLY_SENSITIVE;
        }
        if (combined.contains("salary") || combined.contains("bank")
                || combined.contains("card_number") || combined.contains("medical")) {
            sensitivity = SensitivityLevel.RESTRICTED;
        }

        // Check category
        DataCategory category = DataCategory.BUSINESS;
        for (Pattern p : BUSINESS_PATTERNS) {
            if (p.matcher(combined).find()) {
                category = DataCategory.BUSINESS;
                break;
            }
        }
        if (cleanName.startsWith("created_") || cleanName.startsWith("updated_")
                || cleanName.contains("_at") || cleanName.equals("status")) {
            category = DataCategory.SYSTEM;
        }
        if (cleanName.contains("config") || cleanName.contains("param")
                || cleanName.contains("setting")) {
            category = DataCategory.CONFIG;
        }
        if (combined.contains("report") || combined.contains("analysis")
                || combined.contains("metric") || combined.contains("count")) {
            category = DataCategory.ANALYTICS;
        }

        return new DataCatalogService.ClassifiedColumn(columnName, sensitivity, category);
    }

    @Override
    public List<DataElement> getSensitiveColumns(String schema, String connection) {
        List<DataElement> result = new ArrayList<>();
        List<DataCatalogEntry> entries = scanSchema(schema, connection);
        for (DataCatalogEntry entry : entries) {
            for (DataElement col : entry.columns()) {
                if (col.sensitivityLevel().getLevel() >= 2) {
                    result.add(col);
                }
            }
        }
        return result;
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    private String fetchTableComment(JdbcTemplate jdbc, DatabaseDialect dialect, String tableName) {
        try {
            String sql = dialect.tableCommentsQuery();
            if (sql == null) return "";
            List<Map<String, Object>> rows = jdbc.queryForList(sql);
            return rows.stream()
                    .filter(r -> tableName.equalsIgnoreCase(String.valueOf(r.get("table_name"))))
                    .map(r -> String.valueOf(r.getOrDefault("table_comment", "")))
                    .findFirst()
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private List<DataElement> fetchColumnComments(JdbcTemplate jdbc, DatabaseDialect dialect,
                                                   String tableName) {
        List<DataElement> elements = new ArrayList<>();
        try {
            String sql = dialect.columnCommentsQuery(tableName);
            if (sql == null) return elements;
            List<Map<String, Object>> rows = jdbc.queryForList(sql);
            for (Map<String, Object> row : rows) {
                String colName = String.valueOf(row.get("column_name"));
                String dataType = String.valueOf(row.getOrDefault("data_type", "UNKNOWN"));
                int nullable = row.get("nullable") instanceof Number n
                        ? n.intValue() : 1;
                String comment = String.valueOf(row.getOrDefault("column_comment", ""));

        ClassifiedColumn classified = classifyColumn(colName, comment);
                elements.add(new DataElement(
                        null, tableName, colName, dataType, nullable,
                        comment, List.of(), classified.sensitivity(),
                        classified.category().getEn(), classified.suggestion()
                ));
            }
        } catch (Exception e) {
            log.debug("Column comment fetch failed for {}: {}", tableName, e.getMessage());
        }
        return elements;
    }

    private long estimateRowCount(JdbcTemplate jdbc, DatabaseDialect dialect, String tableName) {
        try {
            String sql = dialect.getTableRowCountSql(tableName);
            if (sql == null) return -1;
            List<Map<String, Object>> rows = jdbc.queryForList(sql, tableName);
            if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
                Object val = rows.get(0).values().iterator().next();
                return val instanceof Number n ? n.longValue() : -1;
            }
        } catch (Exception e) {
            log.debug("Row count unavailable: {}", e.getMessage());
        }
        return -1;
    }

    private long estimateTableSize(JdbcTemplate jdbc, DatabaseDialect dialect, String tableName) {
        try {
            String sql = dialect.estimateTableSizeSql(tableName, null);
            if (sql == null) return -1;
            List<Map<String, Object>> rows = jdbc.queryForList(sql, tableName);
            if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
                Object val = rows.get(0).values().iterator().next();
                return val instanceof Number n ? n.longValue() : -1;
            }
        } catch (Exception e) {
            log.debug("Table size unavailable: {}", e.getMessage());
        }
        return -1;
    }

    private DataCategory inferCategory(List<DataElement> columns, String tableComment) {
        String comment = tableComment.toLowerCase();
        for (DataElement col : columns) {
            if (col.sensitivityLevel().getLevel() >= 3) return DataCategory.PERSONAL_INFO;
        }
        if (comment.contains("pay") || comment.contains("billing") || comment.contains("finance")
                || comment.contains("amount") || comment.contains("price")) {
            return DataCategory.FINANCIAL;
        }
        if (comment.contains("report") || comment.contains("summary") || comment.contains("analytics")) {
            return DataCategory.ANALYTICS;
        }
        if (comment.contains("config") || comment.contains("setting") || comment.contains("param")) {
            return DataCategory.CONFIG;
        }
        return DataCategory.BUSINESS;
    }

    private SensitivityLevel inferMaxSensitivity(List<DataElement> columns) {
        return columns.stream()
                .map(DataElement::sensitivityLevel)
                .max(Comparator.comparingInt(SensitivityLevel::getLevel))
                .orElse(SensitivityLevel.INTERNAL);
    }

    private List<String> extractKeywords(List<DataElement> columns, String tableComment) {
        Set<String> keywords = new LinkedHashSet<>();
        if (tableComment != null && !tableComment.isBlank()) {
            keywords.add(tableComment);
        }
        for (DataElement col : columns) {
            if (col.columnComment() != null && !col.columnComment().isBlank()) {
                keywords.add(col.columnComment());
            }
            if (col.keywords() != null) keywords.addAll(col.keywords());
        }
        return new ArrayList<>(keywords);
    }

    private String buildDescription(String tableComment, long rowCount, int columnCount) {
        StringBuilder sb = new StringBuilder();
        if (tableComment != null && !tableComment.isBlank()) {
            sb.append(tableComment);
        } else {
            sb.append("数据表 ").append(rowCount > 0 ? "共 " + rowCount + " 行" : "");
        }
        if (rowCount > 0) {
            sb.append("，").append(rowCount).append(" 行数据");
        }
        sb.append("，").append(columnCount).append(" 个字段");
        return sb.toString();
    }

    private DataCatalogEntry buildErrorEntry(String tableName, String connection, String errorMsg) {
        return new DataCatalogEntry(connection, null, tableName, "", 0, 0, List.of(),
                DataCategory.OTHER, SensitivityLevel.INTERNAL, List.of(),
                "生成失败");
    }

}
