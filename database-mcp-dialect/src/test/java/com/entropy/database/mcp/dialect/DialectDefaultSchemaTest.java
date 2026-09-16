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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住每个方言「调用方省略 schema 时到底搜哪个 schema」的答案。
 *
 * <p>为什么需要这一套：{@code describeTable} 的参数描述曾写着「省略时默认 PUBLIC」。那个默认值在代码里
 * 从来不存在（元数据 SQL 一直是按方言兜到会话当前 schema 的），但它一旦被谁当真实现出来，在 Oracle 上
 * 就是错的——{@code PUBLIC} 是角色不是 schema，{@code all_tab_columns.owner = 'PUBLIC'} 一行都匹配不上，
 * 于是一张存在的表会被报成「不存在」。一个错的 schema 默认值比诚实的失败更糟，所以这些值必须是被断言的
 * 契约，而不是散在各方言私有方法里的字面量。
 *
 * <p>断言分两层，第二层才是真正的证据：先钉 {@link DatabaseDialect#currentSchemaExpression()} 的取值，
 * 再钉<strong>它真的出现在该方言发出的元数据 SQL 里</strong>。只测第一层的话，把钩子接错线（比如
 * {@code columnsQuery} 还在用旧的硬编码字面量）测试照样全绿。
 */
class DialectDefaultSchemaTest {

    /** 每个方言的默认 schema 表达式，以及它在真实 SQL 里的样子。 */
    private static Stream<Arguments> defaultSchemaExpressions() {
        return Stream.of(
                Arguments.of(new OracleDialect(), "USER"),
                Arguments.of(new PostgresDialect(), "current_schema()"),
                Arguments.of(new MySqlDialect(), "DATABASE()"),
                Arguments.of(new SqlServerDialect(), "SCHEMA_NAME()"),
                Arguments.of(new Db2Dialect(), "CURRENT SCHEMA"),
                Arguments.of(new H2Dialect(), "CURRENT_SCHEMA"),
                Arguments.of(new GenericDialect(), "CURRENT_SCHEMA"));
    }

    /** {@link SqliteDialect} 单独列出：它没有 schema 概念，正确答案是 null。 */
    private static Stream<DatabaseDialect> schemaAwareDialects() {
        return defaultSchemaExpressions().map(args -> (DatabaseDialect) args.get()[0]);
    }

    @Nested
    @DisplayName("每个方言的默认 schema")
    class DefaultSchema {

        @ParameterizedTest(name = "{0} 省略 schema 时搜 {1}")
        @MethodSource("com.entropy.database.mcp.dialect.DialectDefaultSchemaTest#defaultSchemaExpressions")
        void currentSchemaExpressionIsPinned(DatabaseDialect dialect, String expression) {
            assertThat(dialect.currentSchemaExpression()).isEqualTo(expression);
        }

        /**
         * 证据层：表达式必须真的出现在该方言的三条单表元数据 SQL 里。这一条挂掉意味着钩子没接上线，
         * 契约值与实际行为已经分叉。
         */
        @ParameterizedTest(name = "{0} 的元数据 SQL 里真的用了 {1}")
        @MethodSource("com.entropy.database.mcp.dialect.DialectDefaultSchemaTest#defaultSchemaExpressions")
        void metadataQueriesUseThatExpressionWhenNoSchemaIsNamed(DatabaseDialect dialect, String expression) {
            assertThat(dialect.columnsQuery("CUSTOMER", null)).contains(expression);
            assertThat(dialect.indexesQuery("CUSTOMER", null)).contains(expression);
            assertThat(dialect.tablesQuery(null)).contains(expression);
        }

        /**
         * Oracle 的正确答案是登录用户，绝不能是 {@code PUBLIC}——这就是线上那次 describeTable 的病灶，
         * 单独钉一条，免得将来有人「顺手」把参数描述里的 PUBLIC 实现出来。
         */
        @Test
        @DisplayName("Oracle 省略 schema 时搜登录用户，而不是 PUBLIC")
        void oracleDefaultsToTheConnectingUserNotPublic() {
            OracleDialect oracle = new OracleDialect();

            assertThat(oracle.currentSchemaExpression()).isEqualTo("USER");
            assertThat(oracle.columnsQuery("ALIPAY_PAY_TXN_DETAIL", null))
                    .contains("owner = USER")
                    .doesNotContain("PUBLIC");
        }

        @Test
        @DisplayName("SQLite 没有 schema 概念，如实返回 null 而不是编一个名字")
        void sqliteHasNoSchemaConcept() {
            SqliteDialect sqlite = new SqliteDialect();

            assertThat(sqlite.currentSchemaExpression()).isNull();
            assertThat(sqlite.currentSchemaQuery()).isNull();
            assertThat(sqlite.supportsSchema()).isFalse();
            // 元数据查询里根本没有 schema 谓词，所以「搜哪个 schema」这个问题对它无意义。
            assertThat(sqlite.columnsQuery("CUSTOMER", "HR")).doesNotContain("HR");
        }
    }

    @Nested
    @DisplayName("currentSchemaQuery：把表达式解析成真实的 schema 名")
    class CurrentSchemaQuery {

        @ParameterizedTest(name = "{0} 的查询里带着自己的表达式")
        @MethodSource("com.entropy.database.mcp.dialect.DialectDefaultSchemaTest#schemaAwareDialects")
        void queryEmbedsTheExpression(DatabaseDialect dialect) {
            assertThat(dialect.currentSchemaQuery())
                    .isNotNull()
                    .contains(dialect.currentSchemaExpression())
                    .containsIgnoringCase("select");
        }

        /** Oracle 与 DB2 的 SELECT 必须带 FROM，缺了会在真库上直接语法错。 */
        @Test
        @DisplayName("Oracle 走 DUAL，DB2 走 SYSIBM.SYSDUMMY1")
        void dialectsThatNeedAFromClauseHaveOne() {
            assertThat(new OracleDialect().currentSchemaQuery()).contains("FROM DUAL");
            assertThat(new Db2Dialect().currentSchemaQuery()).contains("FROM SYSIBM.SYSDUMMY1");
        }

        /**
         * 唯一能在本地跑真库的方言，所以这条是真正的语法验证而不是文本断言。
         *
         * <p>它一开始就抓到一个 bug：默认实现本来带 {@code AS current_schema} 别名，而
         * {@code CURRENT_SCHEMA} 是 H2 2.x 的保留字，H2 报 {@code expected "identifier"}——查询整条失败，
         * 于是结果里的 schema 退化成占位串。别名已经去掉：调用方按第一列读值，本来就不需要它。
         */
        @Test
        @DisplayName("H2 上真的能执行，并返回 PUBLIC")
        void h2ActuallyExecutesIt() {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:defaultschema;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            assertThat(jdbc.queryForObject(new H2Dialect().currentSchemaQuery(), String.class))
                    .isEqualTo("PUBLIC");
        }
    }

    @Nested
    @DisplayName("resolveSchema：调用方传了 schema 时的归一化")
    class ResolveSchema {

        @ParameterizedTest(name = "{0} 把没给的 schema 归一成 null")
        @MethodSource("com.entropy.database.mcp.dialect.DialectDefaultSchemaTest#schemaAwareDialects")
        void unusableSchemaResolvesToNull(DatabaseDialect dialect) {
            assertThat(dialect.resolveSchema(null)).isNull();
            assertThat(dialect.resolveSchema("")).isNull();
            assertThat(dialect.resolveSchema("   ")).isNull();
            // 会被拼进 SQL，所以不是纯标识符的一律拒收而不是转义。
            assertThat(dialect.resolveSchema("HR'; DROP TABLE T --")).isNull();
        }

        @Test
        @DisplayName("Oracle 与 DB2 转大写，其余方言原样保留")
        void caseFoldingFollowsTheCatalog() {
            assertThat(new OracleDialect().resolveSchema("qditp")).isEqualTo("QDITP");
            assertThat(new Db2Dialect().resolveSchema("qditp")).isEqualTo("QDITP");

            assertThat(new PostgresDialect().resolveSchema("sales")).isEqualTo("sales");
            assertThat(new MySqlDialect().resolveSchema("sales")).isEqualTo("sales");
            assertThat(new SqlServerDialect().resolveSchema("sales")).isEqualTo("sales");
            assertThat(new H2Dialect().resolveSchema("sales")).isEqualTo("sales");
        }

        @ParameterizedTest(name = "{0} 会 trim")
        @MethodSource("com.entropy.database.mcp.dialect.DialectDefaultSchemaTest#schemaAwareDialects")
        void surroundingWhitespaceIsTrimmed(DatabaseDialect dialect) {
            assertThat(dialect.resolveSchema("  hr  ")).isEqualTo(dialect.resolveSchema("hr"));
        }

        /**
         * 归一化的结果必须与真正拼进 SQL 的那个值一致：Oracle 上传 {@code qditp} 时
         * SQL 里是 {@code 'QDITP'}，结果里回填的也必须是 {@code QDITP}，否则报错信息会自信地写错。
         */
        @ParameterizedTest(name = "{0} 拼进 SQL 的就是 resolveSchema 的结果")
        @MethodSource("com.entropy.database.mcp.dialect.DialectDefaultSchemaTest#schemaAwareDialects")
        void resolvedSchemaIsWhatReachesTheSql(DatabaseDialect dialect) {
            String requested = "hr";
            String resolved = dialect.resolveSchema(requested);

            assertThat(dialect.columnsQuery("CUSTOMER", requested))
                    .contains("'" + resolved + "'")
                    .doesNotContain(dialect.currentSchemaExpression());
        }
    }
}
