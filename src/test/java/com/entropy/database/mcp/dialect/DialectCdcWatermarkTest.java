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
package com.entropy.database.mcp.dialect;

import com.entropy.database.mcp.exception.McpValidationException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CDC watermark handling: a watermark is used as {@code fromLsn} for the next incremental read, so
 * it has to preserve ordering. Hashing a non-numeric LSN (the previous behaviour) produced values
 * unrelated to the real position, which silently skipped or replayed changes.
 */
class DialectCdcWatermarkTest {

    @Test
    void oracleReadsTheScnAsANumber() {
        assertThat(new OracleDialect().parseLsn(Map.<String, Object>of("current_scn", 2_345_678L)))
                .isEqualTo(2_345_678L);
    }

    @Test
    void postgresDecodesTheWalLsnInsteadOfHashingIt() {
        // 0/16B3748 = (0 << 32) | 0x16B3748
        assertThat(new PostgresDialect().parseLsn(Map.<String, Object>of("current_lsn", "0/16B3748")))
                .isEqualTo(0x16B3748L);
        // A file-boundary crossing must sort above everything in the previous segment.
        assertThat(new PostgresDialect().parseLsn(Map.<String, Object>of("current_lsn", "1/0")))
                .isEqualTo(1L << 32);
    }

    @Test
    void postgresWatermarkOrderingMatchesWalOrdering() {
        PostgresDialect pg = new PostgresDialect();

        long earlier = pg.parseLsn(Map.<String, Object>of("current_lsn", "0/FFFFFFFF"));
        long later = pg.parseLsn(Map.<String, Object>of("current_lsn", "1/00000001"));

        assertThat(later).isGreaterThan(earlier);
    }

    @Test
    void postgresRoundTripsTheWatermarkBackIntoPgLsnText() {
        PostgresDialect pg = new PostgresDialect();
        long parsed = pg.parseLsn(Map.<String, Object>of("current_lsn", "3/16B3748"));

        assertThat(pg.cdcLsnParameter(parsed)).isEqualTo("3/16B3748");
    }

    @Test
    void postgresRejectsAMalformedWalLsn() {
        assertThatThrownBy(() -> new PostgresDialect().parseLsn(Map.<String, Object>of("current_lsn", "not-an-lsn")))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("not-an-lsn");
    }

    @Test
    void mysqlPacksTheBinlogCoordinateIntoOneOrderedValue() {
        MySqlDialect mysql = new MySqlDialect();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("File", "mysql-bin.000042");
        row.put("Position", 1024L);

        long lsn = mysql.parseLsn(row);

        assertThat(lsn).isEqualTo((42L << 32) | 1024L);

        Map<String, Object> nextFile = new LinkedHashMap<>();
        nextFile.put("File", "mysql-bin.000043");
        nextFile.put("Position", 4L);
        assertThat(mysql.parseLsn(nextFile)).isGreaterThan(lsn);
    }

    @Test
    void mysqlRejectsAnUnparseableBinlogCoordinate() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("File", "mysql-bin.latest");
        row.put("Position", 1024L);

        assertThatThrownBy(() -> new MySqlDialect().parseLsn(row))
                .isInstanceOf(McpValidationException.class);
    }

    @Test
    void unparseableWatermarkFailsInsteadOfFallingBackToAHash() {
        assertThatThrownBy(() -> new H2Dialect().parseLsn(Map.<String, Object>of("something_else", "0/16B3748")))
                .isInstanceOf(McpValidationException.class);
    }

    @Test
    void oracleReadsChangesWithRealOperationTypesFromAFlashbackVersionQuery() {
        String sql = new OracleDialect().cdcReadChangesSql("HR", "EMPLOYEES", 100L);

        assertThat(sql).contains("VERSIONS_OPERATION AS change_type");
        assertThat(sql).contains("VERSIONS BETWEEN SCN ? AND MAXVALUE");
        assertThat(sql).contains("\"HR\".\"EMPLOYEES\"");
        // Row versions that predate the range carry no operation and must not be reported.
        assertThat(sql).contains("VERSIONS_OPERATION IS NOT NULL");
        assertThat(placeholders(sql)).isEqualTo(1);
    }

    @Test
    void auditBasedDialectsBindExactlyOnePlaceholderAndQuoteTheAuditTable() {
        String mysql = new MySqlDialect().cdcReadChangesSql("app", "orders", 5L);
        assertThat(mysql).contains("`app`.`orders_audit`");
        assertThat(placeholders(mysql)).isEqualTo(1);

        String postgres = new PostgresDialect().cdcReadChangesSql("app", "orders", 5L);
        assertThat(postgres).contains("\"app\".\"orders_audit\"");
        assertThat(postgres).contains("CAST(? AS pg_lsn)");
        assertThat(placeholders(postgres)).isEqualTo(1);
    }

    @Test
    void mirrorTableDdlQuotesEveryIdentifier() {
        assertThat(new OracleDialect().cdcCreateMirrorTableSql("HR", "COPY", "SELECT * FROM \"HR\".\"EMPLOYEES\""))
                .isEqualTo("CREATE TABLE \"HR\".\"COPY\" AS SELECT * FROM \"HR\".\"EMPLOYEES\"");
        assertThat(new PostgresDialect().cdcCreateMirrorTableSql("app", "copy", "SELECT * FROM \"app\".\"orders\""))
                .isEqualTo("CREATE TABLE \"app\".\"copy\" AS SELECT * FROM \"app\".\"orders\"");
        assertThat(new MySqlDialect().cdcCreateMirrorTableSql("app", "copy", "SELECT * FROM `app`.`orders`"))
                .isEqualTo("CREATE TABLE `app`.`copy` AS SELECT * FROM `app`.`orders`");
    }

    private static long placeholders(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }
}
