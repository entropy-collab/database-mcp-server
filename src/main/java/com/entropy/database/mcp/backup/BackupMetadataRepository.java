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

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory backup metadata store with auto-cleanup of expired records.
 *
 * <p>Each record retains the backup's full SQL script, so this store is the fastest route to an
 * OutOfMemoryError in a long-running process. It is therefore bounded by both count and age;
 * eviction is silent, and {@link #get} returning {@code null} is the expected signal that a
 * backup is no longer available for restore.
 */
@Repository
public class BackupMetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(BackupMetadataRepository.class);

    private static final int MAX_RECORDS = 200;
    private static final Duration MAX_RETENTION = Duration.ofDays(7);

    private final ConcurrentMap<String, BackupMetadata> store = Caffeine.newBuilder()
            .maximumSize(MAX_RECORDS)
            .expireAfterWrite(MAX_RETENTION)
            .<String, BackupMetadata>build()
            .asMap();

    public String save(BackupMetadata metadata) {
        String id = metadata.backupId() != null ? metadata.backupId() : UUID.randomUUID().toString();
        store.put(id, metadata.withBackupId(id));
        log.info("Backup saved: id={}, table={}, type={}, status={}",
                id, metadata.tableName(), metadata.type(), metadata.status());
        return id;
    }

    public void update(BackupMetadata metadata) {
        if (metadata.backupId() != null) {
            store.computeIfPresent(metadata.backupId(), (k, existing) -> metadata);
        }
    }

    public BackupMetadata get(String backupId) {
        return store.get(backupId);
    }

    public List<BackupMetadata> list(String connectionKey, String tableName, int limit) {
        return store.values().stream()
                .filter(m -> connectionKey == null || connectionKey.isBlank() || connectionKey.equals(m.connectionKey()))
                .filter(m -> tableName == null || tableName.isBlank() || tableName.equals(m.tableName()))
                .sorted(Comparator.comparing(BackupMetadata::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * 最近一条「成功执行过」的备份记录，用于推导下一次增量的起始水位。
     *
     * <p>{@link BackupStatus#PARTIAL} 也算：被截断的备份同样捕获了一段真实数据，把它排除掉会让水位退回到
     * 更早的记录，重复备份已经拿到的行。至于它的水位能推进到哪里，由调用方按 {@code dataWatermark} 判断。
     */
    public BackupMetadata latestFor(String connectionKey, String tableName) {
        return store.values().stream()
                .filter(m -> connectionKey.equals(m.connectionKey()))
                .filter(m -> tableName.equals(m.tableName()))
                .filter(m -> m.status() == BackupStatus.COMPLETED || m.status() == BackupStatus.PARTIAL)
                .sorted(Comparator.comparing(BackupMetadata::createdAt).reversed())
                .findFirst()
                .orElse(null);
    }

    public boolean delete(String backupId) {
        return store.remove(backupId) != null;
    }

    public int cleanupOldRecords(int retentionDays) {
        Instant cutoff = Instant.now().minusSeconds((long) retentionDays * 86400);
        List<String> toDelete = store.values().stream()
                .filter(m -> m.createdAt().isBefore(cutoff))
                .map(BackupMetadata::backupId)
                .toList();
        toDelete.forEach(store::remove);
        if (!toDelete.isEmpty()) {
            log.info("Cleaned up {} expired backup records", toDelete.size());
        }
        return toDelete.size();
    }

    public int size() {
        return store.size();
    }

    // ─── Factory helpers ─────────────────────────────────────────────────────

    public static BackupMetadata create(String connectionKey, String tableName, String schema,
                                        BackupType type, BackupStatus status, String sqlScript,
                                        long totalRows, long backedUpRows) {
        return new BackupMetadata(
                null, connectionKey, tableName, schema, type, status,
                null, Instant.now(), null, null,
                totalRows, backedUpRows, 0, sqlScript, null, null);
    }

    public static BackupMetadata updated(BackupMetadata original, BackupStatus status,
                                          Instant startedAt, Instant completedAt,
                                          long restoredRows, String error) {
        return new BackupMetadata(
                original.backupId(), original.connectionKey(), original.tableName(), original.schema(),
                original.type(), status, original.targetTable(),
                original.createdAt(), startedAt, completedAt,
                original.totalRows(), original.backedUpRows(), restoredRows,
                original.sqlScript(), error, original.dataWatermark());
    }
}
