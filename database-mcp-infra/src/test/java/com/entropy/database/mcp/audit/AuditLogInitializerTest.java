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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「审计库是不是只活在进程内存里」的判定。
 *
 * <p>为什么判据在 JDBC URL 上而不是 {@code DatabaseMetaData#getDatabaseProductName()}：产品名只能
 * 告诉你这是 H2，而 {@code jdbc:h2:mem:...} 与 {@code jdbc:h2:file:...} 在留痕可靠性上是**相反**的
 * 结论——前者重启即空，后者落盘可查。按产品名告警会把落盘的 H2 也误报成不可靠，按 URL 才分得开。
 *
 * <p>这条告警是 {@code application.yml} 给 {@code spring.datasource.url} 配默认内存库的**对价**：
 * 默认值让审计查询工具开箱可用，代价是历史不持久，而这个代价必须在启动日志里说出来。本项目历史上
 * 的缺陷正是「审计静默落进匿名内存库、同时报告持久化正常」（见 {@link AuditLogRepository} 类注释）。
 */
class AuditLogInitializerTest {

    @Nested
    class TreatedAsInMemory {

        @Test
        void h2MemIsTheShippedDefault() {
            assertThat(AuditLogInitializer.warnIfInMemory(
                    "jdbc:h2:mem:mcp_audit;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")).isTrue();
        }

        @Test
        void urlCaseDoesNotMatter() {
            assertThat(AuditLogInitializer.warnIfInMemory("JDBC:H2:MEM:MCP_AUDIT")).isTrue();
        }

        @Test
        void hsqldbMem() {
            assertThat(AuditLogInitializer.warnIfInMemory("jdbc:hsqldb:mem:audit")).isTrue();
        }

        @Test
        void derbyMemory() {
            assertThat(AuditLogInitializer.warnIfInMemory("jdbc:derby:memory:audit;create=true")).isTrue();
        }

        @Test
        void sqliteInMemoryUsesAColonMemoryColonPath() {
            assertThat(AuditLogInitializer.warnIfInMemory("jdbc:sqlite::memory:")).isTrue();
        }
    }

    @Nested
    class TreatedAsDurable {

        /** 落盘的 H2 是可留存的，按产品名判定会把它一起误报。 */
        @Test
        void h2FileIsNotInMemory() {
            assertThat(AuditLogInitializer.warnIfInMemory("jdbc:h2:file:/var/lib/mcp/audit")).isFalse();
        }

        @Test
        void postgres() {
            assertThat(AuditLogInitializer.warnIfInMemory(
                    "jdbc:postgresql://audit-db:5432/mcp_audit")).isFalse();
        }

        @Test
        void oracle() {
            assertThat(AuditLogInitializer.warnIfInMemory(
                    "jdbc:oracle:thin:@audit-db:1521/AUDIT")).isFalse();
        }

        /**
         * 库名里出现 "memory" 不代表它是内存库。判据要求 {@code :memory:} 两侧的冒号，
         * 或者 {@code mem} 紧跟在驱动子协议之后。
         */
        @Test
        void aDatabaseNamedMemoryIsStillDurable() {
            assertThat(AuditLogInitializer.warnIfInMemory(
                    "jdbc:postgresql://audit-db:5432/memory_audit")).isFalse();
        }

        @Test
        void blankAndNullAreNotClaimedToBeInMemory() {
            assertThat(AuditLogInitializer.warnIfInMemory(null)).isFalse();
            assertThat(AuditLogInitializer.warnIfInMemory("   ")).isFalse();
        }
    }
}
