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
import com.entropy.database.mcp.facade.DatabaseReadOperations;
import com.entropy.database.mcp.facade.DatabaseWriteOperations;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CdcServiceImplTest {

    private static final String CONNECTION = "prod";

    /**
     * 固定的方言实例：探测 SQL 与位点 SQL 现在都走同一个 {@code queryRows(sql, connection)} 签名，
     * 需要同时给两者不同答案的用例只能按 SQL 本身区分，所以直接问方言要那两条 SQL，而不是写死字符串。
     */
    private static final OracleDialect ORACLE = new OracleDialect();

    @Mock
    private DynamicDataSourceManager dataSourceManager;
    @Mock
    private ByokDataSourceContext ctx;
    /** 读侧替身：服务只通过 facade 能力接口访问数据库，测试就替换这个接口。 */
    @Mock
    private DatabaseReadOperations reads;
    /** 写侧替身：只有建镜像表那条 DDL 会用到。 */
    @Mock
    private DatabaseWriteOperations writes;

    private CdcServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CdcServiceImpl(dataSourceManager, reads, writes);
        when(dataSourceManager.acquire(CONNECTION)).thenReturn(ctx);
        when(ctx.getDialect()).thenReturn(ORACLE);
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

        verify(writes, never()).executeDdl(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"t; DROP TABLE x --", "t\"x"})
    void createMirrorTableRejectsAMaliciousSourceTable(String sourceTable) {
        assertThatThrownBy(() -> service.createMirrorTable(CONNECTION, "HR", sourceTable, "HR", "COPY"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("sourceTable");

        verify(writes, never()).executeDdl(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HR; DROP TABLE x --", "HR\"x"})
    void createMirrorTableRejectsAMaliciousTargetSchema(String targetSchema) {
        assertThatThrownBy(() -> service.createMirrorTable(CONNECTION, "HR", "EMPLOYEES", targetSchema, "COPY"))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("targetSchema");

        verify(writes, never()).executeDdl(anyString(), anyString());
    }

    @Test
    void createMirrorTableQuotesEveryIdentifierForLegitimateNames() {
        service.createMirrorTable(CONNECTION, "HR", "EMPLOYEES", "STAGING", "EMPLOYEES_COPY");

        verify(writes).executeDdl("CREATE TABLE \"STAGING\".\"EMPLOYEES_COPY\" AS "
                + "SELECT * FROM \"HR\".\"EMPLOYEES\"", CONNECTION);
    }

    // ─── readChanges: failure is not "no changes" ──────────────────────────

    @Test
    void readChangesPropagatesAFailedQueryInsteadOfReturningAnEmptyList() {
        when(reads.queryRows(anyString(), anyString(), any(Object[].class)))
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
        when(reads.queryRows(anyString(), anyString(), any(Object[].class))).thenReturn(List.of(
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
        when(reads.queryRows(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        assertThatThrownBy(() -> service.getLastLsn(CONNECTION))
                .isInstanceOf(McpQueryException.class);
    }

    /**
     * 位点读法从 {@code queryForMap} 换成「取 queryRows 的首行」后，空结果不再由 Spring 抛
     * {@code EmptyResultDataAccessException}，必须由服务自己判断——否则一个空 map 交给
     * {@code parseLsn}，报出来的就是「方言解析不了位点」而不是「位点读不到」。
     */
    @Test
    void getLastLsnFailsWhenTheWatermarkQueryReturnsNoRows() {
        when(reads.queryRows(anyString(), anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> service.getLastLsn(CONNECTION))
                .isInstanceOf(McpQueryException.class)
                .hasMessageContaining("watermark");
    }

    @Test
    void getLastLsnNormalizesTheDialectWatermark() {
        when(ctx.getDialect()).thenReturn(new MySqlDialect());
        // MySQL 的 watermark 是 SELECT UNIX_TIMESTAMP() AS current_lsn 的秒值，与审计表
        // event_time > FROM_UNIXTIME(?) 同一单位。
        when(reads.queryRows(anyString(), anyString()))
                .thenReturn(List.of(Map.<String, Object>of("current_lsn", 1_735_689_600L)));

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
        when(reads.queryRows(anyString(), anyString())).thenReturn(List.of(
                Map.<String, Object>of("supported", 1),
                Map.<String, Object>of("supported", 1)));

        assertThat(service.isCdcSupported(CONNECTION)).isTrue();
    }

    @Test
    void cdcSupportIsFalseWhenTheProbeReportsZero() {
        when(reads.queryRows(anyString(), anyString()))
                .thenReturn(List.of(Map.<String, Object>of("supported", 0)));

        assertThat(service.isCdcSupported(CONNECTION)).isFalse();
    }

    @Test
    void cdcSupportIsFalseWhenTheDialectHasNoProbe() {
        when(ctx.getDialect()).thenReturn(new com.entropy.database.mcp.dialect.H2Dialect());

        assertThat(service.isCdcSupported(CONNECTION)).isFalse();
        verify(reads, never()).queryRows(anyString(), anyString());
    }

    @Test
    void statusReportsAnUnavailableWatermarkRatherThanZero() {
        // 探测与位点现在共用 queryRows(sql, connection) 这一个签名，所以按方言给出的 SQL 区分两者。
        when(reads.queryRows(eq(ORACLE.cdcCheckSupportSql()), anyString()))
                .thenReturn(List.of(Map.<String, Object>of("supported", 1)));
        when(reads.queryRows(eq(ORACLE.cdcGetLastLsnSql()), anyString()))
                .thenThrow(new DataAccessResourceFailureException("v$database not readable"));

        CdcStatus status = service.getStatus(CONNECTION);

        assertThat(status.cdcSupported()).isTrue();
        assertThat(status.currentLsn()).isEqualTo(CdcServiceImpl.LSN_UNAVAILABLE);
    }

    // ─── 进程内状态的边界 ──────────────────────────────────────────────────

    /**
     * 计数按连接名索引，而连接会被租约驱逐——没人回来清它。此前三个 map 都是无界无 TTL 的
     * {@code ConcurrentHashMap}，长跑进程里只增不减。
     */
    @Test
    void eventCountersStopGrowingOnceTheConnectionBudgetIsReached() {
        when(dataSourceManager.acquire(anyString())).thenReturn(ctx);
        when(reads.queryRows(anyString(), anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.<String, Object>of("change_type", "I", "primary_keys", "1")));

        int connections = CdcServiceImpl.MAX_TRACKED_CONNECTIONS * 2;
        for (int i = 0; i < connections; i++) {
            service.readChanges("evicted-" + i, "HR", "EMPLOYEES", 1L);
        }

        assertThat(service.trackedConnectionCount())
                .isLessThanOrEqualTo(CdcServiceImpl.MAX_TRACKED_CONNECTIONS)
                .isLessThan(connections);
    }

    /**
     * 订阅是业务注册表而不是缓存：超限要明确报错，而不是静默 LRU 驱逐——静默驱逐的表现是「CDC 悄悄停了」，
     * 调用方拿不到任何信号。
     */
    @Test
    void subscriptionRegistrationIsRejectedInsteadOfSilentlyEvictingOldOnes() {
        for (int i = 0; i < CdcServiceImpl.MAX_SUBSCRIPTIONS; i++) {
            service.registerSubscription(subscription("sub-" + i));
        }

        assertThatThrownBy(() -> service.registerSubscription(subscription("one-too-many")))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("limit");

        // 已有订阅一条都没丢；同名覆盖不受上限影响，否则满载时改一条已有订阅会无故失败。
        assertThat(service.listSubscriptions(CONNECTION)).hasSize(CdcServiceImpl.MAX_SUBSCRIPTIONS);
        service.registerSubscription(subscription("sub-0"));
        assertThat(service.listSubscriptions(CONNECTION)).hasSize(CdcServiceImpl.MAX_SUBSCRIPTIONS);
    }

    private static CdcSubscription subscription(String name) {
        return new CdcSubscription(name, CONNECTION, "HR", "EMPLOYEES%",
                List.of(CdcChangeType.INSERT), 1000L, true);
    }
}
