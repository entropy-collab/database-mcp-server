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

import com.entropy.database.mcp.dialect.OracleDialect;
import com.entropy.database.mcp.security.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Oracle 取执行计划的两步协议。
 *
 * <p>为什么值得单独钉住：Oracle 的 {@code EXPLAIN PLAN FOR} 不返回结果集，而是往
 * {@code SYS.PLAN_TABLE$} 写行——那是<b>会话级</b>临时表（长春 19c 实测
 * {@code TEMPORARY=Y / DURATION=SYS$SESSION}）。所以"写计划"和"读计划"必须落在同一条物理连接上；
 * 各拿一次连接的写法在池上是空结果，而且空得很随机（同一条连接被复用时偶尔又是对的）。
 * 0.5.0 线上就撞到过这个：{@code analyzeQuery} 的 planRows 恒为空。
 */
class ExecutionPlanRepositoryOracleTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement explainStatement;
    private PreparedStatement fetchStatement;
    private PreparedStatement cleanupStatement;
    private ExecutionPlanRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        explainStatement = mock(Statement.class);
        fetchStatement = mock(PreparedStatement.class);
        cleanupStatement = mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(explainStatement);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.startsWith("DELETE") ? cleanupStatement : fetchStatement;
        });
        // 先把 ResultSet 桩全部立好再喂给 executeQuery：在 when(...) 的实参里做嵌套打桩会被 Mockito
        // 判成 UnfinishedStubbingException。
        ResultSet plan = singleRowPlan();
        when(fetchStatement.executeQuery()).thenReturn(plan);

        SqlValidator validator = mock(SqlValidator.class);
        repository = new ExecutionPlanRepositoryImpl(new JdbcTemplate(dataSource), new OracleDialect(), validator);
    }

    @Test
    @DisplayName("EXPLAIN 与读回计划跑在同一条物理连接上")
    void explainAndFetchShareOneConnection() throws Exception {
        List<Map<String, Object>> rows = repository.explainPlanRows("SELECT 1 FROM dual");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("OPERATION", "TABLE ACCESS").containsEntry("ID", 0);
        // 一次 getConnection：两条语句共用一条连接，会话级临时表才看得到自己刚写的行。
        verify(dataSource, times(1)).getConnection();
    }

    @Test
    @DisplayName("用 STATEMENT_ID 隔开本次计划，并在读完后清掉临时表里的行")
    void bindsStatementIdAndCleansUp() throws Exception {
        repository.explainPlanRows("SELECT 1 FROM dual");

        ArgumentCaptor<String> explainSql = ArgumentCaptor.forClass(String.class);
        verify(explainStatement).execute(explainSql.capture());
        assertThat(explainSql.getValue())
                .startsWith("EXPLAIN PLAN SET STATEMENT_ID = 'mcp_query_")
                .endsWith("' FOR SELECT 1 FROM dual");

        ArgumentCaptor<String> boundId = ArgumentCaptor.forClass(String.class);
        verify(fetchStatement).setString(eq(1), boundId.capture());
        assertThat(explainSql.getValue()).contains("'" + boundId.getValue() + "'");

        // 连接会被池复用，临时表的行按会话保留，不删就一直堆。
        verify(cleanupStatement).executeUpdate();
    }

    private ResultSet singleRowPlan() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("ID");
        when(meta.getColumnLabel(2)).thenReturn("OPERATION");
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(0);
        when(rs.getObject(2)).thenReturn("TABLE ACCESS");
        return rs;
    }

    /**
     * 分支由方言能力决定，不再由类名决定。
     *
     * <p>这条用例就是"类名判断"的反例：{@code OracleDialect} 不是 final，一个子类（同理还有 CGLIB 代理、
     * 将来的 {@code Oracle23Dialect}）在旧写法下 {@code getSimpleName()} 不等于 "OracleDialect"，于是
     * 静默走单步 EXPLAIN——那在 Oracle 上恒返回空计划。
     */
    @Test
    @DisplayName("OracleDialect 的子类仍然走两步 EXPLAIN")
    void aSubclassOfOracleDialectStillTakesTheTwoStepPath() throws Exception {
        ExecutionPlanRepository subclassed = new ExecutionPlanRepositoryImpl(
                new JdbcTemplate(dataSource), new Oracle23Dialect(), mock(SqlValidator.class));

        List<Map<String, Object>> rows = subclassed.explainPlanRows("SELECT 1 FROM dual");

        assertThat(rows).hasSize(1);
        verify(explainStatement).execute(anyString());
        verify(cleanupStatement).executeUpdate();
    }

    /**
     * 读取阶段抛异常时，本次写进 plan_table 的行仍然被清掉。
     *
     * <p>清理原来跟在读取之后、不在 finally 里：EXPLAIN 已经写进会话级临时表，读取一失败这些行就留在会话
     * 里，而连接会被池复用到 max-lifetime——正是那段注释自己担心的堆积。
     */
    @Test
    @DisplayName("读取计划失败时 plan_table 仍被清理")
    void planTableIsCleanedUpWhenTheFetchFails() throws Exception {
        when(fetchStatement.executeQuery()).thenThrow(new SQLException("ORA-00942"));

        assertThatThrownBy(() -> repository.explainPlanRows("SELECT 1 FROM dual"))
                .isInstanceOf(DataAccessException.class);

        verify(cleanupStatement).executeUpdate();
    }

    /** 清理自身失败只降级为日志：它在 finally 里，抛出去会盖掉真正的读取异常。 */
    @Test
    @DisplayName("清理失败不会盖掉计划结果")
    void aFailingCleanupDoesNotBreakTheCall() throws Exception {
        when(cleanupStatement.executeUpdate()).thenThrow(new SQLException("ORA-00942"));

        assertThat(repository.explainPlanRows("SELECT 1 FROM dual")).hasSize(1);
    }

    /** {@code OracleDialect} 不是 final，所以这种子类是现实存在的扩展方式。 */
    private static final class Oracle23Dialect extends OracleDialect {
    }
}
