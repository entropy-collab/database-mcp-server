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
import com.entropy.database.mcp.backup.BackupMetadata;
import com.entropy.database.mcp.backup.BackupMetadataRepository;
import com.entropy.database.mcp.backup.BackupType;
import com.entropy.database.mcp.properties.BackupProperties;
import com.entropy.database.mcp.service.DatabaseBackupService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Backup and restore MCP tools.
 */
@Component
public class BackupTools extends McpToolBase {

    private final DatabaseBackupService backupService;
    private final BackupMetadataRepository metadataRepository;
    private final BackupProperties backupProps;

    public BackupTools(DatabaseBackupService backupService,
                       BackupMetadataRepository metadataRepository,
                       BackupProperties backupProps) {
        this.backupService = backupService;
        this.metadataRepository = metadataRepository;
        this.backupProps = backupProps;
    }

    @McpTool(description = "Full backup of a table's data as INSERT statements")
    public Map<String, Object> backupTable(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Table name to backup") String tableName,
            @McpToolParam(description = "Maximum rows to backup (0 = unlimited)") int maxRows,
            @McpToolParam(description = "Backup type: FULL or INCREMENTAL") String type,
            @McpToolParam(description = "Output format: sql, json, or text", required = false) String format) {
        return safeExecute(() -> {
            String actualType = (type == null || type.isBlank()) ? "FULL" : type.toUpperCase();
            Map<String, Object> result = "INCREMENTAL".equals(actualType) && backupProps.incrementalEnabled()
                    ? backupService.backupDataIncremental(tableName, maxRows, connectionName)
                    : backupService.backupData(tableName, maxRows, connectionName);
            if (result.containsKey("error")) return result;
            return formatResult(result, format, actualType);
        });
    }

    @McpTool(description = "Backup table schema definition as DDL statements")
    public Map<String, Object> backupSchema(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Table name to backup schema for") String tableName) {
        return safeExecute(() -> backupService.backupSchema(tableName, connectionName));
    }

    @McpTool(description = "Restore a table from a backup by replaying the saved SQL script in a transaction")
    public Map<String, Object> restoreBackup(
            @McpToolParam(description = "Backup ID to restore from") String backupId,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName) {
        return safeExecute(() -> backupService.restoreBackup(backupId, connectionName));
    }

    @McpTool(description = "Quick restore: truncate target table then load data from backup")
    public Map<String, Object> quickRestore(
            @McpToolParam(description = "Backup ID to restore from") String backupId,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName) {
        return safeExecute(() -> backupService.quickRestore(backupId, connectionName));
    }

