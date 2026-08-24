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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * CDC service implementation using database-specific change capture mechanisms.
 *
 * <p>Strategy selection per dialect:
 * <ul>
 *   <li>Oracle — Flashback Query (SCN-based)</li>
 *   <li>MySQL — Trigger-based audit tables (binlog requires Debezium)</li>
 *   <li>PostgreSQL — pglogical / trigger-based audit (WAL LSN)</li>
 * </ul>
 *
 * <p>Uses a functional {@link LsnExtractor} strategy pattern to normalize
 * the heterogeneous LSN/SCN/binlog-position values each dialect returns into
 * a single {@code long} type.
 */
@Service
public class CdcServiceImpl implements CdcService {

    private static final Logger log = LoggerFactory.getLogger(CdcServiceImpl.class);

    /** Function that extracts a normalized long LSN from a dialect-specific query result map. */
    @FunctionalInterface
    private interface LsnExtractor {
        long extract(Map<String, Object> row);
    }

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
            return sql != null && Integer.valueOf(1).equals(ctx.getJdbcTemplate().queryForObject(sql, Integer.class));
        } catch (Exception e) {
            log.warn("CDC support check failed for '{}': {}", connection, e.getMessage(), e);
            return false;
        } finally {
            if (ctx != null) ctx.close();
        }
    }

    // ─── Read Changes ─────────────────────────────────────────────────────

    @Override
    public List<CdcChangeEvent> readChanges(String connection, String schema, String table, long fromLsn) {
        List<CdcChangeEvent> events = new ArrayList<>();
        ByokDataSourceContext ctx = null;
        try {
            ctx = dataSourceManager.acquire(connection);
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();

            String sql = dialect.cdcReadChangesSql(schema, table, fromLsn);
            if (sql == null) {
                log.warn("CDC read not supported for dialect '{}'", dialect.getDialectName());
                return List.of();
            }

            for (Map<String, Object> row : jdbc.queryForList(sql, fromLsn)) {
                CdcChangeType changeType = CdcChangeType.fromCode((String) row.get("change_type"));
                if (changeType == null) continue;

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
        } catch (Exception e) {
            log.warn("Failed to read CDC changes for {}.{}: {}", schema, table, e.getMessage(), e);
        } finally {
            if (ctx != null) ctx.close();
        }
        return events;
    }

    // ─── Last LSN ─────────────────────────────────────────────────────────

    /**
     * Extract the current LSN for a connection using dialect-specific extraction strategies.
     * Falls back to a hash of the string representation for non-numeric LSN formats.
     */
    @Override
    public long getLastLsn(String connection) {
        try {
            ByokDataSourceContext ctx = dataSourceManager.acquire(connection);
            DatabaseDialect dialect = ctx.getDialect();
            String sql = dialect.cdcGetLastLsnSql();
            if (sql == null) return 0L;

            Map<String, Object> row = ctx.getJdbcTemplate().queryForMap(sql);
            return extractLsn(row, dialect);
        } catch (Exception e) {
            log.debug("Failed to get LSN for '{}': {}", connection, e.getMessage());
            return 0L;
        }
    }

    /**
     * Strategy pattern: selects the appropriate LSN extractor based on dialect column names.
     */
    private long extractLsn(Map<String, Object> row, DatabaseDialect dialect) {
        return List.of(
                        Map.entry("current_scn", (Function<Object, Long>) v -> toLongOrNull(v)),
                        Map.entry("lsn", (Function<Object, Long>) v -> toLongOrNull(v)),
                        Map.entry("current_lsn", (Function<Object, Long>) v -> toLongOrNull(v)),
                        Map.entry("File", (Function<Object, Long>) v -> toLongOrNull(v)),
                        Map.entry("position", (Function<Object, Long>) v -> toLongOrNull(v))
                )
                .stream()
                .map(e -> {
                    Long v = e.getValue().apply(row.get(e.getKey()));
                    return v != null && v > 0 ? v : null;
                })
                .filter(v -> v != null && v > 0)
                .findFirst()
                .orElseGet(() -> (long) hashFallback(row));
    }

    private long hashFallback(Map<String, Object> row) {
        // Fall back to hashing the first non-null value for string-based LSN formats (e.g., PostgreSQL WAL LSN)
        return row.values().stream()
                .filter(v -> v != null)
                .map(Object::toString)
                .findFirst()
                .map(s -> s.isEmpty() ? 0L : Math.floorMod(s.hashCode(), Long.MAX_VALUE))
                .orElse(0L);
    }

    // ─── Mirror Table ─────────────────────────────────────────────────────

    @Override
    public void createMirrorTable(String connection, String sourceSchema, String sourceTable,
                                  String targetSchema, String targetTable) {
        ByokDataSourceContext ctx = null;
        try {
            ctx = dataSourceManager.acquire(connection);
            DatabaseDialect dialect = ctx.getDialect();
            JdbcTemplate jdbc = ctx.getJdbcTemplate();

            String sql = dialect.cdcCreateMirrorTableSql(targetSchema, targetTable,
                    "SELECT * FROM " + dialect.quote(sourceTable));
            if (sql == null) {
                throw new UnsupportedOperationException(
                        "Mirror table creation not supported for dialect: " + dialect.getDialectName());
            }
            jdbc.execute(sql);
            log.info("Created mirror table {}.{} from {}.{}", targetSchema, targetTable, sourceSchema, sourceTable);
        } finally {
            if (ctx != null) ctx.close();
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
        long lsn = supported ? getLastLsn(connection) : 0L;
        long totalEvents = eventCounters.getOrDefault(connection, 0L);
        long lastEvent = lastEventTimes.getOrDefault(connection, 0L);
        int activeSubs = (int) subscriptions.values().stream()
                .filter(s -> s.connection().equals(connection) && s.active())
                .count();
        return new CdcStatus(connection, supported, lsn, activeSubs, totalEvents, lastEvent);
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
