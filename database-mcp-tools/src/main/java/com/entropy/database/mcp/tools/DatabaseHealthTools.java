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
import com.entropy.database.mcp.facade.DatabaseOperations;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.entropy.database.mcp.util.ValidationUtils.requireNotBlank;
import static com.entropy.database.mcp.util.ValidationUtils.validateIdentifier;

/**
 * Database health and diagnostics tools.
 */
@Component
public class DatabaseHealthTools extends McpToolBase {

    /**
     * The full contract rather than a narrower capability: these tools read, resolve dialects, and
     * (for {@code gatherTableStats} on Oracle) issue a DBMS_STATS block, and no single capability
     * interface spans that combination.
     */
    private final DatabaseOperations routingFacade;

    public DatabaseHealthTools(DatabaseOperations routingFacade) {
        this.routingFacade = routingFacade;
    }

    @McpTool(description = """
            【数据库健康检查】按当前连接的方言执行一条轻量探活 SQL，确认连接真的可用。
            前置条件：必须传 connection——先调用 createNamedConnection 注册连接；所有方言都支持本工具。
            使用场景：排查数据库问题的第一步，先确认连接通不通，再往下查会话、锁与容量。
            返回字段：connection、dialect（方言实现类名，如 OracleDialect）、status（固定为 healthy）、rows（探活 SQL 返回的行，Oracle 为 SELECT 'OK' FROM DUAL）。
            不要用于：查看数据库产品名与版本（用 getDatabaseInfo）；查看连接池指标（用 getPoolStats）。
            标签：[read, health, connection, diagnostics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> checkHealth(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return DialectQueryUtils.checkHealth(routingFacade, connection);
    }

    @McpTool(description = """
            【查看活动会话】列出数据库当前的用户会话及其状态、等待事件与正在执行的 SQL 标识。
            前置条件：必须传 connection。仅 Oracle（读 v$session，需要 v$ 视图访问权限）、MySQL（information_schema.processlist）、PostgreSQL（pg_stat_activity）支持；通用方言只返回一行占位数据；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：查谁连着库、谁在跑长 SQL；排查阻塞的第一步。
            返回字段：dialect、rows（数组，每项含 sid、serial#、username、status、machine、program、logon_time、last_call_et、event、wait_class、sql_id）。
            不要用于：查看锁对象与锁模式（用 showLocks）；查看谁阻塞谁的因果链（用 showBlockingTree）。排查顺序建议 listActiveSessions 看会话 → showLocks 看锁 → showBlockingTree 定位源头。
            标签：[read, dba, session, diagnostics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listActiveSessions(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listActiveSessionsSql();
            if (sql == null) throw new IllegalStateException("listActiveSessions is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = """
            【查看锁】列出数据库当前持有与等待中的锁，以及持锁会话的信息。
            前置条件：必须传 connection。仅 Oracle（v$lock 关联 v$session）、MySQL（information_schema.innodb_trx）、PostgreSQL（pg_locks 关联 pg_stat_activity）支持；通用方言只返回一行占位数据；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：确认某张表或某行是否被锁住、锁模式是什么、持锁多久了。
            返回字段：dialect、rows（数组，每项含 sid、serial#、type、id1、id2、lmode、request、ctime、username、status、event）。
            不要用于：判断阻塞的上下游关系（用 showBlockingTree，它直接给出等待方与阻塞方的配对）；只看会话不看锁（用 listActiveSessions）。
            标签：[read, dba, lock, diagnostics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> showLocks(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.showLocksSql();
            if (sql == null) throw new IllegalStateException("showLocks is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = """
            【查看阻塞链】列出「谁在等谁」的阻塞配对，直接定位阻塞源头会话。
            前置条件：必须传 connection。仅 Oracle（v$lock 自关联）、MySQL（information_schema.innodb_lock_waits）、PostgreSQL（pg_locks 未授予与已授予配对）支持；通用方言只返回一行占位数据；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：应用出现超时或长时间挂起，需要找出该处理的源头会话。
            返回字段：dialect、rows（数组，每项含 waiter_sid、waiter_serial、waiter_user、waiter_event、blocker_sid、blocker_serial、blocker_user、blocker_event）。
            不要用于：查看全部锁明细（用 showLocks）；查看全部会话（用 listActiveSessions）；终止会话（用 killSession）。
            标签：[read, dba, lock, blocking, diagnostics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> showBlockingTree(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.showBlockingTreeSql();
            if (sql == null) throw new IllegalStateException("showBlockingTree is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = """
            【查看表空间】列出表空间及其容量使用情况。
            前置条件：必须传 connection。Oracle 读 dba_tablespaces（需要 DBA 权限）；MySQL 按 database 聚合表大小近似模拟；PostgreSQL 读 pg_tablespace；通用方言返回全 0 的占位行；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：容量巡检、判断空间是否即将耗尽。
            返回字段：dialect、rows（数组，每项含 tablespace_name、contents、extent_management、status、size_mb、used_mb；只有 Oracle 的 used_mb 是真实值，其他方言恒为 0）。
            不要用于：单表占用空间（用 estimateTableSize）；数据文件级别的自动扩展信息（用 listDataFiles）。
            标签：[read, dba, storage, tablespace]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listTablespaces(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listTablespacesSql();
            if (sql == null) throw new IllegalStateException("listTablespaces is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = """
            【查看数据文件】列出数据文件及其大小、状态与自动扩展设置。
            前置条件：必须传 connection。Oracle 读 dba_data_files（需要 DBA 权限），是唯一返回真实自动扩展信息的方言；MySQL 以表为单位近似模拟；PostgreSQL 读 pg_class 的物理文件路径；通用方言返回占位行；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：Oracle 容量告警后确认哪个数据文件已接近 maxbytes、是否开启了 autoextend。
            返回字段：dialect、rows（数组，每项含 file_name、tablespace_name、bytes、blocks、status、autoextensible、maxbytes、increment_by、max_mb；非 Oracle 方言的 autoextensible/maxbytes/increment_by 为固定占位值）。
            不要用于：表空间层面的容量汇总（用 listTablespaces）。
            标签：[read, dba, storage, datafile]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listDataFiles(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listDataFilesSql();
            if (sql == null) throw new IllegalStateException("listDataFiles is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = """
            【估算表占用空间】从数据字典读取单表的物理占用大小，不扫描表数据。
            前置条件：必须传 connection。Oracle 读 dba_segments（需要 DBA 权限）；MySQL 读 information_schema.tables；PostgreSQL 用 pg_total_relation_size；H2、SQL Server、SQLite、DB2 与通用方言返回 size_mb=0 的占位行，数值不可信。
            使用场景：判断表是否过大、评估备份或全表扫描的代价。
            返回字段：dialect、tableName、rows（数组，每项含 segment_name、segment_type、size_mb、extents）。
            不要用于：重新收集统计信息（用 gatherTableStats，那会写数据字典）；查看索引是否失效（用 showIndexStatus）；需要精确行数时请直接 SELECT COUNT(*)。
            标签：[read, dba, storage, table, estimate]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> estimateTableSize(
            @McpToolParam(description = "表名，必填。Oracle 会自动转成大写去匹配数据字典") String tableName,
            @McpToolParam(description = "Schema 名，可省略；省略时 Oracle 取当前用户、MySQL 取当前 database、PostgreSQL 取 current_schema()", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        requireNotBlank(tableName, "tableName");
        // estimateTableSizeSql declares exactly one placeholder for the table name (see the
        // bind-parameter contract on DatabaseDialect); binding nothing left Oracle/MySQL/PostgreSQL
        // rejecting the statement outright. The name has to be dialect-normalized, since Oracle's
        // data dictionary stores it upper-cased.
        Map<String, Object> result = executeWithDialect(connection,
                dialect -> dialect.estimateTableSizeSql(tableName, schema),
                dialect -> List.of(dialect.normalizeTableName(tableName)));
        result.put("tableName", tableName);
        return result;
    }

    @McpTool(description = """
            【查看失效对象】列出编译状态失效的数据库对象。
            前置条件：必须传 connection。仅 Oracle 有真实意义（读 dba_objects 中 status='INVALID' 的对象，需要 DBA 权限）；MySQL 的实现把所有表都标成 INVALID、PostgreSQL 的实现把对象都标成 VALID，两者都不反映真实失效状态；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：Oracle 发布 DDL 后检查是否有存储过程、视图、触发器被打成 INVALID，需要重新编译。
            返回字段：dialect、rows（数组，每项含 owner、object_name、object_type、status）。
            标签：[read, dba, oracle, invalid-objects]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listInvalidObjects(
            @McpToolParam(description = "Schema 名过滤，可省略；省略时 Oracle 取当前用户、MySQL 取当前 database、PostgreSQL 取 current_schema()", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listInvalidObjectsSql(schema);
            if (sql == null) throw new IllegalStateException("listInvalidObjects is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        }, schema == null ? null : schema);
    }

    @McpTool(description = """
            【重新收集表统计信息】让数据库重新收集指定表的优化器统计信息。注意本工具会写入数据字典，不是只读查询。
            前置条件：必须传 connection。Oracle 执行 DBMS_STATS.GATHER_TABLE_STATS（需要该表的 ANALYZE 权限，大表耗时较长）；MySQL 执行 ANALYZE TABLE；PostgreSQL 执行 ANALYZE；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：执行计划明显走错、统计信息陈旧或从未收集过。
            返回字段：dialect、tableName、rows（固定为空数组——收集统计信息不返回结果集）。
            不要用于：只想知道表占多大空间（用 estimateTableSize，只读且不写库）；只想看索引状态（用 showIndexStatus）；查看执行计划（用 explainPlan）。
            标签：[write, dba, statistics, optimizer]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> gatherTableStats(
            @McpToolParam(description = "表名，必填。Oracle 会自动转成大写") String tableName,
            @McpToolParam(description = "Schema 名，可省略；省略时使用当前用户或当前 database", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        requireNotBlank(tableName, "tableName");
        // The generated statement inlines both identifiers: ANALYZE and the DBMS_STATS block cannot
        // bind an object name as a parameter, so they must be whitelisted here instead.
        validateIdentifier(tableName, "tableName");
        if (schema != null && !schema.isBlank()) {
            validateIdentifier(schema, "schema");
        }
        Map<String, Object> result = executeWithDialect(connection, dialect -> {
            String sql = dialect.gatherTableStatsSql(tableName, schema);
            if (sql == null) throw new IllegalStateException("gatherTableStats is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
        result.put("tableName", tableName);
        return result;
    }

    @McpTool(description = """
            【查看索引状态】列出索引的有效性（是否 UNUSABLE）、唯一性与统计信息时间。
            前置条件：必须传 connection。Oracle 读 dba_indexes（需要 DBA 权限），status 反映真实的 VALID/UNUSABLE；MySQL 读 information_schema.statistics、PostgreSQL 读 pg_index，两者的 status 恒为 VALID，只有索引名与唯一性可信；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：Oracle 分区维护或直接路径加载后确认索引是否失效；核对索引唯一性。
            返回字段：dialect、rows（数组，每项含 owner、table_name、index_name、status、uniqueness、last_analyzed、num_rows、distinct_keys）。
            不要用于：查看索引包含哪些列（用 listIndexes）；获取建索引建议（用 recommendIndexes）；估算表占用空间（用 estimateTableSize）；重新收集统计信息（用 gatherTableStats，会写库）。
            标签：[read, dba, index, diagnostics]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> showIndexStatus(
            @McpToolParam(description = "表名过滤。本参数未声明为可选，需显式传入；传 null 时返回该 Schema 下的全部索引") String tableName,
            @McpToolParam(description = "Schema 名，可省略；省略时 Oracle 取当前用户、MySQL 取当前 database、PostgreSQL 取 current_schema()", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.showIndexStatusSql(tableName, schema);
            if (sql == null) throw new IllegalStateException("showIndexStatus is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        }, schema, tableName);
    }

    @McpTool(description = """
            【生成闪回查询语句】返回一条按时间点查询历史数据的 SQL 模板文本；本工具只产出模板，不返回历史数据本身。
            前置条件：必须传 connection；timestamp 虽标为可选，实际为空会被直接拒绝。仅 Oracle 生成可执行的 AS OF TIMESTAMP 模板，且要求该表的 undo 保留期覆盖目标时间点；PostgreSQL 生成的模板带 AS OF TIMESTAMP 语法但 PostgreSQL 并不支持，拿去执行会报错；MySQL 与其他方言返回一条注明「不支持闪回」的占位模板。
            使用场景：Oracle 上误删或误更新数据后，先取到闪回语句，再自行执行以比对历史快照。
            返回字段：dialect、tableName、timestamp、rows（其中 sql_template 为生成的 SQL 文本，时间点以 ? 占位）。
            不要用于：真正恢复数据（用 restoreBackup 或 quickRestore）；查询当前数据（用 executeQuery）。
            标签：[read, dba, oracle, flashback, template]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> flashbackQuery(
            @McpToolParam(description = "表名，必填") String tableName,
            @McpToolParam(description = "目标时间点。Oracle 模板按 YYYY-MM-DD HH24:MI:SS 解析（如 2026-08-26 10:30:00）；虽标为可选，实际必填") String timestamp,
            @McpToolParam(description = "Schema 名，可省略；当前实现不会把它拼进生成的模板", required = false) String schema,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return safeExecute(() -> {
            requireNotBlank(tableName, "tableName");
            requireNotBlank(timestamp, "timestamp");
            DatabaseDialect dialect = routingFacade.getDialect(connection);
            String sqlTemplate = dialect.flashbackQuerySql(tableName);
            if (sqlTemplate == null) {
                sqlTemplate = "SELECT 'SELECT * FROM %s -- Flashback not supported for this dialect' AS sql_template".formatted(dialect.quote(tableName));
            }
            List<Map<String, Object>> rows = sqlTemplate.contains("?")
                    ? routingFacade.queryRows(sqlTemplate, connection, timestamp)
                    : routingFacade.queryRows(sqlTemplate, connection);
            return success(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(routingFacade, connection),
                    "tableName", tableName, "timestamp", timestamp,
                    "rows", rows));
        });
    }

    @McpTool(description = """
            【查看 undo 使用情况】列出回滚段（undo）表空间的容量与使用率。
            前置条件：必须传 connection。仅 Oracle 有真实数据（读 dba_undo_extents，需要 DBA 权限）；MySQL 返回全 0 的占位行；PostgreSQL 返回的是表空间容量汇总而非 undo，不能当作 undo 依据；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：Oracle 报 ORA-01555 快照过旧，或长事务导致 undo 膨胀时。
            返回字段：dialect、rows（数组，每项含 tablespace_name、size_mb、used_mb、free_mb、used_pct）。
            不要用于：普通表空间容量（用 listTablespaces）。
            标签：[read, dba, oracle, undo, storage]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> showUndoUsage(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.showUndoUsageSql();
            if (sql == null) throw new IllegalStateException("showUndoUsage is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = """
            【查看当前用户权限】列出当前登录用户自身拥有的权限。
            前置条件：必须传 connection。Oracle 读 user_sys_privs 与 user_tab_privs（不需要 DBA 权限）；MySQL 读 information_schema.user_privileges；PostgreSQL 读 role_table_grants 与 role_routine_grants；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：操作被拒绝、报权限不足时，先确认自己到底有哪些权限。
            返回字段：dialect、rows（数组，每项含 privilege、admin_option、grantable）。
            不要用于：查看别人的权限（用 listGrants 并传入用户名或角色名）。
            标签：[read, dba, security, privilege]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listCurrentPrivileges(@McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return executeWithDialect(connection, dialect -> {
            String sql = dialect.listCurrentPrivilegesSql();
            if (sql == null) throw new IllegalStateException("listCurrentPrivileges is not supported for dialect: " + dialect.getClass().getSimpleName());
            return sql;
        });
    }

    @McpTool(description = """
            【查看指定用户或角色的授权】列出授予某个用户或角色的权限。
            前置条件：必须传 connection 与 userName。Oracle 读 dba_tab_privs 与 dba_sys_privs（需要 DBA 权限）；MySQL 读 information_schema.table_privileges；PostgreSQL 读 role_table_grants 与 role_routine_grants；H2、SQL Server、SQLite、DB2 未实现，调用会报「不支持该方言」。
            使用场景：权限审计、确认某账号是否被多授了权限。
            返回字段：dialect、userName、rows（数组，每项含 grantee、privilege、grantable；Oracle 另含 admin_option，MySQL/PostgreSQL 另含 grantor）。
            不要用于：查看自己的权限（用 listCurrentPrivileges，不需要 DBA 权限）。
            标签：[read, dba, security, privilege, audit]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listGrants(
            @McpToolParam(description = "用户名或角色名，必填。Oracle 数据字典中存的是大写，通常需传大写（如 SCOTT）") String userName,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        requireNotBlank(userName, "userName");
        Map<String, Object> result = executeWithDialect(connection, dialect -> dialect.listGrantsSql(userName));
        result.put("userName", userName);
        return result;
    }

    private Map<String, Object> executeWithDialect(String connection, java.util.function.Function<DatabaseDialect, String> sqlProvider, Object... params) {
        return safeExecute(() -> {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(routingFacade, connection, sqlProvider, params);
            return success(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(routingFacade, connection),
                    "rows", result.get("rows")));
        });
    }

    /**
     * Variant for the metadata queries whose bind arguments depend on the dialect - the table name
     * has to be normalized by the same dialect that produced the SQL.
     */
    private Map<String, Object> executeWithDialect(String connection,
                                                  java.util.function.Function<DatabaseDialect, String> sqlProvider,
                                                  java.util.function.Function<DatabaseDialect, List<Object>> argsProvider) {
        return safeExecute(() -> {
            Map<String, Object> result = DialectQueryUtils.executeDialectQuery(routingFacade, connection, sqlProvider, argsProvider);
            return success(Map.of(
                    "dialect", DialectQueryUtils.getDialectName(routingFacade, connection),
                    "rows", result.get("rows")));
        });
    }
}
