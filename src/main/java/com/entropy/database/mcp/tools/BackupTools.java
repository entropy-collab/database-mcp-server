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
import com.entropy.database.mcp.backup.DatabaseBackupService;
import com.entropy.database.mcp.properties.BackupProperties;
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

    @McpTool(description = """
            【备份单表数据】把一张表的数据导出为可回放的 INSERT 语句，并在服务端登记一条备份记录。
            前置条件：先调用 createNamedConnection 注册数据库连接。备份记录只存在内存中（最多 200 条、最长保留 7 天），过期或被挤出后无法再用于恢复，重要备份请及时取走 sqlStatements 自行落盘。
            使用场景：改数据或跑 DDL 之前先留一份可回滚的数据快照。
            返回字段：format 省略或为 sql 时返回 backupType、backupId、rowCount、sqlStatements；format=json 返回 backupId、tableName、connection、type、status、maxRows、totalRows、rowCount、truncated（命中行数上限时为 true）、statements，截断时另有 hint；INCREMENTAL 额外返回 watermarkColumn、watermark、nextWatermark、rowsBackedUp；format=text 返回 backupType 与 result（内层同 json）。
            注意：truncated=true 表示只备到了一部分，备份记录状态记为 PARTIAL，这样的备份会被 quickRestore 拒绝（它会先清空表，用不完整备份还原等于丢数据）；增量备份被截断时水位只推进到已捕获的最大水位，重复调用即可续备剩余变更。
            不要用于：备份表结构（用 backupSchema，导出的是 DDL 而非数据）；恢复数据（用 restoreBackup 或 quickRestore）。本工具一次只处理一张表，没有整库备份能力。
            标签：[write, backup, table, data, export]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> backupTable(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "要备份的表名，必填") String tableName,
            @McpToolParam(description = "最大备份行数。传 0 或负数表示改用服务端配置的上限（entropy.mcp.database.backup.max-backup-rows，默认 500000）；传正数时取两者较小值") int maxRows,
            @McpToolParam(description = "备份类型，取值 FULL 或 INCREMENTAL，省略时按 FULL 处理。INCREMENTAL 还需服务端开启 incrementalEnabled，且表上要有可识别的时间戳水位列（如 UPDATED_AT、UPDATE_TIME、CREATED_AT），否则会返回 error 而不是退化成全量") String type,
            @McpToolParam(description = "输出格式，取值 sql、json、text，省略时按 sql 处理", required = false) String format) {
        return safeExecute(() -> {
            String actualType = (type == null || type.isBlank()) ? "FULL" : type.toUpperCase();
            Map<String, Object> result = "INCREMENTAL".equals(actualType) && backupProps.incrementalEnabled()
                    ? backupService.backupDataIncremental(tableName, maxRows, connectionName)
                    : backupService.backupData(tableName, maxRows, connectionName);
            if (result.containsKey("error")) return result;
            return formatResult(result, format, actualType);
        });
    }

    @McpTool(description = """
            【备份单表结构】导出一张表的建表 DDL，并登记一条备份记录。
            前置条件：先调用 createNamedConnection 注册连接。依赖方言提供 DDL 提取语句，目前只有 Oracle 实现（DBMS_METADATA.GET_DDL，需要相应权限），其他方言会因取不到 DDL 语句而失败。
            使用场景：改表结构前留存原始 DDL、或把表结构搬到另一套环境。
            返回字段：backupId、tableName、connection、type（固定为 SCHEMA）、ddl（建表语句文本）、rowCount（固定为 0）。
            注意：结构备份记录的 type 是 SCHEMA，只能用 restoreBackup 回放 DDL；用它的 backupId 调 quickRestore 会被拒绝，因为 quickRestore 先清空表、而结构备份里没有任何 INSERT。
            不要用于：备份表里的数据（用 backupTable）；对比两张表的结构差异（用 diffSchema）。名字里的 Schema 指「表结构」，不是整个数据库 Schema——本工具一次只导出一张表。
            标签：[write, backup, schema, ddl, export]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> backupSchema(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "要导出结构的表名，必填") String tableName) {
        return safeExecute(() -> backupService.backupSchema(tableName, connectionName));
    }

    @McpTool(description = """
            【回放备份脚本恢复】在单个事务中回放备份记录里保存的全部 SQL 语句，把数据追加回目标库。
            前置条件：需要先有 backupId（用 listBackups 找、用 getBackup 确认）。恢复前不会清空目标表，因此已存在的主键会导致冲突报错；任一语句失败则整个事务回滚，表保持原样，备份记录不会被改动、可以重试。
            使用场景：目标表为空或需要把备份数据追加进去；或备份内容不只是 INSERT（例如 backupSchema 产生的 DDL），需要原样回放。
            返回字段：backupId、tableName、connection、statementsApplied、restoredRows、status（COMPLETED）、restoreStartedAt、restoreCompletedAt、restoreDurationMs；备份本身不完整（PARTIAL）时额外返回 warning，说明回放进去的只是原表的一部分。
            不要用于：需要「先清空再灌回」的整表还原（用 quickRestore：它会先 DELETE 全表，再只回放 INSERT 语句）。
            标签：[write, restore, backup, transaction]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> restoreBackup(
            @McpToolParam(description = "备份 ID，必填。可从 backupTable 的返回值或 listBackups 的 records[].backupId 获取") String backupId,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName) {
        return safeExecute(() -> backupService.restoreBackup(backupId, connectionName));
    }

    @McpTool(description = """
            【整表还原】先清空目标表，再回放备份中的 INSERT 语句，把表还原成备份时的状态。
            前置条件：需要先有 backupId（用 listBackups 找、用 getBackup 确认）。只接受「完整的数据备份」：type=SCHEMA 的结构备份、status=PARTIAL 的截断备份、以及不含任何 INSERT 的备份都会返回 error 并且不动目标表——否则清空之后无可回放内容，等于把表清空并提交。清空用的是 DELETE 而非 TRUNCATE，因此整个过程在一个事务内：失败会回滚，原有数据不丢；代价是超大表较慢。备份脚本中的非 INSERT 语句会被跳过。
            使用场景：目标表数据已被写坏，需要丢弃现有数据、整表回到备份时点。
            返回字段：backupId、tableName、connection、statementsApplied（实际回放的 INSERT 条数）、restoredRows（含 DELETE 影响的行数）、status（QUICK_RESTORE_COMPLETED）、restoreStartedAt、restoreCompletedAt、restoreDurationMs；被拒绝时返回 error 与 hint（附带 backupType 或 backupStatus）。
            不要用于：只想把备份数据追加进表、不想清空现有数据（用 restoreBackup）；回放 DDL 类备份或不完整备份（用 restoreBackup，本工具只回放 INSERT 且要求备份完整）。
            标签：[write, restore, backup, truncate, destructive]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> quickRestore(
            @McpToolParam(description = "备份 ID，必填。目标表取自该备份记录中的 tableName，不能另行指定") String backupId,
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName) {
        return safeExecute(() -> backupService.quickRestore(backupId, connectionName));
    }

    @McpTool(description = """
            【列出备份记录】按连接、表名、类型筛选备份记录清单。
            使用场景：恢复前先找到目标 backupId、或盘点当前留存了哪些备份。
            返回字段：total（本次返回条数）、storageTotal（服务端当前留存的备份总数）、records（数组，每项含 backupId、tableName、connection、type、status、createdAt、totalRows、backedUpRows、restoredRows、durationMs，若有则附带 completedAt、errorDetail）。status=PARTIAL 表示该备份命中行数上限、只备了一部分，不能用于 quickRestore。
            不要用于：查看某条备份的完整信息含 SQL 脚本预览（用 getBackup 按 id 取详情，列表里不含 sqlScriptPreview 与 startedAt）。
            标签：[read, backup, list]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listBackups(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "按表名精确过滤（区分大小写），省略则不过滤", required = false) String tableName,
            @McpToolParam(description = "返回条数上限，省略或传非正数时默认 50", required = false) Integer limit,
            @McpToolParam(description = "按备份类型过滤，取值 FULL、INCREMENTAL 或 SCHEMA（结构备份）；省略则返回全部，传其他值会报参数校验错误", required = false) String typeFilter) {
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

    @McpTool(description = """
            【查看单条备份详情】按 backupId 取一条备份记录的完整信息。
            前置条件：需要已知 backupId；不确定时先用 listBackups 检索。备份记录会因过期或容量上限被淘汰，找不到时返回 NOT_FOUND 错误，说明该备份已不可用于恢复。
            使用场景：恢复前确认这条备份的表名、行数、状态与脚本内容是否符合预期。
            返回字段：backupId、tableName、connection、type、status、createdAt、totalRows、backedUpRows、restoredRows、durationMs，以及（存在时）startedAt、completedAt、errorDetail、sqlScriptPreview（SQL 脚本前 500 字符预览）。type=SCHEMA 表示只含 DDL、没有数据；status=PARTIAL 表示只备到了一部分，这两种备份都不能用于 quickRestore。
            不要用于：按条件检索多条记录（用 listBackups）。
            标签：[read, backup, detail]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getBackup(@McpToolParam(description = "备份 ID，必填") String backupId) {
        return safeExecute(() -> {
            BackupMetadata meta = metadataRepository.get(backupId);
            if (meta == null) throw new McpToolException(ErrorCode.NOT_FOUND, "Backup not found: " + backupId + " (backupId=" + backupId + ")");
            return success(toBackupMetaMap(meta));
        });
    }

    @McpTool(description = """
            【删除单条备份】按 backupId 删除一条备份记录及其保存的 SQL 脚本。删除不可恢复，删除后该备份再也无法用于恢复。
            前置条件：建议先用 getBackup 确认这条记录确实不再需要。
            使用场景：明确某条备份已作废、需要立即释放它占用的内存。
            返回字段：deleted（true 表示确实删掉了，false 表示该 id 本来就不存在）、backupId。
            不要用于：按保留天数批量清理过期备份（用 cleanupBackups）。
            标签：[write, backup, delete, destructive]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> deleteBackup(@McpToolParam(description = "要删除的备份 ID，必填") String backupId) {
        return safeExecute(() -> {
            boolean deleted = metadataRepository.delete(backupId);
            return success(Map.of("deleted", deleted, "backupId", backupId));
        });
    }

    @McpTool(description = """
            【按保留期批量清理备份】删除创建时间早于保留期的所有备份记录。这是批量删除且不可恢复，被清掉的备份无法再用于恢复。
            前置条件：建议先用 listBackups 确认将被清理的范围，再调用本工具。
            使用场景：定期清理陈旧备份、释放服务端内存。
            返回字段：cleaned（本次删除的条数）、retentionDays（本次实际生效的保留天数）、remaining（清理后仍留存的备份总数）。
            不要用于：删除指定的某一条备份（用 deleteBackup）。
            标签：[write, backup, cleanup, retention, destructive]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> cleanupBackups(
            @McpToolParam(description = "保留天数，创建时间早于「当前时间减该天数」的记录会被删除。省略时取服务端配置 entropy.mcp.database.backup.retention-days（默认 30）", required = false) Integer retentionDays) {
        return safeExecute(() -> {
            int days = retentionDays != null ? retentionDays : backupProps.retentionDays();
            int cleaned = metadataRepository.cleanupOldRecords(days);
            return success(Map.of("cleaned", cleaned, "retentionDays", days, "remaining", metadataRepository.size()));
        });
    }

    @McpTool(description = """
            【查看备份模块配置】读取服务端备份模块的当前配置与备份留存数量。
            使用场景：调用备份工具前先确认功能是否开启、增量备份是否可用、单次备份的行数上限是多少。
            返回字段：enabled、incrementalEnabled、maxBackupRows、retentionDays、autoCleanup、defaultBackupSchema、recoverModeCascade、totalBackups（当前留存的备份条数）。
            标签：[read, backup, config]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
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
