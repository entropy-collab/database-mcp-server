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
package com.entropy.database.mcp.cdc;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.MySqlDialect;
import com.entropy.database.mcp.dialect.OracleDialect;
import com.entropy.database.mcp.exception.McpQueryException;
import com.entropy.database.mcp.exception.McpValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CdcServiceImplTest {

    private static final String CONNECTION = "prod";

    @Mock
    private DynamicDataSourceManager dataSourceManager;
    @Mock
    private ByokDataSourceContext ctx;
    @Mock
    private JdbcTemplate jdbc;

    private CdcServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CdcServiceImpl(dataSourceManager);
        when(dataSourceManager.acquire(CONNECTION)).thenReturn(ctx);
        when(ctx.getDialect()).thenReturn(new OracleDialect());
        when(ctx.getJdbcTemplate()).thenReturn(jdbc);
    }

    // ─── createMirrorTable: DDL injection ─────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "t AS SELECT 1; DROP TABLE users; --",
            "t; DROP TABLE x --",
            "t\" ; DROP TABLE users; --",
            "t`x",
            "t]x",
            "t x"
    })
    void createMirrorTableRejectsAMaliciousTargetTable(String targetTable) {
        assertThatThrownBy(() -> service.createMirrorTable(CONNECTION, "HR", "EMPLOYEES", "HR", targetTable))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("targetTable");

        verify(jdbc, never()).execute(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"t; DROP TABLE x --", "t\"x"})
    void createMirrorTableRejectsAMaliciousSourceTable(String sourceTable) {
        assertThatThrownBy(() -> service.createMirrorTable(CONNECTION, "HR", sourceTable, "HR", "COPY"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("sourceTable");

        verify(jdbc, never()).execute(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HR; DROP TABLE x --", "HR\"x"})
    void createMirrorTableRejectsAMaliciousTargetSchema(String targetSchema) {
        assertThatThrownBy(() -> service.createMirrorTable(CONNECTION, "HR", "EMPLOYEES", targetSchema, "COPY"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("targetSchema");

        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void createMirrorTableQuotesEveryIdentifierForLegitimateNames() {
        service.createMirrorTable(CONNECTION, "HR", "EMPLOYEES", "STAGING", "EMPLOYEES_COPY");

        verify(jdbc).execute("CREATE TABLE \"STAGING\".\"EMPLOYEES_COPY\" AS "
                + "SELECT * FROM \"HR\".\"EMPLOYEES\"");
    }

    // ─── readChanges: failure is not "no changes" ──────────────────────────

    @Test
    void readChangesPropagatesAFailedQueryInsteadOfReturningAnEmptyList() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("ORA-00942: table or view does not exist"));

        assertThatThrownBy(() -> service.readChanges(CONNECTION, "HR", "EMPLOYEES", 100L))
                .isInstanceOf(McpQueryException.class)
                .hasMessageContaining("EMPLOYEES");
    }

    @Test
    void readChangesRejectsAnInjectedTableName() {
        assertThatThrownBy(() -> service.readChanges(CONNECTION, "HR", "EMPLOYEES\" ; DROP TABLE x --", 1L))
                .isInstanceOf(McpValidationException.class);
    }

    @Test
    void readChangesMapsDialectCodesToEventsInsteadOfSkippingThem() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.<String, Object>of("change_type", "I", "primary_keys", "1"),
                Map.<String, Object>of("change_type", "U", "primary_keys", "2"),
                Map.<String, Object>of("change_type", "SOMETHING_NEW", "primary_keys", "3")));

        List<CdcChangeEvent> events = service.readChanges(CONNECTION, "HR", "EMPLOYEES", 100L);

        assertThat(events).extracting(CdcChangeEvent::changeType)
                .containsExactly(CdcChangeType.INSERT, CdcChangeType.UPDATE, CdcChangeType.UNKNOWN);
    }

    @Test
    void readChangesFailsWhenTheDialectHasNoCdcSupport() {
        when(ctx.getDialect()).thenReturn(new com.entropy.database.mcp.dialect.H2Dialect());

        assertThatThrownBy(() -> service.readChanges(CONNECTION, "PUBLIC", "USERS", 1L))
                .isInstanceOf(McpQueryException.class)
                .hasMessageContaining("not supported");
    }

    // ─── getLastLsn: 0 is a real position, not an error code ───────────────

    @Test
    void getLastLsnFailsInsteadOfReportingPositionZero() {
        when(jdbc.queryForMap(anyString()))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        assertThatThrownBy(() -> service.getLastLsn(CONNECTION))
                .isInstanceOf(McpQueryException.class);
    }

    @Test
    void getLastLsnNormalizesTheDialectWatermark() {
        when(ctx.getDialect()).thenReturn(new MySqlDialect());
        // MySQL 的 watermark 是 SELECT UNIX_TIMESTAMP() AS current_lsn 的秒值，与审计表
        // event_time > FROM_UNIXTIME(?) 同一单位。
        when(jdbc.queryForMap(anyString())).thenReturn(Map.<String, Object>of("current_lsn", 1_735_689_600L));

        assertThat(service.getLastLsn(CONNECTION)).isEqualTo(1_735_689_600L);
    }

    // ─── isCdcSupported: a probe row count is not a capability signal ───────

    /**
     * The probe used to be read with {@code queryForObject}, which demands exactly one row: the
     * multi-branch {@code UNION ALL} probes returned one row per matching branch, so the most
     * capable database threw and was reported as "not supported".
     */
    @Test
    void cdcSupportSurvivesAProbeThatReturnsMoreThanOneRow() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.<String, Object>of("supported", 1),
                Map.<String, Object>of("supported", 1)));

        assertThat(service.isCdcSupported(CONNECTION)).isTrue();
    }

    @Test
    void cdcSupportIsFalseWhenTheProbeReportsZero() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.<String, Object>of("supported", 0)));

        assertThat(service.isCdcSupported(CONNECTION)).isFalse();
    }

    @Test
    void cdcSupportIsFalseWhenTheDialectHasNoProbe() {
        when(ctx.getDialect()).thenReturn(new com.entropy.database.mcp.dialect.H2Dialect());

        assertThat(service.isCdcSupported(CONNECTION)).isFalse();
        verify(jdbc, never()).queryForList(anyString());
    }

    @Test
    void statusReportsAnUnavailableWatermarkRatherThanZero() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.<String, Object>of("supported", 1)));
        when(jdbc.queryForMap(anyString()))
                .thenThrow(new DataAccessResourceFailureException("v$database not readable"));

        CdcStatus status = service.getStatus(CONNECTION);

        assertThat(status.cdcSupported()).isTrue();
        assertThat(status.currentLsn()).isEqualTo(CdcServiceImpl.LSN_UNAVAILABLE);
    }
}
