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
package com.entropy.database.mcp.routing;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.facade.DatabaseAdminOperations;
import com.entropy.database.mcp.facade.DatabaseMetadataOperations;
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import com.entropy.database.mcp.facade.DatabaseWriteOperations;
import com.entropy.database.mcp.facade.MetaDataCallback;
import com.entropy.database.mcp.facade.TransactionContext;
import com.entropy.database.mcp.facade.TransactionalWork;
import com.entropy.database.mcp.repository.BatchInsertHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
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
        JdbcTemplate jdbc = context.getWriteJdbcTemplate();
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
        long written = BatchInsertHelper.batchInsert(context.getWriteJdbcTemplate(), sql, namedRows,
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

    /**
     * Run {@code work} inside one transaction on one connection.
     *
     * <p>The invariant this method has to hold is "a caller that receives an exception did not get
     * a commit". Two things threaten it, and both are handled here rather than in
     * {@link #restoreAndClose}:
     *
     * <ul>
     *   <li>Restoring {@code autoCommit} while a transaction is still open <em>commits</em> it
     *       (JDBC 4.3, {@code Connection#setAutoCommit}). So the restore only happens once the
     *       transaction is settled — committed, or rolled back successfully. {@code settled}
     *       tracks exactly that, and {@link #restoreAndClose} rolls back first when it is false.</li>
     *   <li>An {@link Error} — {@code OutOfMemoryError}, {@code StackOverflowError} — is not an
     *       {@link Exception}, so a {@code catch (Exception)} here would let it reach the
     *       {@code finally} with the transaction still open and get it committed. The catch is
     *       therefore on {@link Throwable}; an {@code Error} is rolled back and rethrown as-is
     *       rather than wrapped, because it describes VM state, not a failed tool call.</li>
     * </ul>
     */
    @Override
    public <T> T inTransaction(String connection, TransactionalWork<T> work) {
        Connection conn = null;
        boolean originalAutoCommit = true;
        // Whether the transaction has reached a definite end: committed, or rolled back with a
        // rollback() that returned normally. Anything else means work may still be pending.
        boolean settled = false;
        try {
            conn = context.getConnection();
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            T result;
            try {
                result = work.execute(new JdbcTransactionContext(conn));
            } catch (Throwable t) {
                settled = rollbackQuietly(conn, t);
                if (t instanceof Error error) {
                    throw error;
                }
                throw wrap(t, "Transaction rolled back");
            }
            conn.commit();
            settled = true;
            return result;
        } catch (SQLException e) {
            // Reached when getConnection/getAutoCommit/setAutoCommit/commit fail. A failed commit
            // leaves settled == false, so the finally below rolls back before touching autoCommit.
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Transaction failed: " + e.getMessage(), e, context.getKey());
        } finally {
            if (conn != null) {
                restoreAndClose(conn, originalAutoCommit, settled);
            }
        }
    }

    /**
     * Roll back, attaching a rollback failure to the original error rather than replacing it: the
     * reason the work failed is what the caller needs, the rollback failure is secondary evidence.
     *
     * @return {@code true} when {@code rollback()} returned normally, i.e. nothing is left pending
     *         on the connection. {@code false} means the transaction is still open and autoCommit
     *         must not be restored.
     */
    private boolean rollbackQuietly(Connection conn, Throwable cause) {
        try {
            conn.rollback();
            return true;
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
            return false;
        }
    }

    private void restoreAndClose(Connection conn, boolean originalAutoCommit, boolean settled) {
        boolean safeToRestore = settled || rollbackBeforeRestore(conn);
        if (safeToRestore) {
            try {
                conn.setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                // The connection is about to go back to the pool; leaving autoCommit flipped would
                // silently change behaviour for the next borrower, so this is worth a warning.
                log.warn("Failed to restore autoCommit on {}: {}", context.getKey(), e.getMessage());
            }
        } else {
            // Restoring autoCommit here would commit the very work the caller is being told failed.
            // Closing without restoring is the lesser evil: the pool discards or resets the
            // connection, and an uncommitted transaction dies with it.
            log.error("Leaving autoCommit unrestored on {}: the transaction could not be rolled "
                    + "back and switching autoCommit back on would commit it", context.getKey());
        }
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Failed to close transaction connection on {}: {}", context.getKey(), e.getMessage());
        }
    }

    /**
     * Last-resort rollback for paths that reached the {@code finally} without settling the
     * transaction — a failed {@code commit()}, or an {@link Error} thrown by the work.
     */
    private boolean rollbackBeforeRestore(Connection conn) {
        try {
            conn.rollback();
            return true;
        } catch (SQLException e) {
            log.error("Rollback failed on {} while closing an unsettled transaction: {}",
                    context.getKey(), e.getMessage());
            return false;
        }
    }

    private McpToolException wrap(Throwable e, String what) {
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
     * {@code JdbcTemplate}, which would borrow a second connection and commit independently. The
     * flip side is that nothing applies a {@code queryTimeout} on the way — the per-category
     * templates cannot help here — so each method sets its own ceiling. Without it a single blocked
     * statement pins both a pool connection and the request thread until the driver gives up, which
     * for most drivers means indefinitely.
     */
    private final class JdbcTransactionContext implements TransactionContext {

        private final Connection conn;

        private JdbcTransactionContext(Connection conn) {
            this.conn = conn;
        }

        @Override
        public int update(String sql, Object... args) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                applyTimeout(ps, context.getStatementTimeouts().writeSeconds());
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
                // The DDL ceiling: this overload is the one DdlExecutionTools drives, and DDL can
                // wait on a metadata lock far longer than a row-level write.
                applyTimeout(stmt, context.getStatementTimeouts().ddlSeconds());
                stmt.execute(sql);
            } catch (SQLException e) {
                throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                        "Statement failed in transaction: " + e.getMessage(), e, context.getKey());
            }
        }

        @Override
        public List<Map<String, Object>> queryRows(String sql, Object... args) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                applyTimeout(ps, context.getStatementTimeouts().readSeconds());
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return readAll(rs);
                }
            } catch (SQLException e) {
                throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                        "Query failed in transaction: " + e.getMessage(), e, context.getKey());
            }
        }

        /**
         * Best-effort ceiling: a driver that does not support {@code setQueryTimeout} throws
         * {@link SQLFeatureNotSupportedException}, and refusing to run the statement at all would
         * be a worse outcome than running it unbounded on that driver.
         */
        private void applyTimeout(Statement stmt, int seconds) {
            if (seconds <= 0) {
                return;
            }
            try {
                stmt.setQueryTimeout(seconds);
            } catch (SQLFeatureNotSupportedException e) {
                log.debug("Driver for {} does not support setQueryTimeout; statement runs unbounded",
                        context.getKey());
            } catch (SQLException e) {
                log.warn("Failed to set query timeout on {}: {}", context.getKey(), e.getMessage());
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
