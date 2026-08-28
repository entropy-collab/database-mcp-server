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

import java.time.Instant;

/**
 * Metadata record for a backup task.
 *
 * @param dataWatermark 本次实际捕获到的最大水位值（增量备份专用，其它类型为 {@code null}）。
 *                      下一次增量必须从这里续，而不是从备份完成时刻续：备份完成时刻会把「本次没来得及
 *                      捕获的变更行」永久跳过。
 */
public record BackupMetadata(
    String backupId,
    String connectionKey,
    String tableName,
    String schema,
    BackupType type,
    BackupStatus status,
    String targetTable,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt,
    long totalRows,
    long backedUpRows,
    long restoredRows,
    String sqlScript,
    String errorDetail,
    Instant dataWatermark
) {
    public BackupMetadata {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isCompleted() { return status == BackupStatus.COMPLETED; }
    public boolean isFailed()    { return status == BackupStatus.FAILED; }
    public boolean isRunning()   { return status == BackupStatus.RUNNING; }
    public boolean hasError()    { return errorDetail != null && !errorDetail.isBlank(); }

    /** 命中行数上限、只捕获了表的一部分：不能用于「先清空再灌回」的整表还原。 */
    public boolean isPartial()   { return status == BackupStatus.PARTIAL; }

    /** 只含 DDL 的结构备份：数据恢复路径必须拒绝它，否则会把目标表清空成 0 行。 */
    public boolean isSchemaOnly() { return type == BackupType.SCHEMA; }

    public long durationMs() {
        if (startedAt == null || completedAt == null) return -1;
        return java.time.Duration.between(startedAt, completedAt).toMillis();
    }

    // ─── Immutable builders ────────────────────────────────────────────────

    public BackupMetadata withBackupId(String id) {
        return new BackupMetadata(id, connectionKey, tableName, schema, type,
                status, targetTable, createdAt, startedAt, completedAt,
                totalRows, backedUpRows, restoredRows, sqlScript, errorDetail, dataWatermark);
    }

    public BackupMetadata withStatus(BackupStatus status) {
        return new BackupMetadata(backupId, connectionKey, tableName, schema, type,
                status, targetTable, createdAt, startedAt, completedAt,
                totalRows, backedUpRows, restoredRows, sqlScript, errorDetail, dataWatermark);
    }

    public BackupMetadata withTiming(Instant startedAt, Instant completedAt) {
        return new BackupMetadata(backupId, connectionKey, tableName, schema, type,
                status, targetTable, createdAt, startedAt, completedAt,
                totalRows, backedUpRows, restoredRows, sqlScript, errorDetail, dataWatermark);
    }

    public BackupMetadata withRestoredRows(long restoredRows) {
        return new BackupMetadata(backupId, connectionKey, tableName, schema, type,
                status, targetTable, createdAt, startedAt, completedAt,
                totalRows, backedUpRows, restoredRows, sqlScript, errorDetail, dataWatermark);
    }

    public BackupMetadata withDataWatermark(Instant dataWatermark) {
        return new BackupMetadata(backupId, connectionKey, tableName, schema, type,
                status, targetTable, createdAt, startedAt, completedAt,
                totalRows, backedUpRows, restoredRows, sqlScript, errorDetail, dataWatermark);
    }

    public BackupMetadata withError(String error) {
        return new BackupMetadata(backupId, connectionKey, tableName, schema, type,
                BackupStatus.FAILED, targetTable, createdAt, startedAt, completedAt,
                totalRows, backedUpRows, restoredRows, sqlScript, error, dataWatermark);
    }

    // ─── Static factories ─────────────────────────────────────────────────

    public static BackupMetadata create(String connectionKey, String tableName, String schema,
                                        BackupType type, BackupStatus status,
                                        String sqlScript, int totalRows, int backedUpRows) {
        return new BackupMetadata(
                java.util.UUID.randomUUID().toString(),
                connectionKey, tableName, schema, type, status, null,
                Instant.now(), null, null,
                totalRows, backedUpRows, 0, sqlScript, null, null);
    }

    public static BackupMetadata updated(BackupMetadata original, BackupStatus status,
                                         Instant startedAt, Instant completedAt,
                                         long restoredRows, String errorDetail) {
        return new BackupMetadata(
                original.backupId(), original.connectionKey(), original.tableName(), original.schema(),
                original.type(), status, original.targetTable(),
                original.createdAt(), startedAt, completedAt,
                original.totalRows(), original.backedUpRows(), restoredRows,
                original.sqlScript(), errorDetail, original.dataWatermark());
    }
}
