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
import com.entropy.database.mcp.security.SqlValidator;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.entropy.database.mcp.util.ValidationUtils.requireNotEmpty;

/**
 * DDL execution tools.
 */
@Component
public class DdlExecutionTools extends McpToolBase {

    private final DatabaseOperations routingFacade;
    private final SqlValidator sqlValidator;
    private final boolean ddlAllowed;
    private final boolean gatewayEnabled;

    public DdlExecutionTools(DatabaseOperations routingFacade,
                             SqlValidator sqlValidator,
                             org.springframework.core.env.Environment environment) {
        this.routingFacade = routingFacade;
        this.sqlValidator = sqlValidator;
        this.ddlAllowed = Boolean.parseBoolean(environment.getProperty("entropy.mcp.database.ddl.allowed", "false"));
        this.gatewayEnabled = Boolean.parseBoolean(environment.getProperty("entropy.mcp.gateway.enabled", "false"));
    }

    private boolean isGatewayEnabled() {
        return gatewayEnabled;
    }

    // ─── DDL ────────────────────────────────────────────────────────────────

    @McpTool(description = """
            【执行单条 DDL】在当前连接上执行一条 DDL 语句（CREATE / ALTER / DROP 等）。
            前置条件：必须配置 entropy.mcp.database.ddl.allowed=true，否则直接拒绝；连接需先用 createNamedConnection 注册；语句会先过 DDL 校验器。
            使用场景：改表结构、建表、建删索引等一次性结构变更。
            注意：执行前建议先用 backupData 备份将被影响的表数据；Oracle 与 MySQL 的 DDL 隐式提交，出错无法回滚。
            返回字段：affectedRows、success。
            不要用于：一次执行多条 DDL（用 executeDdlBatch，可拿到逐条结果与回滚说明）；只想校验语法不执行（用 validateDdl）；执行 DML 或查询（写入用 insertData 等 ETL 工具，查询用 executeQuery）。
            标签：[write, ddl, schema, destructive]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> executeDdl(
            @McpToolParam(description = "要执行的单条 DDL 语句（如 CREATE TABLE / ALTER TABLE / DROP INDEX）") String sql,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (!ddlAllowed) {
            throw new McpToolException(ErrorCode.SQL_OPERATION_NOT_ALLOWED, ToolParams.DDL_DISABLED_MSG);
        }
        return routingFacade.executeDdl(sql, connection);
    }

    @McpTool(description = """
            【DDL 前数据备份】把表数据导出成一组 INSERT 语句，并登记一条 FULL 类型的备份记录。
            前置条件：连接需先用 createNamedConnection 注册；表必须存在，否则返回 error 字段而不是抛错。
            使用场景：执行 executeDdl / executeDdlBatch 改结构之前先留一份数据快照，便于出问题时手工回灌。
            注意：maxRows 传 0 或负数会按配置项 entropy.mcp.database.backup.max-backup-rows 的上限截断（负数会被参数校验拒绝，0 表示用上限）；返回的 truncated=true 说明数据被行数上限截断，不是完整备份。
            返回字段：backupId、tableName、connection、type（固定 FULL）、maxRows（实际生效的上限）、totalRows、rowCount、truncated、statements（INSERT 语句数组）；表不存在时只返回 error。
            不要用于：需要增量备份、指定输出格式（sql/json/text）或后续用 restoreBackup 恢复的完整备份流程（用 backupTable）；只想比较结构差异（用 diffSchema）。
            标签：[read, backup, ddl, safety]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> backupData(
            @McpToolParam(description = "要备份的表名") String tableName,
            @McpToolParam(description = "备份行数上限，必须为非负整数；传 0 表示使用服务端配置的备份行数上限") int maxRows,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        if (maxRows < 0) {
            throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "maxRows must be non-negative (tableName=" + tableName + ", maxRows=" + maxRows + ", connection=" + connection + ")");
        }
        return routingFacade.backupData(tableName, maxRows, connection);
    }

