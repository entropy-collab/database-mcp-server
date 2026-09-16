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
package com.entropy.database.mcp.security;

import com.entropy.database.mcp.exception.McpSqlValidationException;
import com.entropy.database.mcp.properties.DatabaseProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉住 {@code validateDdl} 对 Oracle 分区 DDL 的接受面，逐条对应 jsqlparser 的解析能力。
 *
 * <h2>为什么这份测试值得单独存在</h2>
 * 校验器用 {@link net.sf.jsqlparser.parser.CCJSqlParserUtil} 解析后按语句类型做白名单，所以
 * 「哪些 DDL 能过闸门」不是本仓库自己决定的，而是随 {@code jsqlparser.version} 漂移。分区 DDL 正好
 * 落在这条边界上：升级 5.3 → 5.4 把 {@code PARTITION BY RANGE ... VALUES LESS THAN} 从「解析不出来
 * 所以被拒」变成了「解析成 CreateTable 所以放行」，而索引的 {@code LOCAL}/{@code GLOBAL} 两个版本
 * 都不认。
 *
 * <p>所以这里同时钉住**能过的**和**过不去的**。前者防升级回退，后者防「以为已经支持了」——下次调
 * {@code jsqlparser.version} 时，本类失败的用例就是那个版本真正带来的解析面变化，是有用的信号而不是噪声：
 * 某条 {@code rejected} 用例变红，说明该语法现在能解析了，把它挪到 {@code accepted} 即可。
 *
 * <p>测的是 {@code validateDdl} 而不是裸 parser：闸门除了解析还要过语句类型白名单和表白名单，
 * 「parser 认得」不等于「工具放行」。
 */
@DisplayName("Oracle partition DDL through validateDdl")
class OraclePartitionDdlSupportTest {

    /** 关掉表白名单，只考察语法与语句类型这两道。 */
    private static SqlValidatorImpl validator() {
        return new SqlValidatorImpl(new DatabaseProperties(
                false, null,
                new DatabaseProperties.QueryProperties(100, 30, true, 10000, 500, 100),
                null, null, null,
                new DatabaseProperties.SecurityProperties(10, 5, List.of()),
                null, null, null, null, null, null, null, null, null, null));
    }

    @Nested
    @DisplayName("accepted")
    class Accepted {

        /**
         * 建表带 RANGE 分区。5.3 上这条是被拒的，5.4 起放行——本仓库解锁按月分区表就靠这一条。
         */
        @Test
        void rangePartitionedTable() {
            assertThatCode(() -> validator().validateDdl("""
                    CREATE TABLE ALIPAY_PAY_TXN_DETAIL (
                        ID NUMBER(19) NOT NULL,
                        TXN_DATE NUMBER(6) NOT NULL
                    ) PARTITION BY RANGE (TXN_DATE) (
                        PARTITION P202606 VALUES LESS THAN (202607),
                        PARTITION P_MAX VALUES LESS THAN (MAXVALUE)
                    )"""))
                    .doesNotThrowAnyException();
        }

        /** 分区子句里带 TABLESPACE，是生产脚本的常见形状。 */
        @Test
        void rangePartitionWithPerPartitionTablespace() {
            assertThatCode(() -> validator().validateDdl(
                    "CREATE TABLE T (ID NUMBER(19), D NUMBER(6)) PARTITION BY RANGE (D) "
                            + "(PARTITION P1 VALUES LESS THAN (1) TABLESPACE TS_DATA)"))
                    .doesNotThrowAnyException();
        }

