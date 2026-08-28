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
import com.entropy.database.mcp.byok.StatementTemplates;
import com.entropy.database.mcp.properties.StatementTimeouts;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.properties.BackupProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
        return service(maxBackupRows, new DdlAwareH2Dialect());
    }

    private DatabaseBackupServiceImpl service(int maxBackupRows, DatabaseDialect dialect) {
        ByokDataSourceContext ctx = new ByokDataSourceContext(CONNECTION,
                jdbcTemplate.getDataSource(), dialect,
                StatementTemplates.over(jdbcTemplate.getDataSource(), jdbcTemplate,
                        StatementTimeouts.defaults()),
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

    /** 三行各自一个水位值，这样截断的边界落在行与行之间，能验证水位推进到哪一行。 */
    private void spreadWatermarks() {
        jdbcTemplate.update("UPDATE PEOPLE SET UPDATED_AT = TIMESTAMP '2020-01-01 00:00:00' WHERE ID = 1");
        jdbcTemplate.update("UPDATE PEOPLE SET UPDATED_AT = TIMESTAMP '2021-01-01 00:00:00' WHERE ID = 2");
        jdbcTemplate.update("UPDATE PEOPLE SET UPDATED_AT = TIMESTAMP '2022-01-01 00:00:00' WHERE ID = 3");
    }

    private static Instant at(String timestamp) {
        return Timestamp.valueOf(timestamp).toInstant();
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

    // ─── 数据恢复路径只接受「完整的数据备份」 ──────────────────────────────

    @Test
    @DisplayName("a schema backup's id is refused by quick restore and the rows survive")
    void schemaBackupIsRefusedByQuickRestore() {
        DatabaseBackupServiceImpl service = service(1000);
        Map<String, Object> schemaBackup = service.backupSchema("PEOPLE", CONNECTION);
        String backupId = (String) schemaBackup.get("backupId");

        assertThat(repository.get(backupId).type()).isEqualTo(BackupType.SCHEMA);

        Map<String, Object> refusal = service.quickRestore(backupId, CONNECTION);

        assertThat((String) refusal.get("error")).contains("schema (DDL) backup", "only accepts data");
        assertThat(refusal).doesNotContainKey("status");
        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a zero-row data backup is refused by quick restore instead of emptying the table")
    void emptyDataBackupIsRefusedByQuickRestore() {
        DatabaseBackupServiceImpl service = service(1000);
        jdbcTemplate.execute("DELETE FROM PEOPLE");
        String backupId = (String) service.backupData("PEOPLE", 0, CONNECTION).get("backupId");
        for (int i = 1; i <= 3; i++) {
            jdbcTemplate.update("INSERT INTO PEOPLE VALUES (?, ?, TIMESTAMP '2020-01-01 00:00:00')",
                    i, "person" + i);
        }

        Map<String, Object> refusal = service.quickRestore(backupId, CONNECTION);

        assertThat((String) refusal.get("error")).contains("no INSERT statement");
        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a truncated full backup is recorded PARTIAL and refused by quick restore")
    void truncatedFullBackupIsPartialAndRefusedByQuickRestore() {
        DatabaseBackupServiceImpl service = service(2);
        Map<String, Object> backup = service.backupData("PEOPLE", 0, CONNECTION);
        String backupId = (String) backup.get("backupId");

        assertThat(backup.get("truncated")).isEqualTo(true);
        assertThat(backup.get("status")).isEqualTo("PARTIAL");
        assertThat(repository.get(backupId).status()).isEqualTo(BackupStatus.PARTIAL);

        Map<String, Object> refusal = service.quickRestore(backupId, CONNECTION);

        assertThat((String) refusal.get("error")).contains("incomplete (PARTIAL)");
        assertThat(refusal).containsEntry("backupStatus", "PARTIAL");
        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("restoring a partial backup is allowed but says so in the result")
    void restoringAPartialBackupWarns() {
        DatabaseBackupServiceImpl service = service(2);
        String backupId = (String) service.backupData("PEOPLE", 0, CONNECTION).get("backupId");
        jdbcTemplate.execute("DELETE FROM PEOPLE");

        Map<String, Object> result = service.restoreBackup(backupId, CONNECTION);

        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat((String) result.get("warning")).contains("PARTIAL", "not a faithful copy");
        assertThat(rowCount()).isEqualTo(2);
    }

    // ─── 方言契约：单表元数据查询只绑一个「归一化后的表名」 ─────────────────

    @Test
    @DisplayName("an Oracle-shaped columnsQuery — one placeholder, schema resolved inside — yields columns")
    void oracleShapedColumnsQueryYieldsColumns() {
        Map<String, Object> backup = service(1000, new OracleShapedH2Dialect())
                .backupData("people", 0, CONNECTION);

        assertThat(backup).doesNotContainKey("error");
        assertThat(backup.get("rowCount")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) backup.get("statements");
        assertThat(statements).hasSize(3);
        // 列名与列值都取到了，才说明「一个占位符 + 大小写不敏感取列名」两件事都对。
        assertThat(statements.getFirst()).contains("ID, NAME, UPDATED_AT").contains("'person1'");
    }

    @Test
    @DisplayName("a two-placeholder columnsQuery fails loudly instead of reporting 'Table not found'")
    void outOfContractColumnsQueryFailsLoudly() {
        DatabaseBackupServiceImpl service = service(1000, new TwoPlaceholderH2Dialect());

        // 旧实现按占位符个数猜参数，把 schema 绑成 NULL，于是查不到列、返回 "Table not found"——
        // 一个存在的表被说成不存在，备份静默失效。现在参数个数由契约固定，不匹配就直接报错。
        assertThrows(DataAccessException.class, () -> service.backupData("PEOPLE", 0, CONNECTION));
    }

    // ─── 增量备份的水位不能跳过没备到的行 ──────────────────────────────────

    @Test
    @DisplayName("a truncated incremental reports truncated and only advances to the rows it captured")
    void truncatedIncrementalKeepsTheWatermarkResumable() {
        spreadWatermarks();

        Map<String, Object> first = service(2).backupDataIncremental("PEOPLE", 0, CONNECTION);

        assertThat(first.get("rowsBackedUp")).isEqualTo(2);
        assertThat(first.get("truncated")).isEqualTo(true);
        assertThat(first.get("status")).isEqualTo("PARTIAL");
        // 水位停在「完整捕获的最后一个水位值」，绝不是备份时刻：2021 那批可能被切成两半。
        assertThat(first.get("nextWatermark")).isEqualTo(at("2020-01-01 00:00:00").toString());
        assertThat(repository.get((String) first.get("backupId")).dataWatermark())
                .isEqualTo(at("2020-01-01 00:00:00"));

        Map<String, Object> second = service(1000).backupDataIncremental("PEOPLE", 0, CONNECTION);

        assertThat(second.get("watermark")).isEqualTo(at("2020-01-01 00:00:00").toString());
        assertThat(second.get("truncated")).isEqualTo(false);
        assertThat(second.get("rowsBackedUp")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) second.get("statements");
        // ID=3 是第一次被截掉的那行：它必须出现在下一次增量里，否则就是永久空洞。
        assertThat(String.join("\n", statements)).contains("'person3'").contains("'person2'");
        assertThat(second.get("nextWatermark")).isEqualTo(at("2022-01-01 00:00:00").toString());
    }

    @Test
    @DisplayName("when every captured row shares one watermark the mark stands still rather than skipping rows")
    void truncatedIncrementalOnOneWatermarkGroupDoesNotAdvance() {
        // 三行同水位、上限 2：无论怎么切都无法判断这个水位值是否已经取完，只能原地不动重跑。
        Map<String, Object> first = service(2).backupDataIncremental("PEOPLE", 0, CONNECTION);

        assertThat(first.get("truncated")).isEqualTo(true);
        assertThat(first.get("nextWatermark")).isEqualTo(Instant.EPOCH.toString());

        Map<String, Object> second = service(1000).backupDataIncremental("PEOPLE", 0, CONNECTION);

        assertThat(second.get("rowsBackedUp")).isEqualTo(3);
    }

    // ─── Test dialects ────────────────────────────────────────────────────

    /**
     * H2 已符合方言契约：{@link H2Dialect#columnsQuery} 用 {@code CURRENT_SCHEMA} 解析 schema，只留一个表名
     * 占位符。这里只补一条 H2 没有的 DDL 提取语句（形状跟 Oracle 一样吃两个参数），好让 backupSchema 跑通。
     */
    private static class DdlAwareH2Dialect extends H2Dialect {
        @Override
        public String getTableDdlQuery(String tableName, String schema) {
            return "SELECT 'CREATE TABLE PEOPLE (ID INT)' AS DDL FROM DUAL "
                    + "WHERE CAST(? AS VARCHAR) IS NOT NULL AND CAST(? AS VARCHAR) IS NULL";
        }
    }

    /**
     * 契约落地后的 Oracle 形态：owner 由方言内部解析、只剩一个表名占位符，表名先经
     * {@code normalizeTableName} 折叠成大写，列名标签则用小写（MySQL/PG 的报法）以验证取列名不区分大小写。
     */
    private static final class OracleShapedH2Dialect extends H2Dialect {
        @Override
        public String columnsQuery(String table, String schema) {
            return """
                    SELECT COLUMN_NAME AS "column_name", DATA_TYPE AS "data_type"
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = CURRENT_SCHEMA
                      AND TABLE_NAME = ?
                    ORDER BY ORDINAL_POSITION
                    """;
        }

        @Override
        public String normalizeTableName(String table) {
            return table.toUpperCase(Locale.ROOT);
        }

        /** Oracle 的未加引号标识符会折叠成大写；H2 里加引号的小写名字反而找不到表，所以跟着折叠。 */
        @Override
        public String quote(String name) {
            return name.toUpperCase(Locale.ROOT);
        }
    }

    /** 违反契约的旧形状：两个占位符（schema, table）。备份不再替它猜参数。 */
    private static final class TwoPlaceholderH2Dialect extends H2Dialect {
        @Override
        public String columnsQuery(String table, String schema) {
            return """
                    SELECT COLUMN_NAME
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = COALESCE(CAST(? AS VARCHAR), CURRENT_SCHEMA)
                      AND TABLE_NAME = ?
                    ORDER BY ORDINAL_POSITION
                    """;
        }
    }
}
