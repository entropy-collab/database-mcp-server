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
package com.entropy.database.mcp.security;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Query audit logger for tracking database operations.
 */
public interface QueryAuditLogger {

    /**
     * Log audit entry asynchronously to avoid blocking query execution.
     */
    void log(String tool, String sql, int rowCount, long durationMs, boolean success, @Nullable String connectionKey);

    /**
     * Log audit entry with error details asynchronously.
     */
    void log(String tool, String sql, int rowCount, long durationMs, boolean success, @Nullable String error, @Nullable String connectionKey);

    /**
     * 显式传入 principal 的审计入口。用于 SecurityContext 尚未就绪或已被清除的场景——
     * 典型如表单登录成功（filter 写 context 之前）、登录失败（压根没有 context）、退出（context 刚被清掉）。
     * 普通 MCP 工具调用走上面的两个重载即可，principal 会从 SecurityContextHolder 取。
     */
    void logWithPrincipal(String tool, String sql, int rowCount, long durationMs, boolean success,
                          @Nullable String error, @Nullable String connectionKey, @Nullable String principal);

    /**
     * Returns the most recent {@code limit} buffered audit log entries, newest first.
     */
    List<Map<String, Object>> getRecentLogs(int limit);
}