    @McpTool(description = """
            【对比表结构】比较同一连接下两张表的列集合与列类型差异。
            前置条件：连接需先用 createNamedConnection 注册；两张表都要存在。
            使用场景：数据搬运前核对源表与目标表是否兼容、上线前比对新旧表结构。
            返回字段：source_table、target_table、only_in_source（仅源表有的列名数组）、only_in_target（仅目标表有的列名数组）、type_differences（数组，每项含 column、source_type、target_type）、compatible（三者均为空时为 true）。
            不要用于：查看单张表的完整结构（用 describeTable）；比较索引差异（用 listIndexes 分别查看）。
            标签：[read, schema, diff, migration]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> diffSchema(
            @McpToolParam(description = "源表名") String sourceTable,
            @McpToolParam(description = "目标表名") String targetTable,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return routingFacade.diffSchema(sourceTable, targetTable, connection);
    }

    // ─── Remote DDL ─────────────────────────────────────────────────────────

    @McpTool(description = """
            【在指定连接上执行单条 DDL】面向网关场景的远程 DDL 执行，必须显式指定连接名。
            前置条件：必须配置 entropy.mcp.gateway.enabled=true；connectionName 必填不可为空；连接需先用 createNamedConnection 注册；语句会先过 DDL 校验器。
            使用场景：管理某个具体 BYOK 连接上的表结构，且需要在结果里拿到执行耗时。
            注意：本工具只受网关开关控制，不检查 entropy.mcp.database.ddl.allowed；Oracle 与 MySQL 的 DDL 隐式提交，失败无法回滚。
            返回字段：connectionName、ddl（回显执行的语句）、affectedRows、durationMs、message。
            不要用于：一次执行多条 DDL（用 executeDdlBatch）；只想做语法校验（用 validateDdl）；本地默认连接上的简单 DDL（用 executeDdl，它受 ddl.allowed 控制）。
            标签：[write, ddl, remote, schema, destructive]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> executeDdlRemote(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "要执行的单条 DDL 语句（如 CREATE TABLE、ALTER TABLE、DROP INDEX）") String ddl) throws Exception {
        if (!isGatewayEnabled()) {
            throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, "Gateway is not enabled");
        }
        validateRequired(connectionName, "connectionName");
        return safeExecute(() -> {
            sqlValidator.validateDdl(ddl);
            long startTime = System.currentTimeMillis();
            int affected = routingFacade.executeUpdate(ddl, connectionName);
            long duration = System.currentTimeMillis() - startTime;
            return success(Map.of(
                    "connectionName", connectionName,
                    "ddl", ddl,
                    "affectedRows", affected,
                    "durationMs", duration,
                    "message", "DDL executed successfully"
            ));
        });
    }

    @McpTool(description = """
            【批量执行 DDL】在一个事务里按顺序执行多条 DDL，逐条汇报结果，遇到第一条失败即停止。
            前置条件：必须配置 entropy.mcp.gateway.enabled=true；statements 不能为空；连接需先用 createNamedConnection 注册。每条语句都会先过 DDL 校验器。
            原子性差异（务必据此决策）：PostgreSQL 与 SQL Server 把 DDL 纳入事务，中途失败可干净回滚，结构保持不变；Oracle 与 MySQL 对每条 DDL 隐式提交，回滚无效，失败前已执行的语句是永久生效的，必须人工反向修复。返回值里的 transactional 表明本次走的是哪种模式，rolledBack 表明是否真的回滚，非事务型失败时还会给出 appliedBeforeFailure 列出已生效的语句。
            使用场景：一组必须成套上线的结构变更；在 Oracle/MySQL 上执行前应先用 backupData 备份并做好人工回退预案。
            返回字段：connectionName、totalStatements、succeeded、failed、results（数组，每项含 ddl、success，成功含 durationMs，失败含 error）、durationMs、transactional、rolledBack、appliedBeforeFailure（仅非事务型且失败时出现）、message。
            不要用于：只执行一条 DDL（用 executeDdl 或 executeDdlRemote）；上线前只想确认语法（用 validateDdl，完全不执行）。
            标签：[write, ddl, batch, transaction, destructive]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> executeDdlBatch(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "按执行顺序排列的 DDL 语句列表，不能为空；遇到第一条失败即中止后续语句") List<String> statements) throws Exception {
        if (!isGatewayEnabled()) {
            throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, ToolParams.GATEWAY_NOT_ENABLED_MSG);
        }
        requireNotEmpty(statements, "statements");
        return safeExecute(() -> {
            try {
                return routingFacade.inTransaction(connectionName, tx -> {
                    // Whether wrapping DDL in a transaction means anything on this target. Oracle and
                    // MySQL commit DDL implicitly, so a rollback after a mid-batch failure is a
                    // no-op and earlier statements are already permanent. Reporting "rolled back" in
                    // that case would be false.
                    boolean transactional = tx.ddlIsTransactional();

                    long startTime = System.currentTimeMillis();
                    List<Map<String, Object>> results = new ArrayList<>();
                    List<String> applied = new ArrayList<>();
                    boolean allSuccess = true;

                    for (String ddl : statements) {
                        try {
                            sqlValidator.validateDdl(ddl);
                            long stmtStart = System.currentTimeMillis();
                            tx.execute(ddl);
                            long stmtDuration = System.currentTimeMillis() - stmtStart;
                            applied.add(ddl);
                            results.add(Map.of("ddl", ddl, "success", true, "durationMs", stmtDuration));
                        } catch (RuntimeException e) {
                            allSuccess = false;
                            log.warn("DDL statement failed in batch on connection {}: {}",
                                    connectionName, ddl, e);
                            results.add(Map.of("ddl", ddl, "success", false,
                                    "error", "DDL execution failed: " + e.getMessage()));
                            // Stop on first failure — no point applying the rest of a broken batch
                            break;
                        }
                    }

                    if (!allSuccess && transactional) {
                        // The rollback belongs to the facade and only happens if this work throws,
                        // so the report rides out on the exception instead of being returned.
                        applied.clear();
                        throw new DdlBatchRolledBack(buildBatchPayload(connectionName, statements.size(),
                                applied, results, false, true, true,
                                System.currentTimeMillis() - startTime));
                    }
                    return success(buildBatchPayload(connectionName, statements.size(), applied, results,
                            allSuccess, transactional, false, System.currentTimeMillis() - startTime));
                });
            } catch (DdlBatchRolledBack e) {
                return success(e.payload());
            }
        });
    }

    /**
     * Carries the per-statement report out of a transaction that must roll back.
     *
     * <p>Extends {@link McpToolException} so the facade re-throws it unchanged rather than wrapping
     * it, keeping the payload reachable at the catch site.
     */
    private static final class DdlBatchRolledBack extends McpToolException {

        private final transient Map<String, Object> payload;

        private DdlBatchRolledBack(Map<String, Object> payload) {
            super(ErrorCode.QUERY_EXECUTION_FAILED, "DDL batch rolled back");
            this.payload = payload;
        }

        private Map<String, Object> payload() {
            return payload;
        }
    }

    private static Map<String, Object> buildBatchPayload(String connectionName, int totalStatements,
                                                         List<String> applied, List<Map<String, Object>> results,
                                                         boolean allSuccess, boolean transactional,
                                                         boolean rolledBack, long totalDuration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connectionName", connectionName);
        payload.put("totalStatements", totalStatements);
        payload.put("succeeded", applied.size());
        payload.put("failed", allSuccess ? 0 : 1);
        payload.put("results", results);
        payload.put("durationMs", totalDuration);
        payload.put("transactional", transactional);
        payload.put("rolledBack", rolledBack);
        if (!allSuccess && !transactional) {
            payload.put("appliedBeforeFailure", applied);
        }
        payload.put("message", buildBatchMessage(allSuccess, transactional, applied.size()));
        return payload;
    }

    private static String buildBatchMessage(boolean allSuccess, boolean transactional, int appliedCount) {
        if (allSuccess) {
            return "All DDL statements executed successfully";
        }
        if (transactional) {
            return "Transaction rolled back due to failure — the schema is unchanged";
        }
        return "This database commits DDL implicitly, so nothing was rolled back. "
                + appliedCount + " statement(s) are already permanent and must be reversed manually.";
    }

    @McpTool(description = """
            【DDL 语法校验】只做静态校验、完全不连库执行，逐条给出是否合法。
            前置条件：必须配置 entropy.mcp.gateway.enabled=true；statements 不能为空。
            使用场景：executeDdlBatch 之前先试跑一遍，尤其是在 Oracle/MySQL 这类无法回滚的库上。
            注意：即使某条不合法也不会报错，而是在对应结果项里标 valid=false；error 固定为 Validation failed，具体原因只写服务端日志，不回传。
            返回字段：totalStatements、validCount、invalidCount、results（数组，每项含 ddl、valid，valid=false 时含 error）、message。
            不要用于：真正执行 DDL（用 executeDdl / executeDdlRemote / executeDdlBatch）；校验 SELECT 语句或评估查询风险（用 assessQueryRisk 或 explainPlan）。
            标签：[read, ddl, validation, dry-run]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> validateDdl(
            @McpToolParam(description = "待校验的 DDL 语句列表，不能为空") List<String> statements) throws Exception {
        if (!isGatewayEnabled()) {
            throw new McpToolException(ErrorCode.CONNECTION_GATEWAY_DISABLED, ToolParams.GATEWAY_NOT_ENABLED_MSG);
        }
        requireNotEmpty(statements, "statements");
        return safeExecute(() -> {
            List<Map<String, Object>> results = new ArrayList<>();
            boolean allValid = true;

            for (String ddl : statements) {
                try {
                    sqlValidator.validateDdl(ddl);
                    results.add(Map.of("ddl", ddl, "valid", true));
                } catch (RuntimeException e) {
                    // A dry run reports every statement's verdict, so a rejected statement is a
                    // result rather than a failure. The reason stays in the log because the response
                    // deliberately does not echo validator internals back to the caller.
                    allValid = false;
                    log.debug("DDL validation rejected statement: {}", e.getMessage());
                    results.add(Map.of("ddl", ddl, "valid", false, "error", "Validation failed"));
                }
            }

            return success(Map.of(
                    "totalStatements", statements.size(),
                    "validCount", results.stream().filter(r -> (boolean) r.get("valid")).count(),
                    "invalidCount", results.stream().filter(r -> !(boolean) r.get("valid")).count(),
                    "results", results,
                    "message", allValid ? "All DDL statements are valid" : "Some DDL statements have validation errors"
            ));
        });
    }
}
