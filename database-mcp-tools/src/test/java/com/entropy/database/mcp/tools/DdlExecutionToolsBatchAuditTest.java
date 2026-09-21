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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.facade.DatabaseOperations;
import com.entropy.database.mcp.facade.TransactionContext;
import com.entropy.database.mcp.facade.TransactionalWork;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.security.QueryAuditLogger;
import com.entropy.database.mcp.security.SqlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * executeDdlBatch 的每条语句都必须留下审计记录。
 *
 * <p>{@code PerformanceTimingAspect} 是全服务唯一的审计写入点，它只切 facade 层，而本工具是在
 * {@code inTransaction} 的回调里直接调 {@code tx.execute(ddl)}，这些调用根本不经过切面。线上实测：
 * 通过本工具执行 57 条 DDL，审计里只有走 executeDdl 的 2 条有记录，另外 55 条（2 个序列、10 个索引、
 * 43 条 COMMENT ON）一条都没有——批量 DDL 可以改光整个库的结构而不留任何痕迹。
 */
class DdlExecutionToolsBatchAuditTest {

    private static final String CONNECTION = "qditp";

    private final DatabaseOperations routingFacade = mock(DatabaseOperations.class);
    private final SqlValidator sqlValidator = mock(SqlValidator.class);
    private final CapturingAuditLogger auditLogger = new CapturingAuditLogger();

    private final List<String> executed = new ArrayList<>();

    private DdlExecutionTools tools() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("entropy.mcp.gateway.enabled", "true");
        DatabaseProperties properties = new DatabaseProperties(true, "h2", null, null, null,
                new DatabaseProperties.DdlProperties(true),
                null, null, null, null, null, null, null, null, null, null, null);
        return new DdlExecutionTools(routingFacade, sqlValidator, properties, environment, auditLogger);
    }

    /**
     * 用一个真事务上下文替身跑通回调，这样断言看到的是"每条语句都执行了、也都审计了"，
     * 而不是"facade 被调了一次"。
     */
    private void runTransactionInline(boolean ddlTransactional, RuntimeException failOnSecond) {
        TransactionContext tx = new TransactionContext() {
            @Override
            public int update(String sql, Object... args) {
                return 0;
            }

            @Override
            public void execute(String sql) {
                executed.add(sql);
                if (failOnSecond != null && executed.size() == 2) {
                    throw failOnSecond;
                }
            }

            @Override
            public List<Map<String, Object>> queryRows(String sql, Object... args) {
                return List.of();
            }

            @Override
            public boolean ddlIsTransactional() {
                return ddlTransactional;
            }
        };
        when(routingFacade.inTransaction(nullable(String.class), any())).thenAnswer(invocation -> {
            TransactionalWork<?> work = invocation.getArgument(1);
            return work.execute(tx);
        });
    }

    /** 每条语句一条审计，tool 名是 executeDdlBatch，connection 是调用方给的真实值。 */
    @Test
    void everyStatementOfASuccessfulBatchIsAudited() throws Exception {
        runTransactionInline(true, null);
        List<String> statements = List.of(
                "CREATE SEQUENCE seq_a",
                "CREATE INDEX idx_a ON t (id)",
                "COMMENT ON TABLE t IS 'demo'");

        tools().executeDdlBatch(CONNECTION, statements);

        assertThat(executed).isEqualTo(statements);
        assertThat(auditLogger.entries()).hasSize(3)
                .allSatisfy(entry -> {
                    assertThat(entry.tool()).isEqualTo("executeDdlBatch");
                    assertThat(entry.connectionKey()).isEqualTo(CONNECTION);
                    assertThat(entry.success()).isTrue();
                    assertThat(entry.error()).isNull();
                    assertThat(entry.durationMs()).isGreaterThanOrEqualTo(0);
                });
        assertThat(auditLogger.entries()).extracting(CapturingAuditLogger.Entry::sql)
                .isEqualTo(statements);
    }

    /** 失败的那一条也要记，而且要带上错误信息——审计的价值恰恰在失败与被拒的尝试上。 */
    @Test
    void theFailingStatementIsAuditedToo() throws Exception {
        runTransactionInline(true, new IllegalStateException("ORA-00955"));

        tools().executeDdlBatch(CONNECTION, List.of(
                "CREATE SEQUENCE seq_a", "CREATE SEQUENCE seq_a", "CREATE INDEX idx_a ON t (id)"));

        assertThat(auditLogger.entries()).hasSize(2);
        assertThat(auditLogger.entries().get(0).success()).isTrue();
        assertThat(auditLogger.entries().get(1))
                .satisfies(entry -> {
                    assertThat(entry.success()).isFalse();
                    assertThat(entry.error()).contains("ORA-00955");
                    assertThat(entry.sql()).isEqualTo("CREATE SEQUENCE seq_a");
                });
    }

    /** connection 省略时透传 null，不编造 "primary" 之类不存在的连接名。 */
    @Test
    void anAbsentConnectionIsPassedThroughAsNull() throws Exception {
        runTransactionInline(false, null);

        tools().executeDdlBatch(null, List.of("COMMENT ON TABLE t IS 'demo'"));

        assertThat(auditLogger.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.connectionKey()).isNull());
    }

    /** 记录每一次审计写入。 */
    private static final class CapturingAuditLogger implements QueryAuditLogger {

        record Entry(String tool, String sql, int rowCount, long durationMs, boolean success,
                     String error, String connectionKey) {
        }

        private final List<Entry> entries = new ArrayList<>();

        @Override
        public void log(String tool, String sql, int rowCount, long durationMs, boolean success,
                        String connectionKey) {
            log(tool, sql, rowCount, durationMs, success, null, connectionKey);
        }

        @Override
        public void log(String tool, String sql, int rowCount, long durationMs, boolean success,
                        String error, String connectionKey) {
            entries.add(new Entry(tool, sql, rowCount, durationMs, success, error, connectionKey));
        }

        @Override
        public void logWithPrincipal(String tool, String sql, int rowCount, long durationMs,
                                     boolean success, String error, String connectionKey,
                                     String principal) {
            log(tool, sql, rowCount, durationMs, success, error, connectionKey);
        }

        @Override
        public List<Map<String, Object>> getRecentLogs(int limit) {
            return List.of();
        }

        List<Entry> entries() {
            return entries;
        }
    }
}
