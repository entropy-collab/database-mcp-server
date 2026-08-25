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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CDC watermark handling: a watermark is used as {@code fromLsn} for the next incremental read, so
 * it has to preserve ordering. Hashing a non-numeric LSN (the previous behaviour) produced values
 * unrelated to the real position, which silently skipped or replayed changes.
 *
 * <p>Ordering is not enough on its own: the watermark's *unit* must be the one the read predicate
 * consumes. Counting {@code ?} placeholders cannot catch a unit mismatch, which is how MySQL ended
 * up binding a packed binlog coordinate into {@code FROM_UNIXTIME(?)} and reading 0 rows forever.
 */
class DialectCdcWatermarkTest {

    private static JdbcTemplate h2;

    @BeforeAll
    static void openH2() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cdcwatermark;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        h2 = new JdbcTemplate(ds);
    }

    /** Every dialect, so a newly added CDC implementation cannot quietly skip the probe contract. */
    private static Stream<DatabaseDialect> allDialects() {
        return Stream.of(new OracleDialect(), new MySqlDialect(), new PostgresDialect(), new H2Dialect(),
                new SqlServerDialect(), new SqliteDialect(), new Db2Dialect(), new GenericDialect());
    }

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

    /**
     * A half that does not fit its 32 bits used to be truncated silently, so a later WAL position
     * could normalize to a *smaller* number than an earlier one and break the ordering the whole
     * incremental read relies on.
     */
    @Test
    void postgresRejectsAWalLsnThatWouldWrapAroundInsteadOfTruncatingIt() {
        assertThatThrownBy(() -> new PostgresDialect().parseLsn(Map.<String, Object>of("current_lsn", "0/1FFFFFFFF")))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("32 bits");

        assertThatThrownBy(() -> new PostgresDialect().parseLsn(Map.<String, Object>of("current_lsn", "1FFFFFFFF/0")))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("32 bits");
    }

    /**
     * The closed loop the placeholder count cannot see: getLastLsn → parseLsn → cdcLsnParameter →
     * the {@code ?} of cdcReadChangesSql must all speak Unix seconds on MySQL.
     */
    @Test
    void mysqlWatermarkAndReadPredicateShareTheSameUnit() {
        MySqlDialect mysql = new MySqlDialect();

        assertThat(mysql.cdcGetLastLsnSql()).contains("UNIX_TIMESTAMP()");

        // 2025-01-01T00:00:00Z 的 Unix 秒，模拟 SELECT UNIX_TIMESTAMP() AS current_lsn 的返回行。
        long watermark = mysql.parseLsn(Map.<String, Object>of("current_lsn", 1_735_689_600L));
        assertThat(watermark).isEqualTo(1_735_689_600L);

        Object bound = mysql.cdcLsnParameter(watermark);
        assertThat(bound).isEqualTo(1_735_689_600L);
        assertThat(mysql.cdcReadChangesSql("app", "orders", watermark)).contains("FROM_UNIXTIME(?)");

        // 打包过的 binlog 位点 ((7 << 32) | 512 = 30064771584) 当成秒来解释是公元 2922 年，
        // 谓词 event_time > FROM_UNIXTIME(30064771584) 匹配不到任何审计行（8.0.28 之前更是超出
        // FROM_UNIXTIME 的 32 位上限直接返回 NULL，谓词恒为 NULL）。真实位点必须落在秒的量级上。
        assertThat((Long) bound).isLessThan(2_147_483_647L);
        assertThat((7L << 32) | 512L).isGreaterThan(2_147_483_647L);
    }

    /**
     * A {@code SHOW MASTER STATUS} row must not be accepted as a watermark any more: its
     * {@code Position} column is a binlog offset, and reading it as seconds would look plausible
     * while pointing at 1970.
     */
    @Test
    void mysqlRejectsABinlogCoordinateAsAWatermark() {
        Map<String, Object> showMasterStatus = new LinkedHashMap<>();
        showMasterStatus.put("File", "mysql-bin.000007");
        showMasterStatus.put("Position", 512L);

        assertThatThrownBy(() -> new MySqlDialect().parseLsn(showMasterStatus))
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
        // 单参数 CONCAT 对结果没有任何影响，只会让人以为主键是拼接出来的。
        assertThat(mysql).doesNotContain("CONCAT(");

        String postgres = new PostgresDialect().cdcReadChangesSql("app", "orders", 5L);
        assertThat(postgres).contains("\"app\".\"orders_audit\"");
        assertThat(postgres).contains("CAST(? AS pg_lsn)");
        assertThat(placeholders(postgres)).isEqualTo(1);
    }

    /**
     * {@code CdcServiceImpl.isCdcSupported} reads one value out of this probe. A multi-branch
     * {@code UNION ALL} returned one row per matching branch, so the best-configured database threw
     * {@code IncorrectResultSizeDataAccessException} and was reported as "CDC not supported".
     */
    @ParameterizedTest
    @MethodSource("allDialects")
    void cdcSupportProbeIsShapedAsASingleRowSingleValue(DatabaseDialect dialect) {
        String sql = dialect.cdcCheckSupportSql();
        assumeTrue(sql != null, dialect.getDialectName() + " implements no CDC support probe");

        String flat = sql.replaceAll("\\s+", " ").trim();
        assertThat(flat).doesNotContainIgnoringCase("UNION");
        // 一条 SELECT 而不是多段拼接，且投影出单列判定值。
        assertThat(flat.toUpperCase().split("SELECT", -1).length - 1).isEqualTo(1);
        assertThat(flat).containsIgnoringCase("AS supported");
        assertThat(flat).doesNotContain(";");
    }

    /**
     * The same contract verified for real where it can be: the dialects whose probe only uses
     * portable SQL are executed on H2 and must come back as exactly one row holding 0 or 1. The rest
     * reference server-specific views or functions ({@code v$parameter}, {@code pg_is_in_recovery()},
     * {@code UNIX_TIMESTAMP()}) that exist on no embeddable database, so they stay on the text
     * assertions above.
     */
    @ParameterizedTest
    @MethodSource("allDialects")
    void cdcSupportProbeReturnsOneRowWhenItCanRunOnH2(DatabaseDialect dialect) {
        String sql = dialect.cdcCheckSupportSql();
        assumeTrue(sql != null, dialect.getDialectName() + " implements no CDC support probe");

        List<Map<String, Object>> rows;
        try {
            rows = h2.queryForList(sql);
        } catch (DataAccessException e) {
            assumeTrue(false, dialect.getDialectName() + " probe cannot run on H2: " + e.getMessage());
            return;
        }
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasSize(1);
        assertThat(((Number) rows.getFirst().values().iterator().next()).intValue()).isIn(0, 1);
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
