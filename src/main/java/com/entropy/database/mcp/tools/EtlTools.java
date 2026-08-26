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

import com.entropy.database.mcp.config.DatabaseConstants;
import com.entropy.database.mcp.properties.EtlConfig;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.etl.JobExecutionEngine;
import com.entropy.database.mcp.etl.JobExecution;
import com.entropy.database.mcp.etl.MigrationJob;
import com.entropy.database.mcp.etl.Step;
import com.entropy.database.mcp.etl.StepType;
import com.entropy.database.mcp.facade.DatabaseOperations;
import com.entropy.database.mcp.security.SqlValidator;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.entropy.database.mcp.util.ValidationUtils.*;

/**
 * Unified ETL tools.
 * Replaces: DataMigrationTools (data operations), EtlJobTools
 */
@Component
@ConditionalOnProperty(name = "entropy.mcp.gateway.enabled", havingValue = "true")
public class EtlTools extends McpToolBase {

    private static final int DEFAULT_BATCH_INSERT_SIZE = 1000;

    private final DynamicDataSourceManager dataSourceManager;
    private final DatabaseOperations routingFacade;
    private final JobExecutionEngine executionEngine;
    private final EtlConfig etlConfig;
    private final SqlValidator sqlValidator;

    /**
     * @param dataSourceManager only for {@link #createNamedConnection}: registering a connection is
     *                          connection-management, not a database operation, so it has no seam
     *                          on the facade.
     */
    public EtlTools(DynamicDataSourceManager dataSourceManager,
                    DatabaseOperations routingFacade,
                    JobExecutionEngine executionEngine,
                    EtlConfig etlConfig,
                    SqlValidator sqlValidator) {
        this.dataSourceManager = dataSourceManager;
        this.routingFacade = routingFacade;
        this.executionEngine = executionEngine;
        this.etlConfig = etlConfig;
        this.sqlValidator = sqlValidator;
    }