        /**
         * 分区键是 {@code VARCHAR2} 的 yyyyMMdd 字符串，边界因此是**字符串字面量**而不是数字。
         *
         * <p>单列这一条是因为它与上面的数字边界走的不是同一条解析路径，而真实迁移脚本用的正是
         * 这一种（按月分区、分区键为 {@code VARCHAR2(8 CHAR)}）。只测数字边界就断言「建表解锁了」
         * 是不成立的。列定义里的 {@code VARCHAR2(n CHAR)}、内联 {@code CONSTRAINT ... PRIMARY KEY}、
         * {@code DEFAULT SYSTIMESTAMP} 一并带上，都是同一份脚本里的真实写法。
         */
        @Test
        void stringPartitionBoundsAsInTheRealSchema() {
            assertThatCode(() -> validator().validateDdl("""
                    CREATE TABLE ALIPAY_PAY_TXN_DETAIL (
                        ID          NUMBER(22) NOT NULL,
                        ORDER_NO    VARCHAR2(128 CHAR) NOT NULL,
                        PAY_TYPE    VARCHAR2(32 CHAR) DEFAULT 'PAY' NOT NULL,
                        TXN_DATE    VARCHAR2(8 CHAR) NOT NULL,
                        UPDATE_TIME TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL,
                        CONSTRAINT PK_ALIPAY_PAY_TXN_DETAIL PRIMARY KEY (ID)
                    )
                    PARTITION BY RANGE (TXN_DATE) (
                        PARTITION P202606 VALUES LESS THAN ('20260701'),
                        PARTITION P202607 VALUES LESS THAN ('20260801'),
                        PARTITION P_MAX   VALUES LESS THAN (MAXVALUE)
                    )"""))
                    .doesNotThrowAnyException();
        }

        /** HASH 分区在 5.3 上就能过，不是这次升级带来的。 */
        @Test
        void hashPartitionedTable() {
            assertThatCode(() -> validator().validateDdl(
                    "CREATE TABLE T (ID NUMBER(19)) PARTITION BY HASH (ID) PARTITIONS 8"))
                    .doesNotThrowAnyException();
        }

