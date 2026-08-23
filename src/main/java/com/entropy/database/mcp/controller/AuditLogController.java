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

    private final QueryAuditLogger auditLogger;
    private final AuditLogRepository auditLogRepository;

    public AuditLogController(QueryAuditLogger auditLogger, AuditLogRepository auditLogRepository) {
        this.auditLogger = auditLogger;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Polling endpoint: returns the most recent audit log entries from memory buffer.
     *
     * GET /api/audit/logs?limit=50&operation=executeQuery
     */
    @GetMapping("/logs")
    public List<Map<String, Object>> getLogs(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String operation) {
        List<Map<String, Object>> all = auditLogger.getRecentLogs(limit);
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
     */
    @GetMapping("/history")
    public List<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String connectionKey,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        Instant start = parseInstant(startTime);
        Instant end = parseInstant(endTime);
        List<AuditLogEntity> entities = auditLogRepository.query(tool, connectionKey, start, end, limit);
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

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
