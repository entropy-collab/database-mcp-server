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

public class SqliteDialect extends AbstractDatabaseDialect {

    /**
     * Quotes an identifier, escaping any embedded double quote by doubling it so that a
     * delimiter inside {@code name} can never terminate the identifier context.
     */
    @Override
    public String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** SQLite has no schemas, so the {@code schema} argument has nothing to resolve. */
    @Override
    public String tablesQuery(String schema) {
        return """
            SELECT name AS table_name, 0 AS row_count
            FROM sqlite_master
            WHERE type = 'table'
              AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """;
    }

    /**
     * SQLite 没有 schema 概念，所以「省略 schema 时搜哪个 schema」这个问题无解，返回 {@code null}。
     *
     * <p>{@code null} 是诚实的答案而不是缺失的实现：本方言的元数据查询里根本没有 schema 谓词
     * （见上面 {@link #tablesQuery(String)} 与 {@link #columnsQuery(String, String)}），
     * {@code main} / {@code temp} 那套 ATTACH 名字与其他库的 schema 不是一回事，硬报一个名字
     * 会让调用方以为自己搜的是某个具体 schema。{@link #currentSchemaQuery()} 因此也是 {@code null}。
     */
    @Override
    public String currentSchemaExpression() {
        return null;
    }


    /**
     * The table-valued {@code pragma_table_info} form rather than {@code PRAGMA table_info(...)}: a
     * PRAGMA statement cannot take a bind parameter, so the table name had to be concatenated and the
     * query carried no placeholder at all - the one shape callers cannot bind for. The column labels
     * are aliased to the names the other dialects use, since callers read them by name.
     */
    @Override
    public String columnsQuery(String table, String schema) {
        return """
            SELECT name AS column_name,
                   type AS data_type,
                   CASE WHEN "notnull" = 1 THEN 'NO' ELSE 'YES' END AS is_nullable
            FROM pragma_table_info(?)
            ORDER BY cid
            """;
    }

    /** Same reasoning as {@link #columnsQuery(String, String)}: the bindable table-valued form. */
    @Override
    public String indexesQuery(String table, String schema) {
        return """
            SELECT name AS index_name,
                   CASE WHEN "unique" = 1 THEN 0 ELSE 1 END AS non_unique,
                   NULL AS column_name,
                   seq AS seq_in_index
            FROM pragma_index_list(?)
            ORDER BY name
            """;
    }

    @Override
    public String applyLimit(String sql, int limit, int offset) {
        if (offset <= 0) {
            return sql + " LIMIT " + limit;
        }
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public boolean supportsSchema() {
        return false;
    }

    @Override
    public boolean supportsLimit() {
        return true;
    }

    @Override
    public String schemasQuery() {
        // SQLite does not have schemas; return empty result set
        return "SELECT 'main' AS schema_name WHERE 1 = 0";
    }

    @Override
    public String viewsQuery(String schema) {
        return """
            SELECT name AS name, sql AS definition
            FROM sqlite_master
            WHERE type = 'view'
            ORDER BY name
            """;
    }

    @Override
    public String sequencesQuery(String schema) {
        // SQLite does not support sequences; return empty result set
        return """
            SELECT '' AS name, '' AS minimum_value, '' AS maximum_value, '' AS increment, 0 AS cache_size
            WHERE 1 = 0
            """;
    }

    @Override
    public String getTableRowCountSql(String schema, String tableName) {
        return "SELECT COUNT(*) AS row_count FROM " + quote(tableName);
    }

    @Override
    public String getHealthCheckSql() {
        return "SELECT 'OK' AS status";
    }

    /** SQLite 的字符串字面量里反斜杠是普通字符；显式声明以免被当成未表态而拒收含反斜杠的行。 */
    @Override
    public BackslashInLiteral backslashInLiteral() {
        return BackslashInLiteral.LITERAL;
    }
}
