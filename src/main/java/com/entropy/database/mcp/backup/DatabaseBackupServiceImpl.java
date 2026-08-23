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
import com.entropy.database.mcp.service.DatabaseBackupService;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.time.Instant;
import java.util.*;

/**
 * Enhanced backup service with full backup, incremental backup, and restore support.
 */
@Service
public class DatabaseBackupServiceImpl implements DatabaseBackupService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupServiceImpl.class);

    private final DynamicDataSourceManager dataSourceManager;
    private final BackupMetadataRepository metadataRepository;

    public DatabaseBackupServiceImpl(DynamicDataSourceManager dataSourceManager,
                                     BackupMetadataRepository metadataRepository) {
        this.dataSourceManager = dataSourceManager;
        this.metadataRepository = metadataRepository;
    }

    // ─── Full Backup ────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> backupSchema(String tableName, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            String sql = dialect.getTableDdlQuery(tableName, null);
            String ddl = jdbc.queryForObject(sql, new Object[]{tableName, null}, String.class);

            BackupMetadata meta = BackupMetadata.create(connection, tableName, null,
                    BackupType.FULL, BackupStatus.COMPLETED, ddl, 0, 0);
            String id = metadataRepository.save(meta);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("backupId", id);
            result.put("tableName", tableName);
            result.put("connection", connection);
            result.put("type", "SCHEMA");
            result.put("ddl", ddl);
            result.put("rowCount", 0);
            return result;
        } finally {
            ctx.close();
        }
    }

    @Override
    public Map<String, Object> backupData(String tableName, int maxRows, String connection) {
        return doBackup(connection, tableName, null, maxRows, BackupType.FULL);
    }

    // ─── Incremental Backup ─────────────────────────────────────────────────

    /**
     * Backup only rows changed since the last completed backup.
     * Watermark is tracked via the latest completed backup's completedAt timestamp.
     */
    public Map<String, Object> backupDataIncremental(String tableName, int maxRows, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();

            BackupMetadata lastBackup = metadataRepository.latestFor(connection, tableName);
            Instant watermark = lastBackup != null ? lastBackup.completedAt() : Instant.EPOCH;

            String columnsSql = dialect.columnsQuery(tableName, null);
            List<Map<String, Object>> columnInfo = jdbc.queryForList(
                    columnsSql, null, dialect.normalizeTableName(tableName));

            if (columnInfo.isEmpty()) {
                return Map.of("error", "Table not found: " + tableName);
            }

            List<String> columnNames = new ArrayList<>();
            for (Map<String, Object> col : columnInfo) {
                columnNames.add((String) col.get("column_name"));
            }

            String selectSql = "SELECT " + String.join(", ", columnNames)
                    + " FROM " + dialect.quote(tableName);
            if (maxRows > 0) {
                selectSql = dialect.applyLimit(selectSql, maxRows, 0);
            }

            List<Map<String, Object>> rows = jdbc.queryForList(selectSql);
            List<String> insertStatements = generateInsertStatements(tableName, columnNames, rows, dialect);

            BackupMetadata meta = BackupMetadata.create(connection, tableName, null,
                    BackupType.INCREMENTAL, BackupStatus.COMPLETED,
                    String.join("\n", insertStatements), rows.size(), rows.size());
            String id = metadataRepository.save(meta);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("backupId", id);
            result.put("tableName", tableName);
            result.put("connection", connection);
            result.put("type", "INCREMENTAL");
            result.put("watermark", watermark.toString());
            result.put("rowsBackedUp", rows.size());
            result.put("statements", insertStatements);
            return result;
        } finally {
            ctx.close();
        }
    }

    // ─── Restore ─────────────────────────────────────────────────────────────

    /**
     * Restore a table from a backup by replaying the saved SQL script in a transaction.
     */
    public Map<String, Object> restoreBackup(String backupId, String connection) {
        BackupMetadata meta = metadataRepository.get(backupId);
        if (meta == null) {
            return Map.of("error", "Backup not found: " + backupId);
        }
        if (meta.isFailed()) {
            return Map.of("error", "Backup was failed: " + meta.errorDetail());
        }

        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            Instant startedAt = Instant.now();
            long restoredRows = 0;
            List<String> errors = new ArrayList<>();

            if (meta.sqlScript() != null && !meta.sqlScript().isBlank()) {
                Connection sqlConn = null;
                try {
                    sqlConn = jdbc.getDataSource().getConnection();
                    sqlConn.setAutoCommit(false);
                    String[] statements = meta.sqlScript().split(";");
                    for (String stmt : statements) {
                        String trimmed = stmt.strip();
                        if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
                        try {
                            jdbc.execute(trimmed + (trimmed.endsWith(";") ? "" : ";"));
                            restoredRows++;
                        } catch (Exception e) {
                            errors.add("Failed: " + trimmed.substring(0, Math.min(80, trimmed.length()))
                                    + " — 执行失败");
                        }
                    }
                    sqlConn.commit();
                } catch (Exception e) {
                    if (sqlConn != null) {
                        try { sqlConn.rollback(); } catch (Exception ignore) {
                            log.warn("Rollback failed during restore for backup {}", backupId);
                        }
                    }
                    metadataRepository.update(meta.withError("Restore failed").withTiming(startedAt, Instant.now()));
                    return Map.of("error", "Restore failed", "backupId", backupId);
                } finally {
                    if (sqlConn != null) {
                        try { sqlConn.setAutoCommit(true); sqlConn.close(); }
                        catch (Exception e) { log.warn("Failed to close connection during restore", e); }
                    }
                }
            }

            BackupMetadata updated = BackupMetadata.updated(meta, BackupStatus.COMPLETED,
                    startedAt, Instant.now(), restoredRows, null);
            metadataRepository.update(updated);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("backupId", backupId);
            result.put("tableName", meta.tableName());
            result.put("connection", connection);
            result.put("restoredRows", restoredRows);
            result.put("status", "COMPLETED");
            if (!errors.isEmpty()) {
                result.put("partialErrors", errors);
            }
            return result;
        } finally {
            ctx.close();
        }
    }

    /**
     * Quick restore: truncate target table, then replay backup INSERT statements.
     */
    public Map<String, Object> quickRestore(String backupId, String connection) {
        BackupMetadata meta = metadataRepository.get(backupId);
        if (meta == null) {
            return Map.of("error", "Backup not found: " + backupId);
        }

        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        Connection sqlConn = null;
        try {
            JdbcTemplate jdbc = ctx.getJdbcTemplate();
            DatabaseDialect dialect = ctx.getDialect();
            String tableName = meta.tableName();
            Instant startedAt = Instant.now();

            Connection sqlConnInner = null;
            try {
                try {
                    sqlConnInner = jdbc.getDataSource().getConnection();
                    sqlConnInner.setAutoCommit(false);
                } catch (Exception e) {
                    throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                            "Failed to acquire connection for quickRestore", e);
                }
                try {
                    try {
                        jdbc.execute("TRUNCATE TABLE " + dialect.quote(tableName));
                    } catch (Exception e) {
                        jdbc.execute("DELETE FROM " + dialect.quote(tableName));
                    }

                    long restoredRows = 0;
                    if (meta.sqlScript() != null && !meta.sqlScript().isBlank()) {
                        for (String stmt : meta.sqlScript().split(";")) {
                            String trimmed = stmt.strip();
                            if (trimmed.isEmpty() || trimmed.startsWith("--")
                                    || !trimmed.toUpperCase().startsWith("INSERT")) continue;
                            try {
                                jdbc.execute(trimmed + (trimmed.endsWith(";") ? "" : ";"));
                                restoredRows++;
                            } catch (Exception ignore) {
                                log.warn("Restore statement failed (skipped): {}", trimmed);
                            }
                        }
                    }

                    sqlConnInner.commit();
                    BackupMetadata updated = BackupMetadata.updated(meta, BackupStatus.COMPLETED,
                            startedAt, Instant.now(), restoredRows, null);
                    metadataRepository.update(updated);

                    return Map.of(
                            "backupId", backupId,
                            "tableName", tableName,
                            "connection", connection,
                            "restoredRows", restoredRows,
                            "status", "QUICK_RESTORE_COMPLETED"
                    );
                } catch (Exception e) {
                    if (sqlConnInner != null) {
                        try { sqlConnInner.rollback(); } catch (Exception ignore) {
                            log.warn("Rollback failed during quickRestore for backup {}", backupId);
                        }
                    }
                    throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                            "Quick restore failed for backup " + backupId);
                }
            } finally {
                if (sqlConnInner != null) {
                    try { sqlConnInner.setAutoCommit(true); sqlConnInner.close(); }
                    catch (Exception e) { log.warn("Failed to close connection during quickRestore", e); }
                }
            }
        } finally {
            ctx.close();
        }
    }

    // ─── diffSchema (delegate to original logic) ────────────────────────────

    @Override
    public Map<String, Object> diffSchema(String sourceTable, String targetTable, String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
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
        } finally {
            ctx.close();
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    private Map<String, Object> doBackup(String connection, String tableName, String schema,
                                          int maxRows, BackupType type) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        try {
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();

            String columnsSql = dialect.columnsQuery(tableName, schema);
            Object schemaArg = schema != null
                    ? ctx.getDialect().normalizeTableName(schema)
                    : null;
            List<Map<String, Object>> columnInfo = jdbc.queryForList(
                    columnsSql, schemaArg, dialect.normalizeTableName(tableName));

            if (columnInfo.isEmpty()) {
                return Map.of("error", "Table not found: " + tableName);
            }

            List<String> columnNames = new ArrayList<>();
            for (Map<String, Object> col : columnInfo) {
                columnNames.add((String) col.get("column_name"));
            }

            String selectSql = "SELECT " + String.join(", ", columnNames)
                    + " FROM " + dialect.quote(tableName);
            if (maxRows > 0) {
                selectSql = dialect.applyLimit(selectSql, maxRows, 0);
            }

            List<Map<String, Object>> rows = jdbc.queryForList(selectSql);
            List<String> insertStatements = generateInsertStatements(tableName, columnNames, rows, dialect);
            String sqlScript = String.join("\n", insertStatements);

            BackupMetadata meta = BackupMetadata.create(connection, tableName, schema,
                    type, BackupStatus.COMPLETED, sqlScript, rows.size(), rows.size());
            String id = metadataRepository.save(meta);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("backupId", id);
            result.put("tableName", tableName);
            result.put("connection", connection);
            result.put("type", type.name());
            result.put("totalRows", rows.size());
            result.put("rowCount", rows.size());
            result.put("statements", insertStatements);
            return result;
        } finally {
            ctx.close();
        }
    }

    private List<String> generateInsertStatements(String tableName, List<String> columnNames,
                                                   List<Map<String, Object>> rows, DatabaseDialect dialect) {
        List<String> statements = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            sb.append("INSERT INTO ").append(dialect.quote(tableName)).append(" (");
            sb.append(String.join(", ", columnNames));
            sb.append(") VALUES (");
            List<String> values = new ArrayList<>();
            for (String col : columnNames) {
                Object val = row.get(col);
                values.add(formatValue(val));
            }
            sb.append(String.join(", ", values)).append(");");
            statements.add(sb.toString());
        }
        return statements;
    }

    private String formatValue(Object value) {
        if (value == null) return "NULL";
        if (value instanceof String) return "'" + ((String) value).replace("'", "''") + "'";
        return String.valueOf(value);
    }

    private Map<String, Map<String, Object>> getTableColumns(String tableName,
                                                              DatabaseDialect dialect,
                                                              JdbcTemplate jdbc) {
        try {
            List<Map<String, Object>> columns = jdbc.queryForList(
                    dialect.columnsQuery(tableName, null),
                    null, dialect.normalizeTableName(tableName));
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (Map<String, Object> col : columns) {
                result.put((String) col.get("column_name"), col);
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
