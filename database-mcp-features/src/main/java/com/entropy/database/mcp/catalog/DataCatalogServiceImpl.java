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
import com.entropy.database.mcp.properties.ThreadPoolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

@Service
public class DataCatalogServiceImpl implements DataCatalogService, DisposableBean {

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

    /**
     * Upper bound on concurrent per-table catalog generation.
     *
     * <p>Every table costs three round trips on a connection borrowed from the BYOK pool, so an
     * unbounded {@code parallelStream()} over a thousand-table schema would try to occupy the whole
     * pool (and starve every other caller of the same connection). The default of four is
     * deliberately smaller than the default pool size.
     *
     * <p>It bounds <b>one shared pool</b> rather than a pool per call: a per-call pool bounded each
     * scan individually but nothing bounded the total, so N concurrent {@code scanSchema} calls
     * meant 4N threads competing for the same BYOK connections.
     */
    private final int scanParallelism;
    private final ExecutorService scanPool;

    public DataCatalogServiceImpl(DynamicDataSourceManager dataSourceManager,
                                  ThreadPoolProperties threadPoolProperties) {
        this.dataSourceManager = dataSourceManager;
        this.scanParallelism = (threadPoolProperties != null ? threadPoolProperties
                : ThreadPoolProperties.defaults()).catalogScanSize();
        this.scanPool = Executors.newFixedThreadPool(scanParallelism, runnable -> {
            Thread thread = new Thread(runnable, "catalog-scan");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void destroy() {
        scanPool.shutdownNow();
    }

    // ─── Catalog Generation ──────────────────────────────────────────────────

    @Override
    public DataCatalogEntry generateCatalog(String tableName, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            return generateCatalog(ctx, tableName, connection, null, null);
        } catch (Exception e) {
            log.warn("Failed to generate catalog for {}: {}", tableName, e.getMessage(), e);
            return buildErrorEntry(tableName, connection);
        }
    }

    /**
     * Builds one catalog entry on an already-acquired context.
     *
     * @param schema        the schema the table lives in, or {@code null} for the connection's current
     *                      one. Passed on to every dialect metadata query: without it a scan of a
     *                      non-default schema looked up each table in the current schema instead and
     *                      came back empty.
     * @param tableComments schema-wide table comment lookup (upper-cased table name to comment), or
     *                      {@code null} to fetch the single table's comment on demand
     */
    private DataCatalogEntry generateCatalog(ByokDataSourceContext ctx, String tableName,
                                             String connection, String schema,
                                             Map<String, String> tableComments) {
        DatabaseDialect dialect = ctx.getDialect();
        JdbcTemplate jdbc = ctx.getJdbcTemplate();
        String normalizedTable = dialect.normalizeTableName(tableName);

        String tableComment = tableComments != null
                ? tableComments.getOrDefault(normalize(normalizedTable), "")
                : fetchTableComment(jdbc, dialect, schema, normalizedTable);

        ColumnMetadata columnMetadata = fetchColumnComments(jdbc, dialect, schema, normalizedTable);
        List<DataElement> columns = columnMetadata.columns();

        long rowCount = estimateRowCount(jdbc, dialect, schema, normalizedTable);
        long sizeMb = estimateTableSize(jdbc, dialect, schema, normalizedTable);

        DataCategory overallCat = inferCategory(columns, tableComment);
        SensitivityLevel maxSens = inferMaxSensitivity(columns);
        List<String> keywords = extractKeywords(columns, tableComment);
        String description = buildDescription(tableComment, rowCount, columns.size(),
                columnMetadata.failure());

        return new DataCatalogEntry(connection, schema, normalizedTable, tableComment,
                rowCount, sizeMb, columns, overallCat, maxSens, keywords, description);
    }

    @Override
    public List<DataCatalogEntry> scanSchema(String schema, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();

            // Per the dialect contract tablesQuery resolves the schema itself and declares no
            // placeholder. Binding one when a schema was given is what made this fail on Oracle -
            // its tablesQuery never had a schema parameter - and the exception was swallowed below,
            // so the whole catalog and every sensitive-column scan came back empty.
            String tablesSql = dialect.tablesQuery(schema);
            List<Map<String, Object>> tables = jdbc.queryForList(tablesSql);

            List<String> tableNames = tables.stream()
                    .map(row -> rowString(row, "table_name", null))
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .toList();
            if (tableNames.isEmpty()) {
                return List.of();
            }

            // One query for the whole schema instead of one per table.
            Map<String, String> tableComments = fetchAllTableComments(jdbc, dialect, schema);

            return generateCatalogs(ctx, tableNames, connection, schema, tableComments);
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
            List<Map<String, Object>> rows;
            if (searchSql == null) {
                // Fallback: search via standard table search
                rows = jdbc.queryForList(dialect.searchTablesQuery(keyword), "%" + keyword + "%");
            } else {
                String kw = "%" + keyword + "%";
                rows = jdbc.queryForList(searchSql, kw, kw);
            }

            List<String> tableNames = rows.stream()
                    .map(row -> rowString(row, "table_name", null))
                    .filter(Objects::nonNull)
                    .toList();
            if (tableNames.isEmpty()) {
                return List.of();
            }
            return generateCatalogs(ctx, tableNames, connection, null,
                    fetchAllTableComments(jdbc, dialect, null));
        } catch (Exception e) {
            log.warn("Asset search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Generates catalog entries for several tables with bounded concurrency.
     *
     * <p>Results are collected from {@link Future}s in submission order: the previous implementation
     * had every worker call {@code ArrayList.add} on one shared list, which drops entries, leaves
     * null holes or throws {@code ArrayIndexOutOfBoundsException} depending on timing.
     */
    private List<DataCatalogEntry> generateCatalogs(ByokDataSourceContext ctx, List<String> tableNames,
                                                    String connection, String schema,
                                                    Map<String, String> tableComments) {
        int parallelism = Math.min(scanParallelism, tableNames.size());
        if (parallelism <= 1) {
            return tableNames.stream()
                    .map(name -> safeGenerateCatalog(ctx, name, connection, schema, tableComments))
                    .toList();
        }

        List<Future<DataCatalogEntry>> futures = tableNames.stream()
                .map(name -> scanPool.submit(
                        () -> safeGenerateCatalog(ctx, name, connection, schema, tableComments)))
                .toList();

        List<DataCatalogEntry> entries = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                entries.add(futures.get(i).get());
            } catch (ExecutionException e) {
                log.warn("Catalog generation failed for {}: {}",
                        tableNames.get(i), e.getCause() != null ? e.getCause() : e, e);
                entries.add(buildErrorEntry(tableNames.get(i), connection));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Abandoning the remaining futures would leave them running against a connection
                // the caller is about to release, so cancel what has not started yet.
                futures.subList(i, futures.size()).forEach(f -> f.cancel(true));
                log.warn("Schema scan interrupted after {} of {} tables",
                        entries.size(), tableNames.size());
                return List.copyOf(entries);
            }
        }
        return entries;
    }

    private DataCatalogEntry safeGenerateCatalog(ByokDataSourceContext ctx, String tableName,
                                                 String connection, String schema,
                                                 Map<String, String> tableComments) {
        try {
            return generateCatalog(ctx, tableName, connection, schema, tableComments);
        } catch (Exception e) {
            log.warn("Failed to generate catalog for {}: {}", tableName, e.getMessage(), e);
            return buildErrorEntry(tableName, connection);
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

    /**
     * Reads the table comments of the whole schema once.
     *
     * @return upper-cased table name to comment; empty when the dialect exposes no comment source
     */
    private Map<String, String> fetchAllTableComments(JdbcTemplate jdbc, DatabaseDialect dialect,
                                                      String schema) {
        String sql = dialect.tableCommentsQuery(schema);
        if (sql == null) {
            return Map.of();
        }
        try {
            Map<String, String> comments = new HashMap<>();
            for (Map<String, Object> row : jdbc.queryForList(sql)) {
                String name = rowString(row, "table_name", null);
                if (name != null) {
                    comments.put(normalize(name), rowString(row, "table_comment", ""));
                }
            }
            return comments;
        } catch (Exception e) {
            log.warn("Table comment fetch failed: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * Reads the comment of a single table.
     *
     * <p>Uses the single-table dialect query rather than reading the whole schema and discarding all
     * but one row: on a thousand-table database, cataloguing one table used to transfer a thousand
     * comments. Per the dialect contract the SQL carries exactly one {@code ?} for the table name.
     */
    private String fetchTableComment(JdbcTemplate jdbc, DatabaseDialect dialect, String schema,
                                     String tableName) {
        String sql = dialect.tableCommentQuery(schema, tableName);
        if (sql == null) {
            return "";
        }
        try {
            for (Map<String, Object> row : jdbc.queryForList(sql, tableName)) {
                return rowString(row, "table_comment", "");
            }
        } catch (Exception e) {
            log.warn("Table comment fetch failed for {}: {}", tableName, e.getMessage(), e);
        }
        return "";
    }

    /**
     * Reads column comments for one table.
     *
     * <p>Per the dialect contract the SQL carries exactly one {@code ?} for the table name, bound with
     * the dialect-normalized name (upper case on Oracle, where {@code all_tab_columns.table_name} is
     * stored upper case). The bind used to be derived by counting placeholders, which silently bound
     * the table name into a schema parameter on the dialects that had two.
     */
    private ColumnMetadata fetchColumnComments(JdbcTemplate jdbc, DatabaseDialect dialect,
                                               String schema, String tableName) {
        String sql = dialect.columnCommentsQuery(schema, tableName);
        if (sql == null) {
            return ColumnMetadata.unsupported();
        }
        List<DataElement> elements = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, tableName);
            for (Map<String, Object> row : rows) {
                String colName = rowString(row, "column_name", null);
                if (colName == null) {
                    continue;
                }
                String dataType = rowString(row, "data_type", "UNKNOWN");
                Integer nullable = parseNullable(rowValue(row, "nullable"));
                String comment = rowString(row, "column_comment", "");

                ClassifiedColumn classified = classifyColumn(colName, comment);
                elements.add(new DataElement(
                        null, tableName, colName, dataType, nullable,
                        comment, List.of(), classified.sensitivity(),
                        classified.category().getEn(), classified.suggestion()
                ));
            }
        } catch (Exception e) {
            // A failed query is not "this table has no columns": report it instead of hiding it,
            // otherwise every downstream sensitivity decision silently degrades to INTERNAL.
            log.warn("Column comment fetch failed for {}: {}", tableName, e.getMessage(), e);
            return new ColumnMetadata(List.of(),
                    "列元数据查询失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return new ColumnMetadata(List.copyOf(elements), null);
    }

    /**
     * Row count for one table: the catalog prefers the optimizer's estimate, which reads a catalog
     * view, and only falls back to an exact {@code COUNT(*)} when the dialect has no estimate or has
     * never had statistics gathered. A schema scan would otherwise scan every table in the schema.
     */
    private long estimateRowCount(JdbcTemplate jdbc, DatabaseDialect dialect, String schema,
                                  String tableName) {
        long estimate = queryRowCount(jdbc,
                dialect.getTableRowCountEstimateSql(schema, tableName), tableName);
        if (estimate >= 0) {
            return estimate;
        }
        return queryRowCount(jdbc, dialect.getTableRowCountSql(schema, tableName), null);
    }

    /**
     * @param tableNameArg the value for the single {@code ?} the SQL declares, or {@code null} when it
     *                     declares none (the exact count quotes the table name into the SQL, since a
     *                     {@code FROM} clause cannot take a bind parameter)
     */
    private long queryRowCount(JdbcTemplate jdbc, String sql, String tableNameArg) {
        if (sql == null) {
            return -1;
        }
        try {
            List<Map<String, Object>> rows = tableNameArg == null
                    ? jdbc.queryForList(sql)
                    : jdbc.queryForList(sql, tableNameArg);
            if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
                Object val = rows.get(0).values().iterator().next();
                return val instanceof Number n ? n.longValue() : -1;
            }
        } catch (Exception e) {
            log.debug("Row count unavailable: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Table size in MB. Per the dialect contract the SQL takes the table name as its only bind value;
     * a dialect with no size source returns {@code null} and the size is reported as unknown rather
     * than as a fabricated zero.
     */
    private long estimateTableSize(JdbcTemplate jdbc, DatabaseDialect dialect, String schema,
                                   String tableName) {
        try {
            String sql = dialect.estimateTableSizeSql(tableName, schema);
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
        String comment = tableComment == null ? "" : tableComment.toLowerCase();
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

    private String buildDescription(String tableComment, long rowCount, int columnCount,
                                     String columnFailure) {
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
        if (columnFailure != null) {
            sb.append("（").append(columnFailure).append("）");
        }
        return sb.toString();
    }

    private DataCatalogEntry buildErrorEntry(String tableName, String connection) {
        return new DataCatalogEntry(connection, null, tableName, "", 0, 0, List.of(),
                DataCategory.OTHER, SensitivityLevel.INTERNAL, List.of(),
                "生成失败");
    }

    // ─── Result-set helpers ──────────────────────────────────────────────────

    /**
     * Column-comment lookup outcome.
     *
     * @param columns the columns that were read
     * @param failure non-null when the query failed, so an empty column list is never mistaken for
     *                "this table genuinely has no columns"
     */
    private record ColumnMetadata(List<DataElement> columns, String failure) {
        static ColumnMetadata unsupported() {
            return new ColumnMetadata(List.of(), null);
        }
    }

    /**
     * Case-insensitive column lookup.
     *
     * <p>JDBC drivers disagree on label case: Oracle and H2 return {@code TABLE_NAME} while MySQL
     * and PostgreSQL return {@code table_name}, so a plain {@code row.get("table_name")} yields
     * null on half of the supported dialects.
     */
    private static Object rowValue(Map<String, Object> row, String column) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        if (row.containsKey(column)) {
            return row.get(column);
        }
        String upper = column.toUpperCase(Locale.ROOT);
        if (row.containsKey(upper)) {
            return row.get(upper);
        }
        String lower = column.toLowerCase(Locale.ROOT);
        if (row.containsKey(lower)) {
            return row.get(lower);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String rowString(Map<String, Object> row, String column, String fallback) {
        Object value = rowValue(row, column);
        return value != null ? String.valueOf(value) : fallback;
    }

    /** Accepts both the numeric flag and the {@code YES}/{@code NO} spelling of {@code IS_NULLABLE}. */
    private static Integer parseNullable(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof String s) {
            String v = s.trim();
            if (v.equalsIgnoreCase("NO") || v.equalsIgnoreCase("N") || v.equals("0")) return 0;
            if (v.equalsIgnoreCase("YES") || v.equalsIgnoreCase("Y") || v.equals("1")) return 1;
        }
        return 1;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }
}
