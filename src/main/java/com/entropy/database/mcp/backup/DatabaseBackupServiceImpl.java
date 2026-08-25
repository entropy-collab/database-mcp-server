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
package com.entropy.database.mcp.backup;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.properties.BackupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Enhanced backup service with full backup, incremental backup, and restore support.
 */
@Service
public class DatabaseBackupServiceImpl implements DatabaseBackupService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupServiceImpl.class);

    /**
     * Column names accepted as an incremental watermark, in order of preference.
     *
     * <p>Update timestamps come first: a creation timestamp only sees inserts, so an incremental
     * backup keyed on it silently misses updates. That is still better than a full scan mislabelled
     * as incremental, so creation timestamps are accepted last and reported in the result.
     */
    private static final List<String> WATERMARK_CANDIDATES = List.of(
            "UPDATED_AT", "UPDATE_TIME", "UPDATED_ON", "UPDATED_TIME",
            "MODIFIED_AT", "MODIFY_TIME", "MODIFIED_TIME", "LAST_MODIFIED",
            "LAST_UPDATE", "LAST_UPDATED", "GMT_MODIFIED",
            "CREATED_AT", "CREATE_TIME", "CREATED_TIME", "GMT_CREATE");

    private final DynamicDataSourceManager dataSourceManager;
    private final BackupMetadataRepository metadataRepository;
    private final BackupProperties backupProperties;

    public DatabaseBackupServiceImpl(DynamicDataSourceManager dataSourceManager,
                                     BackupMetadataRepository metadataRepository,
                                     BackupProperties backupProperties) {
        this.dataSourceManager = dataSourceManager;
        this.metadataRepository = metadataRepository;
        this.backupProperties = backupProperties;
    }

    // ─── Full Backup ────────────────────────────────────────────────────────

    /**
     * Backs up a table's DDL only.
     *
     * <p>The record is typed {@link BackupType#SCHEMA} rather than {@code FULL}: it carries no INSERT
     * statements, and a data-restore path that cannot tell it apart from a data backup would clear the
     * target table and replay nothing.
     */
    @Override
    public Map<String, Object> backupSchema(String tableName, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        JdbcTemplate jdbc = ctx.getJdbcTemplate();
        String sql = dialect.getTableDdlQuery(tableName, null);
        String ddl = jdbc.queryForObject(sql, new Object[]{tableName, null}, String.class);

        Instant now = Instant.now();
        BackupMetadata meta = BackupMetadata.create(connection, tableName, null,
                BackupType.SCHEMA, BackupStatus.COMPLETED, ddl, 0, 0).withTiming(now, now);
        String id = metadataRepository.save(meta);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backupId", id);
        result.put("tableName", tableName);
        result.put("connection", connection);
        result.put("type", BackupType.SCHEMA.name());
        result.put("ddl", ddl);
        result.put("rowCount", 0);
        return result;
    }

    @Override
    public Map<String, Object> backupData(String tableName, int maxRows, String connection) {
        return doBackup(connection, tableName, null, maxRows, BackupType.FULL);
    }

    // ─── Incremental Backup ─────────────────────────────────────────────────

    @Override
    public Map<String, Object> backupDataIncremental(String tableName, int maxRows, String connection) {
        return backupDataIncremental(tableName, maxRows, connection, null);
    }

    /**
     * Backs up only the rows whose watermark column moved past the previous backup.
     *
     * <p>An incremental backup that does not filter is a full backup, and labelling it
     * {@code INCREMENTAL} makes a later restore replay rows that are already there. So the filter is
     * mandatory: when no watermark column is given and none can be detected, the request is
     * <em>refused</em> with an explanation rather than degraded to a full scan under an incremental
     * label. Callers who want everything can ask for a full backup explicitly.
     *
     * @param watermarkColumn the timestamp column to filter on, or {@code null} to auto-detect one
     *                        from {@link #WATERMARK_CANDIDATES}
     */
    @Override
    public Map<String, Object> backupDataIncremental(String tableName, int maxRows, String connection,
                                                     String watermarkColumn) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        // Bulk ceiling: the watermark-filtered SELECT below scans a real table, which is not work
        // an interactive read timeout should be sized for.
        JdbcTemplate jdbc = ctx.getEtlJdbcTemplate();

        List<String> columnNames = readColumnNames(jdbc, dialect, tableName, null);
        if (columnNames.isEmpty()) {
            return Map.of("error", "Table not found: " + tableName);
        }

        String resolvedWatermarkColumn = resolveWatermarkColumn(columnNames, watermarkColumn);
        if (resolvedWatermarkColumn == null) {
            return Map.of(
                    "error", "Incremental backup refused for " + tableName
                            + ": no watermark column available",
                    "reason", watermarkColumn != null
                            ? "Column '" + watermarkColumn + "' does not exist on " + tableName
                            : "None of the recognised watermark columns exist on " + tableName,
                    "recognisedWatermarkColumns", WATERMARK_CANDIDATES,
                    "hint", "Pass an explicit watermark column, or run a FULL backup — a backup "
                            + "without a watermark filter is a full copy and must not be recorded "
                            + "as INCREMENTAL");
        }

        Instant watermark = resolveWatermark(connection, tableName);
        int effectiveMaxRows = resolveMaxRows(maxRows);

        // ORDER BY 是正确性要求，不是排版偏好：命中行数上限时，只有按水位排序才能保证被截断掉的是「水位最大
        // 的那一段」，从而算出一个可以续上的水位。无序截断会让任意一批行落到水位之后，永远进不了下一次增量。
        String selectSql = "SELECT " + quoteAll(dialect, columnNames)
                + " FROM " + dialect.quote(tableName)
                + " WHERE " + dialect.quote(resolvedWatermarkColumn) + " > ?"
                + " ORDER BY " + dialect.quote(resolvedWatermarkColumn);
        selectSql = dialect.applyLimit(selectSql, effectiveMaxRows, 0);

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(selectSql, Timestamp.from(watermark));
        } catch (DataAccessException e) {
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Incremental backup of " + tableName + " failed while filtering on "
                            + resolvedWatermarkColumn + " — the watermark column must be a date/time "
                            + "type comparable to a timestamp. Cause: " + e.getMessage(), e);
        }

        List<String> insertStatements = generateInsertStatements(tableName, columnNames, rows, dialect);
        boolean truncated = rows.size() >= effectiveMaxRows;
        Instant nextWatermark = advanceWatermark(rows, resolvedWatermarkColumn, watermark, truncated);

        Instant now = Instant.now();
        BackupMetadata meta = BackupMetadata.create(connection, tableName, null,
                        BackupType.INCREMENTAL,
                        truncated ? BackupStatus.PARTIAL : BackupStatus.COMPLETED,
                        String.join("\n", insertStatements), rows.size(), rows.size())
                .withTiming(now, now)
                .withDataWatermark(nextWatermark);
        String id = metadataRepository.save(meta);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backupId", id);
        result.put("tableName", tableName);
        result.put("connection", connection);
        result.put("type", "INCREMENTAL");
        result.put("watermarkColumn", resolvedWatermarkColumn);
        result.put("watermark", watermark.toString());
        result.put("nextWatermark", nextWatermark.toString());
        result.put("maxRows", effectiveMaxRows);
        result.put("rowsBackedUp", rows.size());
        result.put("truncated", truncated);
        if (truncated) {
            result.put("status", BackupStatus.PARTIAL.name());
            result.put("hint", "Hit the row ceiling of " + effectiveMaxRows + ", so this backup holds "
                    + "only the oldest slice of the pending changes. The watermark was advanced only "
                    + "to " + nextWatermark + ", so the remaining rows are picked up by the next "
                    + "incremental run — repeat it until truncated is false. Raise maxRows to catch up "
                    + "in fewer rounds.");
        }
        result.put("statements", insertStatements);
        return result;
    }

    /**
     * Watermark to hand to the next incremental run.
     *
     * <p>Never the backup's completion time: the rows that this run did not capture still carry an
     * older watermark, and moving the mark to "now" hides them from every later incremental — a hole
     * no retry can fill. The mark therefore only ever moves to a watermark value that was actually
     * captured.
     *
     * <p>When the run was truncated the largest captured value is not safe either: rows sharing that
     * value may sit on both sides of the cut, so the mark stops at the last <em>fully</em> captured
     * value. If every captured row shares one value the mark cannot move at all — the ceiling is
     * smaller than a single watermark group, and standing still (re-reading the same slice next time)
     * is the only option that does not drop rows. Rows beyond the mark are replayed by a later
     * incremental, so a restore may see them twice; duplicates are recoverable, holes are not.
     */
    private Instant advanceWatermark(List<Map<String, Object>> rows, String watermarkColumn,
                                     Instant previousWatermark, boolean truncated) {
        List<Instant> captured = rows.stream()
                .map(row -> toInstant(caseInsensitive(row).get(watermarkColumn)))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (captured.isEmpty()) {
            return previousWatermark;
        }
        Instant highest = captured.get(captured.size() - 1);
        if (!truncated) {
            return highest;
        }
        return captured.stream()
                .filter(value -> value.isBefore(highest))
                .reduce((a, b) -> b)
                .orElse(previousWatermark);
    }

    /**
     * @return the matching column name as spelled by the table, or {@code null} when there is none
     */
    private String resolveWatermarkColumn(List<String> columnNames, String requested) {
        if (requested != null && !requested.isBlank()) {
            return columnNames.stream()
                    .filter(c -> c.equalsIgnoreCase(requested.trim()))
                    .findFirst()
                    .orElse(null);
        }
        for (String candidate : WATERMARK_CANDIDATES) {
            Optional<String> match = columnNames.stream()
                    .filter(c -> c.equalsIgnoreCase(candidate))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
        }
        return null;
    }

    /**
     * Starting watermark for this incremental run.
     *
     * <p>Preference order matters. A previous incremental records the highest watermark value it
     * actually captured, and that is the only mark that is guaranteed not to skip a row: resuming from
     * the previous backup's <em>completion time</em> jumps over every change the previous run left
     * behind. The completion time is only used for a plain full backup, which captured the whole table
     * anyway; {@code completedAt} can be null on a record that was never timed, hence the
     * {@code createdAt} fallback.
     *
     * <p>A truncated full backup carries neither: it captured an arbitrary subset and no watermark, so
     * the only safe mark is the epoch — rescan everything rather than assume the missing rows are old.
     */
    private Instant resolveWatermark(String connection, String tableName) {
        BackupMetadata lastBackup = metadataRepository.latestFor(connection, tableName);
        if (lastBackup == null) {
            return Instant.EPOCH;
        }
        if (lastBackup.dataWatermark() != null) {
            return lastBackup.dataWatermark();
        }
        if (lastBackup.isPartial()) {
            log.info("Backup {} of {} is PARTIAL and carries no watermark; the next incremental "
                            + "restarts from the epoch so the rows it never captured are not skipped",
                    lastBackup.backupId(), tableName);
            return Instant.EPOCH;
        }
        return lastBackup.completedAt() != null ? lastBackup.completedAt() : lastBackup.createdAt();
    }

    // ─── Restore ─────────────────────────────────────────────────────────────

    /**
     * Restore a table from a backup by replaying the saved SQL script in a single transaction.
     *
     * <p>All statements run on one JDBC connection with {@code autoCommit=false}. Any failure
     * rolls the whole batch back and surfaces an error — a restore is never reported as
     * COMPLETED when part of it did not apply.
     *
     * <p>The backup record itself is never modified. Writing the restore's outcome back onto it
     * conflated two different things: a failed restore marked the <em>backup</em> FAILED, and the
     * {@code isFailed()} guard below then refused every later attempt — one transient outage in the
     * target database permanently retired an intact backup. Restore outcomes live in the returned
     * {@link RestoreResult} instead.
     */
    @Override
    public Map<String, Object> restoreBackup(String backupId, String connection) {
        BackupMetadata meta = metadataRepository.get(backupId);
        if (meta == null) {
            return Map.of("error", "Backup not found: " + backupId);
        }
        if (meta.isFailed()) {
            return Map.of("error", "Backup was failed: " + meta.errorDetail());
        }

        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        Instant startedAt = Instant.now();
        List<String> statements = splitStatements(meta.sqlScript());

        long restoredRows;
        try {
            restoredRows = replayInTransaction(ctx, null, statements);
        } catch (Exception e) {
            log.warn("Restore of backup {} failed after {} ms; the backup record is left intact "
                            + "so the restore can be retried",
                    backupId, java.time.Duration.between(startedAt, Instant.now()).toMillis(), e);
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Restore failed for backup " + backupId + " — the transaction was rolled back, "
                            + "so the table is unchanged and the backup remains usable. Cause: "
                            + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>(
                new RestoreResult(backupId, meta.tableName(), connection, "COMPLETED",
                        statements.size(), restoredRows, startedAt, Instant.now()).asMap());
        if (meta.isPartial()) {
            // 这条路径只追加、不清空，所以不必拒绝；但调用方必须知道它拿回来的不是完整表数据。
            result.put("warning", "Backup " + backupId + " is PARTIAL: it hit the row ceiling and holds "
                    + "only part of " + meta.tableName() + " (" + meta.backedUpRows() + " rows). The "
                    + "table now contains just what this backup captured plus whatever was already "
                    + "there — it is not a faithful copy of the source table.");
        }
        return result;
    }

    /**
     * Quick restore: clear the target table, then replay the backup's INSERT statements.
     *
     * <p>The table is cleared with {@code DELETE}, not {@code TRUNCATE}, on purpose.
     * {@code TRUNCATE} is DDL on Oracle and MySQL and commits implicitly, which would make the
     * clear step irreversible — a subsequent insert failure would leave the table permanently
     * empty. {@code DELETE} participates in the transaction, so a failed replay restores the
     * original rows. The trade-off is speed on very large tables.
     *
     * <p>Because the clear step always runs, this entry point is only safe for a <em>complete data</em>
     * backup, and it refuses anything else before touching the table. A schema backup or a zero-row
     * backup replays no INSERT at all, so "clear then replay" used to mean "empty the table and commit"
     * and still report {@code QUICK_RESTORE_COMPLETED}; a truncated backup would have replayed only the
     * slice it captured, silently dropping the rest of the table.
     */
    @Override
    public Map<String, Object> quickRestore(String backupId, String connection) {
        BackupMetadata meta = metadataRepository.get(backupId);
        if (meta == null) {
            return Map.of("error", "Backup not found: " + backupId);
        }
        if (meta.isFailed()) {
            return Map.of("error", "Backup was failed: " + meta.errorDetail());
        }
        if (meta.isSchemaOnly()) {
            return Map.of(
                    "error", "Quick restore refused for backup " + backupId
                            + ": this is a schema (DDL) backup, not a data backup — quickRestore only "
                            + "accepts data backups",
                    "backupType", meta.type().name(),
                    "hint", "quickRestore clears " + meta.tableName() + " first and would replay no "
                            + "INSERT, leaving the table empty. Use restoreBackup to replay the DDL, or "
                            + "back the data up with backupTable and restore that.");
        }
        if (meta.isPartial()) {
            return Map.of(
                    "error", "Quick restore refused for backup " + backupId
                            + ": this backup is incomplete (PARTIAL) — it hit the row ceiling and holds "
                            + "only " + meta.backedUpRows() + " rows of " + meta.tableName(),
                    "backupStatus", meta.status().name(),
                    "hint", "quickRestore clears the table first, so restoring a partial backup would "
                            + "drop the rows it never captured. Take a full backup with a higher maxRows, "
                            + "or use restoreBackup to append what this backup does contain.");
        }

        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        String tableName = meta.tableName();
        Instant startedAt = Instant.now();

        List<String> inserts = splitStatements(meta.sqlScript()).stream()
                .filter(stmt -> stmt.toUpperCase(Locale.ROOT).startsWith("INSERT"))
                .toList();
        if (inserts.isEmpty()) {
            return Map.of(
                    "error", "Quick restore refused for backup " + backupId
                            + ": the backup contains no INSERT statement, so it holds no data — "
                            + "quickRestore only accepts data backups",
                    "backupType", meta.type().name(),
                    "backedUpRows", meta.backedUpRows(),
                    "hint", "quickRestore clears " + tableName + " before replaying, so running it with "
                            + "an empty backup would empty the table and commit. Check the backup with "
                            + "getBackup, or take a fresh data backup with backupTable.");
        }

        long restoredRows;
        try {
            restoredRows = replayInTransaction(ctx,
                    "DELETE FROM " + dialect.quote(tableName), inserts);
        } catch (Exception e) {
            log.warn("Quick restore of backup {} failed; the backup record is left intact so the "
                    + "restore can be retried", backupId, e);
            throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Quick restore failed for backup " + backupId + " — the transaction was rolled "
                            + "back, so " + tableName + " still holds its original rows and the "
                            + "backup remains usable. Cause: " + e.getMessage(), e);
        }

        return new RestoreResult(backupId, tableName, connection, "QUICK_RESTORE_COMPLETED",
                inserts.size(), restoredRows, startedAt, Instant.now()).asMap();
    }

    /**
     * Replay statements on a single connection inside one transaction.
     *
     * <p>Returns the total number of affected rows. Rolls back and rethrows on the first failure;
     * partial application is never committed.
     */
    private long replayInTransaction(ByokDataSourceContext ctx, String clearStatement,
                                     List<String> statements) throws SQLException {
        try (Connection conn = ctx.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement jdbcStatement = conn.createStatement()) {
                // Raw connection, so no JdbcTemplate applies a ceiling here. A replay is a bulk
                // write: without this a stalled restore holds both the connection and the request
                // thread for as long as the driver is willing to wait.
                applyBulkTimeout(jdbcStatement, ctx);
                long affected = 0;
                if (clearStatement != null) {
                    affected += jdbcStatement.executeUpdate(clearStatement);
                }
                for (String sql : statements) {
                    affected += jdbcStatement.executeUpdate(sql);
                }
                conn.commit();
                return affected;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException restoreFailure) {
                    log.warn("Failed to restore autoCommit after replay", restoreFailure);
                }
            }
        }
    }

    /**
     * Apply the bulk statement ceiling, best effort.
     *
     * <p>A driver without {@code setQueryTimeout} support throws
     * {@link SQLFeatureNotSupportedException}; refusing the restore over that would be worse than
     * running it unbounded on such a driver.
     */
    private void applyBulkTimeout(Statement statement, ByokDataSourceContext ctx) {
        int seconds = ctx.getStatementTimeouts().etlSeconds();
        if (seconds <= 0) {
            return;
        }
        try {
            statement.setQueryTimeout(seconds);
        } catch (SQLFeatureNotSupportedException e) {
            log.debug("Driver for {} does not support setQueryTimeout; replay runs unbounded",
                    ctx.getKey());
        } catch (SQLException e) {
            log.warn("Failed to set replay query timeout on {}: {}", ctx.getKey(), e.getMessage());
        }
    }

    /**
     * Split a SQL script on top-level semicolons.
     *
     * <p>Quote- and comment-aware: a {@code ;} inside a string literal, a quoted identifier or a
     * {@code --} comment does not end a statement. A naive {@code split(";")} truncates any row
     * whose data contains a semicolon.
     */
    static List<String> splitStatements(String script) {
        if (script == null || script.isBlank()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;

        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (inSingleQuote) {
                current.append(c);
                if (c == '\'') {
                    // '' is an escaped quote, not a terminator
                    if (i + 1 < script.length() && script.charAt(i + 1) == '\'') {
                        current.append(script.charAt(++i));
                    } else {
                        inSingleQuote = false;
                    }
                }
                continue;
            }
            if (inDoubleQuote) {
                current.append(c);
                if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            switch (c) {
                case '\'' -> { inSingleQuote = true; current.append(c); }
                case '"' -> { inDoubleQuote = true; current.append(c); }
                case '-' -> {
                    if (i + 1 < script.length() && script.charAt(i + 1) == '-') {
                        inLineComment = true;
                        i++;
                    } else {
                        current.append(c);
                    }
                }
                case ';' -> {
                    addIfNotBlank(statements, current);
                    current.setLength(0);
                }
                default -> current.append(c);
            }
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder candidate) {
        String stmt = candidate.toString().strip();
        if (!stmt.isEmpty()) {
            statements.add(stmt);
        }
    }

    // ─── diffSchema (delegate to original logic) ────────────────────────────

    @Override
    public Map<String, Object> diffSchema(String sourceTable, String targetTable, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        JdbcTemplate jdbc = ctx.getJdbcTemplate();
        DatabaseDialect dialect = ctx.getDialect();

        var sourceCols = getTableColumns(sourceTable, dialect, jdbc);
        var targetCols = getTableColumns(targetTable, dialect, jdbc);

        Set<String> sourceNames = sourceCols.keySet();
        Set<String> targetNames = targetCols.keySet();

        List<String> onlyInSource = new ArrayList<>(sourceNames);
        onlyInSource.removeAll(targetNames);
        List<String> onlyInTarget = new ArrayList<>(targetNames);
        onlyInTarget.removeAll(sourceNames);

        List<Map<String, Object>> typeDiffs = new ArrayList<>();
        for (String col : sourceNames) {
            if (targetNames.contains(col)) {
                String srcType = String.valueOf(sourceCols.get(col).get("data_type"));
                String tgtType = String.valueOf(targetCols.get(col).get("data_type"));
                if (!Objects.equals(srcType, tgtType)) {
                    typeDiffs.add(Map.of("column", col, "source_type", srcType, "target_type", tgtType));
                }
            }
        }

        return Map.of(
                "source_table", sourceTable,
                "target_table", targetTable,
                "only_in_source", onlyInSource,
                "only_in_target", onlyInTarget,
                "type_differences", typeDiffs,
                "compatible", onlyInSource.isEmpty() && onlyInTarget.isEmpty() && typeDiffs.isEmpty()
        );
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    private Map<String, Object> doBackup(String connection, String tableName, String schema,
                                          int maxRows, BackupType type) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        JdbcTemplate jdbc = ctx.getEtlJdbcTemplate();

        List<String> columnNames = readColumnNames(jdbc, dialect, tableName, schema);
        if (columnNames.isEmpty()) {
            return Map.of("error", "Table not found: " + tableName);
        }

        int effectiveMaxRows = resolveMaxRows(maxRows);
        String selectSql = dialect.applyLimit("SELECT " + quoteAll(dialect, columnNames)
                + " FROM " + dialect.quote(tableName), effectiveMaxRows, 0);

        List<Map<String, Object>> rows = jdbc.queryForList(selectSql);
        List<String> insertStatements = generateInsertStatements(tableName, columnNames, rows, dialect);
        String sqlScript = String.join("\n", insertStatements);
        boolean truncated = rows.size() >= effectiveMaxRows;

        // 截断这件事必须留在元数据里：只写在本次返回值里，listBackups/getBackup 之后只看到 COMPLETED，
        // 拿它做「先清空再灌回」的整表还原会静默丢掉没备到的那部分行。
        Instant now = Instant.now();
        BackupMetadata meta = BackupMetadata.create(connection, tableName, schema,
                type, truncated ? BackupStatus.PARTIAL : BackupStatus.COMPLETED,
                sqlScript, rows.size(), rows.size())
                .withTiming(now, now);
        String id = metadataRepository.save(meta);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backupId", id);
        result.put("tableName", tableName);
        result.put("connection", connection);
        result.put("type", type.name());
        result.put("status", meta.status().name());
        result.put("maxRows", effectiveMaxRows);
        result.put("totalRows", rows.size());
        result.put("rowCount", rows.size());
        result.put("truncated", truncated);
        if (truncated) {
            result.put("hint", "Hit the row ceiling of " + effectiveMaxRows + ", so this backup is a "
                    + "partial copy of " + tableName + " and is recorded as PARTIAL. quickRestore "
                    + "refuses it, because clearing the table and replaying only this slice would drop "
                    + "the rest. Raise maxRows (or the configured ceiling) for a complete backup.");
        }
        result.put("statements", insertStatements);
        return result;
    }

    /**
     * Row ceiling actually applied to a backup query.
     *
     * <p>{@code maxRows <= 0} used to mean "no limit at all": the whole table was read into memory,
     * turned into one INSERT string per row and joined into a single script held by the metadata
     * store, so the peak footprint was several times the table size. There is now always a ceiling,
     * taken from {@code entropy.mcp.database.backup.max-backup-rows}.
     */
    private int resolveMaxRows(int requested) {
        int ceiling = backupProperties.maxBackupRows();
        if (requested <= 0) {
            log.debug("Backup requested with maxRows={}, applying the configured ceiling of {}",
                    requested, ceiling);
            return ceiling;
        }
        return Math.min(requested, ceiling);
    }

    /**
     * Column names of a table, as spelled by its catalog.
     *
     * <p>Per the dialect contract a single-table metadata query declares exactly one placeholder — the
     * table name, already normalised by the dialect — and resolves the schema internally. Counting
     * placeholders to guess the argument list is what broke Oracle: its {@code columnsQuery} declared
     * {@code (owner, table_name)}, every caller here passes {@code schema == null}, so {@code owner} was
     * bound to NULL, no column came back and the backup reported "Table not found".
     */
    private List<String> readColumnNames(JdbcTemplate jdbc, DatabaseDialect dialect,
                                          String tableName, String schema) {
        String columnsSql = dialect.columnsQuery(tableName, schema);
        List<Map<String, Object>> columnInfo = jdbc.queryForList(columnsSql,
                dialect.normalizeTableName(tableName));

        List<String> columnNames = new ArrayList<>();
        for (Map<String, Object> col : columnInfo) {
            // Oracle/H2 报 COLUMN_NAME，MySQL/PG 报 column_name：按大小写不敏感取，否则整表列清单为空。
            Object name = caseInsensitive(col).get("column_name");
            if (name != null) {
                columnNames.add(String.valueOf(name));
            }
        }
        return columnNames;
    }

    /**
     * Case-insensitive view of one result row.
     *
     * <p>Column labels come back in whatever case the driver reports — uppercase on Oracle and H2,
     * lowercase on MySQL and PostgreSQL — so every lookup keyed by a fixed spelling has to be
     * case-insensitive. Getting this wrong is silent: the value reads as null and the backup happily
     * writes NULL into the restore script.
     */
    private static Map<String, Object> caseInsensitive(Map<String, Object> row) {
        Map<String, Object> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        normalized.putAll(row);
        return normalized;
    }

    /**
     * Interprets a watermark column value as an instant.
     *
     * <p>Drivers hand back a date/time column as any of these types depending on vendor and JDBC
     * version. {@code null} means "cannot be interpreted", and the caller then leaves the watermark
     * where it was rather than inventing one.
     */
    private static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case LocalDateTime localDateTime -> localDateTime.atZone(ZoneId.systemDefault()).toInstant();
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case ZonedDateTime zonedDateTime -> zonedDateTime.toInstant();
            // java.sql.Date/Time 覆写了 toInstant() 直接抛异常，只能走毫秒值。
            case java.util.Date date -> Instant.ofEpochMilli(date.getTime());
            default -> null;
        };
    }

    private String quoteAll(DatabaseDialect dialect, List<String> columnNames) {
        return columnNames.stream().map(dialect::quote).reduce((a, b) -> a + ", " + b).orElse("*");
    }

    private List<String> generateInsertStatements(String tableName, List<String> columnNames,
                                                   List<Map<String, Object>> rows, DatabaseDialect dialect) {
        List<String> statements = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            sb.append("INSERT INTO ").append(dialect.quote(tableName)).append(" (");
            sb.append(quoteAll(dialect, columnNames));
            sb.append(") VALUES (");
            List<String> values = new ArrayList<>();
            Map<String, Object> lookup = caseInsensitive(row);
            for (String col : columnNames) {
                values.add(formatValue(lookup.get(col)));
            }
            sb.append(String.join(", ", values)).append(");");
            statements.add(sb.toString());
        }
        return statements;
    }

    private String formatValue(Object value) {
        if (value == null) return "NULL";
        if (value instanceof String) return "'" + ((String) value).replace("'", "''") + "'";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private Map<String, Map<String, Object>> getTableColumns(String tableName,
                                                              DatabaseDialect dialect,
                                                              JdbcTemplate jdbc) {
        try {
            String columnsSql = dialect.columnsQuery(tableName, null);
            List<Map<String, Object>> columns = jdbc.queryForList(columnsSql,
                    dialect.normalizeTableName(tableName));
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (Map<String, Object> col : columns) {
                Object name = caseInsensitive(col).get("column_name");
                if (name != null) {
                    result.put(String.valueOf(name), col);
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
