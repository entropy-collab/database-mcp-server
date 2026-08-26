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
package com.entropy.database.mcp.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 建表 + 插入 + 查询在真实数据库（H2）上的往返验证。
 *
 * <p>列名从 {@code sql}/{@code rows}/{@code timestamp} 改成了 {@code sql_text}/{@code row_count}/
 * {@code event_time}，DDL、INSERT、SELECT、{@code deleteOlderThan} 和 RowMapper 分散在五处，漏改一处
 * 单测（全是 mock）不会发现，只有真的建表读写一遍才会暴露。
 */
class AuditLogRepositoryH2Test {

    private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-02T10:00:00Z");

    private AuditLogRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new org.h2.jdbcx.JdbcDataSource();
        // 每个测试一套独立的库，避免 audit_log 的残留行影响断言
        dataSource.setURL("jdbc:h2:mem:audit_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        this.repository = new AuditLogRepository(new JdbcTemplate(dataSource));
    }

    private static AuditLogEntity entry(String tool, Instant at, String connectionKey) {
        return new AuditLogEntity(null, tool, "SELECT 1 FROM dual", 7, 42L, true, null, at, connectionKey);
    }

    @Test
    void createsTheTableAndRoundTripsAnEntry() {
        repository.insert(entry("executeQuery", EARLIER, "primary"));

        List<AuditLogEntity> found = repository.query(null, null, null, null, 10);

        assertThat(found).hasSize(1);
        AuditLogEntity stored = found.getFirst();
        assertThat(stored.id()).isNotNull();
        assertThat(stored.tool()).isEqualTo("executeQuery");
        assertThat(stored.sql()).isEqualTo("SELECT 1 FROM dual");
        assertThat(stored.rows()).isEqualTo(7);
        assertThat(stored.durationMs()).isEqualTo(42L);
        assertThat(stored.success()).isTrue();
        assertThat(stored.connectionKey()).isEqualTo("primary");
        assertThat(stored.timestamp()).isEqualTo(EARLIER);
    }

    @Test
    void filtersAndOrdersByEventTime() {
        repository.insert(entry("executeQuery", EARLIER, "primary"));
        repository.insert(entry("exportCsv", LATER, "reporting"));

        assertThat(repository.query(null, null, null, null, 10))
                .extracting(AuditLogEntity::tool)
                .containsExactly("exportCsv", "executeQuery");
        assertThat(repository.query("exportCsv", null, null, null, 10))
                .extracting(AuditLogEntity::tool)
                .containsExactly("exportCsv");
        assertThat(repository.query(null, "primary", null, null, 10))
                .extracting(AuditLogEntity::tool)
                .containsExactly("executeQuery");
        assertThat(repository.query(null, null, null, EARLIER, 10))
                .extracting(AuditLogEntity::tool)
                .containsExactly("executeQuery");
        // 分页子句同样是拼出来的，限制条数必须真的生效
        assertThat(repository.query(null, null, null, null, 1))
                .extracting(AuditLogEntity::tool)
                .containsExactly("exportCsv");
    }

    @Test
    void deletesByEventTime() {
        repository.insert(entry("executeQuery", EARLIER, "primary"));
        repository.insert(entry("exportCsv", LATER, "reporting"));

        assertThat(repository.deleteOlderThan(LATER)).isEqualTo(1);
        assertThat(repository.query(null, null, null, null, 10))
                .extracting(AuditLogEntity::tool)
                .containsExactly("exportCsv");
    }

    @Test
    void ensureTableExistsIsIdempotent() {
        // AuditLogInitializer 启动时先调一次，随后每条审计的 insert 又会调一次
        repository.ensureTableExists();
        repository.ensureTableExists();

        assertThat(repository.deleteOlderThan(LATER)).isZero();
    }
}
