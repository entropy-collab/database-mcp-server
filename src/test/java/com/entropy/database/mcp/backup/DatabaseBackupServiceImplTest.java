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
import com.entropy.database.mcp.byok.ByokInfrastructure;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.properties.BackupProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the two ways the backup service used to destroy value it had already produced.
 *
 * <p>A failed restore wrote FAILED onto the <em>backup</em> record, and the restore entry point
 * refuses failed backups — so one transient error in the target database retired an intact backup
 * for good. And {@code maxRows <= 0} skipped the limit entirely, reading a whole table into memory
 * and into a single script string.
 */
class DatabaseBackupServiceImplTest {

    private static final String CONNECTION = "h2-backup";

    private static JdbcTemplate jdbcTemplate;

    private BackupMetadataRepository repository;

    @BeforeAll
    static void createDataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:backupsvc;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);
    }

    @BeforeEach
    void resetSchema() {
        repository = new BackupMetadataRepository();
        jdbcTemplate.execute("DROP TABLE IF EXISTS PEOPLE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS NO_WATERMARK");
        jdbcTemplate.execute("""
                CREATE TABLE PEOPLE (
                    ID INT PRIMARY KEY,
                    NAME VARCHAR(40),
                    UPDATED_AT TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE TABLE NO_WATERMARK (ID INT PRIMARY KEY, NAME VARCHAR(40))");
        for (int i = 1; i <= 3; i++) {
            jdbcTemplate.update("INSERT INTO PEOPLE VALUES (?, ?, TIMESTAMP '2020-01-01 00:00:00')",
                    i, "person" + i);
        }
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────

    private DatabaseBackupServiceImpl service(int maxBackupRows) {
        ByokDataSourceContext ctx = new ByokDataSourceContext(CONNECTION,
                jdbcTemplate.getDataSource(), new SchemaAwareH2Dialect(), jdbcTemplate,
                new ByokInfrastructure(null, null, null, null, null, null));
        DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);
        when(manager.acquire(anyString())).thenReturn(ctx);
        BackupProperties properties = new BackupProperties(true, true, maxBackupRows,
                30, true, "backup_schema", true);
        return new DatabaseBackupServiceImpl(manager, repository, properties);
    }

    private long rowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM PEOPLE", Long.class);
        return count == null ? -1 : count;
    }

    // ─── Restore isolation ────────────────────────────────────────────────

    @Test
    @DisplayName("a failed restore leaves the backup record COMPLETED and retryable")
    void failedRestoreDoesNotFailTheBackup() {
        DatabaseBackupServiceImpl service = service(1000);
        String backupId = (String) service.backupData("PEOPLE", 0, CONNECTION).get("backupId");

        // Replaying the INSERTs while the rows are still present violates the primary key.
        assertThrows(McpToolException.class, () -> service.restoreBackup(backupId, CONNECTION));

        BackupMetadata afterFailure = repository.get(backupId);
        assertThat(afterFailure).isNotNull();
        assertThat(afterFailure.isFailed()).isFalse();
        assertThat(afterFailure.status()).isEqualTo(BackupStatus.COMPLETED);
        assertThat(afterFailure.errorDetail()).isNull();
        assertThat(afterFailure.restoredRows()).isZero();
        assertThat(rowCount()).isEqualTo(3);

        // The same backup can be restored once the obstacle is gone.
        jdbcTemplate.execute("DELETE FROM PEOPLE");
        Map<String, Object> retry = service.restoreBackup(backupId, CONNECTION);

        assertThat(retry).doesNotContainKey("error");
        assertThat(retry.get("status")).isEqualTo("COMPLETED");
        assertThat(retry.get("restoredRows")).isEqualTo(3L);
        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a failed quick restore also leaves the backup record intact")
    void failedQuickRestoreDoesNotFailTheBackup() {
        DatabaseBackupServiceImpl service = service(1000);
        String backupId = (String) service.backupData("PEOPLE", 0, CONNECTION).get("backupId");

        // Dropping a backed-up column makes the replay fail mid-transaction.
        jdbcTemplate.execute("ALTER TABLE PEOPLE DROP COLUMN NAME");

        assertThrows(McpToolException.class, () -> service.quickRestore(backupId, CONNECTION));

        BackupMetadata afterFailure = repository.get(backupId);
        assertThat(afterFailure.isFailed()).isFalse();
        assertThat(afterFailure.status()).isEqualTo(BackupStatus.COMPLETED);
    }

    @Test
    @DisplayName("a successful restore reports its own timing without touching the backup stats")
    void restoreResultIsSeparateFromTheBackupRecord() {
        DatabaseBackupServiceImpl service = service(1000);
        String backupId = (String) service.backupData("PEOPLE", 0, CONNECTION).get("backupId");
        jdbcTemplate.execute("DELETE FROM PEOPLE");

        Map<String, Object> result = service.restoreBackup(backupId, CONNECTION);

        assertThat(result).containsKeys("restoreStartedAt", "restoreCompletedAt", "restoreDurationMs");
        assertThat(repository.get(backupId).restoredRows()).isZero();
        assertThat(repository.get(backupId).backedUpRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("quick restore replaces the table contents from the backup")
    void quickRestoreReplacesRows() {
        DatabaseBackupServiceImpl service = service(1000);
        String backupId = (String) service.backupData("PEOPLE", 0, CONNECTION).get("backupId");
        jdbcTemplate.update("INSERT INTO PEOPLE VALUES (99, 'stray', TIMESTAMP '2021-01-01 00:00:00')");

        Map<String, Object> result = service.quickRestore(backupId, CONNECTION);

        assertThat(result.get("status")).isEqualTo("QUICK_RESTORE_COMPLETED");
        assertThat(rowCount()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PEOPLE WHERE ID = 99", Long.class)).isZero();
    }

    @Test
    @DisplayName("an unknown backup id is an error, not an exception")
    void unknownBackupIsReported() {
        assertThat(service(1000).restoreBackup("no-such-id", CONNECTION))
                .containsEntry("error", "Backup not found: no-such-id");
    }

    // ─── Row ceiling ──────────────────────────────────────────────────────

    @Test
    @DisplayName("maxRows <= 0 falls back to the configured ceiling instead of reading everything")
    void nonPositiveMaxRowsIsCapped() {
        Map<String, Object> result = service(2).backupData("PEOPLE", 0, CONNECTION);

        assertThat(result.get("maxRows")).isEqualTo(2);
        assertThat(result.get("rowCount")).isEqualTo(2);
        assertThat(result.get("truncated")).isEqualTo(true);
    }

    @Test
    @DisplayName("a request above the ceiling is clamped to it")
    void requestAboveCeilingIsClamped() {
        assertThat(service(2).backupData("PEOPLE", 100, CONNECTION).get("rowCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("a request below the ceiling is honoured")
    void requestBelowCeilingIsHonoured() {
        assertThat(service(1000).backupData("PEOPLE", 1, CONNECTION).get("rowCount")).isEqualTo(1);
    }

    // ─── Incremental honesty ──────────────────────────────────────────────

    @Test
    @DisplayName("an incremental backup filters on the detected watermark column")
    void incrementalFiltersOnWatermark() {
        DatabaseBackupServiceImpl service = service(1000);

        Map<String, Object> first = service.backupDataIncremental("PEOPLE", 0, CONNECTION);
        assertThat(first.get("type")).isEqualTo("INCREMENTAL");
        assertThat(first.get("watermarkColumn")).isEqualTo("UPDATED_AT");
        assertThat(first.get("rowsBackedUp")).isEqualTo(3);

        // Nothing changed since the first backup completed.
        Map<String, Object> second = service.backupDataIncremental("PEOPLE", 0, CONNECTION);
        assertThat(second.get("rowsBackedUp")).isEqualTo(0);

        jdbcTemplate.update("UPDATE PEOPLE SET UPDATED_AT = TIMESTAMP '2099-01-01 00:00:00' "
                + "WHERE ID = 2");
        Map<String, Object> third = service.backupDataIncremental("PEOPLE", 0, CONNECTION);
        assertThat(third.get("rowsBackedUp")).isEqualTo(1);
    }

    @Test
    @DisplayName("without a watermark column the request is refused, not relabelled as a full copy")
    void incrementalWithoutWatermarkIsRefused() {
        Map<String, Object> result = service(1000)
                .backupDataIncremental("NO_WATERMARK", 0, CONNECTION);

        assertThat(result).containsKey("error");
        assertThat((String) result.get("error")).contains("no watermark column");
        assertThat(result).containsKey("recognisedWatermarkColumns");
        assertThat(result).doesNotContainKey("backupId");
        assertThat(repository.size()).isZero();
    }

    @Test
    @DisplayName("an explicit watermark column that does not exist is refused by name")
    void unknownExplicitWatermarkIsRefused() {
        Map<String, Object> result = service(1000)
                .backupDataIncremental("PEOPLE", 0, CONNECTION, "NO_SUCH_COLUMN");

        assertThat((String) result.get("reason")).contains("NO_SUCH_COLUMN");
        assertThat(repository.size()).isZero();
    }

    @Test
    @DisplayName("an explicit watermark column is used as given")
    void explicitWatermarkIsUsed() {
        Map<String, Object> result = service(1000)
                .backupDataIncremental("PEOPLE", 0, CONNECTION, "updated_at");

        assertThat(result.get("watermarkColumn")).isEqualTo("UPDATED_AT");
        assertThat(result.get("rowsBackedUp")).isEqualTo(3);
    }

    @Test
    @DisplayName("a backup record with no completion time no longer breaks the next incremental")
    void missingCompletionTimeIsTolerated() {
        repository.save(BackupMetadata.create(CONNECTION, "PEOPLE", null,
                BackupType.FULL, BackupStatus.COMPLETED, "", 0, 0));

        Map<String, Object> result = service(1000).backupDataIncremental("PEOPLE", 0, CONNECTION);

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("watermark")).isNotNull();
    }

    // ─── Test dialect ─────────────────────────────────────────────────────

    /**
     * {@link H2Dialect#columnsQuery} filters on {@code TABLE_SCHEMA IS NULL} when no schema is
     * given, which matches nothing in H2, and declares a single placeholder. The test supplies a
     * two-placeholder variant that defaults to {@code PUBLIC}, since fixing the dialect is out of
     * scope here.
     */
    private static final class SchemaAwareH2Dialect extends H2Dialect {
        @Override
        public String columnsQuery(String table, String schema) {
            return """
                    SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = COALESCE(?, 'PUBLIC')
                      AND TABLE_NAME = ?
                    ORDER BY ORDINAL_POSITION
                    """;
        }
    }
}
