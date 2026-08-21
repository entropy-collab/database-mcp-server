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

import com.entropy.database.mcp.config.QueryConfig;
import com.entropy.database.mcp.facade.DatabaseFacade;
import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import com.entropy.database.mcp.gateway.FederatedQueryGateway;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.stream.SseStreamManager;
import com.entropy.database.mcp.util.ValidationUtils;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static com.entropy.database.mcp.tools.McpToolUtils.errorResponse;
import static com.entropy.database.mcp.tools.McpToolUtils.successResponse;

/**
 * Cross-database tools for federated queries, DB Link administration, and analytics.
 */
@Configuration
@ConditionalOnProperty(name = "entropy.mcp.gateway.enabled", havingValue = "true")
public class CrossDatabaseTools {

    private static final String GATEWAY_NOT_ENABLED_MSG = "Cross-database gateway is not enabled";
    private static final String FEDERATED_GATEWAY_NOT_ENABLED_MSG = "Federated gateway is not enabled";
    private static final int DEFAULT_CROSS_DB_MAX_ROWS = 100;
    private static final int DEFAULT_COMPLEX_ANALYTICS_MAX_ROWS = 50;
    private static final int MAX_CTE_ROWS = 50000;

    private final RoutingDatabaseFacade routingFacade;
    private final SqlValidator sqlValidator;
    private final SseStreamManager sseStreamManager;
    private final DatabaseFacade databaseFacade;
    private final FederatedQueryGateway gateway;
    private final JdbcTemplate primaryJdbcTemplate;
    private final boolean gatewayEnabled;
    private final int maxExportRows;

    public CrossDatabaseTools(RoutingDatabaseFacade routingFacade,
                              SqlValidator sqlValidator,
                              SseStreamManager sseStreamManager,
                              DatabaseFacade databaseFacade,
                              FederatedQueryGateway gateway,
                              JdbcTemplate primaryJdbcTemplate,
                              QueryConfig queryConfig,
                              Environment environment) {
        this.routingFacade = routingFacade;
        this.sqlValidator = sqlValidator;
        this.sseStreamManager = sseStreamManager;
        this.databaseFacade = databaseFacade;
        this.gateway = gateway;
        this.primaryJdbcTemplate = primaryJdbcTemplate;
        this.gatewayEnabled = Boolean.parseBoolean(environment.getProperty("entropy.mcp.gateway.enabled", "false"));
        this.maxExportRows = queryConfig != null ? queryConfig.maxExportRows() : 500;
    }

    private boolean isGatewayEnabled() {
        return gatewayEnabled;
    }

