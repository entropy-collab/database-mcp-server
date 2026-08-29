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
    void tablesQueryInlinesTheOwnerAndBindsNothing() {
        var sql = dialect.tablesQuery("HR");
        Assertions.assertThat(sql).contains("all_tables");
        // The owner is resolved into the SQL: it used to be an `owner = ?` no caller ever filled,
        // while the computed owner was dropped on the floor.
        Assertions.assertThat(sql).contains("owner = 'HR'");
        Assertions.assertThat(placeholders(sql)).isZero();
    }

    @Test
    void tablesQueryWithoutSchemaFallsBackToTheCurrentUser() {
        var sql = dialect.tablesQuery(null);
        Assertions.assertThat(sql).contains("owner = USER");
        Assertions.assertThat(placeholders(sql)).isZero();
    }

    @Test
    void columnsQueryContainsAllTabColumns() {
        var sql = dialect.columnsQuery("EMPLOYEES", "HR");
        Assertions.assertThat(sql).contains("all_tab_columns");
        Assertions.assertThat(sql).contains("owner = 'HR'");
        Assertions.assertThat(sql).contains("table_name = ?");
        Assertions.assertThat(placeholders(sql)).isEqualTo(1);
    }

    @Test
    void columnsQueryWithoutSchemaFallsBackToTheCurrentUser() {
        var sql = dialect.columnsQuery("EMPLOYEES", null);
        Assertions.assertThat(sql).contains("owner = USER");
        Assertions.assertThat(placeholders(sql)).isEqualTo(1);
    }

    @Test
    void indexesQueryContainsAllIndColumns() {
        var sql = dialect.indexesQuery("EMPLOYEES", "HR");
        Assertions.assertThat(sql).contains("all_indexes");
        Assertions.assertThat(sql).contains("i.table_owner = 'HR'");
        Assertions.assertThat(sql).contains("i.table_name = ?");
        Assertions.assertThat(placeholders(sql)).isEqualTo(1);
    }

    /** A schema that is not a plain identifier degrades to the current user, it is never concatenated. */
    @Test
    void aNonIdentifierSchemaIsNotSplicedIntoTheSql() {
        var sql = dialect.tablesQuery("HR' OR '1'='1");
        Assertions.assertThat(sql).contains("owner = USER");
        Assertions.assertThat(sql).doesNotContain("OR '1'");
    }

    private static long placeholders(String sql) {
        return sql.chars().filter(c -> c == '?').count();
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

    @Test
    void buildUpsertSqlBindsParametersInTheMergeSource() {
        var sql = dialect.buildUpsertSql("EMPLOYEES", java.util.List.of("ID", "NAME"), java.util.List.of("ID"));

        // Placeholders must sit in the USING subquery: `SELECT ID AS ID FROM DUAL` fails with
        // ORA-00904 because DUAL has no such column.
        Assertions.assertThat(sql).contains("USING (SELECT ? AS ID, ? AS NAME FROM DUAL) source");
        Assertions.assertThat(sql).contains("ON (target.ID = source.ID)");
        Assertions.assertThat(sql).contains("UPDATE SET target.NAME = source.NAME");
        // The INSERT branch reads the already-bound source row rather than binding a second time.
        Assertions.assertThat(sql).contains("INSERT (ID, NAME) VALUES (source.ID, source.NAME)");
    }

    @Test
    void buildUpsertSqlBindsOneParameterPerColumn() {
        var sql = dialect.buildUpsertSql("T", java.util.List.of("A", "B", "C"), java.util.List.of("A"));

        Assertions.assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(3);
    }
}
