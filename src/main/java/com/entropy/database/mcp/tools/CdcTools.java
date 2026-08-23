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
import com.entropy.database.mcp.properties.CdcProperties;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

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

    public CdcTools(CdcService cdcService, CdcProperties props) {
        this.cdcService = cdcService;
        this.props = props;
    }

    @McpTool(description = "检查指定连接是否支持 CDC（Flashback/binary log/pglogical 等）")
    public Map<String, Object> checkCdcSupport(
            @McpToolParam(description = "连接名称") String connection) {
        return safeExecute(() -> {
            boolean supported = cdcService.isCdcSupported(connection);
            Map<String, Object> r = context("connection", connection, "cdcSupported", supported);
            r.put("note", supported
                    ? "该数据库支持 CDC，可使用 readChanges / createMirrorTable 等功能"
                    : "该数据库不支持原生 CDC，建议配置触发器审计表或引入 Debezium");
            return success(r);
        });
    }

    @McpTool(description = "读取指定表的 CDC 变更事件：INSERT/UPDATE/DELETE 操作记录，支持从指定 LSN 开始拉取")
    public Map<String, Object> readChanges(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "Schema 名称") String schema,
            @McpToolParam(description = "表名") String table,
            @McpToolParam(description = "起始 LSN/SCN（可选，不传则从最新位置开始）") Long fromLsn) {
        return safeExecute(() -> {
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
            }).collect(Collectors.toList()));
            return success(result);
        });
    }

    @McpTool(description = "获取数据库当前的 LSN/SCN/binlog position，用于 CDC 断点续读")
    public Map<String, Object> getCurrentLsn(
            @McpToolParam(description = "连接名称") String connection) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            long lsn = cdcService.getLastLsn(connection);
            return success(context("connection", connection, "currentLsn", lsn));
        });
    }

    @McpTool(description = "创建镜像表：将源表结构和数据复制到目标表，用于 CDC 实时同步目标库")
    public Map<String, Object> createMirrorTable(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "源 Schema") String sourceSchema,
            @McpToolParam(description = "源表名") String sourceTable,
            @McpToolParam(description = "目标 Schema") String targetSchema,
            @McpToolParam(description = "目标表名") String targetTable) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            cdcService.createMirrorTable(connection, sourceSchema, sourceTable, targetSchema, targetTable);

            String source = sourceSchema + "." + sourceTable;
            String target = targetSchema + "." + targetTable;
            Map<String, Object> r = context("connection", connection, "source", source, "target", target);
            return success(r);
        });
    }

    @McpTool(description = "注册 CDC 订阅：指定监听哪些表/模式的变更事件（INSERT/UPDATE/DELETE）")
    public Map<String, Object> registerSubscription(
            @McpToolParam(description = "连接名称") String connection,
            @McpToolParam(description = "订阅名称（唯一标识）") String name,
            @McpToolParam(description = "Schema 名称") String schema,
            @McpToolParam(description = "表名模式（支持通配符，如 orders_*）") String tablePattern,
            @McpToolParam(description = "监听变更类型，逗号分隔：INSERT,UPDATE,DELETE", required = false) String changeTypes,
            @McpToolParam(description = "轮询间隔（毫秒），默认 1000", required = false) Long pollIntervalMs) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            validateRequired(name, "name");

            List<CdcChangeType> types = parseChangeTypes(changeTypes);
            long interval = (pollIntervalMs != null && pollIntervalMs > 0) ? pollIntervalMs : props.defaultPollIntervalMs();

            CdcSubscription sub = new CdcSubscription(name, connection, schema, tablePattern, types, interval, true);
            cdcService.registerSubscription(sub);

            Map<String, Object> r = context("subscription", name, "connection", connection, "schema", schema, "tablePattern", tablePattern);
            r.put("changeTypes", types.stream().map(CdcChangeType::getCode).collect(Collectors.toList()));
            r.put("pollIntervalMs", interval);
            return success(r);
        });
    }

    @McpTool(description = "列出当前所有 CDC 订阅及其状态")
    public Map<String, Object> listSubscriptions(
            @McpToolParam(description = "连接名称（可选，不传返回所有连接）") String connection) {
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
                m.put("changeTypes", s.changeTypes().stream().map(CdcChangeType::getCode).collect(Collectors.toList()));
                m.put("pollIntervalMs", s.pollIntervalMs());
                m.put("active", s.active());
                return m;
            }).collect(Collectors.toList()));
            return success(result);
        });
    }

    @McpTool(description = "注销指定的 CDC 订阅")
    public Map<String, Object> unregisterSubscription(
            @McpToolParam(description = "订阅名称") String name) {
        return safeExecute(() -> {
            cdcService.unregisterSubscription(name);
            return success("unsubscribed", name);
        });
    }

    @McpTool(description = "查看 CDC 引擎运行状态：LSN、已捕获事件数、活跃订阅数等")
    public Map<String, Object> getCdcStatus(
            @McpToolParam(description = "连接名称") String connection) {
        return safeExecute(() -> {
            validateRequired(connection, "connection");
            CdcStatus status = cdcService.getStatus(connection);
            Map<String, Object> r = context("connection", connection);
            r.put("cdcSupported", status.cdcSupported());
            r.put("currentLsn", status.currentLsn());
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

    @McpTool(description = "查看 CDC 模块配置参数")
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
                .collect(Collectors.toList());
    }
}
