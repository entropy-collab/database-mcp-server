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

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.facade.DatabaseOperations;
import com.entropy.database.mcp.gateway.FederatedQueryGateway;
import com.entropy.database.mcp.properties.QueryConfig;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.util.ValidationUtils;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cross-database tools for federated queries, DB Link administration, and analytics.
 */
@Component
public class CrossDatabaseTools extends McpToolBase {

    private static final int DEFAULT_CROSS_DB_MAX_ROWS = 100;
    private static final int DEFAULT_COMPLEX_ANALYTICS_MAX_ROWS = 50;
    private static final int MAX_CTE_ROWS = 50000;
    private static final int MAX_DB_LINK_PASSWORD_LENGTH = 128;
    /** Printable ASCII minus the characters that could terminate the inlined password literal. */
    private static final Pattern DB_LINK_PASSWORD_PATTERN = Pattern.compile("[\\x21-\\x7E&&[^\"'\\\\`]]+");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,127}$");

    private final DatabaseOperations routingFacade;
    private final SqlValidator sqlValidator;
    private final FederatedQueryGateway gateway;
    private final boolean gatewayEnabled;
    private final int maxExportRows;

    public CrossDatabaseTools(DatabaseOperations routingFacade,
                              SqlValidator sqlValidator,
                              FederatedQueryGateway gateway,
                              QueryConfig queryConfig,
                              Environment environment) {
        this.routingFacade = routingFacade;
        this.sqlValidator = sqlValidator;
        this.gateway = gateway;
        this.gatewayEnabled = Boolean.parseBoolean(environment.getProperty("entropy.mcp.gateway.enabled", "false"));
        this.maxExportRows = queryConfig != null ? queryConfig.maxExportRows() : 500;
    }

    @McpTool(description = """
            【跨库 JOIN 查询】通过 Oracle DB Link 在一条 SELECT 里关联本地表与远程表。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；远程库须已用 createDbLink 建好 DB Link；仅适用于 Oracle（行数限制用 ROWNUM 实现）。
            使用场景：本地表要和远程表做 JOIN、子查询或聚合，且不方便把远程库单独连进来。
            语法：SQL 中用 @db_link 引用远程表（如 t@my_db_link）；只允许单条 SELECT，不能带分号。
            返回字段：sql（实际执行的语句）、rowCount、data（数组，每项为一行结果）。
            不要用于：把同一条 SQL 分发到多个已注册库并汇总（用 executeFederatedQuery）；给不同库下发不同 SQL（用 executeSelectiveQuery）；本项目预置的对账分析模板（用 queryComplexCrossDatabaseAnalytics）。
            标签：[read, cross-database, dblink, join, oracle]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> queryCrossDatabaseJoin(
            @McpToolParam(description = "含 @db_link 语法的单条 SELECT 语句，不得包含分号") String sql,
            @McpToolParam(description = "返回行数上限，传 null 时默认 100") Integer maxRows,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (!isGatewayEnabled()) throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Cross-database gateway is not enabled (sql=" + sql + ")");
        if (sql.trim().contains(";")) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "SQL must be a single statement (no semicolons allowed)");
        }
        return safeExecute(() -> {
            sqlValidator.validateSelect(sql);
            int limit = maxRows != null ? maxRows : DEFAULT_CROSS_DB_MAX_ROWS;
            String limitedSql = "SELECT * FROM (" + sql.trim() + ") WHERE ROWNUM <= " + limit;
            List<Map<String, Object>> rows = routingFacade.queryRows(limitedSql, connection);
            return success(Map.of("sql", sql.trim(), "rowCount", rows.size(), "data", rows));
        });
    }

    @McpTool(description = """
            【列出远程表】通过 DB Link 查询远程 Oracle 库中指定 owner 拥有的表名。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；DB Link 已存在（可先用 testDbLink 验证连通性）。
            使用场景：探索远程库有哪些表可用，为 queryCrossDatabaseJoin 构造 SQL 做准备。
            返回字段：数组，每项只含 table_name（来自远程库 all_tables）。
            不要用于：查看远程表的列结构（用 describeRemoteTable）；查看本地库的表（用 listTables）。
            标签：[read, cross-database, dblink, metadata, list]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listRemoteTables(
            @McpToolParam(description = "DB Link 名称，须为合法标识符（字母或下划线开头，仅含字母、数字、下划线）") String dbLinkName,
            @McpToolParam(description = "远程 Schema 属主。传 USER（不区分大小写）表示远程库的当前登录用户；否则传具体 Schema 名，内部会自动转大写匹配") String owner,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (!isGatewayEnabled()) throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Cross-database gateway is not enabled (dbLinkName=" + dbLinkName + ", owner=" + owner + ")");
        ValidationUtils.validateIdentifier(dbLinkName, "dbLinkName");
        if (!"USER".equalsIgnoreCase(owner)) {
            ValidationUtils.validateIdentifier(owner, "owner");
        }
        try {
            List<Map<String, Object>> rows = "USER".equalsIgnoreCase(owner)
                    ? routingFacade.queryRows(String.format("SELECT table_name FROM all_tables@%s WHERE owner = USER", dbLinkName), connection)
                    : routingFacade.queryRows(String.format("SELECT table_name FROM all_tables@%s WHERE owner = UPPER(?)", dbLinkName), connection, owner.toUpperCase());
            return rows;
        } catch (DataAccessException e) {
            // Only a genuine failure on the far side of the link is a federated failure. Validation
            // and connection-resolution errors carry a code the caller can act on and are left alone.
            log.warn("Federated query failed: dbLink={}, owner={}, connection={}", dbLinkName, owner, connection, e);
            throw new McpToolException(ErrorCode.FEDERATED_QUERY_FAILED,
                    "Federated query failed for dbLink: " + dbLinkName, e);
        }
    }

    @McpTool(description = """
            【查看远程表结构】通过 DB Link 查询远程 Oracle 表的列定义。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；DB Link 已存在。
            使用场景：写跨库 SQL 前确认远程表的字段名与类型。
            返回字段：数组按列序返回，每项含 column_name、data_type、data_length、nullable。
            不要用于：本地表结构（用 describeTable）；只想拿远程表清单（用 listRemoteTables）。
            标签：[read, cross-database, dblink, metadata, table]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> describeRemoteTable(
            @McpToolParam(description = "DB Link 名称，须为合法标识符") String dbLinkName,
            @McpToolParam(description = "远程表名，须为合法标识符，内部自动转大写匹配") String remoteTable,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (!isGatewayEnabled()) throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Cross-database gateway is not enabled (dbLinkName=" + dbLinkName + ", remoteTable=" + remoteTable + ")");
        ValidationUtils.validateIdentifier(dbLinkName, "dbLinkName");
        ValidationUtils.validateIdentifier(remoteTable, "remoteTable");
        try {
            String sql = String.format(
                    "SELECT column_name, data_type, data_length, nullable FROM all_tab_columns@%s WHERE table_name = UPPER(?) ORDER BY column_id",
                    dbLinkName);
            return routingFacade.queryRows(sql, connection, remoteTable.toUpperCase());
        } catch (DataAccessException e) {
            log.warn("Federated describe failed: dbLink={}, remoteTable={}, connection={}",
                    dbLinkName, remoteTable, connection, e);
            throw new McpToolException(ErrorCode.FEDERATED_QUERY_FAILED,
                    "Federated query failed for dbLink: " + dbLinkName, e);
        }
    }

    @McpTool(description = """
            【预置跨库对账分析】执行内置写死的交易质量分析 SQL：本地交易明细表通过 DB Link 关联远程 REMOTE_QUALITY_TABLE 与 DIM_STATION，做分组聚合并计算 RANK/DENSE_RANK/PERCENT_RANK 排名。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；DB Link 已存在；远程库必须有 REMOTE_QUALITY_TABLE 与 DIM_STATION 两张表；本地分区表名为 localTablePrefix + partitionDate；仅适用于 Oracle。
            使用场景：本项目固定的车站交易额、延迟与缺失数据对账报表，参数只有表前缀与日期区间。
            注意：SQL 完全固定，不接受自定义语句；本地明细最多参与 50000 行 CTE 计算。
            返回字段：template（固定为 complex_cross_database_analytics）、localTable、remoteTables、rowCount、durationMs、data（数组，每项含 TXN_DATE、STATION_NAME、OWNER_LINE_CODE、STATION_TYPE、TICKET_TYPE、TXN_COUNT、TOTAL_AMOUNT、TOTAL_REWARD、AVG_AMOUNT、MAX_AMOUNT、DELAYED_TXNS、MISSING_DATE_TXNS、UNIQUE_TICKETS、LINE_REVENUE_RANK、STATION_TYPE_DENSE_RANK、REVENUE_PERCENTILE；列名大小写由 JDBC 驱动决定，Oracle 返回大写）。
            不要用于：任何自定义的跨库 SQL（用 queryCrossDatabaseJoin）；多库同构查询汇总（用 executeFederatedQuery）。
            标签：[read, cross-database, dblink, analytics, template]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> queryComplexCrossDatabaseAnalytics(
            @McpToolParam(description = "DB Link 名称，须为合法标识符") String dbLinkName,
            @McpToolParam(description = "本地交易明细表前缀，须为合法标识符（如 TBL_STL_TXN_DTL_），与 partitionDate 拼接成完整表名") String localTablePrefix,
            @McpToolParam(description = "分区日期，必须是 8 位数字 YYYYMMDD（如 20260201）") String partitionDate,
            @McpToolParam(description = "远程质量表的起始日期，ISO 格式 YYYY-MM-DD 且必须是真实日历日期（如 2026-02-01）") String startDate,
            @McpToolParam(description = "远程质量表的结束日期，ISO 格式 YYYY-MM-DD 且必须是真实日历日期（如 2026-02-28）") String endDate,
            @McpToolParam(description = "返回行数上限，传 null 时默认 50") Integer maxRows,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (!isGatewayEnabled()) throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Cross-database gateway is not enabled (dbLinkName=" + dbLinkName + ", localTablePrefix=" + localTablePrefix + ", partitionDate=" + partitionDate + ")");
        ValidationUtils.validateIdentifier(dbLinkName, "dbLinkName");
        ValidationUtils.validateIdentifier(localTablePrefix, "localTablePrefix");
        if (!partitionDate.matches("\\d{8}")) throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "partitionDate must be in YYYYMMDD format");
        // startDate/endDate are inlined into DATE '...' literals, so they are pinned to a real
        // calendar date rather than only to a shape.
        ValidationUtils.validateIsoDate(startDate, "startDate");
        ValidationUtils.validateIsoDate(endDate, "endDate");
        String localTable = localTablePrefix + partitionDate;
        int limit = maxRows != null ? maxRows : DEFAULT_COMPLEX_ANALYTICS_MAX_ROWS;
        String sql = buildComplexAnalyticsSql(localTable, dbLinkName, startDate, endDate, limit);
        return safeExecute(() -> {
            long startTime = System.currentTimeMillis();
            List<Map<String, Object>> rows = routingFacade.queryRows(sql, connection);
            return success(Map.of(
                    "template", "complex_cross_database_analytics", "localTable", localTable,
                    "remoteTables", "REMOTE_QUALITY_TABLE@" + dbLinkName + ", DIM_STATION@" + dbLinkName,
                    "rowCount", rows.size(), "durationMs", System.currentTimeMillis() - startTime, "data", rows));
        });
    }

    @McpTool(description = """
            【获取跨库 SQL 模板】返回四段可直接套用的 DB Link 跨库 SQL 骨架，其中 _DB_LINK_ 为占位符，需替换成真实 DB Link 名。
            前置条件：需开启 entropy.mcp.gateway.enabled=true。
            使用场景：不熟悉 @db_link 写法时，先取模板再交给 queryCrossDatabaseJoin 执行。
            返回字段：inner_join、left_join、subquery、aggregate，每个值都是一段 SQL 文本。
            不要用于：只读查询模板（那是 executeSqlTemplate 的能力，与跨库无关）。
            标签：[read, cross-database, dblink, template]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getCrossDatabaseTemplates() {
        if (!isGatewayEnabled()) throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Cross-database gateway is not enabled");
        return success(Map.of(
                "inner_join", String.format("SELECT ... FROM local_table t INNER JOIN remote_table@%%s s ON t.col = s.col WHERE ...", "_DB_LINK_"),
                "left_join", String.format("SELECT ... FROM local_table t LEFT JOIN remote_table@%%s s ON t.col = s.col WHERE ...", "_DB_LINK_"),
                "subquery", String.format("SELECT ... FROM local_table t WHERE t.col IN (SELECT col FROM remote_table@%%s)", "_DB_LINK_"),
                "aggregate", String.format("SELECT t.category, COUNT(*) as local_cnt, (SELECT COUNT(*) FROM remote_table@%%s r WHERE r.category = t.category) as remote_cnt FROM local_table t GROUP BY t.category", "_DB_LINK_")
        ));
    }

    @McpTool(description = """
            【列出联邦库】列出联邦网关中已注册的所有数据库，并逐个探测其连接元数据。
            前置条件：需开启 entropy.mcp.gateway.enabled=true 且联邦网关可用。注册的是网关客户端（databaseId），与 BYOK 连接名是两套体系。
            使用场景：执行 executeFederatedQuery / executeSelectiveQuery 前确认有哪些 databaseId 可用、是否连得上。
            返回字段：数组，每项含 id、status（connected / not_found / error）；status=connected 时还含 databaseProductName、databaseProductVersion、driverName、url；status=error 时含 error。
            不要用于：查看 BYOK 连接（用 listConnections）。
            标签：[read, federated, gateway, list]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<Map<String, Object>> listDatabases() {
        if (!isGatewayEnabled() || gateway == null) throw new McpToolException(ErrorCode.FEDERATED_GATEWAY_UNAVAILABLE, "Federated gateway is not enabled");
        return gateway.listDatabases();
    }

    @McpTool(description = """
            【查看联邦库详情】探测联邦网关中某个已注册库的连接状态与产品信息。
            前置条件：需开启 entropy.mcp.gateway.enabled=true 且联邦网关可用；databaseId 来自 listDatabases。
            使用场景：联邦查询报错时先确认该库是否连得上、是什么数据库产品与版本。
            返回字段：id、status（connected / not_found / error）；connected 时含 databaseProductName、databaseProductVersion、driverName、url；error 时含 error。库未注册时只返回 id 与 status=not_found，不抛异常。
            不要用于：查看 BYOK 连接的信息（用 describeConnection）。
            标签：[read, federated, gateway, metadata]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getFederatedDatabaseInfo(@McpToolParam(description = "联邦网关中注册的数据库标识（databaseId），取值来自 listDatabases") String databaseId) {
        if (!isGatewayEnabled() || gateway == null) throw new McpToolException(ErrorCode.FEDERATED_GATEWAY_UNAVAILABLE, "Federated gateway is not enabled (databaseId=" + databaseId + ")");
        return gateway.getDatabaseInfo(databaseId);
    }

    @McpTool(description = """
            【联邦同构查询】把同一条 SELECT 并发下发到多个已注册的联邦库，按库汇总结果。
            前置条件：需开启 entropy.mcp.gateway.enabled=true 且联邦网关可用；databases 中的 id 须已在网关注册（见 listDatabases）。不需要 DB Link。
            使用场景：同一张表分库存放（分片、分区域部署），要用一条相同的 SQL 把各库结果都取回来。
            注意：单库失败不会中断整体，该库结果记为 status=error；每库各自按其方言施加行数上限。
            返回字段：databases（入参库列表）、results（以 databaseId 为键，值含 status=success 时的 rowCount、data，或 status=error 时的 error）、executionTimeMs、successCount。
            不要用于：不同库要跑不同 SQL（用 executeSelectiveQuery）；一条 SQL 内部要 JOIN 本地表与远程表（用 queryCrossDatabaseJoin，走 DB Link）。
            标签：[read, federated, gateway, query, parallel]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> executeFederatedQuery(
            @McpToolParam(description = "要在每个库上执行的 SELECT 语句，只允许查询；本工具不支持绑定参数，占位符会执行失败") String query,
            @McpToolParam(description = "目标库标识列表（databaseId），取值来自 listDatabases") List<String> databases,
            @McpToolParam(description = "每个库各自的返回行数上限；传 null 时使用服务端配置的默认行数上限") Integer maxRows) {
        if (!isGatewayEnabled() || gateway == null) throw new McpToolException(ErrorCode.FEDERATED_GATEWAY_UNAVAILABLE, "Federated gateway is not enabled (query=" + query + ", databases=" + databases + ")");
        return gateway.executeFederatedQuery(query, databases, maxRows);
    }

    @McpTool(description = """
            【联邦异构查询】给不同的联邦库分别下发不同的 SELECT，并发执行后按库汇总。
            前置条件：需开启 entropy.mcp.gateway.enabled=true 且联邦网关可用；映射中的键须是已注册的 databaseId（见 listDatabases）。不需要 DB Link。
            使用场景：各库表结构不同、需要各写一条 SQL；或对不同库取不同维度的数据后由调用方自行拼装。
            注意：行数上限统一使用服务端配置的默认值，无法按库覆盖；单库失败只影响该库的结果项。
            返回字段：queries（入参映射）、results（以 databaseId 为键，值含 status=success 时的 rowCount、data，或 status=error 时的 error）、executionTimeMs、successCount。
            不要用于：所有库跑同一条 SQL（用 executeFederatedQuery，可指定 maxRows）；单条 SQL 内跨库 JOIN（用 queryCrossDatabaseJoin）。
            标签：[read, federated, gateway, query, parallel]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> executeSelectiveQuery(@McpToolParam(description = "databaseId 到 SELECT 语句的映射（如 {\"dbA\": \"SELECT ...\", \"dbB\": \"SELECT ...\"}），键取值来自 listDatabases") Map<String, String> databaseQueries) {
        if (!isGatewayEnabled() || gateway == null) throw new McpToolException(ErrorCode.FEDERATED_GATEWAY_UNAVAILABLE, "Federated gateway is not enabled (databaseQueries=" + databaseQueries + ")");
        return gateway.executeSelectiveQuery(databaseQueries);
    }

    @McpTool(description = """
            【联邦网关统计】查看联邦网关注册的客户端数量。
            前置条件：需开启 entropy.mcp.gateway.enabled=true 且联邦网关可用。
            使用场景：确认网关是否已注册库、排查联邦查询找不到 databaseId 的问题。
            返回字段：clientCount（注册客户端数）、databases（可列出的库数量，与 clientCount 同源）。
            不要用于：查看每个库的连接明细（用 listDatabases）；查看 BYOK 连接池指标（用 getPoolStats）。
            标签：[read, federated, gateway, metrics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getGatewayStatistics() {
        if (!isGatewayEnabled() || gateway == null) throw new McpToolException(ErrorCode.FEDERATED_GATEWAY_UNAVAILABLE, "Federated gateway is not enabled");
        return success(Map.of("clientCount", gateway.getClientCount(), "databases", gateway.listDatabases().size()));
    }

    @McpTool(description = """
            【创建 DB Link】在当前 Oracle 库中执行 CREATE DATABASE LINK，建立指向远程库的链路。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；当前连接的账号须有 CREATE DATABASE LINK 权限；仅 Oracle 可用。
            使用场景：为 queryCrossDatabaseJoin、listRemoteTables、describeRemoteTable 打通远程库访问。
            注意：Oracle 无法为该语句绑定密码，密码只能内联，因此对密码字符集有严格限制；重复创建同名链路会由数据库报错。
            返回字段：dbLinkName、message。
            不要用于：注册本服务自己使用的数据库连接（用 createNamedConnection 建立 BYOK 连接）。
            标签：[write, cross-database, dblink, ddl, oracle]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = true))
    public Map<String, Object> createDbLink(
            @McpToolParam(description = "要创建的 DB Link 名称，须为合法标识符（字母或下划线开头，仅含字母、数字、下划线，最长 128 位）") String dbLinkName,
            @McpToolParam(description = "远程库主机名或 IP") String host,
            @McpToolParam(description = "远程库端口，如 1521") String port,
            @McpToolParam(description = "远程库服务名（SERVICE_NAME）") String serviceName,
            @McpToolParam(description = "远程库登录用户名，须为合法标识符") String username,
            @McpToolParam(description = "远程库登录密码，必填且不能为空；最长 128 位，只允许可打印 ASCII 字符，且不得包含双引号、单引号、反斜杠、反引号") String password,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (!isGatewayEnabled()) throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Cross-database gateway is not enabled (dbLinkName=" + dbLinkName + ", host=" + host + ", port=" + port + ", serviceName=" + serviceName + ", username=" + username + ")");
        ValidationUtils.validateIdentifier(dbLinkName, "dbLinkName");
        ValidationUtils.validateIdentifier(username, "username");
        ValidationUtils.validateHost(host);
        ValidationUtils.validatePort(port);
        ValidationUtils.validateServiceName(serviceName);
        if (password == null) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "password is required");
        }
        // Oracle cannot bind the password of CREATE DATABASE LINK, so it has to be inlined. Rather
        // than trying to escape it (quote doubling inside a quoted identifier is not a reliable
        // escape for this statement), the character set is restricted and anything that could
        // terminate the literal is rejected outright.
        validateDbLinkPassword(password);
        String dblinkSql = String.format(
                "CREATE DATABASE LINK %s CONNECT TO %s IDENTIFIED BY \"%s\" USING '(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=%s)(PORT=%s))(CONNECT_DATA=(SERVICE_NAME=%s)))'",
                dbLinkName, username, password, host, port, serviceName);
        sqlValidator.validateDdl(dblinkSql);
        return safeExecute(() -> {
            routingFacade.executeUpdate(dblinkSql, connection);
            return success(Map.of("dbLinkName", dbLinkName, "message", String.format("Database link '%s' created successfully", dbLinkName)));
        });
    }

    @McpTool(description = """
            【删除 DB Link】执行 DROP DATABASE LINK 删除指定链路。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；仅 Oracle 可用。
            使用场景：链路信息过期、账号密码变更需要重建，或清理不再使用的跨库链路。
            注意：删除后所有依赖该链路的 @db_link 查询立即失效。
            返回字段：dbLinkName、message。
            标签：[write, cross-database, dblink, ddl, destructive]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> dropDbLink(
            @McpToolParam(description = "要删除的 DB Link 名称，须为合法标识符") String dbLinkName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (!isGatewayEnabled()) throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Cross-database gateway is not enabled (dbLinkName=" + dbLinkName + ")");
        ValidationUtils.validateIdentifier(dbLinkName, "dbLinkName");
        String dropSql = String.format("DROP DATABASE LINK %s", dbLinkName);
        sqlValidator.validateDdl(dropSql);
        return safeExecute(() -> {
            routingFacade.executeUpdate(dropSql, connection);
            return success(Map.of("dbLinkName", dbLinkName, "message", String.format("Database link '%s' dropped successfully", dbLinkName)));
        });
    }

    @McpTool(description = """
            【测试 DB Link】对远程表执行 SELECT COUNT(*) 来验证链路连通性与访问权限。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；DB Link 已由 createDbLink 建好；须指定一张确实存在的远程表。
            使用场景：跨库查询报错时先分段排查——确认链路本身通不通、远程表能不能读。
            注意：会对远程表做全表计数，超大表上开销较高。
            返回字段：dbLink、remoteTable、rowCount（远程表总行数）、message。
            不要用于：验证 BYOK 连接是否可用（用 describeConnection 或 checkHealth）。
            标签：[read, cross-database, dblink, diagnostics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> testDbLink(
            @McpToolParam(description = "DB Link 名称，须为合法标识符") String dbLinkName,
            @McpToolParam(description = "用于计数验证的远程表名，须为合法标识符且在远程库真实存在") String remoteTable,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (!isGatewayEnabled()) throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Cross-database gateway is not enabled (dbLinkName=" + dbLinkName + ", remoteTable=" + remoteTable + ")");
        ValidationUtils.validateIdentifier(dbLinkName, "dbLinkName");
        ValidationUtils.validateIdentifier(remoteTable, "remoteTable");
        String sql = String.format("SELECT COUNT(*) as cnt FROM %s@%s", remoteTable, dbLinkName);
        return safeExecute(() -> {
            List<Map<String, Object>> rows = routingFacade.queryRows(sql, connection);
            // COUNT(*) comes back as whatever numeric type the driver chose (Oracle: BigDecimal),
            // and the column label case is driver-dependent, so read the first cell positionally.
            Object cell = rows.isEmpty() || rows.get(0).isEmpty()
                    ? null
                    : rows.get(0).values().iterator().next();
            long count = cell instanceof Number number ? number.longValue() : 0L;
            return success(Map.of(
                    "dbLink", dbLinkName, "remoteTable", remoteTable, "rowCount", count,
                    "message", String.format("Successfully queried %s@%s, found %d rows", remoteTable, dbLinkName, count)));
        });
    }

    private boolean isGatewayEnabled() {
        return gatewayEnabled;
    }

    /**
     * Restricts a DB-link password to characters that cannot terminate the literal it is inlined
     * into. Quote doubling was the previous defence, but a doubled {@code "} inside an Oracle
     * quoted identifier is not a general escape, so such a password is refused with a clear
     * message instead of being mangled.
     */
    private void validateDbLinkPassword(String password) {
        if (password.isEmpty()) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "password cannot be empty");
        }
        if (password.length() > MAX_DB_LINK_PASSWORD_LENGTH) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "password must be at most " + MAX_DB_LINK_PASSWORD_LENGTH + " characters");
        }
        if (password.indexOf('"') >= 0) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "password must not contain a double quote: CREATE DATABASE LINK cannot bind the "
                            + "password, and a double quote cannot be escaped safely inside the "
                            + "IDENTIFIED BY literal. Change the remote password and retry.");
        }
        if (!DB_LINK_PASSWORD_PATTERN.matcher(password).matches()) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "password contains characters that cannot be inlined into CREATE DATABASE LINK: "
                            + "only printable ASCII without quote, backslash or backtick is accepted.");
        }
    }

    /**
     * Builds the pre-canned analytics statement.
     *
     * <p>Package-private so the generated SQL can be asserted without a live Oracle link. Every
     * interpolated value is validated by the caller: {@code localTable} and {@code dbLinkName} are
     * identifiers, the two dates are real calendar dates, and both row caps are {@code int}s.
     */
    static String buildComplexAnalyticsSql(String localTable, String dbLinkName, String startDate, String endDate, int limit) {
        return String.format("""
            WITH fcs_transactions AS (
                SELECT TXN_DATE, TXN_TIME, TICKET_ID, TICKET_TYPE, TXN_STATION_ID, LAST_STATION_ID,
                    TXN_AMT, REWARD_AMT, PAY_TYPE FROM %s WHERE ROWNUM <= %d
            ),
            remote_quality AS (
                SELECT TO_CHAR(TXN_DATE, 'YYYYMMDD') as TXN_DATE, TICKET_ID, STATION_CODE, TRANS_CODE,
                    DEV_CODE, DELAY_FLAG, MISSING_DATE_FLAG
                FROM REMOTE_QUALITY_TABLE@%s
                WHERE TXN_DATE BETWEEN DATE '%s' AND DATE '%s'
            ),
            station_dim AS (
                SELECT STATION_CODE, STATION_NAME, OWNER_LINE_CODE, STATION_TYPE, PARA_VERSION_NO
                FROM DIM_STATION@%s
            )
            SELECT f.TXN_DATE, s.STATION_NAME, s.OWNER_LINE_CODE, s.STATION_TYPE, f.TICKET_TYPE,
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
            LEFT JOIN remote_quality q ON f.TXN_DATE = q.TXN_DATE AND f.TICKET_ID = q.TICKET_ID AND f.TXN_STATION_ID = q.STATION_CODE
            WHERE f.TXN_AMT > 0
            GROUP BY f.TXN_DATE, s.STATION_NAME, s.OWNER_LINE_CODE, s.STATION_TYPE, f.TICKET_TYPE
            HAVING COUNT(*) > 5
            ORDER BY total_amount DESC
            FETCH FIRST %d ROWS ONLY
            """, localTable, MAX_CTE_ROWS, dbLinkName, startDate, endDate, dbLinkName, limit);
    }
}
