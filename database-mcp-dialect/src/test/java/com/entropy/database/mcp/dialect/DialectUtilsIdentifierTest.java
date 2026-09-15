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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DialectUtils#isPlainIdentifier(String)} 与 {@link DialectUtils#schemaExpression} 的接受集。
 *
 * <p>规则本身已下沉到 {@code SqlIdentifiers}（contract 模块），所以这里钉的是本模块自己那一层：
 * trim 仍然属于本方法的契约，以及非标识符的 schema 必须退化成方言的「当前 schema」表达式而不是
 * 拼出一个匹配不到任何行的 {@code IS NULL}。
 */
class DialectUtilsIdentifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "PUBLIC",
            "_tmp_stage",          // 下划线开头
            "V$SESSION",           // Oracle 数据字典视图
            "  ORDERS  "           // 前后空白由本方法 trim 掉，这是它与 SqlIdentifiers.isPlain 的唯一差别
    })
    void acceptsPlainIdentifiers(String name) {
        assertThat(DialectUtils.isPlainIdentifier(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "s' OR '1'='1",
            "\"quoted\"",
            "my schema",           // 内部空白不是前后空白，trim 不掉
            "s; DROP TABLE t",
            "s--comment",
            "s/*comment",
            "订单库",
            "1st_schema"
    })
    void rejectsAnythingThatCouldEscapeALiteral(String name) {
        assertThat(DialectUtils.isPlainIdentifier(name)).isFalse();
    }

    @Test
    void rejectsAnOverlongIdentifier() {
        assertThat(DialectUtils.isPlainIdentifier("a".repeat(129))).isFalse();
    }

    @Test
    void schemaExpressionQuotesAPlainNameAndDegradesOtherwise() {
        assertThat(DialectUtils.schemaExpression("  ORDERS  ", "current_schema()")).isEqualTo("'ORDERS'");
        assertThat(DialectUtils.schemaExpression("_tmp_stage", "current_schema()")).isEqualTo("'_tmp_stage'");
        assertThat(DialectUtils.schemaExpression(null, "current_schema()")).isEqualTo("current_schema()");
        assertThat(DialectUtils.schemaExpression("   ", "current_schema()")).isEqualTo("current_schema()");
        assertThat(DialectUtils.schemaExpression("s' OR '1'='1", "DATABASE()")).isEqualTo("DATABASE()");
    }
}
