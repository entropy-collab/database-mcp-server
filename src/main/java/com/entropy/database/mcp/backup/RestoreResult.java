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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of one restore attempt.
 *
 * <p>Deliberately separate from {@link BackupMetadata}: a restore is an event that happens
 * <em>to</em> a backup, not a property <em>of</em> it. Recording restore status and row counts on the
 * backup record made a single failed restore mark the backup FAILED, which the restore entry point
 * then treated as "this backup is unusable" — losing an intact backup to a transient outage in the
 * target database. Restore results are returned to the caller and never written back.
 *
 * @param backupId          the backup that was replayed
 * @param tableName         table the backup belongs to
 * @param connection        BYOK connection the replay ran against
 * @param status            {@code COMPLETED} or {@code QUICK_RESTORE_COMPLETED}
 * @param statementsApplied number of statements replayed
 * @param restoredRows      total affected row count reported by the driver
 * @param startedAt         when the replay started
 * @param completedAt       when the replay committed
 */
public record RestoreResult(
        String backupId,
        String tableName,
        String connection,
        String status,
        int statementsApplied,
        long restoredRows,
        Instant startedAt,
        Instant completedAt
) {
    public long durationMs() {
        if (startedAt == null || completedAt == null) return -1;
        return Duration.between(startedAt, completedAt).toMillis();
    }

    /** Tool-facing representation. */
    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backupId", backupId);
        result.put("tableName", tableName);
        result.put("connection", connection);
        result.put("statementsApplied", statementsApplied);
        result.put("restoredRows", restoredRows);
        result.put("status", status);
        result.put("restoreStartedAt", startedAt != null ? startedAt.toString() : null);
        result.put("restoreCompletedAt", completedAt != null ? completedAt.toString() : null);
        result.put("restoreDurationMs", durationMs());
        return result;
    }
}