    @McpTool(description = "Execute a cross-database JOIN query using Oracle DB Link syntax (@db_link)")
    public Map<String, Object> queryCrossDatabaseJoin(
            @McpToolParam(description = "SQL query with @db_link syntax") String sql,
            @McpToolParam(description = "Maximum rows to return") Integer maxRows) {
        if (!isGatewayEnabled()) {
            return errorResponse(Map.of("sql", sql), GATEWAY_NOT_ENABLED_MSG, "DisabledException");
        }
        try {
            int limit = maxRows != null ? maxRows : DEFAULT_CROSS_DB_MAX_ROWS;
            String limitedSql = String.format("SELECT * FROM (%s) WHERE ROWNUM <= %d", sql, limit);
            List<Map<String, Object>> rows = primaryJdbcTemplate.queryForList(limitedSql);
            return successResponse(Map.of("sql", sql, "rowCount", rows.size(), "data", rows));
        } catch (Exception e) {
            return errorResponse(Map.of("sql", sql), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "List tables from a remote database via DB Link")
    public List<Map<String, Object>> listRemoteTables(
            @McpToolParam(description = "Database link name") String dbLinkName,
            @McpToolParam(description = "Remote schema owner (use 'USER' for current user)") String owner) {
        if (!isGatewayEnabled()) {
            return List.of(errorResponse(Map.of("dbLinkName", dbLinkName, "owner", owner),
                    GATEWAY_NOT_ENABLED_MSG, "DisabledException"));
        }
        try {
            String sql;
            if ("USER".equalsIgnoreCase(owner)) {
                sql = String.format("SELECT table_name FROM all_tables@%s WHERE owner = USER", dbLinkName);
                return primaryJdbcTemplate.queryForList(sql);
            } else {
                sql = String.format("SELECT table_name FROM all_tables@%s WHERE owner = UPPER(?)", dbLinkName);
                return primaryJdbcTemplate.queryForList(sql, owner.toUpperCase());
            }
        } catch (Exception e) {
            return List.of(errorResponse(Map.of("dbLinkName", dbLinkName, "owner", owner),
                    e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    @McpTool(description = "Describe a remote table's columns via DB Link")
    public List<Map<String, Object>> describeRemoteTable(
            @McpToolParam(description = "Database link name") String dbLinkName,
            @McpToolParam(description = "Remote table name") String remoteTable) {
        if (!isGatewayEnabled()) {
            return List.of(errorResponse(Map.of("dbLinkName", dbLinkName, "remoteTable", remoteTable),
                    GATEWAY_NOT_ENABLED_MSG, "DisabledException"));
        }
        try {
            String sql = String.format(
                    "SELECT column_name, data_type, data_length, nullable " +
                    "FROM all_tab_columns@%s WHERE table_name = UPPER(?) ORDER BY column_id",
                    dbLinkName);
            return primaryJdbcTemplate.queryForList(sql, remoteTable.toUpperCase());
        } catch (Exception e) {
            return List.of(errorResponse(Map.of("dbLinkName", dbLinkName, "remoteTable", remoteTable),
                    e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    @McpTool(description = "Execute a pre-built complex cross-database analytics query with JOINs, subqueries, and analytic functions")
    public Map<String, Object> queryComplexCrossDatabaseAnalytics(
            @McpToolParam(description = "Database link name") String dbLinkName,
            @McpToolParam(description = "Local transaction table prefix (e.g., 'TBL_STL_TXN_DTL_')") String localTablePrefix,
            @McpToolParam(description = "Partition date in YYYYMMDD") String partitionDate,
            @McpToolParam(description = "Start date for remote quality table (e.g., 2026-02-01)") String startDate,
            @McpToolParam(description = "End date for remote quality table (e.g., 2026-02-28)") String endDate,
            @McpToolParam(description = "Maximum rows to return") Integer maxRows) {
        if (!isGatewayEnabled()) {
            return errorResponse(Map.of("dbLinkName", dbLinkName, "localTablePrefix", localTablePrefix, "partitionDate", partitionDate),
                    GATEWAY_NOT_ENABLED_MSG, "DisabledException");
        }
        String localTable = localTablePrefix + partitionDate;
        int limit = maxRows != null ? maxRows : 50;

        String sql = String.format("""
            WITH fcs_transactions AS (
                SELECT 
                    TXN_DATE, TXN_TIME, TICKET_ID, TICKET_TYPE, TXN_STATION_ID, LAST_STATION_ID,
                    TXN_AMT, REWARD_AMT, PAY_TYPE
                FROM %s
                WHERE ROWNUM <= MAX_CTE_ROWS
            ),
            remote_quality AS (
                SELECT 
                    TO_CHAR(TXN_DATE, 'YYYYMMDD') as TXN_DATE, TICKET_ID, STATION_CODE, TRANS_CODE,
                    DEV_CODE, DELAY_FLAG, MISSING_DATE_FLAG
                FROM REMOTE_QUALITY_TABLE@%s
                WHERE TXN_DATE BETWEEN DATE '%s' AND DATE '%s'
            ),
            station_dim AS (
                SELECT STATION_CODE, STATION_NAME, OWNER_LINE_CODE, STATION_TYPE, PARA_VERSION_NO
                FROM DIM_STATION@%s
            )
            SELECT 
                f.TXN_DATE, s.STATION_NAME, s.OWNER_LINE_CODE, s.STATION_TYPE, f.TICKET_TYPE,
                COUNT(*) as txn_count, SUM(f.TXN_AMT) as total_amount, SUM(f.REWARD_AMT) as total_reward,
                AVG(f.TXN_AMT) as avg_amount, MAX(f.TXN_AMT) as max_amount,
                SUM(CASE WHEN q.DELAY_FLAG = 'Y' THEN 1 ELSE 0 END) as delayed_txns,
                SUM(CASE WHEN q.MISSING_DATE_FLAG = 'Y' THEN 1 ELSE 0 END) as missing_date_txns,
                COUNT(DISTINCT f.TICKET_ID) as unique_tickets,
                RANK() OVER (PARTITION BY s.OWNER_LINE_CODE ORDER BY SUM(f.TXN_AMT) DESC) as line_revenue_rank,
                DENSE_RANK() OVER (PARTITION BY s.STATION_TYPE ORDER BY COUNT(*) DESC) as station_type_dense_rank,
                PERCENT_RANK() OVER (ORDER BY SUM(f.TXN_AMT)) as revenue_percentile
            FROM fcs_transactions f
            INNER JOIN station_dim s ON f.TXN_STATION_ID = s.STATION_CODE
            LEFT JOIN remote_quality q 
                ON f.TXN_DATE = q.TXN_DATE AND f.TICKET_ID = q.TICKET_ID AND f.TXN_STATION_ID = q.STATION_CODE
            WHERE f.TXN_AMT > 0
            GROUP BY f.TXN_DATE, s.STATION_NAME, s.OWNER_LINE_CODE, s.STATION_TYPE, f.TICKET_TYPE
            HAVING COUNT(*) > 5
            ORDER BY total_amount DESC
            FETCH FIRST %d ROWS ONLY
            """,
                localTable, dbLinkName, startDate, endDate, dbLinkName, limit
        );

        try {
            long startTime = System.currentTimeMillis();
            List<Map<String, Object>> rows = primaryJdbcTemplate.queryForList(sql);
            long duration = System.currentTimeMillis() - startTime;

            return successResponse(Map.of(
                    "template", "complex_cross_database_analytics",
                    "localTable", localTable,
                    "remoteTables", "REMOTE_QUALITY_TABLE@" + dbLinkName + ", DIM_STATION@" + dbLinkName,
                    "rowCount", rows.size(),
                    "durationMs", duration,
                    "data", rows
            ));
        } catch (Exception e) {
            return errorResponse(Map.of("template", "complex_cross_database_analytics", "sql", sql),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Get pre-built cross-database JOIN query templates")
    public Map<String, Object> getCrossDatabaseTemplates() {
        if (!isGatewayEnabled()) {
            return errorResponse(Map.of(), GATEWAY_NOT_ENABLED_MSG, "DisabledException");
        }
        return successResponse(Map.of(
                "inner_join", String.format("SELECT ... FROM local_table t INNER JOIN remote_table@%%s s ON t.col = s.col WHERE ...", "_DB_LINK_"),
                "left_join", String.format("SELECT ... FROM local_table t LEFT JOIN remote_table@%%s s ON t.col = s.col WHERE ...", "_DB_LINK_"),
                "subquery", String.format("SELECT ... FROM local_table t WHERE t.col IN (SELECT col FROM remote_table@%%s)", "_DB_LINK_"),
                "aggregate", String.format("SELECT t.category, COUNT(*) as local_cnt, (SELECT COUNT(*) FROM remote_table@%%s r WHERE r.category = t.category) as remote_cnt FROM local_table t GROUP BY t.category", "_DB_LINK_")
        ));
    }

    @McpTool(description = "List all registered databases with connection info")
    public List<Map<String, Object>> listDatabases() {
        if (!isGatewayEnabled() || gateway == null) {
            return List.of(errorResponse(Map.of(), FEDERATED_GATEWAY_NOT_ENABLED_MSG, "DisabledException"));
        }
        return gateway.listDatabases();
    }

    @McpTool(description = "Get detailed information about a specific database in the federated gateway")
    public Map<String, Object> getFederatedDatabaseInfo(
            @McpToolParam(description = "Database identifier") String databaseId) {
        if (!isGatewayEnabled() || gateway == null) {
            return errorResponse(Map.of("databaseId", databaseId), FEDERATED_GATEWAY_NOT_ENABLED_MSG, "DisabledException");
        }
        return gateway.getDatabaseInfo(databaseId);
    }

    @McpTool(description = "Execute the same query across multiple databases and aggregate results")
    public Map<String, Object> executeFederatedQuery(
            @McpToolParam(description = "SQL query to execute") String query,
            @McpToolParam(description = "List of database IDs to query") List<String> databases,
            @McpToolParam(description = "Maximum rows per database") Integer maxRows) {
        if (!isGatewayEnabled() || gateway == null) {
            return errorResponse(Map.of("query", query, "databases", databases),
                    "Federated gateway is not enabled", "DisabledException");
        }
        return gateway.executeFederatedQuery(query, databases, maxRows);
    }

    @McpTool(description = "Execute different queries on different databases in parallel")
    public Map<String, Object> executeSelectiveQuery(
            @McpToolParam(description = "Map of databaseId to SQL query") Map<String, String> databaseQueries) {
        if (!isGatewayEnabled() || gateway == null) {
            return errorResponse(Map.of("databaseQueries", databaseQueries), FEDERATED_GATEWAY_NOT_ENABLED_MSG, "DisabledException");
        }
        return gateway.executeSelectiveQuery(databaseQueries);
    }

    @McpTool(description = "Get the number of registered database clients in the federated gateway")
    public Map<String, Object> getGatewayStatistics() {
        if (!isGatewayEnabled() || gateway == null) {
            return errorResponse(Map.of(), FEDERATED_GATEWAY_NOT_ENABLED_MSG, "DisabledException");
        }
        return successResponse(Map.of(
                "clientCount", gateway.getClientCount(),
                "databases", gateway.listDatabases().size()
        ));
    }

    @McpTool(description = "Create an Oracle Database Link for cross-database queries")
    public Map<String, Object> createDbLink(
            @McpToolParam(description = "Database link name") String dbLinkName,
            @McpToolParam(description = "Remote host") String host,
            @McpToolParam(description = "Remote port") String port,
            @McpToolParam(description = "Remote service name") String serviceName,
            @McpToolParam(description = "Remote username") String username,
            @McpToolParam(description = "Remote password") String password) {
        if (!isGatewayEnabled()) {
            return errorResponse(Map.of("dbLinkName", dbLinkName, "host", host, "port", port, "serviceName", serviceName, "username", username),
                    "Cross-database gateway is not enabled", "DisabledException");
        }
        ValidationUtils.validateIdentifier(dbLinkName, "dbLinkName");
        ValidationUtils.validateIdentifier(username, "username");
        ValidationUtils.validateHost(host);
        ValidationUtils.validatePort(port);
        ValidationUtils.validateServiceName(serviceName);

        String escapedPassword = password.replace("\"", "\"\"");
        String dblinkSql = String.format(
                "CREATE DATABASE LINK %s CONNECT TO %s IDENTIFIED BY \"%s\" " +
                "USING '(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=%s)(PORT=%s))(CONNECT_DATA=(SERVICE_NAME=%s)))'",
                dbLinkName, username, escapedPassword, host, port, serviceName);

        try {
            primaryJdbcTemplate.execute(dblinkSql);
            return successResponse(Map.of(
                    "dbLinkName", dbLinkName,
                    "message", String.format("Database link '%s' created successfully", dbLinkName)));
        } catch (Exception e) {
            return errorResponse(Map.of("dbLinkName", dbLinkName), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Drop an Oracle Database Link")
    public Map<String, Object> dropDbLink(
            @McpToolParam(description = "Name of the database link to drop") String dbLinkName) {
        if (!isGatewayEnabled()) {
            return errorResponse(Map.of("dbLinkName", dbLinkName),
                    GATEWAY_NOT_ENABLED_MSG, "DisabledException");
        }
        ValidationUtils.validateIdentifier(dbLinkName, "dbLinkName");
        String dropSql = String.format("DROP DATABASE LINK %s", dbLinkName);
        try {
            primaryJdbcTemplate.execute(dropSql);
            return successResponse(Map.of(
                    "dbLinkName", dbLinkName,
                    "message", String.format("Database link '%s' dropped successfully", dbLinkName)));
        } catch (Exception e) {
            return errorResponse(Map.of("dbLinkName", dbLinkName), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    @McpTool(description = "Test database link connectivity by querying a remote table")
    public Map<String, Object> testDbLink(
            @McpToolParam(description = "Database link name") String dbLinkName,
            @McpToolParam(description = "Remote table name to query") String remoteTable) {
        if (!isGatewayEnabled()) {
            return errorResponse(Map.of("dbLinkName", dbLinkName, "remoteTable", remoteTable),
                    GATEWAY_NOT_ENABLED_MSG, "DisabledException");
        }
        ValidationUtils.validateIdentifier(dbLinkName, "dbLinkName");
        ValidationUtils.validateIdentifier(remoteTable, "remoteTable");
        String sql = String.format("SELECT COUNT(*) as cnt FROM %s@%s", remoteTable, dbLinkName);
        try {
            Integer count = primaryJdbcTemplate.queryForObject(sql, Integer.class);
            return successResponse(Map.of(
                    "dbLink", dbLinkName,
                    "remoteTable", remoteTable,
                    "rowCount", count,
                    "message", String.format("Successfully queried %s@%s, found %d rows", remoteTable, dbLinkName, count)));
        } catch (Exception e) {
            return errorResponse(Map.of("dbLink", dbLinkName, "remoteTable", remoteTable),
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }
}
