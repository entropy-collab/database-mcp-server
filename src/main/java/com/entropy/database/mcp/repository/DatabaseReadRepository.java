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
package com.entropy.database.mcp.repository;

import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.repository.QueryLimits;
import com.entropy.database.mcp.security.DataMaskingService;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.stream.SseStreamManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Database read operations repository.
 * Handles all SELECT queries with proper pagination, caching, and dialect-driven SQL.
 */
public class DatabaseReadRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseReadRepository.class);
    public static final int DEFAULT_MAX_ROWS = 100;
    public static final int DEFAULT_MAX_RESULT_ROWS = 10000;
    public static final int DEFAULT_FETCH_SIZE = 100;
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;
    private final SqlValidator sqlValidator;
    private final DatabaseCache cache;
    private final DataMaskingService maskingService;
    private final int maxRows;
    private final int maxResultRows;
    private final int fetchSize;
    private final int queryTimeoutSeconds;
    private final SseStreamManager sseStreamManager;

    public DatabaseReadRepository(JdbcTemplate jdbcTemplate,
                                  DatabaseDialect dialect,
                                  SqlValidator sqlValidator,
                                  DatabaseCache cache,
                                  DataMaskingService maskingService,
                                  SseStreamManager sseStreamManager) {
        this(jdbcTemplate, dialect, sqlValidator, cache, maskingService,
             DEFAULT_MAX_ROWS, DEFAULT_MAX_RESULT_ROWS, DEFAULT_FETCH_SIZE,
             DEFAULT_QUERY_TIMEOUT_SECONDS, sseStreamManager);
    }

    public DatabaseReadRepository(JdbcTemplate jdbcTemplate,
                                  DatabaseDialect dialect,
                                  SqlValidator sqlValidator,
                                  DatabaseCache cache,
                                  DataMaskingService maskingService,
                                  QueryLimits limits,
                                  SseStreamManager sseStreamManager) {
        this(jdbcTemplate, dialect, sqlValidator, cache, maskingService,
             limits.maxRows(), limits.maxResultRows(), DEFAULT_FETCH_SIZE,
             DEFAULT_QUERY_TIMEOUT_SECONDS, sseStreamManager);
    }

    public DatabaseReadRepository(JdbcTemplate jdbcTemplate,
                                  DatabaseDialect dialect,
                                  SqlValidator sqlValidator,
                                  DatabaseCache cache,
                                  DataMaskingService maskingService,
                                  int maxRows,
                                  int maxResultRows,
                                  int fetchSize,
                                  int queryTimeoutSeconds,
                                  SseStreamManager sseStreamManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        this.sqlValidator = sqlValidator;
        this.cache = cache;
        this.maskingService = maskingService;
        this.maxRows = maxRows;
        this.maxResultRows = maxResultRows;
        this.fetchSize = fetchSize;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.sseStreamManager = sseStreamManager;
    }

    // ─── Metadata Queries (cached via metadataCache) ──────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTables(String schema) {
        String cacheKey = "tables:" + schema;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        String sql = dialect.tablesQuery(schema);
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, schema);
        cache.putMetadata(cacheKey, result);
        return result;
    }

    /**
     * List tables across all schemas, optionally filtered by keyword.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchTables(String keyword) {
        String cacheKey = "tables_all:" + keyword;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        String sql = dialect.searchTablesQuery(keyword);
        List<Map<String, Object>> result;
        if (keyword != null && !keyword.isBlank()) {
            result = jdbcTemplate.queryForList(sql, "%" + keyword + "%");
        } else {
            result = jdbcTemplate.queryForList(sql);
        }
        cache.putMetadata(cacheKey, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<String> listSchemas() {
        String cacheKey = "schemas";
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return (List<String>) cached;
        }
        String sql = dialect.schemasQuery();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        List<String> result = rows.stream()
                .map(row -> row.values().stream().findFirst().orElse(null))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
        cache.putMetadata(cacheKey, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> describeTable(String table, String schema) {
        String cacheKey = "columns:" + schema + "." + table;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "Map<String, Object>");
        }
        String sql = dialect.columnsQuery(table, schema);
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(sql, schema, normalizeTableName(table));
        Map<String, Object> result = Map.of(
            "table", table,
            "schema", schema,
            "columnCount", columns.size(),
            "columns", columns
        );
        cache.putMetadata(cacheKey, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listIndexes(String table, String schema) {
        String cacheKey = "indexes:" + schema + "." + table;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        String sql = dialect.indexesQuery(table, schema);
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, schema, normalizeTableName(table));
        cache.putMetadata(cacheKey, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listViews(String schema) {
        String cacheKey = "views:" + schema;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        String sql = dialect.viewsQuery(schema);
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, schema);
        cache.putMetadata(cacheKey, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSequences(String schema) {
        String cacheKey = "sequences:" + schema;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        String sql = dialect.sequencesQuery(schema);
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, schema);
        cache.putMetadata(cacheKey, result);
        return result;
    }

    // ─── Query Execution (cached via queryCache for first page) ────────────

    @SuppressWarnings("unchecked")
    public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken) {
        return executeQueryWithSse(sql, maxRows, continuationToken, null);
    }

    /**
     * Execute query with optional SSE progress callback.
     */
    @SuppressWarnings("unchecked")
    public PaginatedQueryResult executeQueryWithSse(String sql, int maxRows, String continuationToken,
                                                    SseStreamManager.QueryExecutor<PaginatedQueryResult> executor) {
        if (sseStreamManager == null) {
            return executeQueryDirect(sql, maxRows, continuationToken);
        }
        return sseStreamManager.executeWithProgress(progress -> {
            // SSE event handling can be added here if needed
        }, () -> executeQueryDirect(sql, maxRows, continuationToken));
    }

    @SuppressWarnings("unchecked")
    private PaginatedQueryResult executeQueryDirect(String sql, int maxRows, String continuationToken) {
        // Prepare cache key and pagination params
        int limit = Math.min(maxRows, this.maxRows);
        String schema = extractSchema(sql);
        String cacheKey = "query:" + sha256(schema + "." + sql) + ":" + limit;

        // Cache first-page queries only (no continuation token)
        if (continuationToken == null || continuationToken.isBlank()) {
            // Bloom filter pre-check: if definitely not present, skip cache lookup
            boolean possiblyCached = cache.getQueryBloomFilter().mightContain(schema + "." + sql);
            if (possiblyCached) {
                Object cached = cache.getQuery(cacheKey);
                if (cached != null) {
                    return (PaginatedQueryResult) cached;
                }
            }
        }

        // Execute query with pagination
        int offset = (int) parseCursor(continuationToken);
        String limitedSql = dialect.supportsLimit()
                ? dialect.applyLimit(sql, limit, offset)
                : sql;

        // Apply query timeout to PreparedStatement
        List<Map<String, Object>> rows = jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(limitedSql);
            ps.setFetchSize(fetchSize);
            if (queryTimeoutSeconds > 0) {
                ps.setQueryTimeout(queryTimeoutSeconds);
            }
            return ps;
        }, (ResultSet rs) -> {
            List<Map<String, Object>> results = new ArrayList<>();
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnLabel(i), rs.getObject(i));
                }
                results.add(row);
            }
            return results;
        });

        // Circuit breaker: reject if result exceeds maxResultRows
        if (rows.size() > maxResultRows) {
            throw new com.entropy.database.mcp.exception.McpQueryException(
                com.entropy.database.mcp.exception.ErrorCode.QUERY_RESULT_TOO_LARGE,
                "Query result exceeds max-result-rows limit: " + rows.size() + " > " + maxResultRows);
        }

        boolean hasMore = rows.size() == limit;
        String nextToken = null;
        if (hasMore) {
            nextToken = String.valueOf(offset + limit);
        }

        PaginatedQueryResult result = PaginatedQueryResult.from(rows, nextToken, hasMore);

        // Apply data masking before caching and returning
        List<Map<String, Object>> maskedRows = maskingService.maskResults(rows, sqlValidator.getMaskColumns());
        if (maskedRows != rows) {
            String maskedCacheKey = cacheKey + ":masked";
            result = new PaginatedQueryResult(result.columns(), maskedRows, nextToken, hasMore);
            if (continuationToken == null || continuationToken.isBlank()) {
                cache.putQuery(maskedCacheKey, result);
                cache.getQueryBloomFilter().put(schema + "." + sql);
            }
        }

        // Cache first page results
        if ((continuationToken == null || continuationToken.isBlank()) && maskedRows == rows) {
            cache.putQuery(cacheKey, result);
            cache.getQueryBloomFilter().put(schema + "." + sql);
        }

        return result;
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private static final MessageDigest SHA256;
    static {
        try {
            SHA256 = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SHA-256", e);
        }
    }

    private static String sha256(String input) {
        try {
            byte[] hash = SHA256.digest(input.getBytes());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16); // Use first 16 hex chars as cache key prefix
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private long parseCursor(String token) {
        if (token == null || token.isBlank()) return 0L;
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Extract schema name from SQL. Returns "PUBLIC" if no schema is found.
     * Handles patterns like "FROM schema.table" or "SELECT ... FROM schema.table".
     */
    private static String extractSchema(String sql) {
        if (sql == null || sql.isBlank()) return "PUBLIC";
        // Match schema.table pattern (case-insensitive, after FROM/INTO/UPDATE)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "\\b(?:FROM|INTO|UPDATE)\\s+([A-Z_][A-Z0-9_]*)\\.",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            String schema = matcher.group(1).toUpperCase();
            // Filter out SQL keywords that might be mistaken as schema names
            if (!isSqlKeyword(schema)) {
                return schema;
            }
        }
        return "PUBLIC";
    }

    private static boolean isSqlKeyword(String word) {
        return word.equals("DUAL") || word.equals("SELECT") || word.equals("WHERE")
            || word.equals("SET") || word.equals("VALUES") || word.equals("INSERT")
            || word.equals("DELETE") || word.equals("AND") || word.equals("OR")
            || word.equals("NOT") || word.equals("NULL") || word.equals("TRUE")
            || word.equals("FALSE") || word.equals("EXISTS") || word.equals("IN");
    }

    private String normalizeTableName(String table) {
        if (table == null) {
            return null;
        }
        return dialect.normalizeTableName(table);
    }

    @SuppressWarnings("unchecked")
    private <T> T checkType(Object cached, String cacheKey, String expectedType) {
        if (cached == null) {
            throw new IllegalStateException("Cache miss for key: " + cacheKey);
        }
        if (!(cached instanceof List) && !(cached instanceof Map)) {
            log.warn("Cache entry for {} has unexpected type {}, expected {}", cacheKey,
                    cached.getClass().getName(), expectedType);
            throw new IllegalStateException("Cache type mismatch for key: " + cacheKey
                    + " - got " + cached.getClass().getSimpleName());
        }
        return (T) cached;
    }

    public Map<String, Object> getDatabaseInfo() {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            var meta = conn.getMetaData();
            return Map.of(
                "productName", meta.getDatabaseProductName(),
                "productVersion", meta.getDatabaseProductVersion(),
                "driverName", meta.getDriverName(),
                "driverVersion", meta.getDriverVersion(),
                "url", meta.getURL(),
                "user", meta.getUserName()
            );
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}