    @McpTool(description = "List backup records with optional filters")
    public Map<String, Object> listBackups(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "Filter by table name", required = false) String tableName,
            @McpToolParam(description = "Max records to return", required = false) Integer limit,
            @McpToolParam(description = "Filter by type: FULL, INCREMENTAL, or null for all", required = false) String typeFilter) {
        return safeExecute(() -> {
            int lim = limit != null && limit > 0 ? limit : 50;
            List<BackupMetadata> records = metadataRepository.list(connectionName, tableName, lim);
            if (typeFilter != null && !typeFilter.isBlank()) {
                try {
                    BackupType ft = BackupType.valueOf(typeFilter.toUpperCase());
                    records = records.stream().filter(m -> m.type() == ft).toList();
                } catch (IllegalArgumentException e) {
                    throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                            "Invalid backup type: " + typeFilter);
                }
            }
            List<Map<String, Object>> items = records.stream().map(this::toBackupItem).toList();
            return success(Map.of(
                    "total", items.size(),
                    "records", items,
                    "storageTotal", metadataRepository.size()
            ));
        });
    }

    @McpTool(description = "Get a single backup record by ID")
    public Map<String, Object> getBackup(@McpToolParam(description = "Backup ID") String backupId) {
        return safeExecute(() -> {
            BackupMetadata meta = metadataRepository.get(backupId);
            if (meta == null) throw new McpToolException(ErrorCode.NOT_FOUND, "Backup not found: " + backupId + " (backupId=" + backupId + ")");
            return success(toBackupMetaMap(meta));
        });
    }

    @McpTool(description = "Delete a backup record and its metadata")
    public Map<String, Object> deleteBackup(@McpToolParam(description = "Backup ID to delete") String backupId) {
        return safeExecute(() -> {
            boolean deleted = metadataRepository.delete(backupId);
            return success(Map.of("deleted", deleted, "backupId", backupId));
        });
    }

    @McpTool(description = "Clean up expired backup records older than retention period")
    public Map<String, Object> cleanupBackups(
            @McpToolParam(description = "Retention days (defaults to config)", required = false) Integer retentionDays) {
        return safeExecute(() -> {
            int days = retentionDays != null ? retentionDays : backupProps.retentionDays();
            int cleaned = metadataRepository.cleanupOldRecords(days);
            return success(Map.of("cleaned", cleaned, "retentionDays", days, "remaining", metadataRepository.size()));
        });
    }

    @McpTool(description = "Show current backup configuration")
    public Map<String, Object> getBackupConfig() {
        return safeExecute(() -> success(Map.of(
                "enabled", backupProps.enabled(),
                "incrementalEnabled", backupProps.incrementalEnabled(),
                "maxBackupRows", backupProps.maxBackupRows(),
                "retentionDays", backupProps.retentionDays(),
                "autoCleanup", backupProps.autoCleanup(),
                "defaultBackupSchema", backupProps.defaultBackupSchema(),
                "recoverModeCascade", backupProps.recoverModeCascade(),
                "totalBackups", metadataRepository.size()
        )));
    }

    private Map<String, Object> toBackupItem(BackupMetadata m) {
        var item = context(
                "backupId", m.backupId(),
                "tableName", m.tableName(),
                "connection", m.connectionKey(),
                "type", m.type().name(),
                "status", m.status().name(),
                "createdAt", m.createdAt().toString(),
                "totalRows", m.totalRows(),
                "backedUpRows", m.backedUpRows(),
                "restoredRows", m.restoredRows(),
                "durationMs", m.durationMs()
        );
        if (m.completedAt() != null) item.put("completedAt", m.completedAt().toString());
        if (m.errorDetail() != null) item.put("errorDetail", m.errorDetail());
        return item;
    }

    private Map<String, Object> toBackupMetaMap(BackupMetadata meta) {
        var map = context(
                "backupId", meta.backupId(),
                "tableName", meta.tableName(),
                "connection", meta.connectionKey(),
                "type", meta.type().name(),
                "status", meta.status().name(),
                "createdAt", meta.createdAt().toString(),
                "totalRows", meta.totalRows(),
                "backedUpRows", meta.backedUpRows(),
                "restoredRows", meta.restoredRows(),
                "durationMs", meta.durationMs()
        );
        if (meta.startedAt() != null) map.put("startedAt", meta.startedAt().toString());
        if (meta.completedAt() != null) map.put("completedAt", meta.completedAt().toString());
        if (meta.errorDetail() != null) map.put("errorDetail", meta.errorDetail());
        if (meta.sqlScript() != null) {
            map.put("sqlScriptPreview", meta.sqlScript().substring(0, Math.min(500, meta.sqlScript().length())) + "...");
        }
        return map;
    }

    private Map<String, Object> formatResult(Map<String, Object> result, String format, String backupType) {
        return switch (format != null ? format.toLowerCase() : "sql") {
            case "json" -> result;
            case "text" -> Map.of("backupType", backupType, "result", result);
            default -> {
                @SuppressWarnings("unchecked")
                var statements = (List<String>) result.get("statements");
                yield statements != null
                        ? Map.of("backupType", backupType, "backupId", result.get("backupId"),
                                "rowCount", result.get("rowCount"), "sqlStatements", statements)
                        : result;
            }
        };
    }
}
