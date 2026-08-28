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
package com.entropy.database.mcp.cdc;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpQueryException;
import com.entropy.database.mcp.exception.McpValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDC service implementation using database-specific change capture mechanisms.
 *
 * <p>Strategy selection per dialect:
 * <ul>
 *   <li>Oracle — Flashback Version Query (VERSIONS BETWEEN SCN, real I/U/D per row version)</li>
 *   <li>MySQL — Trigger-based audit tables with a Unix-second watermark (binlog requires Debezium)</li>
 *   <li>PostgreSQL — trigger-based audit with WAL LSN watermark</li>
 * </ul>
 *
 * <p>Failure semantics: every read either returns the changes it found or throws. Returning an
 * empty list (or {@code 0} for a watermark) on error is not allowed, because the caller cannot tell
 * that apart from "the table did not change".
 */
@Service
public class CdcServiceImpl implements CdcService {

    private static final Logger log = LoggerFactory.getLogger(CdcServiceImpl.class);

    /** Sentinel reported by {@link #getStatus} when the watermark could not be read. */
    static final long LSN_UNAVAILABLE = -1L;

    private final DynamicDataSourceManager dataSourceManager;
    private final ConcurrentHashMap<String, CdcSubscription> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> eventCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastEventTimes = new ConcurrentHashMap<>();

