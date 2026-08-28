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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.Db2Dialect;
import com.entropy.database.mcp.dialect.GenericDialect;
import com.entropy.database.mcp.dialect.H2Dialect;
import com.entropy.database.mcp.dialect.MySqlDialect;
import com.entropy.database.mcp.dialect.OracleDialect;
import com.entropy.database.mcp.dialect.PostgresDialect;
import com.entropy.database.mcp.dialect.SqlServerDialect;
import com.entropy.database.mcp.dialect.SqliteDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.facade.DatabaseOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the column discovery of {@link EtlTools#validateDataQuality}.
 *
 * <p>自动取列原来硬编码 Oracle 的 {@code user_tab_columns}，那张字典表只有 Oracle 有：在其余 7 个方言
 * 上，调用方一旦省略 {@code columns}，这个工具就直接抛 SQL 错误。这里的 fake 连接把任何提到
 * {@code user_tab_columns} 的语句当成「表不存在」拒绝掉，正是真实的非 Oracle 库会做的事，所以旧实现
 * 在下面每个非 Oracle 用例上都会失败。
 */
class EtlToolsTest {

    /** 一次 fake 查询：SQL 原文 + 展开后的绑定参数。 */
    private record Executed(String sql, List<Object> args) {}

    private static final String TABLE = "CUSTOMERS";

    private final List<Executed> executed = new ArrayList<>();

    private DatabaseOperations routingFacade;
    private EtlTools etlTools;

    static Stream<DatabaseDialect> allDialects() {
        return Stream.of(new GenericDialect(), new MySqlDialect(), new PostgresDialect(),
                new SqlServerDialect(), new Db2Dialect(), new SqliteDialect(), new H2Dialect(),
                new OracleDialect());
    }

    @BeforeEach
    void setUp() {
        routingFacade = mock(DatabaseOperations.class);
        etlTools = new EtlTools(null, routingFacade, null, null, null);
    }

    /**
     * @param columnLabel 元数据结果集里列名那一列的标签拼写，用来覆盖各库大小写不一致的情况
     */
    private void stubConnection(DatabaseDialect dialect, String columnLabel, String... columnNames) {
        when(routingFacade.getDialect(any())).thenReturn(dialect);
        String metadataSql = dialect.columnsQuery(dialect.normalizeTableName(TABLE), null);
        when(routingFacade.queryRows(anyString(), any(), any(Object[].class))).thenAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            String sql = (String) arguments[0];
            executed.add(new Executed(sql, List.of(Arrays.copyOfRange(arguments, 2, arguments.length))));
            if (sql.toLowerCase().contains("user_tab_columns")) {
                // 非 Oracle 库对这张表的反应：直接报表不存在。
                throw new McpToolException(ErrorCode.QUERY_EXECUTION_FAILED,
                        "Table or view does not exist: user_tab_columns");
            }
            if (sql.equals(metadataSql)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (String columnName : columnNames) {
                    // 普通 LinkedHashMap，不是 LinkedCaseInsensitiveMap：取值必须自己做大小写不敏感。
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(columnLabel, columnName);
                    rows.add(row);
                }
                return rows;
            }
            return List.of(Map.of("cnt", 0L));
        });
    }

    private Executed metadataQuery(DatabaseDialect dialect) {
        String metadataSql = dialect.columnsQuery(dialect.normalizeTableName(TABLE), null);
        return executed.stream().filter(e -> e.sql().equals(metadataSql)).findFirst().orElse(null);
    }

    @ParameterizedTest
    @MethodSource("allDialects")
    void readsColumnsFromTheDialectMetadataQueryWhenColumnsOmitted(DatabaseDialect dialect) {
        stubConnection(dialect, "column_name", "ID", "NAME");

        Map<String, Object> result = etlTools.validateDataQuality("conn", TABLE, null);

        assertThat(executed).extracting(Executed::sql)
                .noneMatch(sql -> sql.toLowerCase().contains("user_tab_columns"));
        assertThat(metadataQuery(dialect)).as("dialect columnsQuery was issued").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary).containsEntry("columnsChecked", 2);
        assertThat(executed).extracting(Executed::sql)
                .anyMatch(sql -> sql.contains(dialect.quote("ID") + " IS NULL"))
                .anyMatch(sql -> sql.contains(dialect.quote("NAME") + " IS NULL"));
    }

    /**
     * 绑定参数契约：单表元数据查询有且只有 1 个 {@code ?}，绑归一化后的表名，schema 从不绑。
     * 多绑一个 schema 会在 8 个方言里报参数个数不符，少绑会让 Oracle 的 owner 变 NULL 而一列都取不到。
     */
    @ParameterizedTest
    @MethodSource("allDialects")
    void bindsExactlyTheNormalizedTableNameToTheMetadataQuery(DatabaseDialect dialect) {
        stubConnection(dialect, "column_name", "ID");

        etlTools.validateDataQuality("conn", TABLE, null);

        Executed metadata = metadataQuery(dialect);
        assertThat(metadata).isNotNull();
        assertThat(metadata.args()).containsExactly(dialect.normalizeTableName(TABLE));
        assertThat(metadata.sql().chars().filter(c -> c == '?')).hasSize(1);
    }

    /** Oracle/H2/DB2/SQL Server 报 COLUMN_NAME，MySQL/PostgreSQL 报 column_name。 */
    @Test
    void readsTheColumnLabelCaseInsensitively() {
        DatabaseDialect dialect = new GenericDialect();
        stubConnection(dialect, "COLUMN_NAME", "ID", "NAME");

        Map<String, Object> result = etlTools.validateDataQuality("conn", TABLE, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary).containsEntry("columnsChecked", 2);
    }

    @Test
    void skipsTheMetadataQueryWhenColumnsAreGiven() {
        DatabaseDialect dialect = new GenericDialect();
        stubConnection(dialect, "column_name", "ID", "NAME");

        etlTools.validateDataQuality("conn", TABLE, List.of("ID"));

        assertThat(metadataQuery(dialect)).as("no column metadata lookup needed").isNull();
        assertThat(executed).extracting(Executed::sql)
                .noneMatch(sql -> sql.toLowerCase().contains("user_tab_columns"));
    }
}