    @McpTool(description = """
            【注册数据库连接】创建一个命名的 BYOK 连接：建连接池、跑一次连通性测试查询，成功后即可被其他工具按名引用。
            前置条件：需开启 entropy.mcp.gateway.enabled=true（本类全部工具都受该开关控制）。
            使用场景：使用本服务任何查询、写入、DDL 工具之前的第一步。
            注意：注册成功后建议先调用 describeConnection 确认连接就绪，再执行查询。
            返回字段：connectionName、dialect（实际生效的方言，未显式传入时由 jdbcUrl 推断）、message、recommendation。
            不要用于：创建 Oracle 跨库链路（用 createDbLink）；把库注册进联邦网关（联邦网关的 databaseId 由服务端注册，见 listDatabases）。
            标签：[write, connection, byok, setup]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public Map<String, Object> createNamedConnection(
            @McpToolParam(description = "连接名，后续所有工具用它引用这个数据库；同名重复注册会复用已有连接池") String name,
            @McpToolParam(description = "JDBC 连接串，必填（如 jdbc:oracle:thin:@host:1521/svc、jdbc:mysql://host:3306/db）") String jdbcUrl,
            @McpToolParam(description = "数据库登录用户名，必填") String username,
            @McpToolParam(description = "数据库登录密码；传 null 视为空字符串") String password,
            @McpToolParam(description = "数据库方言，取值：oracle、mysql、postgres、sqlserver、sqlite、db2、h2、generic；留空时按 jdbcUrl 自动推断") String dialect) {
        return safeExecute(() -> {
            ConnectionProperties properties = ConnectionProperties.builder()
                    .jdbcUrl(jdbcUrl)
                    .username(username)
                    .password(password)
                    .dialect(dialect)
                    .build();
            properties.validate();
            ByokDataSourceContext context = dataSourceManager.acquire(name, properties);
            context.getJdbcTemplate().queryForList(context.getDialect().connectionTestQuery());
            // Connection registration is synchronous, but the MCP tool result is serialized to the client.
            // Advise the LLM to verify the connection before use, as rapid subsequent calls may race with
            // the response delivery.
            return success(Map.of(
                    "connectionName", name,
                    "dialect", properties.dialect(),
                    "message", "Connection created and tested successfully. Call describeConnection to confirm before querying.",
                    "recommendation", "Call describeConnection(\"connection\": \"" + name + "\") before using this connection for queries."
            ));
        });
    }

    @McpTool(description = """
            【批量插入外部数据】把调用方直接给出的行数据按 JDBC 批量方式插入目标表。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；先用 createNamedConnection 注册连接；connectionName 必填不可省略；rows 不能为空。
            使用场景：数据来自模型或外部系统（已经拿在手里的 JSON 行），需要一次性写库。
            注意：列名取自 rows 第一行的键，后续行按同一组列取值，缺失的键写入 null；纯 INSERT，不做去重也不做更新。
            返回字段：connectionName、tableName、rowCount（实际写入行数）、batchSize、durationMs、message。
            不要用于：数据来自 SQL 查询结果（同库或跨库用 insertQueryResult，需分页搬大表用 exportQueryToTable）；需要按主键幂等覆盖（用 upsertData）；需要列改名或大小写转换（用 transformAndInsert）。
            标签：[write, etl, insert, batch]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> insertData(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "目标表名，须为合法标识符") String tableName,
            @McpToolParam(description = "待插入的行列表，每个 Map 是一行（键为列名）；列集合以第一行为准") List<Map<String, Object>> rows,
            @McpToolParam(description = "JDBC 批量提交的批大小，必须为正数；传 null 时使用配置项 entropy.mcp.database.etl.batch-size") Integer batchSize) throws Exception {
        return safeExecute(() -> {
            requireNotBlank(connectionName, "connectionName");
            requireNotBlank(tableName, "tableName");
            validateIdentifier(tableName, "tableName");
            requireNotEmpty(rows, "rows");
            List<String> columns = rows.get(0).keySet().stream().toList();
            int size = batchSize != null ? batchSize : etlConfig.batchSize();
            if (size <= 0) throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "batchSize must be positive, got: " + size);
            long startTime = System.currentTimeMillis();
            long totalRows = routingFacade.batchInsert(tableName, columns, toPositionalRows(columns, rows), size, connectionName);
            return success(Map.of(
                    "connectionName", connectionName, "tableName", tableName,
                    "rowCount", totalRows, "batchSize", size,
                    "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Inserted %d rows into %s", totalRows, tableName)
            ));
        });
    }

    @McpTool(description = """
            【查询结果搬运】在源连接上执行 SELECT，把结果整批写入目标连接的表，支持跨连接搬数。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；源、目标连接都要先用 createNamedConnection 注册；sourceSql 只允许 SELECT。
            使用场景：把一个库的查询结果落到另一个库（或同库另一张表），列名与源结果集保持一致。
            注意：源结果会先整体读入内存再写出，batchSize 只控制写入侧，大表请在 sourceSql 里自行加过滤或行数限制；源结果为空时返回 message=No rows returned 与 rowCount=0。
            返回字段：sourceConnection、targetConnection、targetTable、rowCount、batchSize、durationMs、message。
            不要用于：需要按页搬运超大结果（用 exportQueryToTable，内部按 continuationToken 翻页）；需要改列名或做大小写/类型转换（用 transformAndInsert）；数据不是查出来而是外部传入（用 insertData）。
            标签：[write, etl, insert, copy]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> insertQueryResult(
            @McpToolParam(description = "源库连接名（读取数据的一侧）。" + ToolParams.CONNECTION_DESCRIPTION) String sourceConnectionName,
            @McpToolParam(description = "源库上执行的 SELECT 语句，只允许查询") String sourceSql,
            @McpToolParam(description = "目标库连接名（写入数据的一侧）。" + ToolParams.CONNECTION_DESCRIPTION) String targetConnectionName,
            @McpToolParam(description = "目标表名，须为合法标识符，且列名要与源结果集一致") String targetTable,
            @McpToolParam(description = "写入侧的批大小，必须为正数；传 null 时默认 1000") Integer batchSize) throws Exception {
        return safeExecute(() -> {
            validateIdentifier(targetTable, "targetTable");
            if (sqlValidator != null) sqlValidator.validateSelect(sourceSql);
            int size = batchSize != null ? batchSize : DatabaseConstants.DEFAULT_BATCH_SIZE;
            if (size <= 0) throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "batchSize must be positive, got: " + size);
            long startTime = System.currentTimeMillis();
            long totalRows = routingFacade.copyRows(sourceSql, sourceConnectionName, targetTable, null, size, targetConnectionName);
            // An empty source is the only way nothing lands: a successful insert reports at least
            // one row per submitted row. Keep the dedicated response the caller had before.
            if (totalRows == 0) return emptyResult();
            return success(Map.of(
                    "sourceConnection", sourceConnectionName, "targetConnection", targetConnectionName,
                    "targetTable", targetTable, "rowCount", totalRows, "batchSize", size,
                    "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Inserted %d rows from query into %s", totalRows, targetTable)
            ));
        });
    }

    @McpTool(description = """
            【转换后插入】在同一个连接内把源表数据按列映射搬到目标表，可对每列施加一个转换函数。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；连接已注册且 connectionName 必填不可省略；源表、目标表、映射两侧都必须是该方言下的合法标识符（不接受表达式或子查询）。
            使用场景：源表与目标表列名不一致、或需要统一大小写/去空格/改数值类型的同库搬数。
            注意：源表数据先整体读入内存再批量写出；源表查不到数据时返回 message=No rows returned 与 rowCount=0；纯 INSERT，不做幂等覆盖。
            返回字段：connectionName、sourceTable、targetTable、rowCount、durationMs、message。
            不要用于：跨连接搬数（用 insertQueryResult）；列名完全一致无需转换（用 insertQueryResult 更直接）；按主键覆盖（用 upsertData）。
            标签：[write, etl, transform, insert]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> transformAndInsert(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "源表名，须为合法标识符") String sourceTable,
            @McpToolParam(description = "目标表名，须为合法标识符") String targetTable,
            @McpToolParam(description = "列映射列表，每项格式 源列:目标列[:转换]；转换可选值：upper、lower、trim、int、long、double，省略或写其它值均按原值不转换（如 ['id:ID', 'name:FULL_NAME:upper']）") List<String> columnMapping,
            @McpToolParam(description = "可选的过滤条件，只写 WHERE 之后的部分（不含 WHERE 关键字），会经过合法性校验；省略则全表", required = false) String whereClause,
            @McpToolParam(description = "写入侧的批大小，必须为正数；省略时默认 1000", required = false) Integer batchSize) throws Exception {
        return safeExecute(() -> {
            validateTransformParams(connectionName, sourceTable, targetTable, columnMapping);
            DatabaseDialect dialect = routingFacade.getDialect(connectionName);
            List<String> sourceColumns = new ArrayList<>();
            List<String> targetColumns = new ArrayList<>();
            List<String> transforms = new ArrayList<>();

            for (String mapping : columnMapping) {
                String[] parts = mapping.split(":");
                if (parts.length < 2) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid column mapping: " + mapping + ". Expected format: source:target[:transform] (connectionName=" + connectionName + ", sourceTable=" + sourceTable + ", targetTable=" + targetTable + ")");
                }
                // Both halves of the mapping are interpolated into the SELECT list, so each one has
                // to be a plain identifier: without this, "(SELECT PASSWORD FROM X):Y" is a valid
                // mapping that smuggles a subquery into the projection.
                sourceColumns.add(requireColumnIdentifier(parts[0], dialect, "columnMapping source column"));
                targetColumns.add(requireColumnIdentifier(parts[1], dialect, "columnMapping target column"));
                transforms.add(parts.length >= 3 ? parts[2] : "none");
            }

            String selectSql = buildTransformSelect(sourceColumns, transforms, targetColumns, sourceTable, whereClause, dialect);
            List<Map<String, Object>> rows = routingFacade.queryRows(selectSql, connectionName);
            if (rows.isEmpty()) return emptyResult();

            int size = batchSize != null ? batchSize : DatabaseConstants.DEFAULT_BATCH_SIZE;
            if (size <= 0) throw new IllegalArgumentException("batchSize must be positive, got: " + size);
            long startTime = System.currentTimeMillis();
            long totalRows = routingFacade.batchInsert(targetTable, targetColumns,
                    toPositionalRows(targetColumns, rows), size, connectionName);
            return success(Map.of(
                    "connectionName", connectionName, "sourceTable", sourceTable, "targetTable", targetTable,
                    "rowCount", totalRows, "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Transformed and inserted %d rows", totalRows)
            ));
        });
    }

    @McpTool(description = """
            【幂等写入】按键列匹配做插入或更新（UPSERT），同一批数据重复执行结果一致。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；连接已注册且 connectionName 必填不可省略；rows 与 keyColumns 都不能为空；目标表在 keyColumns 上应有唯一约束或主键。
            使用场景：重放数据、增量同步、失败重试等要求幂等的写入场景。
            注意：列集合取自 rows 第一行的键；批大小固定为 1000，不可配置；键列已存在的行会被更新，属于覆盖写。
            返回字段：connectionName、tableName、keyColumns、rowCount、durationMs、message。
            不要用于：确定只需追加、不希望覆盖已有行（用 insertData）；数据来自查询结果（用 insertQueryResult）。
            标签：[write, etl, upsert, idempotent]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> upsertData(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "目标表名，须为合法标识符") String tableName,
            @McpToolParam(description = "用于匹配已有行的键列列表，不能为空（如 ['id']）") List<String> keyColumns,
            @McpToolParam(description = "待写入的行列表，每个 Map 是一行（键为列名）；列集合以第一行为准，且需包含全部 keyColumns") List<Map<String, Object>> rows) throws Exception {
        return safeExecute(() -> {
            requireNotBlank(connectionName, "connectionName");
            requireNotBlank(tableName, "tableName");
            validateIdentifier(tableName, "tableName");
            requireNotEmpty(rows, "rows");
            requireNotEmpty(keyColumns, "keyColumns");
            List<String> allColumns = rows.get(0).keySet().stream().toList();
            long startTime = System.currentTimeMillis();
            long totalRows = routingFacade.batchUpsert(tableName, keyColumns, allColumns,
                    toPositionalRows(allColumns, rows), DEFAULT_BATCH_INSERT_SIZE, connectionName);
            return success(Map.of(
                    "connectionName", connectionName, "tableName", tableName,
                    "keyColumns", keyColumns, "rowCount", totalRows, "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Upserted %d rows into %s", totalRows, tableName)
            ));
        });
    }

    @McpTool(description = """
            【表数据质量校验】统计指定列的空值、整表在这些列上的重复行，并给出通过率评分。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；连接已注册。省略 columns 时按连接所用方言读取该表的列元数据自动取列，8 种方言均支持。
            使用场景：数据搬运后做落地校验、排查主键或唯一键重复、快速评估某张表的数据整洁度。
            检查项：每个列各做一次 IS NULL 计数（发现即记 NULL_VALUES，severity=WARNING）；对全部列组合做一次重复行计数（发现即记 DUPLICATES，severity=ERROR）；再统计整表行数。
            返回字段：summary（含 table、totalRows、columnsChecked、issuesFound、totalChecks、passed、failed、qualityScore）、issues（数组，每项含 type、column 或 columns、count、severity）、message。
            不要用于：按规则模板做可配置的质量校验（用 checkTableQuality）；只想看行数估算（用 estimateTableSize）。
            标签：[read, etl, quality, validation]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> validateDataQuality(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "要校验的表名，须为该方言下的合法标识符") String tableName,
            @McpToolParam(description = "要检查的列名列表；省略或传空列表时按连接方言的列元数据查询自动读取表的全部列", required = false) List<String> columns) {
        return safeExecute(() -> {
            DatabaseDialect dialect = routingFacade.getDialect(connectionName);
            if (!dialect.isValidIdentifier(tableName)) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid table name: " + tableName + " (connectionName=" + connectionName + ", tableName=" + tableName + ")");
            }
            String validatedTable = dialect.normalizeTableName(tableName);
            // 自动取列原来直接查 Oracle 专有的数据字典 user_tab_columns。那张表只有 Oracle 有，所以在
            // MySQL/PostgreSQL/SQL Server/DB2/SQLite/H2/Generic 上，调用方只要省略 columns，这条查询就会以
            // 「表/视图不存在」的 SQL 错误终止整个工具——即工具在 8 个方言里只有 1 个能用。改走方言层的
            // columnsQuery：各方言各自指向自己的列元数据来源（information_schema.columns、SYSCAT.COLUMNS、
            // pragma_table_info、all_tab_columns），语义一致且不依赖任何单一厂商的字典表。
            List<String> colList = (columns == null || columns.isEmpty())
                    ? readColumnNames(dialect, connectionName, validatedTable)
                    : columns;
            for (String column : colList) {
                if (!dialect.isValidIdentifier(column)) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid column name: " + column + " (connectionName=" + connectionName + ", tableName=" + tableName + ", column=" + column + ")");
                }
            }
            String columnList = colList.stream().map(dialect::quote).reduce((a, b) -> a + ", " + b).orElse("");
            List<Map<String, Object>> issues = new ArrayList<>();
            int totalChecks = 0;
            for (String column : colList) {
                totalChecks++;
                Long nullCount = queryCount("SELECT COUNT(*) FROM " + validatedTable + " WHERE " + dialect.quote(column) + " IS NULL", connectionName);
                if (nullCount != null && nullCount > 0) issues.add(Map.of("type", "NULL_VALUES", "column", column, "count", nullCount, "severity", "WARNING"));
            }
            totalChecks++;
            // The derived table needs an alias: MySQL and PostgreSQL reject an unaliased one, which
            // made this count silently answer 0 there.
            Long duplicateCount = queryCount(
                    "SELECT COUNT(*) FROM (SELECT COUNT(*) cnt FROM " + validatedTable + " GROUP BY " + columnList + " HAVING COUNT(*) > 1) t", connectionName);
            if (duplicateCount != null && duplicateCount > 0) issues.add(Map.of("type", "DUPLICATES", "columns", colList, "count", duplicateCount, "severity", "ERROR"));
            totalChecks++;
            Long rowCount = queryCount("SELECT COUNT(*) FROM " + validatedTable, connectionName);
            double score = totalChecks > 0 ? (double) (totalChecks - issues.size()) / totalChecks * 100 : 100.0;
            return success(Map.of(
                    "summary", Map.of("table", validatedTable, "totalRows", rowCount != null ? rowCount : 0,
                            "columnsChecked", colList.size(), "issuesFound", issues.size(),
                            "totalChecks", totalChecks, "passed", totalChecks - issues.size(),
                            "failed", issues.size(), "qualityScore", score),
                    "issues", issues,
                    "message", String.format("Data quality check completed: %.1f%% passed", score)
            ));
        });
    }

    @McpTool(description = """
            【分页导出到表】在同一连接内按页读取 SELECT 结果并逐页批量写入目标表，适合搬运大结果集。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；连接已注册；targetTable 须为该方言下的合法标识符，且列名与源结果集一致。
            使用场景：结果集大到不宜一次读进内存，需要边翻页边落表。
            注意：翻页页大小等于 batchSize；最多翻 1000 页，达到上限会停止并在服务端日志告警，此时 rowCount 只反映已写入部分。
            返回字段：connectionName、sourceQuery、targetTable、rowCount、batchSize、durationMs、message。
            不要用于：跨连接搬数（用 insertQueryResult）；结果集很小且无需翻页（用 insertQueryResult 更简单）；需要列改名或转换（用 transformAndInsert）；导出成文件而非表（用 exportCsv 或 exportJson）。
            标签：[write, etl, export, batch, paginated]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> exportQueryToTable(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connectionName,
            @McpToolParam(description = "源 SELECT 语句，将被分页执行") String sourceSql,
            @McpToolParam(description = "目标表名，须为合法标识符，列名要与源结果集一致") String targetTable,
            @McpToolParam(description = "每页读取并写入的行数；省略时默认 1000", required = false) Integer batchSize) throws Exception {
        return safeExecute(() -> {
            DatabaseDialect dialect = routingFacade.getDialect(connectionName);
            if (!dialect.isValidIdentifier(targetTable)) {
                throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "Invalid target table name: " + targetTable + " (connectionName=" + connectionName + ")");
            }
            String validatedTargetTable = dialect.normalizeTableName(targetTable);
            int size = batchSize != null ? batchSize : DatabaseConstants.DEFAULT_BATCH_SIZE;
            long startTime = System.currentTimeMillis();
            int totalRows = 0;
            String continuationToken = null;
            int maxPages = DatabaseConstants.DEFAULT_BATCH_SIZE; // safety limit: max DEFAULT_BATCH_SIZE pages × batchSize rows
            int pageCount = 0;
            do {
                PaginatedQueryResult result = routingFacade.executeQuery(sourceSql, size, continuationToken, connectionName);
                if (result.rows().isEmpty()) break;
                List<Map<String, Object>> rows = result.rows();
                List<String> columns = rows.get(0).keySet().stream().toList();
                routingFacade.batchInsert(validatedTargetTable, columns, toPositionalRows(columns, rows), size, connectionName);
                totalRows += rows.size();
                continuationToken = result.continuationToken();
                if (++pageCount >= maxPages) {
                    log.warn("exportQueryToTable reached maxPages={} for table={}, stopping", maxPages, validatedTargetTable);
                    break;
                }
            } while (continuationToken != null && !continuationToken.isBlank());
            return success(Map.of(
                    "connectionName", connectionName, "sourceQuery", sourceSql,
                    "targetTable", validatedTargetTable, "rowCount", totalRows, "batchSize", size,
                    "durationMs", System.currentTimeMillis() - startTime,
                    "message", String.format("Exported %d rows to %s", totalRows, validatedTargetTable)
            ));
        });
    }

    @McpTool(description = """
            【提交 ETL 作业】按 MigrationJob DSL 提交一个多步骤作业，异步执行并立即返回，步骤间可声明依赖关系。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；各步骤用到的连接都要先用 createNamedConnection 注册；steps 不能为空。
            使用场景：需要多步编排（先读、再转换、再写、最后建索引）、步骤有先后依赖、或耗时较长不宜同步等待。
            调用方式：本工具只负责提交，不等待完成；提交后必须用 getJobStatus 轮询 jobId 查看进度与每步结果，可用 listJobs 查看全部作业、stopJob 请求停止。
            jobDefinition 结构：{id, name, description, steps:[{id, type, dependsOn, connection, sourceSql, targetTable, targetConnection, params}]}。step 的 id 或 type 为空会被跳过并记日志；type 非法会报错；params 必须是对象。
            返回字段：jobId、jobName、totalSteps（实际被接受的步骤数）、status（提交时的初始状态）、message。
            不要用于：单步同步写入（用 insertData / insertQueryResult / transformAndInsert / upsertData / exportQueryToTable，这些会当场返回 rowCount）。
            标签：[write, etl, job, async]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> submitEtlJob(@McpToolParam(description = "作业定义对象，须为 Map：id（作业标识）、name（作业名）、description（可选说明）、steps（步骤数组，不能为空）。每个步骤含 id、type（取值：query_to_table、query_to_json、read、transform、ddl、upsert、export，不区分大小写）、dependsOn（前置步骤 id 数组或逗号分隔字符串，可省略）、connection、sourceSql、targetTable、targetConnection、params（对象，放该步骤类型专用参数）") Object jobDefinition) {
        return safeExecute(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> jd = (Map<String, Object>) jobDefinition;
            String jobId = (String) jd.get("id");
            String jobName = (String) jd.get("name");
            String description = (String) jd.getOrDefault("description", "");
            List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) jd.get("steps");
            requireNotEmpty(stepDefs, "steps");
            List<Step> steps = new ArrayList<>();
            for (Map<String, Object> stepDef : stepDefs) {
                String stepId = (String) stepDef.get("id");
                String typeStr = (String) stepDef.get("type");
                if (stepId == null || stepId.isBlank()) { log.warn("Skipping step with null or blank id"); continue; }
                if (typeStr == null || typeStr.isBlank()) { log.warn("Skipping step with null or blank type for step id: {}", stepId); continue; }
                StepType type = StepType.from(typeStr);
                List<String> dependsOn;
                if (stepDef.containsKey("dependsOn")) {
                    Object deps = stepDef.get("dependsOn");
                    if (deps instanceof List<?> depList) {
                        dependsOn = new ArrayList<>();
                        depList.forEach(dep -> dependsOn.add(dep.toString()));
                    } else if (deps instanceof String depStr) {
                        dependsOn = List.of(depStr.split(","));
                    } else {
                        dependsOn = new ArrayList<>();
                    }
                } else {
                    dependsOn = new ArrayList<>();
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> params = new HashMap<>();
                if (stepDef.containsKey("params")) {
                    Object rawParams = stepDef.get("params");
                    if (!(rawParams instanceof Map<?, ?>)) throw new McpToolException(ErrorCode.PARAMETER_VALIDATION_FAILED, "params must be a map");
                    params.putAll((Map<String, Object>) rawParams);
                }
                steps.add(new Step(stepId, type, dependsOn,
                        (String) stepDef.get("connection"), (String) stepDef.get("sourceSql"),
                        (String) stepDef.get("targetTable"), (String) stepDef.get("targetConnection"), params));
            }
            JobExecution execution = executionEngine.submit(new MigrationJob(jobId, jobName, description, steps));
            return success(Map.of(
                    "jobId", jobId, "jobName", jobName, "totalSteps", steps.size(),
                    "status", execution.status(), "message", "Job submitted successfully"
            ));
        });
    }

    @McpTool(description = """
            【查询作业状态】按 jobId 查看某个 ETL 作业的整体进度与每个步骤的执行明细。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；jobId 来自 submitEtlJob 的返回值；作业不存在会报错。
            使用场景：submitEtlJob 之后轮询进度，或作业失败后定位是哪一步、错在哪。
            返回字段：job 对象，含 jobId、jobName、status、startedAt、completedAt、progress（百分比字符串，如 60.0%）、steps（数组，每项含 stepId、status、startedAt、completedAt、rowsAffected、error）。
            不要用于：查看全部作业概览（用 listJobs）。
            标签：[read, etl, job, status]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getJobStatus(@McpToolParam(description = "作业标识，取值为 submitEtlJob 返回的 jobId") String jobId) {
        return safeExecute(() -> {
            Optional<JobExecution> execution = executionEngine.getExecution(jobId);
            if (execution.isEmpty()) throw new McpToolException(ErrorCode.CONNECTION_NOT_FOUND, "Job not found: " + jobId + " (jobId=" + jobId + ")");
            var exec = execution.get();
            List<Map<String, Object>> stepStates = exec.stepStates().entrySet().stream()
                    .map(e -> {
                        var state = e.getValue();
                        return context(
                                "stepId", state.stepId(), "status", state.status(),
                                "startedAt", state.startedAt() != null ? state.startedAt().toString() : null,
                                "completedAt", state.completedAt() != null ? state.completedAt().toString() : null,
                                "rowsAffected", state.rowsAffected(), "error", state.error());
                    }).toList();
            return success(Map.of("job", context(
                    "jobId", exec.jobId(), "jobName", exec.jobName(), "status", exec.status(),
                    "startedAt", exec.startedAt() != null ? exec.startedAt().toString() : null,
                    "completedAt", exec.completedAt() != null ? exec.completedAt().toString() : null,
                    "progress", String.format("%.1f%%", exec.getProgress()),
                    "steps", stepStates)));
        });
    }

    @McpTool(description = """
            【列出所有作业】列出本进程内已提交的全部 ETL 作业概览，按 jobId 升序排列。
            前置条件：需开启 entropy.mcp.gateway.enabled=true。作业记录保存在内存中，服务重启后清空。
            使用场景：忘记 jobId、需要挑出仍在运行或失败的作业。
            返回字段：totalJobs、jobs（数组，每项含 jobId、jobName、status、startedAt、completedAt、progress、totalSteps、completedSteps、failedSteps）。
            不要用于：查看单个作业的步骤级明细（用 getJobStatus）。
            标签：[read, etl, job, list]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listJobs() {
        return safeExecute(() -> {
            List<Map<String, Object>> jobs = executionEngine.listExecutions().stream()
                    .map(exec -> Map.<String, Object>of(
                            "jobId", exec.jobId(), "jobName", exec.jobName(), "status", exec.status(),
                            "startedAt", exec.startedAt() != null ? exec.startedAt().toString() : null,
                            "completedAt", exec.completedAt() != null ? exec.completedAt().toString() : null,
                            "progress", String.format("%.1f%%", exec.getProgress()),
                            "totalSteps", exec.stepStates().size(),
                            "completedSteps", exec.getCompletedStepIds().size(),
                            "failedSteps", exec.getFailedStepIds().size()))
                    .sorted(Comparator.comparing(m -> (String) m.get("jobId")))
                    .toList();
            return success(Map.of("totalJobs", jobs.size(), "jobs", jobs));
        });
    }

    @McpTool(description = """
            【请求停止作业】校验 jobId 存在后记录一次停止请求。
            前置条件：需开启 entropy.mcp.gateway.enabled=true；jobId 必须存在，否则报错。
            使用场景：想中止一个长时间运行的作业。
            注意：当前实现只发出停止信号并写日志，不会真正中断正在执行的步骤，作业状态也不会因此改变；优雅停止需要接入生产级调度器。停止后请用 getJobStatus 确认作业实际状态。
            返回字段：jobId、message。
            标签：[write, etl, job, control]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> stopJob(@McpToolParam(description = "要停止的作业标识，取值为 submitEtlJob 返回的 jobId") String jobId) {
        return safeExecute(() -> {
            Optional<JobExecution> execution = executionEngine.getExecution(jobId);
            if (execution.isEmpty()) throw new McpToolException(ErrorCode.CONNECTION_NOT_FOUND, "Job not found: " + jobId + " (jobId=" + jobId + ")");
            log.warn("Stop requested for job: {} (not implemented in demo)", jobId);
            return success(Map.of("jobId", jobId, "message", "Stop signal sent (requires production job scheduler for graceful shutdown)"));
        });
    }

    private static final java.util.function.Function<String, String> IDENTITY = java.util.function.Function.identity();
    private static final java.util.Map<String, java.util.function.Function<String, String>> TRANSFORM_FN = java.util.Map.of(
            "upper",  s -> "UPPER(" + s + ")",
            "lower",  s -> "LOWER(" + s + ")",
            "trim",   s -> "TRIM(" + s + ")",
            "int",    s -> "CAST(" + s + " AS INTEGER)",
            "long",   s -> "CAST(" + s + " AS BIGINT)",
            "double", s -> "CAST(" + s + " AS DOUBLE)"
    );

    /**
     * Builds the transform projection.
     *
     * <p>Column names are quoted, and the caller has already checked that each one is a plain
     * identifier: quoting on its own would not stop {@code "(SELECT ...)"} from becoming an
     * expression, because the fragment is placed in the SELECT list, not in a value position.
     */
    private String buildTransformSelect(List<String> sourceColumns, List<String> transforms, List<String> targetColumns,
                                         String sourceTable, String whereClause, DatabaseDialect dialect) {
        List<String> selectExprs = new ArrayList<>();
        for (int i = 0; i < sourceColumns.size(); i++) {
            String src = dialect.quote(sourceColumns.get(i));
            String transform = transforms.get(i);
            String expr = TRANSFORM_FN.getOrDefault(transform, IDENTITY).apply(src);
            selectExprs.add(expr + " AS " + dialect.quote(targetColumns.get(i)));
        }
        String sql = "SELECT " + String.join(", ", selectExprs) + " FROM " + sourceTable;
        if (whereClause != null && !whereClause.isBlank()) {
            validateWhereClause(whereClause, "whereClause");
            sql += " WHERE " + whereClause;
        }
        return sql;
    }

    /** Accepts a mapping half only if it is a plain identifier for the connection's dialect. */
    private static String requireColumnIdentifier(String column, DatabaseDialect dialect, String paramName) {
        String trimmed = column == null ? null : column.trim();
        if (trimmed == null || !dialect.isValidIdentifier(trimmed)) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "Invalid " + paramName + ": " + column);
        }
        return trimmed;
    }

    /**
     * Adapt the column-keyed rows the MCP contract accepts to the positional shape the write
     * facade takes, so column order is fixed once here instead of by a PreparedStatement setter.
     */
    private static List<List<Object>> toPositionalRows(List<String> columns, List<Map<String, Object>> rows) {
        List<List<Object>> positional = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            List<Object> values = new ArrayList<>(columns.size());
            for (String column : columns) {
                values.add(row.get(column));
            }
            positional.add(values);
        }
        return positional;
    }

    /**
     * Read a single-row, single-column aggregate.
     *
     * <p>The value arrives as whatever numeric type the driver picked (Oracle answers COUNT(*) as
     * BigDecimal), so it is narrowed here rather than relying on a typed {@code queryForObject}.
     */
    private Long queryCount(String sql, String connectionName) {
        List<Map<String, Object>> rows = routingFacade.queryRows(sql, connectionName);
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            return null;
        }
        Object value = rows.get(0).values().iterator().next();
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 表的列名清单，取自方言层的列元数据查询。
     *
     * <p>绑定参数契约（见 {@link DatabaseDialect} 类注释）：单表元数据查询有且只有 1 个 {@code ?}，
     * 绑定值必须是方言归一化后的表名；schema 永不作为占位符，由方言内联进 SQL。多绑一个 schema 或
     * 一个都不绑，都会在 8 个方言里立刻炸（Oracle 会把 owner 绑成 NULL 从而一列都查不到）。
     *
     * @param normalizedTable 已经过 {@link DatabaseDialect#normalizeTableName(String)} 的表名
     */
    private List<String> readColumnNames(DatabaseDialect dialect, String connectionName, String normalizedTable) {
        return routingFacade.queryRows(dialect.columnsQuery(normalizedTable, null), connectionName, normalizedTable)
                .stream()
                .map(EtlTools::columnNameOf)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 从元数据结果行里大小写不敏感地取 {@code column_name}。
     *
     * <p>结果集列标签的大小写因库而异：Oracle/H2/DB2/SQL Server 报 {@code COLUMN_NAME}，MySQL/
     * PostgreSQL 报 {@code column_name}。按固定拼写直接取会读成 null，列清单静默变空——校验会声称
     * 「0 列全部通过、质量分 100」，且与「表真的没有可检查的列」不可区分。与
     * {@code QualityCheckService.columnNameOf} 保持同一读法。
     */
    private static String columnNameOf(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("column_name")) {
                return entry.getValue() == null ? null : String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private void validateTransformParams(String connectionName, String sourceTable,
                                          String targetTable, List<String> columnMapping) {
        requireNotBlank(connectionName, "connectionName");
        requireNotBlank(sourceTable, "sourceTable");
        requireNotBlank(targetTable, "targetTable");
        validateIdentifier(sourceTable, "sourceTable");
        validateIdentifier(targetTable, "targetTable");
        requireNotEmpty(columnMapping, "columnMapping");
        for (String mapping : columnMapping) {
            String[] parts = mapping.split(":");
            if (parts.length < 2) {
                throw new McpValidationException(
                        com.entropy.database.mcp.exception.ErrorCode.PARAMETER_VALIDATION_FAILED,
                        "Invalid column mapping: " + mapping + ". Expected format: source:target[:transform]");
            }
        }
    }
}
