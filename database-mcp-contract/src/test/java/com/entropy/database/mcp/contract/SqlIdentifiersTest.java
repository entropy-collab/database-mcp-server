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
package com.entropy.database.mcp.contract;

import com.entropy.database.mcp.exception.McpValidationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SqlIdentifiers}, the single source of truth for "bare identifier".
 *
 * <p>这个类现在是 {@code ValidationUtils.validateIdentifier}、{@code DialectUtils.isPlainIdentifier}
 * 与 {@code BatchInsertHelper} 共同的判定入口，所以这里钉的不只是「拒掉注入」，还包括接受集的边界
 * （下划线开头、Oracle 的 {@code $#}、长度上限）——三处委派之后，任何一条边界的漂移都会同时
 * 改变三个入口的行为。
 */
class SqlIdentifiersTest {

    @Nested
    class Accepts {

        @ParameterizedTest
        @ValueSource(strings = {
                "TBL_STL_TXN",
                "t",
                "_tmp_stage",          // 下划线开头：统一前只有 ValidationUtils 拒它
                "V$SESSION",           // Oracle 数据字典视图，$ 是合法字符
                "SYS#",
                "TBL$TMP#1",
                "Order2026"
        })
        void plainIdentifiers(String name) {
            assertThat(SqlIdentifiers.isPlain(name)).isTrue();
            assertThatCode(() -> SqlIdentifiers.requirePlain(name, "tableName"))
                    .doesNotThrowAnyException();
        }

        @Test
        void exactlyTheLengthLimit() {
            assertThat(SqlIdentifiers.isPlain("a".repeat(SqlIdentifiers.MAX_LENGTH))).isTrue();
        }
    }

    @Nested
    class Rejects {

        @ParameterizedTest
        @ValueSource(strings = {
                "t; DROP TABLE x",     // 语句分隔
                "t' OR '1'='1",        // 闭合字面量
                "\"quoted\"",          // 带引号标识符：本服务从不生成，见类注释
                "`backtick`",
                "[bracketed]",
                "my table",            // 空格
                "t--comment",          // 行注释
                "t/*comment",          // 块注释
                "t\\x",                // 反斜杠
                "订单表",              // 非 ASCII
                "1st_table",           // 数字开头
                "$dollar_first",       // $ 只能出现在首字符之后
                "#hash_first",
                "tbl.col",             // 点号：schema 限定名要分开校验，不能整段放行
                "tbl-name"
        })
        void anythingThatCouldEscapeALiteral(String name) {
            assertThat(SqlIdentifiers.isPlain(name)).isFalse();
            assertThatThrownBy(() -> SqlIdentifiers.requirePlain(name, "tableName"))
                    .isInstanceOf(McpValidationException.class)
                    .hasMessageContaining("tableName");
        }

        @Test
        void surroundingWhitespaceIsNotTrimmedAway() {
            // 刻意不 trim：要接受带空白的输入必须由调用方自己 trim（DialectUtils 就是这么做的），
            // 否则「拼进 SQL 的到底是哪个字符串」在调用点看不见。
            assertThat(SqlIdentifiers.isPlain(" TBL ")).isFalse();
        }

        @Test
        void tooLong() {
            String name = "a".repeat(SqlIdentifiers.MAX_LENGTH + 1);
            assertThat(SqlIdentifiers.isPlain(name)).isFalse();
            assertThatThrownBy(() -> SqlIdentifiers.requirePlain(name, "tableName"))
                    .isInstanceOf(McpValidationException.class)
                    .hasMessageContaining(String.valueOf(SqlIdentifiers.MAX_LENGTH));
        }

        @Test
        void blankIsNamedSeparatelyFromInvalidCharacters() {
            // 空与非法字符的修法完全不同，合成一条信息会让调用方只能靠猜
            assertThat(SqlIdentifiers.isPlain(null)).isFalse();
            assertThatThrownBy(() -> SqlIdentifiers.requirePlain(null, "tableName"))
                    .isInstanceOf(McpValidationException.class)
                    .hasMessageContaining("cannot be blank");
            assertThatThrownBy(() -> SqlIdentifiers.requirePlain("   ", "tableName"))
                    .hasMessageContaining("cannot be blank");
        }
    }
}
