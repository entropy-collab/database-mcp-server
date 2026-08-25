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
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.properties.StatementTimeouts;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepository;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * Context for a single BYOK datasource.
 * Encapsulates all per-datasource dependencies.
 */
public class ByokDataSourceContext {
    private static final Logger log = LoggerFactory.getLogger(ByokDataSourceContext.class);

    private final String key;
    private final DataSource dataSource;
    private final DatabaseDialect dialect;
    private final StatementTemplates templates;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ByokInfrastructure infrastructure;

    public ByokDataSourceContext(String key,
                                 DataSource dataSource,
                                 DatabaseDialect dialect,
                                 StatementTemplates templates,
                                 ByokInfrastructure infrastructure) {
        this.key = key;
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.templates = templates;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(templates.read());
        this.infrastructure = infrastructure;
    }

    public String getKey() {
        return key;
    }

    public String connectionName() {
        return key;
    }

    public String tenantId() {
        return null;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public DatabaseDialect getDialect() {
        return dialect;
    }

    /**
     * Template for read work, bounded by the read timeout.
     *
     * <p>The default for anything that is not clearly a write, a DDL statement or a bulk transfer.
     * Picking this one for a long-running statement will abort it at the read ceiling — that is the
     * intended failure mode, and the fix is to pick the matching category below, not to remove the
     * ceiling.
     */
    public JdbcTemplate getJdbcTemplate() {
        return templates.read();
    }

    /** Template for row-modifying statements (INSERT/UPDATE/DELETE, batch writes). */
    public JdbcTemplate getWriteJdbcTemplate() {
        return templates.write();
    }

    /** Template for DDL, which can block on metadata locks far longer than a write. */
    public JdbcTemplate getDdlJdbcTemplate() {
        return templates.ddl();
    }

    /** Template for bulk transfers: ETL steps, backup extraction and replay. */
    public JdbcTemplate getEtlJdbcTemplate() {
        return templates.etl();
    }

    /**
     * The timeout ceilings behind the templates above.
     *
     * <p>Needed by callers that build statements from a raw {@link Connection} — the transaction
     * path — and therefore have to apply the ceiling themselves.
     */
    public StatementTimeouts getStatementTimeouts() {
        return templates.timeouts();
    }

    public NamedParameterJdbcTemplate getNamedParameterJdbcTemplate() {
        return namedParameterJdbcTemplate;
    }

    public DatabaseCache getCache() {
        return infrastructure.cache();
    }

    public DatabaseHealthMonitor getHealthMonitor() {
        return infrastructure.healthMonitor();
    }

    public QueryAuditLogger getAuditLogger() {
        return infrastructure.auditLogger();
    }

    public DatabaseReadRepository getReadRepository() {
        return infrastructure.readRepository();
    }

    public ByokWriteRepository getWriteRepository() {
        return infrastructure.writeRepository();
    }

    public ExecutionPlanRepository getExecutionPlanRepository() {
        return infrastructure.executionPlanRepository();
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public DatabaseMetaData getConnectionMetadata() throws SQLException {
        try (Connection conn = getConnection()) {
            return conn.getMetaData();
        }
    }

    public boolean isValid() {
        try (Connection conn = getConnection()) {
            return conn.isValid(2);
        } catch (SQLException e) {
            log.warn("Connection validation failed for {}: {}", key, e.getMessage(), e);
            return false;
        }
    }

    public void renewLease() {
        // Lease renewal is managed by LeasedDataSource, not the context itself.
        // This method is a no-op for backward compatibility.
    }

    /**
     * Destroy the underlying HikariCP pool.
     *
     * <p>Deliberately package-private: the pool's lifetime is owned by
     * {@link DynamicDataSourceManager} through {@link LeasedDataSource}. Callers that merely
     * finished using a context must NOT close it — doing so tears down the shared pool for
     * every other in-flight caller of the same BYOK connection, and leaves the lease cache
     * holding a dead context until the lease expires. Simply drop the reference instead;
     * JDBC connections are returned to the pool by the JdbcTemplate / try-with-resources.
     */
    void closePool() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.error("Failed to close BYOK datasource: {}", key, e);
            }
        }
    }
}
