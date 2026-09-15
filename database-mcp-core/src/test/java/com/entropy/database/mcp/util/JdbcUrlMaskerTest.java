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
package com.entropy.database.mcp.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JdbcUrlMasker} 的边界。
 *
 * <p>这不是普通的字符串工具测试：它的输出经 {@code ConnectionMetadata.jdbcUrlMasked} 直接回给 MCP
 * 调用方（{@code listConnections} / {@code describeConnection} / {@code getPoolStats}），也就是进模型
 * 上下文、再进客户端保存的对话历史。漏一个形态就是一次不可撤回的凭证泄漏。
 */
class JdbcUrlMaskerTest {

    /** 0.4.0 之前 {@code DynamicDataSourceManagerImpl.maskKey} 的两个绕过口子。 */
    @Nested
    class RegressionsFromTheOldMasker {

        /**
         * 旧实现「没有 {@code @} 就原样返回」，于是参数里的密码被整段回显。
         * {@code ByokProperties.UrlGuard} 的黑名单只拦代码执行类参数，不拦 password，这种 URL 能注册进来。
         */
        @Test
        void redactsPasswordCarriedAsAQueryParameter() {
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://host:3306/db?password=s3cret"))
                    .doesNotContain("s3cret")
                    .isEqualTo("jdbc:mysql://host:3306/db?…");
        }

        @Test
        void redactsPasswordCarriedAsASemicolonParameter() {
            assertThat(JdbcUrlMasker.mask("jdbc:sqlserver://host:1433;password=s3cret"))
                    .doesNotContain("s3cret");
        }

        /** 大小写、别名写法都不能漏——这正是黑名单方案会漏的形状，所以这里走白名单。 */
        @Test
        void redactsCredentialParametersRegardlessOfSpelling() {
            assertThat(JdbcUrlMasker.mask("jdbc:sqlserver://host:1433;PWD=s3cret")).doesNotContain("s3cret");
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://host/db?passwd=s3cret")).doesNotContain("s3cret");
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://host/db?trustStorePassword=s3cret"))
                    .doesNotContain("s3cret");
            assertThat(JdbcUrlMasker.mask("jdbc:foo://host/db?accessToken=s3cret")).doesNotContain("s3cret");
        }

        /**
         * 旧实现在 Oracle 的 {@code user/pw@//host} 形态下，{@code indexOf("//")} 落在
         * {@code indexOf('@')} 之后，拼出的前缀把明文原样带上，产出
         * {@code jdbc:oracle:thin:app/s3cret@//****@//host:1521/svc}。
         */
        @Test
        void redactsOracleStyleUserInfoBeforeTheDoubleSlash() {
            assertThat(JdbcUrlMasker.mask("jdbc:oracle:thin:app/s3cret@//host:1521/svc"))
                    .doesNotContain("s3cret")
                    .isEqualTo("jdbc:oracle:thin:****@//host:1521/svc");
        }
    }

    @Nested
    class UserInfo {

        @Test
        void redactsUserAndPasswordBeforeTheHost() {
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://app:s3cret@127.0.0.1:3306/db"))
                    .isEqualTo("jdbc:mysql://****@127.0.0.1:3306/db");
        }

        /**
         * {@code jdbc:oracle:thin:@host} 是最常见的 Oracle 写法，userinfo 为空。
         * 凭空加一个 {@code ****} 会让读日志的人以为 URL 里藏了凭证。
         */
        @Test
        void leavesAnEmptyUserInfoSegmentAlone() {
            assertThat(JdbcUrlMasker.mask("jdbc:oracle:thin:@host:1521/svc"))
                    .isEqualTo("jdbc:oracle:thin:@host:1521/svc");
        }

        /** 密码本身含 {@code @} 时，必须以最后一个 {@code @} 为界，否则会留下一截明文。 */
        @Test
        void usesTheLastAtSignSoPasswordsContainingAtAreFullyRedacted() {
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://app:p@ss@127.0.0.1:3306/db"))
                    .doesNotContain("p@ss")
                    .isEqualTo("jdbc:mysql://****@127.0.0.1:3306/db");
        }

        @Test
        void leavesUrlsWithoutUserInfoUnchanged() {
            assertThat(JdbcUrlMasker.mask("jdbc:postgresql://host:5432/db"))
                    .isEqualTo("jdbc:postgresql://host:5432/db");
        }
    }

    /**
     * 口令里含参数分隔符时的第三个口子（0.4.x）：当时是「先按第一个 {@code ?} / {@code ;} 切、再掩
     * userinfo」，于是 {@code //app:p?w@host/db} 的 base 段被切成 {@code jdbc:mysql://app:p}——不含
     * {@code @}，掩码原样放过，口令前缀被回显。现在顺序反过来：先定位 userinfo 终点再切参数。
     */
    @Nested
    class PasswordContainingParameterDelimiters {

        @Test
        void doesNotLeakAPasswordPrefixWhenThePasswordContainsAQuestionMark() {
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://app:p?w@host/db"))
                    .doesNotContain("p?w")
                    .doesNotContain("app:p")
                    .isEqualTo("jdbc:mysql://****@host/db");
        }

