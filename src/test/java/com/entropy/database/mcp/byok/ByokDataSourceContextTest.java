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
package com.entropy.database.mcp.byok;

import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.properties.StatementTimeouts;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepository;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ByokDataSourceContextTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private DatabaseDialect dialect;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ByokInfrastructure infrastructure;

    @Test
    void delegatesReadOperations() {
        DatabaseReadRepository readRepository = mock(DatabaseReadRepository.class);
        when(infrastructure.readRepository()).thenReturn(readRepository);

        ByokDataSourceContext context = new ByokDataSourceContext(
                "key1", dataSource, dialect, templatesOver(dataSource), infrastructure
        );

        assertThat(context.getKey()).isEqualTo("key1");
        assertThat(context.getDataSource()).isSameAs(dataSource);
        assertThat(context.getDialect()).isSameAs(dialect);
        assertThat(context.getJdbcTemplate()).isSameAs(jdbcTemplate);
    }

    @Test
    void delegatesCacheAndMonitor() {
        DatabaseCache cache = mock(DatabaseCache.class);
        DatabaseHealthMonitor healthMonitor = mock(DatabaseHealthMonitor.class);
        QueryAuditLogger auditLogger = mock(QueryAuditLogger.class);
        DatabaseReadRepository readRepository = mock(DatabaseReadRepository.class);
        ByokWriteRepository writeRepository = mock(ByokWriteRepository.class);
        ExecutionPlanRepository executionPlanRepository = mock(ExecutionPlanRepository.class);

        when(infrastructure.cache()).thenReturn(cache);
        when(infrastructure.healthMonitor()).thenReturn(healthMonitor);
        when(infrastructure.auditLogger()).thenReturn(auditLogger);
        when(infrastructure.readRepository()).thenReturn(readRepository);
        when(infrastructure.writeRepository()).thenReturn(writeRepository);
        when(infrastructure.executionPlanRepository()).thenReturn(executionPlanRepository);

        ByokDataSourceContext context = new ByokDataSourceContext(
                "key1", dataSource, dialect, templatesOver(dataSource), infrastructure
        );

        assertThat(context.getCache()).isSameAs(cache);
        assertThat(context.getHealthMonitor()).isSameAs(healthMonitor);
        assertThat(context.getAuditLogger()).isSameAs(auditLogger);
        assertThat(context.getReadRepository()).isSameAs(readRepository);
        assertThat(context.getWriteRepository()).isSameAs(writeRepository);
        assertThat(context.getExecutionPlanRepository()).isSameAs(executionPlanRepository);
    }

    @Test
    void closeWithNonCloseableDataSource() {
        ByokDataSourceContext context = new ByokDataSourceContext(
                "key1", dataSource, dialect, templatesOver(dataSource), infrastructure
        );

        // dataSource is a mock that doesn't implement AutoCloseable, so closePool() should be a no-op
        assertThatNoException().isThrownBy(context::closePool);
    }

    @Test
    void closeWithAutoCloseableDataSourceThatThrows() {
        // Create a DataSource that is also an AutoCloseable using anonymous class
        DataSource mockCloseableDataSource = new DataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException { return null; }
            @Override
            public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException { return null; }
            @Override
            public java.io.PrintWriter getLogWriter() throws java.sql.SQLException { return null; }
            @Override
            public void setLogWriter(java.io.PrintWriter out) throws java.sql.SQLException { }
            @Override
            public void setLoginTimeout(int seconds) throws java.sql.SQLException { }
            @Override
            public int getLoginTimeout() throws java.sql.SQLException { return 0; }
            @Override
            public java.util.logging.Logger getParentLogger() { return null; }
            @Override
            public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { return null; }
            @Override
            public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException { return false; }
            public void close() throws SQLException {
                throw new SQLException("close failed");
            }
        };

        ByokDataSourceContext context = new ByokDataSourceContext(
                "key1", mockCloseableDataSource, dialect, templatesOver(mockCloseableDataSource), infrastructure
        );

        // Should catch the exception and not rethrow
        assertThatNoException().isThrownBy(context::closePool);
    }

    /**
     * Wrap the mocked read template with the default ceilings; the sibling templates are derived
     * over the same (mocked) datasource and are never executed here.
     */
    private StatementTemplates templatesOver(DataSource source) {
        return StatementTemplates.over(source, jdbcTemplate, StatementTimeouts.defaults());
    }
}
