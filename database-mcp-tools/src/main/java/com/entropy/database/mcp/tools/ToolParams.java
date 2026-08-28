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

import java.util.Map;

/**
 * Common annotation parameter values and error messages shared across all MCP tool classes.
 * <p>
 * Centralises {@code @McpToolParam} descriptions, user-facing error messages, and
 * exception cause names so they only need to be maintained in one place.
 *
 * <h2>{@code @McpTool} description 书写规范</h2>
 * 所有工具描述统一使用中文，按固定段落顺序书写，缺失的段落直接省略（不要写空段落）：
 * <pre>
 * 【工具名】一句话说明这个工具做什么。
 * 前置条件：调用本工具前必须先完成的事（如先 createNamedConnection 注册连接）。
 * 使用场景：什么情况下该选它。
 * 返回字段：字段名逐个列出，让模型无需试探即知返回结构。
 * 不要用于：本工具不覆盖的场景，并指明该改用哪个工具（仅易混淆工具需要写）。
 * 标签：[read, query, select]
 * </pre>
 * 标签值保持英文小写关键字，作为检索标识符而非描述文本。
 */
public final class ToolParams {

    private ToolParams() {
    }

    /**
     * Description used for every BYOK connection name parameter.
     * Marked {@code required = false} because callers should supply it
     * when they know the connection; omit it when the AI can infer it.
     */
    public static final String CONNECTION_DESCRIPTION = """
            BYOK 连接名，用于指定目标数据库。\
            已注册多个连接时必填；只有一个连接时可省略（自动使用该连接）。\
            注意：连接注册是异步的——调用 createNamedConnection 后请先用 describeConnection \
            确认连接已就绪，再执行查询。""";

    /**
     * Standard SQL templates shared across tool classes.
     */
    public static final Map<String, String> TEMPLATES = Map.of(
            "select_sql", "SELECT * FROM {table} WHERE {condition} LIMIT {limit}",
            "tables_sql", "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = '{schema}'",
            "table_detail_sql", "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = '{table}' AND table_schema = '{schema}' ORDER BY ordinal_position"
    );

    // ─── Error messages ─────────────────────────────────────────────────────

    public static final String DDL_DISABLED_MSG =
            "DDL execution is disabled. Set entropy.mcp.database.ddl.allowed=true to enable.";

    public static final String GATEWAY_NOT_ENABLED_MSG = "Cross-database gateway is not enabled";

    public static final String FEDERATED_GATEWAY_NOT_ENABLED_MSG = "Federated gateway is not enabled";

    public static final String CONNECTION_NOT_FOUND = "Connection not found: ";

    // ─── Exception cause names ──────────────────────────────────────────────

    public static final String CAUSE_DISABLED = "DisabledException";
    public static final String CAUSE_VALIDATION = "ValidationException";
    public static final String CAUSE_NOT_FOUND = "NotFoundException";
    public static final String CAUSE_SECURITY = "SecurityException";
    public static final String CAUSE_CONFIGURATION = "ConfigurationException";
}
