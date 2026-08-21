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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class OracleDialectTest {

    private final OracleDialect dialect = new OracleDialect();

    @Test
    void tablesQueryContainsAllTables() {
        var sql = dialect.tablesQuery("HR");
        Assertions.assertThat(sql).contains("all_tables");
        Assertions.assertThat(sql).contains("owner = ?");
    }

    @Test
    void columnsQueryContainsAllTabColumns() {
        var sql = dialect.columnsQuery("EMPLOYEES", "HR");
        Assertions.assertThat(sql).contains("all_tab_columns");
        Assertions.assertThat(sql).contains("owner = ?");
        Assertions.assertThat(sql).contains("table_name = ?");
    }

    @Test
    void indexesQueryContainsAllIndColumns() {
        var sql = dialect.indexesQuery("EMPLOYEES", "HR");
        Assertions.assertThat(sql).contains("all_indexes");
        Assertions.assertThat(sql).contains("table_owner = ?");
        Assertions.assertThat(sql).contains("table_name = ?");
    }

    @Test
    void applyLimitAddsRownumFilter() {
        var sql = dialect.applyLimit("SELECT * FROM users", 10, 0);
        Assertions.assertThat(sql).contains("ROWNUM");
        Assertions.assertThat(sql).contains("10");
    }

    @Test
    void applyLimitWrapsQueryWhenOffsetIsNonZero() {
        var sql = dialect.applyLimit("SELECT * FROM users", 10, 5);
        Assertions.assertThat(sql).contains("ROWNUM");
        Assertions.assertThat(sql).contains("15");
        Assertions.assertThat(sql).contains("5");
    }
}