        @Test
        void keepsWhitelistingParametersWhenThePasswordContainsAQuestionMark() {
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://app:p?w@host/db?password=x&useSSL=true"))
                    .doesNotContain("p?w")
                    .doesNotContain("password=x")
                    .isEqualTo("jdbc:mysql://****@host/db?useSSL=true&…");
        }

        @Test
        void doesNotLeakAPasswordPrefixWhenThePasswordContainsASemicolon() {
            assertThat(JdbcUrlMasker.mask("jdbc:sqlserver://app:p;w@host:1433;databaseName=orders"))
                    .doesNotContain("p;w")
                    .doesNotContain("app:p")
                    .isEqualTo("jdbc:sqlserver://****@host:1433;databaseName=orders");
        }

        /** {@code #} 与 {@code &} 不是分隔符，但口令里出现它们同样不能留下任何片段。 */
        @Test
        void redactsPasswordsContainingHashOrAmpersand() {
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://app:p#w@host/db"))
                    .doesNotContain("p#w")
                    .isEqualTo("jdbc:mysql://****@host/db");
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://app:p&w@host/db"))
                    .doesNotContain("p&w")
                    .isEqualTo("jdbc:mysql://****@host/db");
        }

        /**
         * 最坏形态：口令同时含 {@code ?} 与 {@code =}，userinfo 判定会把它误认成 {@code key=value} 参数。
         * 此时兜底逻辑按「疑似凭证」把 authority 整段掩掉——宁可丢主机名，也不回显口令片段。
         */
        @Test
        void fallsBackToRedactingTheWholeAuthorityWhenThePasswordAlsoContainsAnEquals() {
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://app:p?w=x@host/db"))
                    .doesNotContain("p?w=x")
                    .doesNotContain("app:p");
        }

        /**
         * 反向约束：{@code @} 落在参数值里（Azure SQL 的 {@code user=me@srv} 是标准写法）时不能把主机名
         * 一起掩掉——主机名是 {@code listConnections} 唯一的辨识信息。
         */
        @Test
        void keepsTheHostWhenTheAtSignBelongsToAParameterValue() {
            assertThat(JdbcUrlMasker.mask(
                    "jdbc:sqlserver://srv.database.windows.net:1433;database=db;user=me@srv;password=x"))
                    .doesNotContain("password=x")
                    .isEqualTo("jdbc:sqlserver://srv.database.windows.net:1433;database=db;…");
        }

        /** 真 userinfo 与「参数里的 @」混用时，掩码要落在前者上。 */
        @Test
        void picksTheRealUserInfoWhenAParameterAlsoContainsAnAtSign() {
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://app:pw@host/db?user=a@b.com"))
                    .isEqualTo("jdbc:mysql://****@host/db?…");
        }
    }

    @Nested
    class DisplaySafeParameters {

        /** 运维要靠这些参数分辨「这个连接连的是哪个库」，全砍掉会让 listConnections 变得没法用。 */
        @Test
        void keepsParametersThatIdentifyTheTarget() {
            assertThat(JdbcUrlMasker.mask("jdbc:sqlserver://host:1433;databaseName=orders"))
                    .isEqualTo("jdbc:sqlserver://host:1433;databaseName=orders");
            assertThat(JdbcUrlMasker.mask("jdbc:postgresql://host/db?currentSchema=sales"))
                    .isEqualTo("jdbc:postgresql://host/db?currentSchema=sales");
        }

        /** 保留的和摘掉的混在一起时，两者都要体现，读者才知道「还有参数被摘了」。 */
        @Test
        void keepsSafeParametersAndMarksThatOthersWereDropped() {
            String masked = JdbcUrlMasker.mask(
                    "jdbc:sqlserver://host:1433;databaseName=orders;password=s3cret");
            assertThat(masked)
                    .doesNotContain("s3cret")
                    .contains("databaseName=orders")
                    .endsWith(";…");
        }

        @Test
        void combinesUserInfoRedactionWithParameterFiltering() {
            assertThat(JdbcUrlMasker.mask("jdbc:mysql://app:s3cret@host/db?password=other&useSSL=true"))
                    .doesNotContain("s3cret")
                    .doesNotContain("other")
                    .isEqualTo("jdbc:mysql://****@host/db?useSSL=true&…");
        }
    }

    @Nested
    class Degenerate {

        /** 返回字面量 "null" 而不是 null，调用方都在直接拼日志。 */
        @Test
        void rendersNullAsALiteral() {
            assertThat(JdbcUrlMasker.mask(null)).isEqualTo("null");
        }

        @Test
        void leavesBlankInputAlone() {
            assertThat(JdbcUrlMasker.mask("")).isEmpty();
            assertThat(JdbcUrlMasker.mask("   ")).isEqualTo("   ");
        }

        /** 非 JDBC 形态也不能抛异常：这条路径在异常处理里被调用，二次抛出会掩盖原始错误。 */
        @Test
        void toleratesInputThatIsNotAJdbcUrl() {
            assertThat(JdbcUrlMasker.mask("external")).isEqualTo("external");
            assertThat(JdbcUrlMasker.mask("@")).isEqualTo("@");
            assertThat(JdbcUrlMasker.mask("?")).isEqualTo("?");
            assertThat(JdbcUrlMasker.mask(";;;")).isEqualTo(";;;");
        }
    }
}
