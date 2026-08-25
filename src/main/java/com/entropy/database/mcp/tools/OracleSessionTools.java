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
import com.entropy.database.mcp.facade.DatabaseAdminOperations;
import com.entropy.database.mcp.facade.DatabaseWriteOperations;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.entropy.database.mcp.util.ValidationUtils.requireNotBlank;

/**
 * Oracle session management tools.
 */
@Component
public class OracleSessionTools extends McpToolBase {

    private final DatabaseWriteOperations writeOperations;
    private final DatabaseAdminOperations adminOperations;
    private final boolean ddlAllowed;

    public OracleSessionTools(DatabaseWriteOperations writeOperations,
                              DatabaseAdminOperations adminOperations,
                              @Value("${entropy.mcp.database.ddl.allowed:false}") boolean ddlAllowed) {
        this.writeOperations = writeOperations;
        this.adminOperations = adminOperations;
        this.ddlAllowed = ddlAllowed;
    }

    @McpTool(description = """
            【强制中断数据库会话】对目标数据库执行 ALTER SYSTEM KILL SESSION，强制中断指定会话。
            破坏性影响：不可恢复。被中断会话中未提交的事务会被回滚，该会话持有的锁被释放，客户端连接直接断开；如果杀错会话，正在运行的业务作业会失败。IMMEDIATE 模式立即中断并回滚，POST_TRANSACTION 模式等当前事务结束后再中断。执行前请务必先用 showLocks 或 showBlockingTree 确认 sid 与 serial# 对应的正是目标阻塞会话。
            前置条件：必须开启 entropy.mcp.database.ddl.allowed=true，否则报操作不被允许；connection 在本工具中必填不可省略；当前连接账号需具备 ALTER SYSTEM 权限；数据库方言必须支持该操作（Oracle 支持，其他方言会报不支持）。
            使用场景：会话长时间阻塞他人、连接被占死且无法从应用侧释放。
            返回字段：sql（实际执行的语句）、sessionId、mode（实际使用的模式）、affectedRows、durationMs、connectionName、message。
            不要用于：查看会话与阻塞关系（用 listActiveSessions、showLocks、showBlockingTree）；释放连接池中的空闲连接（这属于连接池管理，不是数据库会话）。
            标签：[write, admin, session, destructive, oracle]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> killSession(
            @McpToolParam(description = "会话标识，格式必须为 'sid,serial#' 两个整数以逗号分隔（如 '123,4567'），必填；格式不符直接报参数校验失败") String sessionId,
            @McpToolParam(description = "中断模式，取值 IMMEDIATE 或 POST_TRANSACTION（大小写不敏感）；省略或传空时默认 IMMEDIATE，其他取值报参数校验失败", required = false) String mode,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return safeExecute(() -> {
            requireNotBlank(sessionId, "sessionId");
            requireNotBlank(connection, "connection");
            String trimmed = sessionId.trim();
            if (!trimmed.matches("\\d+,\\d+")) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "sessionId must be in format 'sid,serial#' (e.g. '123,4567') (sessionId=" + sessionId + ")");
            }

            String killMode = (mode == null || mode.isBlank()) ? "IMMEDIATE" : mode.trim().toUpperCase();
            if (!killMode.equals("IMMEDIATE") && !killMode.equals("POST_TRANSACTION")) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "mode must be IMMEDIATE or POST_TRANSACTION");
            }

            if (!ddlAllowed) {
                throw new McpToolException(ErrorCode.SQL_OPERATION_NOT_ALLOWED, "DDL execution is disabled. Set entropy.mcp.database.ddl.allowed=true to enable. (sessionId=" + trimmed + ", connection=" + connection + ")");
            }

            var dialect = adminOperations.getDialect(connection);

            String sql = dialect.killSessionSql(trimmed, killMode);
            if (sql == null) {
                throw new McpToolException(ErrorCode.KILL_SESSION_NOT_SUPPORTED, "Kill session is not supported for dialect: " + dialect.getClass().getSimpleName() + " (sessionId=" + trimmed + ", mode=" + killMode + ", connection=" + connection + ")");
            }

            long startTime = System.currentTimeMillis();
            int affected = writeOperations.executeUpdate(sql, connection);
            return success(Map.of(
                    "sql", sql, "sessionId", trimmed, "mode", killMode,
                    "affectedRows", affected, "durationMs", System.currentTimeMillis() - startTime,
                    "connectionName", connection, "message", "Session killed successfully"
            ));
        });
    }
}
