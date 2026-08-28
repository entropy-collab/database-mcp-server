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
package com.entropy.database.mcp.controller;

import com.entropy.database.mcp.audit.AuditLogEntity;
import com.entropy.database.mcp.audit.AuditLogRepository;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditLogController {

    /**
     * Upper bound for the {@code limit} request parameter. Audit rows carry the original SQL, so a
     * single unbounded call would both dump the whole history and hold it all in memory.
     */
    static final int MAX_LIMIT = 1000;

    private final QueryAuditLogger auditLogger;

    /**
     * Absent when no {@code spring.datasource.url} is configured — {@link AuditLogRepository} is
     * gated on that key, and BYOK deployments routinely run without one. The in-memory
     * {@code /logs} endpoint still works in that mode, so the controller must not require it.
     */
    private final AuditLogRepository auditLogRepository;

    public AuditLogController(QueryAuditLogger auditLogger,
                              @org.springframework.lang.Nullable AuditLogRepository auditLogRepository) {
        this.auditLogger = auditLogger;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Polling endpoint: returns the most recent audit log entries from memory buffer.
     *
     * GET /api/audit/logs?limit=50&operation=executeQuery
     *
     * <p>{@code @RequestParam} 一律写出显式 name：不写时 Spring 只能靠 class 文件里的
     * {@code MethodParameters} 反推参数名，而那段信息只有编译带 {@code -parameters} 才会写入。
     * 下游用自己的构建配置重新编译/重打包（不继承 spring-boot-starter-parent 的 compiler 配置）时参数名会
     * 退化成 {@code arg0}，请求进来直接 {@code IllegalArgumentException: Name for argument of type [int] not
     * specified}——是启动后每次调用都失败，而不是编译期报错。
     */
    @GetMapping("/logs")
    public List<Map<String, Object>> getLogs(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "operation", required = false) String operation) {
        List<Map<String, Object>> all = auditLogger.getRecentLogs(clampLimit(limit));
        if (operation != null && !operation.isBlank()) {
            return all.stream()
                    .filter(log -> operation.equals(log.get("tool")))
                    .toList();
        }
        return all;
    }

    /**
     * Query persisted audit logs from database with optional filters.
     *
     * GET /api/audit/history?limit=50&tool=executeQuery&connectionKey=primary&startTime=2024-01-01T00:00:00Z&endTime=2024-01-02T00:00:00Z
     *
     * <p>参数名同样显式写出，理由见 {@link #getLogs}。
     */
    @GetMapping("/history")
    public List<Map<String, Object>> getHistory(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "tool", required = false) String tool,
            @RequestParam(name = "connectionKey", required = false) String connectionKey,
            @RequestParam(name = "startTime", required = false) String startTime,
            @RequestParam(name = "endTime", required = false) String endTime) {
        if (auditLogRepository == null) {
            // No persistence configured: report it rather than 500, and point at the in-memory endpoint.
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Audit history is not persisted: no spring.datasource.url is configured. "
                            + "Use /api/audit/logs for the in-memory buffer.");
        }
        Instant start = parseInstant(startTime);
        Instant end = parseInstant(endTime);
        List<AuditLogEntity> entities =
                auditLogRepository.query(tool, connectionKey, start, end, clampLimit(limit));
        return entities.stream()
                .<Map<String, Object>>map(entity -> Map.of(
                    "id", entity.id(),
                    "tool", entity.tool(),
                    "sql", entity.sql(),
                    "rows", entity.rows(),
                    "durationMs", entity.durationMs(),
                    "success", entity.success(),
                    "error", entity.error(),
                    "timestamp", entity.timestamp().toString(),
                    "connectionKey", entity.connectionKey()
                ))
                .toList();
    }

    private static int clampLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
