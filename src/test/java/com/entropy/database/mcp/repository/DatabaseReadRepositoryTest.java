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
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpQueryException;
import com.entropy.database.mcp.security.DataMaskingService;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.stream.SseStreamManager;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards {@link DatabaseReadRepository}, the component every read path funnels through: it
 * owns offset pagination (continuation tokens, {@code hasMore}), the row-count ceiling that
 * protects the server from oversized results, and the metadata/degradation contracts that
 * MCP tools surface to clients. Assertions run against a real H2 in-memory database so that
 * limit/offset behaviour is verified by actual SQL rather than a mocked JdbcTemplate.
 *
 * <p>Tests marked {@code @Disabled} record defects found while writing this suite: they
 * assert the <em>intended</em> behaviour, not today's, and turn green once fixed.</p>
 */
class DatabaseReadRepositoryTest {

    private static final String ORDERED_QUERY = "SELECT ID, LABEL FROM NUMS ORDER BY ID";

    private static JdbcTemplate jdbcTemplate;

    private RecordingCache cache;

    @BeforeAll
    static void createSchema() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:readrepo;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute("DROP TABLE IF EXISTS NUMS");
        jdbcTemplate.execute("CREATE TABLE NUMS (ID INT PRIMARY KEY, LABEL VARCHAR(20))");
        for (int i = 1; i <= 10; i++) {
            jdbcTemplate.update("INSERT INTO NUMS VALUES (?, ?)", i, "row" + i);
        }
    }

    @BeforeEach
    void freshCache() {
        cache = new RecordingCache();
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────

    private DatabaseReadRepository repository(int maxRows, int maxResultRows) {
        return repository(maxRows, maxResultRows, null);
    }

    private DatabaseReadRepository repository(int maxRows, int maxResultRows, SseStreamManager sse) {
        return new DatabaseReadRepository(jdbcTemplate, new H2Dialect(), new StubValidator(),
            cache, IDENTITY_MASKING, maxRows, maxResultRows, 100, 30, sse);
    }

    private static List<Integer> idsOf(PaginatedQueryResult result) {
        return result.rows().stream().map(row -> (Integer) row.get("ID")).toList();
    }

    // ─── Pagination ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("offset pagination")
    class Pagination {

        @Test
        @DisplayName("first page returns limit rows and hands back the next offset as the token")
        void firstPageEmitsOffsetToken() {
            PaginatedQueryResult page = repository(100, 10000).executeQuery(ORDERED_QUERY, 3, null);

            assertThat(idsOf(page)).containsExactly(1, 2, 3);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.continuationToken()).isEqualTo("3");
            assertThat(page.columns()).containsExactly("ID", "LABEL");
        }

        @Test
        @DisplayName("continuation token is a plain row offset")
        void tokenIsPlainOffset() {
            DatabaseReadRepository repo = repository(100, 10000);

            PaginatedQueryResult second = repo.executeQuery(ORDERED_QUERY, 3, "3");
            assertThat(idsOf(second)).containsExactly(4, 5, 6);
            assertThat(second.continuationToken()).isEqualTo("6");

            PaginatedQueryResult third = repo.executeQuery(ORDERED_QUERY, 3, "6");
            assertThat(idsOf(third)).containsExactly(7, 8, 9);
            assertThat(third.continuationToken()).isEqualTo("9");
        }

        @Test
        @DisplayName("a short final page clears hasMore and returns a null token")
        void partialLastPageEndsPagination() {
            PaginatedQueryResult last = repository(100, 10000).executeQuery(ORDERED_QUERY, 3, "9");

            assertThat(idsOf(last)).containsExactly(10);
            assertThat(last.hasMore()).isFalse();
            assertThat(last.continuationToken()).isNull();
        }

        @Test
        @DisplayName("hasMore is rows.size() == limit, so a full final page costs one extra request")
        void fullLastPageReportsHasMoreThenEmptyPage() {
            DatabaseReadRepository repo = repository(100, 10000);

            PaginatedQueryResult fullLast = repo.executeQuery(ORDERED_QUERY, 5, "5");
            assertThat(idsOf(fullLast)).containsExactly(6, 7, 8, 9, 10);
            assertThat(fullLast.hasMore()).isTrue();
            assertThat(fullLast.continuationToken()).isEqualTo("10");

            PaginatedQueryResult beyond = repo.executeQuery(ORDERED_QUERY, 5, "10");
            assertThat(beyond.rows()).isEmpty();
            assertThat(beyond.hasMore()).isFalse();
            assertThat(beyond.continuationToken()).isNull();
        }

        @Test
        @DisplayName("an offset past the end yields an empty page with no columns")
        void offsetBeyondEndYieldsEmptyPage() {
            PaginatedQueryResult page = repository(100, 10000).executeQuery(ORDERED_QUERY, 3, "12");

            assertThat(page.rows()).isEmpty();
            assertThat(page.columns()).isEmpty();
            assertThat(page.hasMore()).isFalse();
            assertThat(page.continuationToken()).isNull();
        }

        @ParameterizedTest(name = "unusable token [{0}] is treated as offset 0")
        @NullSource
        @EmptySource
        @ValueSource(strings = {"   ", "abc", "3.5", "1e3", "0x10", "3; DROP TABLE NUMS", "+"})
        void unusableTokenFallsBackToFirstPage(String token) {
            PaginatedQueryResult page = repository(100, 10000).executeQuery(ORDERED_QUERY, 3, token);

            assertThat(idsOf(page)).containsExactly(1, 2, 3);
            assertThat(page.continuationToken()).isEqualTo("3");
        }

        @Test
        @DisplayName("a token whose offset exceeds the row count returns an empty terminal page")
        void veryLargeTokenReturnsEmptyPage() {
            PaginatedQueryResult page = repository(100, 10000)
                .executeQuery(ORDERED_QUERY, 3, "99999999999999");

            assertThat(page.rows()).isEmpty();
            assertThat(page.hasMore()).isFalse();
            assertThat(page.continuationToken()).isNull();
        }

        @Test
        @DisplayName("only the first page is cached; paged requests are always executed")
        void onlyFirstPageIsCached() {
            DatabaseReadRepository repo = repository(100, 10000);

            PaginatedQueryResult first = repo.executeQuery(ORDERED_QUERY, 3, null);
            assertThat(cache.queryCacheSize()).isEqualTo(1);
            assertSame(first, repo.executeQuery(ORDERED_QUERY, 3, null));

            repo.executeQuery(ORDERED_QUERY, 3, "3");
            assertThat(cache.queryCacheSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("the SSE wrapper produces the same page as the direct path")
        void sseWrapperPreservesPagination() {
            PaginatedQueryResult page = repository(100, 10000, new SseStreamManager())
                .executeQuery(ORDERED_QUERY, 4, "4");

            assertThat(idsOf(page)).containsExactly(5, 6, 7, 8);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.continuationToken()).isEqualTo("8");
        }

        @Test
        @Disabled("BUG-13: a negative continuation token corrupts pagination instead of being "
            + "rejected. parseCursor() returns -5, the dialect drops the OFFSET clause (offset <= 0) "
            + "so page 1 is returned again, and the next token is computed as offset + limit = -2 — "
            + "a negative token that keeps re-serving page 1. A client following tokens never "
            + "advances.")
        void negativeTokenMustNotProduceANegativeToken() {
            PaginatedQueryResult page = repository(100, 10000).executeQuery(ORDERED_QUERY, 3, "-5");

            assertThat(idsOf(page)).containsExactly(1, 2, 3);
            assertThat(page.continuationToken()).isEqualTo("3");
        }

        @Test
        @Disabled("BUG-14: continuation tokens are parsed as long then narrowed with (int), so any "
            + "token beyond Integer.MAX_VALUE silently wraps. Token 4294967296 (2^32) becomes "
            + "offset 0 and re-serves page 1; token 2147483648 becomes a negative offset. A token "
            + "that cannot be represented should be refused, not truncated.")
        void tokensBeyondIntRangeMustNotWrap() {
            DatabaseReadRepository repo = repository(100, 10000);

            assertThat(repo.executeQuery(ORDERED_QUERY, 3, "4294967296").rows()).isEmpty();
            assertThat(repo.executeQuery(ORDERED_QUERY, 3, "2147483648").rows()).isEmpty();
        }
    }

    // ─── maxRows truncation ───────────────────────────────────────────────

    @Nested
    @DisplayName("maxRows truncation")
    class MaxRowsTruncation {

        @Test
        @DisplayName("a caller asking for more than the configured ceiling is truncated to it")
        void callerRequestAboveCeilingIsTruncated() {
            PaginatedQueryResult page = repository(4, 10000).executeQuery(ORDERED_QUERY, 500, null);

            assertThat(idsOf(page)).containsExactly(1, 2, 3, 4);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.continuationToken()).isEqualTo("4");
        }

        @ParameterizedTest(name = "request of {0} row(s) stays below the ceiling of 100")
        @ValueSource(ints = {1, 2, 7, 10})
        void callerRequestBelowCeilingIsHonoured(int requested) {
            PaginatedQueryResult page = repository(100, 10000).executeQuery(ORDERED_QUERY, requested, null);

            assertThat(page.rows()).hasSize(Math.min(requested, 10));
        }

        @Test
        @DisplayName("the ceiling is per-repository configuration, not a constant")
        void ceilingComesFromConfiguration() {
            assertThat(repository(2, 10000).executeQuery(ORDERED_QUERY, 100, null).rows()).hasSize(2);
            assertThat(repository(6, 10000).executeQuery(ORDERED_QUERY, 100, null).rows()).hasSize(6);
        }

        @Test
        @DisplayName("QueryLimits constructor feeds both ceilings")
        void queryLimitsConstructorIsHonoured() {
            DatabaseReadRepository repo = new DatabaseReadRepository(jdbcTemplate, new H2Dialect(),
                new StubValidator(), cache, IDENTITY_MASKING, new QueryLimits(3, 10000), null);

            assertThat(idsOf(repo.executeQuery(ORDERED_QUERY, 100, null))).containsExactly(1, 2, 3);
        }

        @Test
        @Disabled("BUG-15: maxRows=0 produces an endless pagination loop. limit becomes 0, the query "
            + "returns no rows, yet hasMore is `rows.size() == limit` — 0 == 0 — so hasMore is true "
            + "and the next token is offset + 0, i.e. the same offset. A client following tokens "
            + "requests the same empty page forever. A 0 request should either be rejected or "
            + "terminate the cursor.")
        void zeroMaxRowsMustNotLoopForever() {
            PaginatedQueryResult page = repository(100, 10000).executeQuery(ORDERED_QUERY, 0, null);

            assertThat(page.rows()).isEmpty();
            assertThat(page.hasMore()).isFalse();
            assertThat(page.continuationToken()).isNull();
        }

        @Test
        @Disabled("BUG-16: a negative maxRows is passed straight into the generated SQL "
            + "(`... LIMIT -5`), so the driver fails with DataIntegrityViolationException "
            + "('Invalid value \"-5\" for parameter \"result FETCH\"') instead of the repository "
            + "rejecting the argument or clamping it. Raw driver errors leak to the MCP client and "
            + "differ per dialect.")
        void negativeMaxRowsMustBeRejectedOrClamped() {
            DatabaseReadRepository repo = repository(100, 10000);

            McpQueryException ex = assertThrows(McpQueryException.class,
                () -> repo.executeQuery(ORDERED_QUERY, -5, null));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.QUERY_EXECUTION_FAILED);
        }
    }

    // ─── Result-size circuit breaker ──────────────────────────────────────

    @Nested
    @DisplayName("result-size circuit breaker")
    class CircuitBreaker {

        @Test
        @DisplayName("a result larger than max-result-rows is refused with QUERY_RESULT_TOO_LARGE")
        void refusesOversizedResult() {
            // Threshold scaled down from the 10000 default so the breaker can be exercised
            // without materialising ten thousand rows.
            DatabaseReadRepository repo = repository(50, 5);

            McpQueryException ex = assertThrows(McpQueryException.class,
                () -> repo.executeQuery(ORDERED_QUERY, 50, null));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.QUERY_RESULT_TOO_LARGE);
            assertThat(ex.getMessage())
                .contains("max-result-rows")
                .contains("10 > 5");
        }

        @Test
        @DisplayName("the breaker is exclusive: exactly max-result-rows passes")
        void thresholdIsExclusive() {
            PaginatedQueryResult page = repository(50, 10).executeQuery(ORDERED_QUERY, 50, null);

            assertThat(page.rows()).hasSize(10);
            assertThat(page.hasMore()).isFalse();
        }

        @Test
        @DisplayName("an oversized result is refused rather than cached")
        void oversizedResultIsNotCached() {
            DatabaseReadRepository repo = repository(50, 5);

            assertThrows(McpQueryException.class, () -> repo.executeQuery(ORDERED_QUERY, 50, null));

            assertThat(cache.queryCacheSize()).isZero();
        }

        @Test
        @DisplayName("the breaker does not fire while the page limit keeps the result small")
        void breakerSilentWhenPagingKeepsResultSmall() {
            assertThatCode(() -> repository(3, 5).executeQuery(ORDERED_QUERY, 3, null))
                .doesNotThrowAnyException();
        }
    }

    // ─── getDatabaseInfo ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getDatabaseInfo")
    class DatabaseInfo {

        @Test
        @DisplayName("reports driver and product metadata when a connection is available")
        void reportsMetadata() {
            Map<String, Object> info = repository(100, 10000).getDatabaseInfo();

            assertThat(info).containsKeys("productName", "productVersion",
                "driverName", "driverVersion", "url", "user");
            assertThat(info.get("productName")).isEqualTo("H2");
            assertThat((String) info.get("url")).startsWith("jdbc:h2:mem:readrepo");
            assertThat(info).doesNotContainKey("error");
        }

        @Test
        @DisplayName("degrades to an error map when no DataSource is configured")
        void degradesWithoutDataSource() {
            DatabaseReadRepository repo = new DatabaseReadRepository(new JdbcTemplate(),
                new H2Dialect(), new StubValidator(), cache, IDENTITY_MASKING,
                100, 10000, 100, 30, null);

            assertThat(repo.getDatabaseInfo())
                .containsExactly(Map.entry("error", "Connection information unavailable"));
        }

        @Test
        @DisplayName("degrades to an error map when the connection cannot be opened")
        void degradesWhenConnectionFails() throws SQLException {
            DataSource failing = mock(DataSource.class);
            when(failing.getConnection()).thenThrow(new SQLException("pool exhausted"));
            DatabaseReadRepository repo = new DatabaseReadRepository(new JdbcTemplate(failing),
                new H2Dialect(), new StubValidator(), cache, IDENTITY_MASKING,
                100, 10000, 100, 30, null);

            assertThat(repo.getDatabaseInfo())
                .containsExactly(Map.entry("error", "Connection information unavailable"));
        }
    }

    // ─── describeTable ────────────────────────────────────────────────────

    @Nested
    @DisplayName("describeTable")
    class DescribeTable {

        @Test
        @DisplayName("returns table, schema, columnCount and the column list")
        void returnsColumnMetadata() {
            Map<String, Object> described = repository(100, 10000).describeTable("NUMS", "PUBLIC");

            assertThat(described).containsOnlyKeys("table", "schema", "columnCount", "columns");
            assertThat(described.get("table")).isEqualTo("NUMS");
            assertThat(described.get("schema")).isEqualTo("PUBLIC");
            assertThat(described.get("columnCount")).isEqualTo(2);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) described.get("columns");
            assertThat(columns).hasSize(2);
            assertThat(columns.stream().map(c -> c.get("COLUMN_NAME")))
                .containsExactly("ID", "LABEL");
            assertThat(columns.get(0)).containsKeys("COLUMN_NAME", "DATA_TYPE", "IS_NULLABLE");
        }

        @Test
        @DisplayName("columnCount matches the returned column list for an unknown table")
        void unknownTableReportsNoColumns() {
            Map<String, Object> described =
                repository(100, 10000).describeTable("NO_SUCH_TABLE", "PUBLIC");

            assertThat(described.get("columnCount")).isEqualTo(0);
            assertThat((List<?>) described.get("columns")).isEmpty();
            assertThat(described.get("table")).isEqualTo("NO_SUCH_TABLE");
        }

        @Test
        @DisplayName("metadata is cached per schema-qualified table and reused")
        void metadataIsCached() {
            DatabaseReadRepository repo = repository(100, 10000);

            Map<String, Object> first = repo.describeTable("NUMS", "PUBLIC");
            assertThat(cache.metadataKeys()).contains("columns:PUBLIC.NUMS");
            assertSame(first, repo.describeTable("NUMS", "PUBLIC"));
        }
    }

    // ─── Test doubles ─────────────────────────────────────────────────────

    /** Masking disabled: returns the very same list, which is the repository's "unmasked" path. */
    private static final DataMaskingService IDENTITY_MASKING = new DataMaskingService() {
        @Override
        public List<Map<String, Object>> maskResults(List<Map<String, Object>> rows,
                                                     List<String> explicitMaskColumns) {
            return rows;
        }

        @Override
        public List<String> getMaskColumnsForSchema(List<String> columnNames) {
            return List.of();
        }
    };

    /** Map-backed cache so tests can observe what the repository stored. */
    private static final class RecordingCache implements DatabaseCache {
        private final Map<String, Object> queries = new HashMap<>();
        private final Map<String, Object> metadata = new HashMap<>();
        private final BloomFilter<String> bloomFilter =
            BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 1000);

        Set<String> metadataKeys() {
            return metadata.keySet();
        }

        @Override public Object get(String key, CacheTier tier) { return null; }
        @Override public void put(String key, Object value, CacheTier tier) { }
        @Override public void evict(String key, CacheTier tier) { }
        @Override public Object getQuery(String key) { return queries.get(key); }
        @Override public void putQuery(String key, Object value) { queries.put(key, value); }
        @Override public void evictQuery(String key) { queries.remove(key); }
        @Override public Object getMetadata(String key) { return metadata.get(key); }

        @SuppressWarnings("unchecked")
        @Override public <T> T getMetadata(String key, Function<String, T> loader) {
            return (T) metadata.get(key);
        }

        @Override public void putMetadata(String key, Object value) { metadata.put(key, value); }
        @Override public void evictMetadata(String key) { metadata.remove(key); }
        @Override public void clear() { queries.clear(); metadata.clear(); }
        @Override public void invalidateAll() { clear(); }
        @Override public void shutdown() { }
        @Override public long size() { return queries.size() + metadata.size(); }
        @Override public long queryCacheSize() { return queries.size(); }
        @Override public long metadataCacheSize() { return metadata.size(); }
        @Override public int maxSize() { return 1000; }
        @Override public double queryHitRate() { return 0d; }
        @Override public double metadataHitRate() { return 0d; }
        @Override public Map<String, Object> getStatistics() { return Map.of(); }
        @Override public BloomFilter<String> getQueryBloomFilter() { return bloomFilter; }
    }

    /**
     * The repository does not validate SQL itself — it only reads the mask-column list — so a
     * permissive stub keeps these tests focused on pagination and limits.
     */
    private static final class StubValidator implements SqlValidator {
        @Override public void validateSelect(String sql) { }
        @Override public void validateDdl(String sql) { }
        @Override public int getMaxRows() { return 100; }
        @Override public void setMaxRows(int maxRows) { }
        @Override public void setMaxJoins(int maxJoins) { }
        @Override public void setMaxSubqueryDepth(int maxSubqueryDepth) { }
        @Override public List<String> getMaskColumns() { return List.of(); }
        @Override public void setMaskColumns(List<String> maskColumns) { }
        @Override public Set<String> getAllowedTables() { return Set.of(); }
        @Override public void setAllowedTables(Set<String> allowedTables) { }
        @Override public Set<String> getAllowedOperations() { return Set.of("SELECT"); }
        @Override public void setAllowedOperations(Set<String> allowedOperations) { }
    }
}