    public CdcServiceImpl(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    // ─── CDC Support Check ────────────────────────────────────────────────

    @Override
    public boolean isCdcSupported(String connection) {
        ByokDataSourceContext ctx = null;
        try {
            ctx = dataSourceManager.acquire(connection);
            DatabaseDialect dialect = ctx.getDialect();
            String sql = dialect.cdcCheckSupportSql();
            if (sql == null) {
                return false;
            }
            // 用 queryForList + 首行首值，而不是 queryForObject：后者要求结果「恰好一行」，探测 SQL 只要
            // 多返回一行（历史上 Oracle/PostgreSQL 的多段 UNION ALL 在多个分支命中时就是如此）就抛
            // IncorrectResultSizeDataAccessException，被下面的 catch 吞成「不支持」——配置最完整的库反而
            // 被判成不支持。方言侧的契约仍是单行单值，这里只是不再让「恰好一行」成为正确性的前提。
            List<Map<String, Object>> rows = ctx.getJdbcTemplate().queryForList(sql);
            if (rows.isEmpty()) {
                return false;
            }
            return isTrue(rows.getFirst().values().stream().findFirst().orElse(null));
        } catch (Exception e) {
            log.warn("CDC support check failed for '{}': {}", connection, e.getMessage(), e);
            return false;
        }
    }

    /** Reads the probe value as a flag: {@code 1} / {@code true} means supported. */
    private static boolean isTrue(Object value) {
        if (value instanceof Number number) {
            return number.longValue() != 0L;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && ("1".equals(value.toString().trim()) || "true".equalsIgnoreCase(value.toString().trim()));
    }

    // ─── Read Changes ─────────────────────────────────────────────────────

    @Override
    public List<CdcChangeEvent> readChanges(String connection, String schema, String table, long fromLsn) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        JdbcTemplate jdbc = ctx.getJdbcTemplate();

        // The read SQL interpolates schema/table, so whitelist them before they reach the driver.
        requireIdentifier(dialect, table, "table");
        if (schema != null && !schema.isBlank()) {
            requireIdentifier(dialect, schema, "schema");
        }

        String sql = dialect.cdcReadChangesSql(schema, table, fromLsn);
        if (sql == null) {
            throw new McpQueryException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "CDC read is not supported for dialect '%s' (connection=%s)"
                            .formatted(dialect.getDialectName(), connection));
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql, dialect.cdcLsnParameter(fromLsn));
        } catch (DataAccessException e) {
            // Never degrade a failed read to "no changes": the caller would treat it as an
            // up-to-date table and advance its watermark past changes it never saw.
            throw new McpQueryException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Failed to read CDC changes for %s.%s (connection=%s, fromLsn=%d): %s"
                            .formatted(schema, table, connection, fromLsn, e.getMessage()), e);
        }

        List<CdcChangeEvent> events = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // fromCodeOrUnknown warns once per unrecognized code and keeps the event instead of
            // dropping it, so a dialect/enum mismatch can no longer hide changes.
            CdcChangeType changeType = CdcChangeType.fromCodeOrUnknown((String) row.get("change_type"));

            Instant changeTime = extractTimestamp(row.get("change_time"));
            String primaryKeys = toStringOrEmpty(row.get("primary_keys"));
            String beforeJson = (String) row.get("before_json");
            String afterJson = (String) row.get("after_json");
            Long txId = toLongOrNull(row.get("transaction_id"));

            events.add(new CdcChangeEvent(connection, schema, table, changeType,
                    changeTime, primaryKeys, beforeJson, afterJson, txId, CdcEventStatus.PROCESSED));
        }
        eventCounters.merge(connection, (long) events.size(), Long::sum);
        if (!events.isEmpty()) {
            lastEventTimes.put(connection, System.currentTimeMillis());
        }
        return events;
    }

    // ─── Last LSN ─────────────────────────────────────────────────────────

    /**
     * Reads the current watermark and normalizes it through the dialect. Throws instead of returning
     * {@code 0}, which would be indistinguishable from genuinely sitting at position 0.
     */
    @Override
    public long getLastLsn(String connection) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        String sql = dialect.cdcGetLastLsnSql();
        if (sql == null) {
            throw new McpQueryException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Dialect '%s' does not expose a CDC watermark (connection=%s)"
                            .formatted(dialect.getDialectName(), connection));
        }

        Map<String, Object> row;
        try {
            row = ctx.getJdbcTemplate().queryForMap(sql);
        } catch (DataAccessException e) {
            throw new McpQueryException(ErrorCode.QUERY_EXECUTION_FAILED,
                    "Failed to read the CDC watermark for '%s': %s".formatted(connection, e.getMessage()), e);
        }
        return dialect.parseLsn(row);
    }

    // ─── Mirror Table ─────────────────────────────────────────────────────

    @Override
    public void createMirrorTable(String connection, String sourceSchema, String sourceTable,
                                  String targetSchema, String targetTable) {
        ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
        DatabaseDialect dialect = ctx.getDialect();
        JdbcTemplate jdbc = ctx.getJdbcTemplate();

        // This DDL runs on the raw JdbcTemplate, i.e. outside SqlValidationAspect, so the
        // identifiers are whitelisted here before any of them reaches a SQL string.
        requireIdentifier(dialect, sourceTable, "sourceTable");
        requireIdentifier(dialect, targetSchema, "targetSchema");
        requireIdentifier(dialect, targetTable, "targetTable");
        if (sourceSchema != null && !sourceSchema.isBlank()) {
            requireIdentifier(dialect, sourceSchema, "sourceSchema");
        }

        String qualifiedSource = sourceSchema == null || sourceSchema.isBlank()
                ? dialect.quote(sourceTable)
                : dialect.quote(sourceSchema) + "." + dialect.quote(sourceTable);

        String sql = dialect.cdcCreateMirrorTableSql(targetSchema, targetTable,
                "SELECT * FROM " + qualifiedSource);
        if (sql == null) {
            throw new UnsupportedOperationException(
                    "Mirror table creation not supported for dialect: " + dialect.getDialectName());
        }
        jdbc.execute(sql);
        log.info("Created mirror table {}.{} from {}.{}", targetSchema, targetTable, sourceSchema, sourceTable);
    }

    /**
     * Whitelist check for an identifier that is interpolated into SQL.
     *
     * @throws McpValidationException when the value is not a plain identifier for this dialect
     */
    private static void requireIdentifier(DatabaseDialect dialect, String value, String paramName) {
        if (!dialect.isValidIdentifier(value)) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    "%s '%s' is not a valid %s identifier: only unquoted identifier characters are accepted here"
                            .formatted(paramName, value, dialect.getDialectName()));
        }
    }

    // ─── Subscription Management ──────────────────────────────────────────

    @Override
    public void registerSubscription(CdcSubscription subscription) {
        subscriptions.put(subscription.name(), subscription);
        log.info("Registered CDC subscription '{}' for {}.{}", subscription.name(),
                subscription.schema(), subscription.tablePattern());
    }

    @Override
    public List<CdcSubscription> listSubscriptions(String connection) {
        return subscriptions.values().stream()
                .filter(s -> s.connection().equals(connection) || connection == null)
                .toList();
    }

    @Override
    public void unregisterSubscription(String subscriptionName) {
        subscriptions.remove(subscriptionName);
    }

    // ─── Status ───────────────────────────────────────────────────────────

    @Override
    public CdcStatus getStatus(String connection) {
        boolean supported = isCdcSupported(connection);
        long lsn = supported ? readLsnForStatus(connection) : 0L;
        long totalEvents = eventCounters.getOrDefault(connection, 0L);
        long lastEvent = lastEventTimes.getOrDefault(connection, 0L);
        int activeSubs = (int) subscriptions.values().stream()
                .filter(s -> s.connection().equals(connection) && s.active())
                .count();
        return new CdcStatus(connection, supported, lsn, activeSubs, totalEvents, lastEvent);
    }

    /**
     * Status is a diagnostics view and must stay readable even when the watermark query fails, so
     * the failure is reported as {@link #LSN_UNAVAILABLE} rather than as the plausible value 0.
     */
    private long readLsnForStatus(String connection) {
        try {
            return getLastLsn(connection);
        } catch (RuntimeException e) {
            log.warn("CDC watermark unavailable for '{}': {}", connection, e.getMessage());
            return LSN_UNAVAILABLE;
        }
    }

    // ─── Private Helpers ──────────────────────────────────────────────────

    private static Instant extractTimestamp(Object obj) {
        if (obj instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        } else if (obj instanceof Instant i) {
            return i;
        }
        return Instant.now();
    }

    private static String toStringOrEmpty(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private static Long toLongOrNull(Object obj) {
        return obj instanceof Number n ? n.longValue() : null;
    }
}
