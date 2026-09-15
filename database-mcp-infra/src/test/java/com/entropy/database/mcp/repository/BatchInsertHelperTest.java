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

import com.entropy.database.mcp.exception.McpValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BatchInsertHelper#buildInsertSql} 的标识符闸门。
 *
 * <p>表名与列名在这里是<b>字面拼进 SQL</b> 的（只有行值走绑定参数），所以这是 BYOK 写路径上
 * 最后一道校验。规则已委派给 {@code SqlIdentifiers}，本类验证委派之后的接受集与
 * {@code ValidationUtils.validateIdentifier} / {@code DialectUtils.isPlainIdentifier} 一致，
 * 并且表名与列名仍然分别报错——两者的修法不同。
 */
class BatchInsertHelperTest {

    @Test
    void buildsAParameterizedInsert() {
        assertThat(BatchInsertHelper.buildInsertSql("ORDERS", List.of("ID", "AMOUNT")))
                .isEqualTo("INSERT INTO ORDERS (ID, AMOUNT) VALUES (?, ?)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "_tmp_stage",          // 下划线开头：三处委派后一致放行
            "V$SESSION",           // Oracle 数据字典视图
            "TBL$TMP#1"
    })
    void acceptsTheSameIdentifiersAsTheOtherEntryPoints(String table) {
        assertThat(BatchInsertHelper.buildInsertSql(table, List.of("ID")))
                .isEqualTo("INSERT INTO " + table + " (ID) VALUES (?)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "t; DROP TABLE x",
            "t' OR '1'='1",
            "\"quoted\"",
            "my table",
            "t--comment",
            "t/*comment",
            "订单表",
            "1st_table"
    })
    void rejectsANonPlainTableName(String table) {
        assertThatThrownBy(() -> BatchInsertHelper.buildInsertSql(table, List.of("ID")))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("Invalid table name");
    }

    @Test
    void rejectsAnOverlongTableName() {
        assertThatThrownBy(() -> BatchInsertHelper.buildInsertSql("a".repeat(129), List.of("ID")))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("Invalid table name");
    }

    @Test
    void rejectsABlankTableName() {
        assertThatThrownBy(() -> BatchInsertHelper.buildInsertSql(null, List.of("ID")))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("Invalid table name");
    }

    @Test
    void namesTheOffendingColumn() {
        assertThatThrownBy(() -> BatchInsertHelper.buildInsertSql("ORDERS", List.of("ID", "amount; DROP TABLE x")))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("Invalid column name: amount; DROP TABLE x");
    }
}
