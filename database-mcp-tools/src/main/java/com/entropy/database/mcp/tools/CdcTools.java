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

import com.entropy.database.mcp.cdc.*;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.properties.CdcProperties;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CDC (Change Data Capture) tools.
 * Provides real-time data mirroring, streaming query results, and data change event listening.
 *
 * <p>Extends {@link McpToolBase} to inherit uniform exception handling, parameter validation,
 * and response building — eliminating the repetitive try-catch pattern across all methods.
 */
@Component
public class CdcTools extends McpToolBase {

    private final CdcService cdcService;
    private final CdcProperties props;
    /** Same gate as {@code DdlExecutionTools}: createMirrorTable is CREATE TABLE AS SELECT. */
    private final boolean ddlAllowed;

    public CdcTools(CdcService cdcService, CdcProperties props, Environment environment) {
        this.cdcService = cdcService;
        this.props = props;
        this.ddlAllowed = Boolean.parseBoolean(
                environment.getProperty("entropy.mcp.database.ddl.allowed", "false"));
    }

    /**
     * CDC 模块总开关。{@code enable-mirror-tables} 与 {@code ddl.allowed} 都是硬闸门，总开关却只在
     * getCdcConfig 里被回显——关掉它以后所有工具照旧可用，配置形同虚设。这里把它变成同级别的硬闸门：
     * 凡是会连库读变更、读位点或写入的工具都前置拒绝，只留 getCdcConfig / checkCdcSupport 做诊断
     * （前者读配置、后者只探测能力，都不产生变更流量）。
     *
     * <p>listSubscriptions / unregisterSubscription 不设闸门：它们只读写本进程内存里的订阅登记、不碰
     * 数据库，而且开关被关掉后仍然需要能看到并清理残留登记。
     */
    private void requireCdcEnabled(String tool) {
        if (!props.enabled()) {
            throw new McpToolException(ErrorCode.SQL_OPERATION_NOT_ALLOWED,
                    ("CDC is disabled: %s is not available. Set entropy.mcp.database.cdc.enabled=true to enable it. "
                            + "Only getCdcConfig and checkCdcSupport stay available for diagnostics.")
                            .formatted(tool));
        }
    }

