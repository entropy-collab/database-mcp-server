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
package com.entropy.database.mcp.repository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Runs an already-validated list of DDL statements against one BYOK connection.
 *
 * <p>存在的理由只有一个：让 ETL 的 DDL step 不必自己持有 {@code JdbcTemplate}。能力包不允许
 * 依赖 {@code org.springframework.jdbc..}（ArchUnit R6），而「逐条执行 DDL 并累加影响行数」是
 * JDBC 层的事，和 {@link EtlRowStream} 一样属于 infra。
 *
 * <p><b>为什么不走 {@code ByokWriteRepository.executeDdl}。</b>那条路会对每条语句再校验一遍，
 * 而调用方（DDL step）刻意先把整批语句全部校验完才开始执行——DDL 多半不在一个事务里，逐条
 * 边校验边执行会在第 N 条被拒时留下前 N-1 条已生效的结构变更。它返回的还是一个 Map 而不是
 * 影响行数，step 需要的恰好是后者。所以这里只做执行，校验留在调用方。
 *
 * <p>用 {@link EtlTemplates#getDdlJdbcTemplate()}：DDL 可能在元数据锁上阻塞得比普通写久得多，
 * 用的是 DDL 那一档语句超时上限。
 */
public final class EtlDdlRunner {

    private EtlDdlRunner() {
    }

    /**
     * Execute every statement in order and return the summed update counts.
     *
     * @param statements DDL statements the caller has <em>already</em> validated
     */
    public static long executeAll(EtlTemplates ctx, List<String> statements) {
        JdbcTemplate jdbcTemplate = ctx.getDdlJdbcTemplate();
        long totalAffected = 0;
        for (String ddl : statements) {
            totalAffected += jdbcTemplate.update(ddl);
        }
        return totalAffected;
    }
}