        @Test
        void plainTableAndIndex() {
            assertThatCode(() -> validator().validateDdl("CREATE TABLE T (ID NUMBER(19) NOT NULL)"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validator().validateDdl("CREATE UNIQUE INDEX UK_T ON T (ID)"))
                    .doesNotThrowAnyException();
        }

        /**
         * 分区表上的本地/全局索引。解析器两个版本都不认这个尾巴，放行靠的是
         * {@link PartitionedIndexTailNormalizer} 在解析失败后把尾巴摘掉再校验一次；
         * 执行时用的仍是带尾巴的原文。
         */
        @Test
        void localAndGlobalIndex() {
            assertThatCode(() -> validator().validateDdl(
                    "CREATE UNIQUE INDEX UK_T ON T (ID, TXN_DATE) LOCAL")).doesNotThrowAnyException();
            assertThatCode(() -> validator().validateDdl(
                    "CREATE INDEX IDX_T ON T (TXN_DATE) LOCAL")).doesNotThrowAnyException();
            assertThatCode(() -> validator().validateDdl(
                    "CREATE INDEX IDX_T ON T (TXN_DATE) GLOBAL")).doesNotThrowAnyException();
            assertThatCode(() -> validator().validateDdl(
                    "CREATE BITMAP INDEX IDX_T ON T (FLAG) LOCAL")).doesNotThrowAnyException();
        }

        /** 跨行是迁移脚本的常态，尾巴前有换行也要认。 */
        @Test
        void localIndexAcrossLines() {
            assertThatCode(() -> validator().validateDdl("""
                    CREATE UNIQUE INDEX UK_ALIPAY_PAY_TXN_DETAIL
                        ON ALIPAY_PAY_TXN_DETAIL (ID, TXN_DATE)
                        LOCAL""")).doesNotThrowAnyException();
        }

        /**
         * {@code TABLESPACE} 尾巴是解析器**原生认识**的，不经过尾巴摘除。放在这里是为了记录这条
         * 边界：不是「所有索引尾巴都靠摘」，只有 LOCAL / GLOBAL 需要。
         */
        @Test
        void tablespaceTailIsParsedNativelyNotStripped() {
            assertThatCode(() -> validator().validateDdl(
                    "CREATE INDEX IDX_T ON T (C) TABLESPACE TS_DATA")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("still rejected on jsqlparser 5.4")
    class StillRejected {

        /**
         * 分区表上的本地索引。这是升到 5.4 之后**仍然**卡住的那一类：参照表若 {@code PARTITIONED=YES}，
         * 它的唯一/普通索引通常都写 {@code LOCAL}，于是建表能过、索引过不去。
         */
        @Test
        void localIndexOnANonIndexStatement() {
            // 尾巴摘除只认 CREATE ... INDEX 开头的语句，别的语句以 LOCAL 结尾一律不救。
            assertRejected("ALTER TABLE T MODIFY PARTITION P1 LOCAL");
        }

        /** LIST 分区的 {@code VALUES ('A')} / {@code VALUES (DEFAULT)} 形状仍不认。 */
        @Test
        void listPartitionedTable() {
            assertRejected("CREATE TABLE T (ID NUMBER(19), C VARCHAR2(2)) PARTITION BY LIST (C) "
                    + "(PARTITION P1 VALUES ('A'), PARTITION PD VALUES (DEFAULT))");
        }

        /** 自动按月建分区的 INTERVAL 子句仍不认。 */
        @Test
        void intervalPartitionedTable() {
            assertRejected("CREATE TABLE T (D DATE) PARTITION BY RANGE (D) "
                    + "INTERVAL (NUMTOYMINTERVAL(1,'MONTH')) "
                    + "(PARTITION P0 VALUES LESS THAN (DATE '2026-06-01'))");
        }

        /** 事后加分区仍不认，所以滚动分区维护也走不了这条路。 */
        @Test
        void alterTableAddPartition() {
            assertRejected("ALTER TABLE T ADD PARTITION P202701 VALUES LESS THAN (202702)");
        }

        private void assertRejected(String ddl) {
            assertThatThrownBy(() -> validator().validateDdl(ddl))
                    .isInstanceOf(McpSqlValidationException.class)
                    .satisfies(thrown -> assertThat(((McpSqlValidationException) thrown).getSql())
                            .isEqualTo(ddl));
        }
    }

    /**
     * 尾巴摘除这条路是在放松写入路径的闸门，所以只测「合法分区 DDL 现在能过」远远不够——
     * 那只证明了正向路径。这一组测的是它**不能**被用来做什么。
     */
    @Nested
    @DisplayName("tail stripping cannot be abused")
    class AttackSurface {

        /** 尾巴里藏第二条语句：SqlTailNormalizers 的 SAFE_TAIL 只允许字母与空白，DROP 的字母进不去。 */
        @Test
        void stackedStatementHiddenInTheTail() {
            assertRejected("CREATE INDEX IDX_T ON T (C) LOCAL; DROP TABLE USERS");
            assertRejected("CREATE INDEX IDX_T ON T (C) LOCAL;DROP TABLE USERS");
        }

        /** 注释包裹：注释起止符不在允许的字符类里。 */
        @Test
        void commentInTheTail() {
            assertRejected("CREATE INDEX IDX_T ON T (C) LOCAL /* DROP TABLE USERS */");
            assertRejected("CREATE INDEX IDX_T ON T (C) LOCAL -- rest");
        }

        /** 标点：逗号、引号、括号都不在允许的字符类里。 */
        @Test
        void punctuationInTheTail() {
            assertRejected("CREATE INDEX IDX_T ON T (C) LOCAL, GLOBAL");
            assertRejected("CREATE INDEX IDX_T ON T (C) LOCAL 'x'");
        }

        /**
         * 摘掉尾巴之后走的仍是完整校验管线——表白名单照旧生效，不会因为「这条是靠兜底救回来的」
         * 就跳过后续检查。
         */
        @Test
        void tableWhitelistStillAppliesToTheStrippedStatement() {
            SqlValidatorImpl restricted = new SqlValidatorImpl(new DatabaseProperties(
                    false, null,
                    new DatabaseProperties.QueryProperties(100, 30, true, 10000, 500, 100),
                    null, null, null,
                    new DatabaseProperties.SecurityProperties(10, 5, List.of("T")),
                    null, null, null, null, null, null, null, null, null, null));

            assertThatCode(() -> restricted.validateDdl("CREATE INDEX IDX_T ON T (C) LOCAL"))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> restricted.validateDdl("CREATE INDEX IDX_S ON SECRETS (C) LOCAL"))
                    .isInstanceOf(McpSqlValidationException.class)
                    .hasMessageContaining("Tables not allowed");
        }

        /** 只读路径不走这条缝：validateSelect 上的解析失败照旧直接拒。 */
        @Test
        void selectPathNeverStripsTails() {
            assertThatThrownBy(() -> validator().validateSelect(
                    "CREATE INDEX IDX_T ON T (C) LOCAL"))
                    .isInstanceOf(McpSqlValidationException.class);
        }

        private void assertRejected(String ddl) {
            assertThatThrownBy(() -> validator().validateDdl(ddl))
                    .isInstanceOf(McpSqlValidationException.class);
        }
    }
}