    @McpTool(description = """
            【检查 CDC 支持情况】探测指定连接的数据库是否具备可用的变更捕获能力。使用 CDC 系列工具前的第一步，先用它确认，再决定是否继续。
            前置条件：必须传 connection——先调用 createNamedConnection 注册连接。本工具与 getCdcConfig 一样，即使 entropy.mcp.database.cdc.enabled=false 也可调用，便于诊断。
            检测方式：只探测实例级条件，按各方言真实的读取机制判断。Oracle 走闪回版本查询，检查 undo_retention 是否大于 0（需要 v$parameter 权限，无权限按不支持处理）；MySQL 读触发器审计表、位点取 UNIX_TIMESTAMP()，都不依赖 binlog，故只验证连接可用；PostgreSQL 读触发器审计表、位点取 pg_current_wal_lsn()，因此要求实例不处于恢复状态（备库返回 false）；H2、SQL Server、SQLite、DB2 等方言未实现 CDC，一律返回 false。表级条件（审计表是否存在、源表上是否有 SELECT 权限）无法在连接级别探测，只会在 readChanges 时报错。
            使用场景：接入变更同步前的可行性判断；CDC 工具报错后回头确认是能力缺失还是参数问题。
            返回字段：connection、cdcSupported（布尔）、note（中文结论与后续建议）。
            不要用于：查看 CDC 引擎的运行状态与已捕获事件数（用 getCdcStatus）。
            标签：[read, cdc, capability, precheck]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> checkCdcSupport(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return safeExecute(() -> {
            boolean supported = cdcService.isCdcSupported(connection);
            Map<String, Object> r = context("connection", connection, "cdcSupported", supported);
            r.put("note", supported
                    ? "该数据库支持 CDC，可使用 readChanges / createMirrorTable 等功能"
                    : "该数据库不支持原生 CDC，建议配置触发器审计表或引入 Debezium");
            return success(r);
        });
    }

    @McpTool(description = """
            【读取表的变更事件】从指定位点开始拉取一张表的数据变更记录。
            前置条件：服务端必须开启 entropy.mcp.database.cdc.enabled=true，否则直接拒绝；connection 与 table 必填（connection 虽标为可选，为空会被拒绝）；建议先用 checkCdcSupport 确认能力、再用 getCurrentLsn 拿到起始位点。各方言差异很大：Oracle 走闪回版本查询（VERSIONS BETWEEN SCN），能给出真实的 I/U/D 操作类型，但 beforeJson/afterJson 恒为空、primaryKeys 是 ROWID；MySQL 与 PostgreSQL 都读约定的触发器审计表「原表名_audit」（需事先自行创建并挂触发器，MySQL 的 binlog 只能靠 Debezium 消费、这里读不到），因此操作类型无法还原，changeType 恒为 TRIGGER_AUDIT；H2、SQL Server、SQLite、DB2 未实现，调用直接报错。
            使用场景：增量同步、审计追踪；配合 fromLsn 做断点续读。
            返回字段：connection、schema、table、fromLsn（本次实际使用的起始位点）、eventCount、events（数组，每项含 changeType（形如「I (插入)」）、changeTime、primaryKeys、beforeJson、afterJson、transactionId、status）。
            不要用于：只想知道当前日志位点（用 getCurrentLsn）；只想看引擎状态与累计事件数（用 getCdcStatus）；订阅登记（用 registerSubscription——本工具是主动拉取，不依赖订阅）。
            标签：[read, cdc, changes, incremental]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> readChanges(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection,
            @McpToolParam(description = "Schema 名。Oracle 用于限定源表所属用户；MySQL/PostgreSQL 用于限定审计表所在库或 Schema。传 null 表示使用连接的当前 Schema") String schema,
            @McpToolParam(description = "表名，必填。传原始业务表名即可，MySQL/PostgreSQL 会自动拼成「表名_audit」去读") String table,
            @McpToolParam(description = "起始位点，单位随方言而定，必须来自同一连接的 getCurrentLsn：Oracle 是 SCN、PostgreSQL 是 WAL LSN 解码后的整数、MySQL 是 Unix 秒级时间戳（对应审计表的 event_time）。传 null 或非正数时会自动取当前最新位点作为起点（因此通常读不到历史变更），做增量拉取时应传上一次的位点") Long fromLsn) {
        return safeExecute(() -> {
            requireCdcEnabled("readChanges");
            validateRequired(connection, "connection");
            validateRequired(table, "table");

            long lsn = fromLsn != null && fromLsn > 0 ? fromLsn : cdcService.getLastLsn(connection);
            List<CdcChangeEvent> events = cdcService.readChanges(connection, schema, table, lsn);

            Map<String, Object> result = context("connection", connection, "schema", schema, "table", table, "fromLsn", lsn);
            result.put("eventCount", events.size());
            result.put("events", events.stream().map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("changeType", e.changeType().getCode() + " (" + e.changeType().getZh() + ")");
                m.put("changeTime", e.changeTime().toString());
                m.put("primaryKeys", e.primaryKeys());
                m.put("beforeJson", e.beforeJson());
                m.put("afterJson", e.afterJson());
                m.put("transactionId", e.transactionId());
                m.put("status", e.status().name());
                return m;
            }).toList());
            return success(result);
        });
    }

    @McpTool(description = """
            【获取当前日志位点】读取数据库当前的日志位点，用于 CDC 断点续读的起点或终点标记。
            前置条件：服务端必须开启 entropy.mcp.database.cdc.enabled=true，否则直接拒绝；connection 必填（虽标为可选，为空会被拒绝）。Oracle 读 v$database.CURRENT_SCN（需要 v$ 视图权限）；MySQL 执行 SELECT UNIX_TIMESTAMP()，返回的是秒级时间戳，与审计表 event_time 同一单位；PostgreSQL 读 pg_current_wal_lsn() 并把十六进制 LSN 解码为整数；H2、SQL Server、SQLite、DB2 未实现，调用直接报错。
            使用场景：开始增量同步前先记录位点，之后把它作为 readChanges 的 fromLsn。
            返回字段：connection、currentLsn（整数）。
            不要用于：读取变更数据本身（用 readChanges）；查看引擎状态汇总（用 getCdcStatus，它在位点读取失败时会返回文字说明而不是抛错）。
            标签：[read, cdc, lsn, watermark]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getCurrentLsn(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return safeExecute(() -> {
            requireCdcEnabled("getCurrentLsn");
            validateRequired(connection, "connection");
            long lsn = cdcService.getLastLsn(connection);
            return success(context("connection", connection, "currentLsn", lsn));
        });
    }

    @McpTool(description = """
            【创建镜像表】用 CREATE TABLE AS SELECT 把源表的结构与当前数据整体复制到目标表。
            前置条件：属于 DDL 操作，服务端必须同时开启 entropy.mcp.database.cdc.enabled=true、entropy.mcp.database.ddl.allowed=true 与 entropy.mcp.database.cdc.enable-mirror-tables=true，否则直接拒绝；connection、sourceTable、targetSchema、targetTable 必填；所有库表名都必须是合法标识符（不接受引号或特殊字符）。目标表若已存在会因建表失败而报错。仅 Oracle、MySQL、PostgreSQL 实现，其他方言报「不支持」。
            使用场景：为 CDC 同步准备目标表、或做一次一致性快照留底。
            返回字段：connection、source（源 schema.表名）、target（目标 schema.表名）。
            不要用于：只导出数据不建表（用 backupTable）；持续同步增量变更（本工具只做一次性复制，增量需自行调用 readChanges 后写入）。
            标签：[write, cdc, ddl, mirror, snapshot]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> createMirrorTable(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection,
            @McpToolParam(description = "源 Schema 名，可传 null 表示使用连接的当前 Schema") String sourceSchema,
            @McpToolParam(description = "源表名，必填") String sourceTable,
            @McpToolParam(description = "目标 Schema 名，必填") String targetSchema,
            @McpToolParam(description = "目标表名，必填；该表不能已存在") String targetTable) {
        return safeExecute(() -> {
            // CREATE TABLE ... AS SELECT is DDL and runs outside SqlValidationAspect, so it must
            // pass the same gate as DdlExecutionTools.executeDdl.
            requireCdcEnabled("createMirrorTable");
            if (!ddlAllowed) {
                throw new McpToolException(ErrorCode.SQL_OPERATION_NOT_ALLOWED, ToolParams.DDL_DISABLED_MSG);
            }
            validateRequired(connection, "connection");
            validateRequired(sourceTable, "sourceTable");
            validateRequired(targetTable, "targetTable");
            validateRequired(targetSchema, "targetSchema");
            if (!props.enableMirrorTables()) {
                throw new McpToolException(ErrorCode.SQL_OPERATION_NOT_ALLOWED,
                        "Mirror tables are disabled. Set entropy.mcp.database.cdc.enable-mirror-tables=true to enable.");
            }
            cdcService.createMirrorTable(connection, sourceSchema, sourceTable, targetSchema, targetTable);

            String source = sourceSchema + "." + sourceTable;
            String target = targetSchema + "." + targetTable;
            Map<String, Object> r = context("connection", connection, "source", source, "target", target);
            return success(r);
        });
    }

    @McpTool(description = """
            【登记 CDC 订阅】登记一条订阅，声明要关注哪个连接、哪些表、哪些变更类型。
            前置条件：服务端必须开启 entropy.mcp.database.cdc.enabled=true，否则直接拒绝；connection 与 name 必填（connection 虽标为可选，为空会被拒绝）；建议先用 checkCdcSupport 确认该库支持 CDC。订阅只保存在服务进程内存中，重启即丢失；同名订阅会被直接覆盖。
            使用场景：把关注范围登记下来供后续查询与管理；实际拉取变更数据仍然要自己调用 readChanges，登记订阅本身不会产生推送。
            返回字段：subscription（订阅名）、connection、schema、tablePattern、changeTypes（解析后的类型码数组）、pollIntervalMs（本次实际生效的轮询间隔）。
            不要用于：读取变更数据（用 readChanges）；查看已有订阅（用 listSubscriptions）；取消订阅（用 unregisterSubscription）。
            标签：[write, cdc, subscription, register]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> registerSubscription(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection,
            @McpToolParam(description = "订阅名称，必填且全局唯一；重名会覆盖已有订阅") String name,
            @McpToolParam(description = "Schema 名，可传 null 表示不限定 Schema") String schema,
            @McpToolParam(description = "表名匹配模式，支持通配符（如 orders_*）") String tablePattern,
            @McpToolParam(description = "关注的变更类型，逗号分隔。合法取值：INSERT 或 I、UPDATE 或 U、DELETE 或 D、DDL、TRUNCATE 或 T、TRIGGER_AUDIT、FLASHBACK；无法识别的值会被静默丢弃。省略时默认 INSERT,UPDATE,DELETE", required = false) String changeTypes,
            @McpToolParam(description = "轮询间隔毫秒数，需为正数；省略或非正数时取服务端配置 entropy.mcp.database.cdc.default-poll-interval-ms（默认 1000）", required = false) Long pollIntervalMs) {
        return safeExecute(() -> {
            requireCdcEnabled("registerSubscription");
            validateRequired(connection, "connection");
            validateRequired(name, "name");

            List<CdcChangeType> types = parseChangeTypes(changeTypes);
            long interval = (pollIntervalMs != null && pollIntervalMs > 0) ? pollIntervalMs : props.defaultPollIntervalMs();

            CdcSubscription sub = new CdcSubscription(name, connection, schema, tablePattern, types, interval, true);
            cdcService.registerSubscription(sub);

            Map<String, Object> r = context("subscription", name, "connection", connection, "schema", schema, "tablePattern", tablePattern);
            r.put("changeTypes", types.stream().map(CdcChangeType::getCode).toList());
            r.put("pollIntervalMs", interval);
            return success(r);
        });
    }

    @McpTool(description = """
            【列出 CDC 订阅】列出已登记的订阅及其配置与启用状态。
            前置条件：无。只读服务进程内存中的订阅登记，不连数据库，因此 entropy.mcp.database.cdc.enabled=false 时仍可调用（用于查看开关关闭前留下的登记）。
            使用场景：确认订阅名是否已存在、核对某条订阅关注的表范围与变更类型。传 connection 只返回该连接的订阅，省略则返回全部连接的订阅。
            返回字段：connection（回显入参）、totalCount、subscriptions（数组，每项含 name、connection、schema、tablePattern、changeTypes、pollIntervalMs、active）。
            不要用于：查看引擎运行指标（用 getCdcStatus）；读取变更数据（用 readChanges）。
            标签：[read, cdc, subscription, list]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listSubscriptions(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION) String connection) {
        return safeExecute(() -> {
            List<CdcSubscription> subs = cdcService.listSubscriptions(connection);
            Map<String, Object> result = context("connection", connection);
            result.put("totalCount", subs.size());
            result.put("subscriptions", subs.stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.name());
                m.put("connection", s.connection());
                m.put("schema", s.schema());
                m.put("tablePattern", s.tablePattern());
                m.put("changeTypes", s.changeTypes().stream().map(CdcChangeType::getCode).toList());
                m.put("pollIntervalMs", s.pollIntervalMs());
                m.put("active", s.active());
                return m;
            }).toList());
            return success(result);
        });
    }

    @McpTool(description = """
            【注销 CDC 订阅】按订阅名删除一条订阅登记。
            前置条件：建议先用 listSubscriptions 确认订阅名。传入不存在的名字不会报错。只清理服务进程内存中的登记，不连数据库，因此 entropy.mcp.database.cdc.enabled=false 时仍可调用。
            使用场景：不再关注某个表范围时清理订阅。
            返回字段：unsubscribed（被注销的订阅名）。
            不要用于：删除已读取的变更数据或影响数据库本身——本工具只清理服务端内存中的订阅登记，不动数据库。
            标签：[write, cdc, subscription, unregister]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> unregisterSubscription(
            @McpToolParam(description = "订阅名称，必填") String name) {
        return safeExecute(() -> {
            cdcService.unregisterSubscription(name);
            return success("unsubscribed", name);
        });
    }

    @McpTool(description = """
            【查看 CDC 运行状态】汇总某个连接的 CDC 能力、当前位点、订阅数与累计事件数。
            前置条件：服务端必须开启 entropy.mcp.database.cdc.enabled=true，否则直接拒绝（只有 getCdcConfig 与 checkCdcSupport 在关闭时仍可用）；connection 必填（虽标为可选，为空会被拒绝）。事件计数与最后事件时间只统计本进程通过 readChanges 实际读到的量，进程重启后归零。
            使用场景：巡检同步是否在推进；位点读不出来时用它定位（不会抛错，而是把 currentLsn 报成说明文字）。
            返回字段：connection、cdcSupported、currentLsn（位点整数；读取失败时为字符串「unavailable ...」）、activeSubscriptions、totalEventsCaptured、lastEventTime（yyyy-MM-dd HH:mm:ss，从未有事件时为 N/A）。
            不要用于：读取变更数据（用 readChanges）；只要精确位点数值（用 getCurrentLsn，失败会明确报错而不是降级）；只判断能力（用 checkCdcSupport）。
            标签：[read, cdc, status, monitoring]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getCdcStatus(
            @McpToolParam(description = ToolParams.CONNECTION_DESCRIPTION, required = false) String connection) {
        return safeExecute(() -> {
            requireCdcEnabled("getCdcStatus");
            validateRequired(connection, "connection");
            CdcStatus status = cdcService.getStatus(connection);
            Map<String, Object> r = context("connection", connection);
            r.put("cdcSupported", status.cdcSupported());
            // A negative watermark means the LSN query itself failed; report that rather than
            // letting the client read it as "sitting at position 0".
            r.put("currentLsn", status.currentLsn() >= 0 ? status.currentLsn() : "unavailable (LSN query failed, see server logs)");
            r.put("activeSubscriptions", status.activeSubscriptions());
            r.put("totalEventsCaptured", status.totalEventsCaptured());
            r.put("lastEventTime", status.lastEventTime() > 0
                    ? Instant.ofEpochMilli(status.lastEventTime())
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : "N/A");
            return success(r);
        });
    }

    @McpTool(description = """
            【查看 CDC 模块配置】读取服务端 CDC 模块的当前配置开关与默认值。
            前置条件：无。本工具与 checkCdcSupport 是 enabled=false 时唯一仍可调用的两个 CDC 工具。
            使用场景：调用 readChanges、getCurrentLsn、registerSubscription、createMirrorTable、getCdcStatus 等受开关约束的工具前，先确认 enabled 是否为 true、镜像表是否开启、默认轮询间隔是多少。enabled=false 时上述工具会直接拒绝并提示配置项全名。
            返回字段：enabled、enableRealtimeStreaming、maxEventsPerPoll、defaultPollIntervalMs、enableMirrorTables、maxMirrorTasks、enableEventListeners。
            不要用于：判断某个数据库是否支持 CDC（用 checkCdcSupport，这里只反映服务端配置）。
            标签：[read, cdc, config]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getCdcConfig() {
        return success(Map.of(
                "enabled", props.enabled(),
                "enableRealtimeStreaming", props.enableRealtimeStreaming(),
                "maxEventsPerPoll", props.maxEventsPerPoll(),
                "defaultPollIntervalMs", props.defaultPollIntervalMs(),
                "enableMirrorTables", props.enableMirrorTables(),
                "maxMirrorTasks", props.maxMirrorTasks(),
                "enableEventListeners", props.enableEventListeners()
        ));
    }

    // ─── Private Helpers ──────────────────────────────────────────────────

    private List<CdcChangeType> parseChangeTypes(String changeTypes) {
        if (changeTypes == null || changeTypes.isBlank()) {
            return List.of(CdcChangeType.INSERT, CdcChangeType.UPDATE, CdcChangeType.DELETE);
        }
        return Arrays.stream(changeTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(CdcChangeType::fromCode)
                .filter(Objects::nonNull)
                .toList();
    }
}
