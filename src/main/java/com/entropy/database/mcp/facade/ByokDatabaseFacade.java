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
package com.entropy.database.mcp.facade;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.tools.BatchInsertHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Facade over a single BYOK datasource context.
 *
 * <p>Implements exactly the capabilities that one connection's own context can answer. Backup,
 * schema diffing and cross-connection copying are deliberately absent: they need collaborators
 * that a per-connection context does not have, and are served by {@link RoutingDatabaseFacade}.
 */
class ByokDatabaseFacade implements DatabaseMetadataOperations, DatabaseReadOperations,
        DatabaseWriteOperations, DatabaseAdminOperations {

    private static final Logger log = LoggerFactory.getLogger(ByokDatabaseFacade.class);

    private final ByokDataSourceContext context;

    public ByokDatabaseFacade(ByokDataSourceContext context) {
        this.context = context;
    }

    /**
     * Whether this facade still wraps {@code candidate}.
     *
     * <p>Lets the routing facade reuse a cached instance only while the underlying context is the
     * same object; a re-created pool yields a new context and must not be served by a facade
     * pointing at the old one.
     */
    boolean wraps(ByokDataSourceContext candidate) {
        return this.context == candidate;
    }

    @Override
    public List<Map<String, Object>> executeNamedQuery(String sql, Map<String, Object> params, String connection) {
        return context.getNamedParameterJdbcTemplate().queryForList(sql, params);
    }

    // ─── Read Operations ──────────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> listTables(String schema, String connection) {
        return context.getReadRepository().listTables(schema);
    }

    @Override
    public List<Map<String, Object>> searchTables(String keyword, String connection) {
        return context.getReadRepository().searchTables(keyword);
    }

    @Override
    public List<String> listSchemas(String connection) {
        return context.getReadRepository().listSchemas();
    }

    @Override
    public Map<String, Object> describeTable(String table, String schema, String connection) {
        return context.getReadRepository().describeTable(table, schema);
    }

    @Override
    public List<Map<String, Object>> listIndexes(String table, String schema, String connection) {
        return context.getReadRepository().listIndexes(table, schema);
    }

    @Override
    public List<Map<String, Object>> listViews(String schema, String connection) {
        return context.getReadRepository().listViews(schema);
    }

    @Override
    public List<Map<String, Object>> listSequences(String schema, String connection) {
        return context.getReadRepository().listSequences(schema);
    }

    @Override
    public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken, String connection) {
        return context.getReadRepository().executeQuery(sql, maxRows, continuationToken);
    }

    @Override
    public PaginatedQueryResult executeQueryWithSse(String sql, int maxRows, String continuationToken,
                                                    com.entropy.database.mcp.stream.SseStreamManager.QueryExecutor<PaginatedQueryResult> executor, String connection) {
        return context.getReadRepository().executeQueryWithSse(sql, maxRows, continuationToken, executor);
    }

    @Override
    public List<Map<String, Object>> queryRows(String sql, String connection, Object... args) {
        JdbcTemplate jdbc = context.getJdbcTemplate();
        // The single-argument overload avoids handing the driver an empty parameter array, which
        // some drivers reject for statements containing no placeholders.
        if (args == null || args.length == 0) {
            return jdbc.queryForList(sql);
        }
        return jdbc.queryForList(sql, args);
    }

    @Override
    public Map<String, Object> getDatabaseInfo(String connection) {
        return context.getReadRepository().getDatabaseInfo();
    }

    // ─── Execution Plan ────────────────────────────────────────────────────

    @Override
    public PlanAnalysis explainPlan(String sql, String connection) {
        return context.getExecutionPlanRepository().analyzeExecutionPlan(sql);
    }

    // ─── Write Operations ──────────────────────────────────────────────────

    @Override
    public Map<String, Object> executeDdl(String sql, String connection) {
        return context.getWriteRepository().executeDdl(sql);
    }

    @Override
    public int executeUpdate(String sql, String connection, Object... args) {
        JdbcTemplate jdbc = context.getJdbcTemplate();
        if (args == null || args.length == 0) {
            return jdbc.update(sql);
        }
        return jdbc.update(sql, args);
    }

    @Override
    public long batchInsert(String table, List<String> columns, List<List<Object>> rows,
                            int batchSize, String connection) {
        // buildInsertSql also validates table and column identifiers, so the positional rows
        // cannot smuggle SQL through the only interpolated parts of the statement.
        String sql = BatchInsertHelper.buildInsertSql(table, columns);
        return runBatch(sql, columns, rows, batchSize);
    }

    @Override
    public long batchUpsert(String table, List<String> keyColumns, List<String> columns,
                           List<List<Object>> rows, int batchSize, String connection) {
        DatabaseDialect dialect = context.getDialect();
        // Identifier validation is not part of buildUpsertSql, so run the same check the insert
        // path gets before the names reach an interpolated statement.
        validateIdentifiers(table, columns, keyColumns);
        String sql = dialect.buildUpsertSql(table, columns, keyColumns);
        if (sql == null) {
            throw new McpToolException(ErrorCode.UPSERT_NOT_SUPPORTED,
                    "UPSERT not supported for dialect: " + dialect.getDialectName()
                            + " (table=" + table + ")", context.getKey());
        }
        // Every dialect that implements buildUpsertSql binds the full column list once, in order,
        // so the same positional setter as the plain insert applies.
        return runBatch(sql, columns, rows, batchSize);
    }

    private long runBatch(String sql, List<String> columns, List<List<Object>> rows, int batchSize) {
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        int effectiveBatchSize = batchSize > 0 ? batchSize : rows.size();
        List<Map<String, Object>> namedRows = toNamedRows(columns, rows);
        long written = BatchInsertHelper.batchInsert(context.getJdbcTemplate(), sql, namedRows,
                effectiveBatchSize, BatchInsertHelper.setRowColumns(columns));
        // A driver may answer Statement.SUCCESS_NO_INFO (-2) per batch entry instead of a row
        // count, which sums to a negative total. The batch did not fail — it succeeded without
        // reporting counts — so fall back to the rows submitted rather than surfacing a negative
        // number where the contract promises "total rows written".
        return written < 0 ? rows.size() : written;
    }

    /**
     * Adapt positional rows to the column-keyed shape {@code BatchInsertHelper} works in.
     *
     * <p>A {@link LinkedHashMap} rather than {@code Map.of} because a null cell is a legitimate
     * value to insert.
     */
    private List<Map<String, Object>> toNamedRows(List<String> columns, List<List<Object>> rows) {
        List<Map<String, Object>> namedRows = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            if (row == null || row.size() != columns.size()) {
                throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                        "Row " + i + " has " + (row == null ? "null" : row.size())
                                + " values but " + columns.size() + " columns were declared");
            }
            Map<String, Object> named = new LinkedHashMap<>();
            for (int c = 0; c < columns.size(); c++) {
                named.put(columns.get(c), row.get(c));
            }
            namedRows.add(named);
        }
        return namedRows;
    }

    private void validateIdentifiers(String table, List<String> columns, List<String> keyColumns) {
        DatabaseDialect dialect = context.getDialect();
        if (!dialect.isValidIdentifier(table)) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "Invalid table name: " + table);
        }
        if (columns == null || columns.isEmpty()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "At least one column is required");
        }
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "At least one key column is required for upsert");
        }
        for (String column : columns) {
            if (!dialect.isValidIdentifier(column)) {
                throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                        "Invalid column name: " + column);
            }
        }
        for (String keyColumn : keyColumns) {
            if (!columns.contains(keyColumn)) {
                throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                        "Key column not present in column list: " + keyColumn);
            }
        }
    }

    @Override
    public <T> T inTransaction(String connection, TransactionalWork<T> work) {
        Connection conn = null;
        boolean originalAutoCommit = true;
        try {
            conn = context.getConnection();
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            T result;
            try {
                result = work.execute(new JdbcTransactionContext(conn));
            } catch (Exception e) {
                rollbackQuietly(conn, e);
                throw wrap(e, "Transaction rolled back");
            }
            conn.commit();
            return result;
        } catch (SQLException e) {
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Transaction failed: " + e.getMessage(), e, context.getKey());
        } finally {
            if (conn != null) {
                restoreAndClose(conn, originalAutoCommit);
            }
        }
    }

    /**
     * Attach a rollback failure to the original error rather than replacing it: the reason the
     * work failed is what the caller needs, the rollback failure is secondary evidence.
     */
    private void rollbackQuietly(Connection conn, Exception cause) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private void restoreAndClose(Connection conn, boolean originalAutoCommit) {
        try {
            conn.setAutoCommit(originalAutoCommit);
        } catch (SQLException e) {
            // The connection is about to go back to the pool; leaving autoCommit flipped would
            // silently change behaviour for the next borrower, so this is worth a warning.
            log.warn("Failed to restore autoCommit on {}: {}", context.getKey(), e.getMessage());
        }
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Failed to close transaction connection on {}: {}", context.getKey(), e.getMessage());
        }
    }

    private McpToolException wrap(Exception e, String what) {
        if (e instanceof McpToolException mcp) {
            return mcp;
        }
        return new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                what + ": " + e.getMessage(), e, context.getKey());
    }

    // ─── Cache Operations ──────────────────────────────────────────────────

    @Override
    public void clearCache(String connection) {
        context.getCache().invalidateAll();
    }

    // ─── Statistics ────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> getStatistics(String connection) {
        return Map.of(
                "queryStats", context.getHealthMonitor().getQueryStats().toSummary(),
                "cacheStats", context.getCache().getStatistics()
        );
    }

    @Override
    public DatabaseDialect getDialect(String connection) {
        return context.getDialect();
    }

    @Override
    public <T> T withMetaData(String connection, MetaDataCallback<T> callback) {
        // Deliberately not ByokDataSourceContext#getConnectionMetadata(): that method returns the
        // metadata out of a try-with-resources block, so the connection is already closed by the
        // time a caller uses it. Here the callback runs while the connection is still open.
        try (Connection conn = context.getConnection()) {
            return callback.apply(conn.getMetaData());
        } catch (SQLException e) {
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Failed to read database metadata: " + e.getMessage(), e, context.getKey());
        }
    }

    /**
     * {@link TransactionContext} bound to one JDBC connection.
     *
     * <p>Statements go straight to that connection instead of through the context's
     * {@code JdbcTemplate}, which would borrow a second connection and commit independently.
     */
    private final class JdbcTransactionContext implements TransactionContext {

        private final Connection conn;

        private JdbcTransactionContext(Connection conn) {
            this.conn = conn;
        }

        @Override
        public int update(String sql, Object... args) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                        "Statement failed in transaction: " + e.getMessage(), e, context.getKey());
            }
        }

        @Override
        public void execute(String sql) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            } catch (SQLException e) {
                throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                        "Statement failed in transaction: " + e.getMessage(), e, context.getKey());
            }
        }

        @Override
        public List<Map<String, Object>> queryRows(String sql, Object... args) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return readAll(rs);
                }
            } catch (SQLException e) {
                throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                        "Query failed in transaction: " + e.getMessage(), e, context.getKey());
            }
        }

        @Override
        public boolean ddlIsTransactional() {
            try {
                return !conn.getMetaData().dataDefinitionCausesTransactionCommit();
            } catch (SQLException e) {
                throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                        "Failed to determine DDL transaction behaviour: " + e.getMessage(), e,
                        context.getKey());
            }
        }

        private void bind(PreparedStatement ps, Object[] args) throws SQLException {
            if (args == null) {
                return;
            }
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
        }

        private List<Map<String, Object>> readAll(ResultSet rs) throws SQLException {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        }
    }
}
