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

import com.entropy.database.mcp.security.QueryAuditLogger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit")
public class AuditLogController {

    private final QueryAuditLogger auditLogger;

    public AuditLogController(QueryAuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    /**
     * Polling endpoint: returns the most recent audit log entries.
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
                    .collect(Collectors.toList());
        }
        return all;
    }
}
