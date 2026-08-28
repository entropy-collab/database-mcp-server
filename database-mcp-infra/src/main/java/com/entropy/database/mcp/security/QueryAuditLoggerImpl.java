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
package com.entropy.database.mcp.security;

import com.entropy.database.mcp.audit.AuditLogEntity;
import com.entropy.database.mcp.audit.AuditLogRepository;
import com.entropy.database.mcp.audit.SqlAuditService;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Query audit logger for tracking database operations.
 * Buffers entries in memory for SSE/polling consumers and optionally persists to database.
 * If the default database is not configured (no audit_log table), falls back to file logging.
 */
@Component
public class QueryAuditLoggerImpl implements QueryAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(QueryAuditLoggerImpl.class);
    private static final Logger auditLog = LoggerFactory.getLogger("auditLogger");

    // Bounded buffer: keeps last 100 audit entries for SSE/polling consumers
    private static final int MAX_BUFFER_SIZE = 100;

    /** Placeholder that replaces a secret while keeping the statement's shape readable. */
    private static final String MASK = "'***'";

    /** Field names whose value is a secret wherever they appear as {@code name = value}. */
    private static final String SECRET_FIELDS =
            "password|passwd|pwd|secret|token|api_key|apikey|credential|credentials";

    /**
     * Secret-bearing SQL and URL shapes, applied in order. A single "field = value" regex is not
     * enough: Oracle's {@code IDENTIFIED BY} carries no {@code =}, JDBC URLs carry the password in
     * the authority component, and {@code SET PASSWORD} puts a user reference between the keyword
     * and the value.
     *
     * <p>每条规则的量词都带上界：审计在查询热路径上，一条构造出来的超长语句不应该让正则回溯
     * 拖住调用线程。
     */
    private static final List<Masker> MASKERS = List.of(
            // MySQL 的 PASSWORD('x') 是函数调用而不是赋值，值藏在括号里；先就地脱敏，
            // 否则后面的 SET PASSWORD 规则会把 "PASSWORD('x'" 整段当成值、把括号拆散
            new Masker("(?i)(\\bPASSWORD\\s{0,20}\\(\\s*)"
                    + "(?:'[^']{0,200}'|\"[^\"]{0,200}\"|`[^`]{0,200}`|[^\\s;,()]{0,200})(\\s*\\))",
                    "$1" + MASK + "$2"),
            // SET PASSWORD [FOR 'u'@'h'] = 'x'  →  the user reference is kept, the value is not
            new Masker("(?i)(\\bSET\\s+PASSWORD\\b[^=;]{0,200}?=\\s*)(?:'[^']*'|\"[^\"]*\"|`[^`]*`|[^\\s;,)]+)", "$1" + MASK),
            // IDENTIFIED BY "x" / IDENTIFIED BY x / IDENTIFIED BY VALUES 'hash'
            new Masker("(?i)(\\bIDENTIFIED\\s+BY\\s+(?:VALUES\\s+)?)(?:'[^']*'|\"[^\"]*\"|`[^`]*`|[^\\s;,)]+)", "$1" + MASK),
            // Oracle 的 IDENTIFIED BY <new> REPLACE <old>：旧口令同样是口令，而且往往还在别处有效。
            // 限定同一句里出现过 IDENTIFIED BY，免得把 REPLACE(col, 'a', 'b') 字符串函数的实参也抹掉
            new Masker("(?i)(\\bIDENTIFIED\\s+BY\\b[^;]{0,200}?\\bREPLACE\\s+)"
                    + "(?:'[^']{0,200}'|\"[^\"]{0,200}\"|`[^`]{0,200}`|[^\\s;,)]{1,200})", "$1" + MASK),
            // password = 'x' / "pwd"='x' / token: x  (optionally quoted field name, = or :=)
            new Masker("(?i)(['\"`]?\\b(?:" + SECRET_FIELDS + ")\\b['\"`]?\\s*(?::=|=|:)\\s*)"
                    + "(?:'[^']*'|\"[^\"]*\"|`[^`]*`|[^\\s;,)]+)", "$1" + MASK),
            // PostgreSQL/H2 的 CREATE USER u [WITH] PASSWORD 'x' 在关键字后直接给值，没有 = 也没有 :，
            // 上一条规则整条漏过去。这里只认紧跟其后的引号字面量：既不会把 SET PASSWORD FOR 里的
            // FOR 当成值，也不会把上面几条已经产出的 PASSWORD = '***' 再嚼一遍
            new Masker("(?i)(\\bPASSWORD\\s{1,20})(?:'[^']{0,200}'|\"[^\"]{0,200}\"|`[^`]{0,200}`)", "$1" + MASK),
            // jdbc:mysql://user:pass@host, and any other userinfo-carrying URL
            new Masker("(//[^/@:\\s]{1,200}:)[^@/\\s]{1,200}(@)", "$1***$2"),
            // Oracle easy-connect style user/pass@service
            new Masker("(?i)(\\b[A-Za-z][A-Za-z0-9_$#]{0,127}/)[^\\s/@]{1,200}(@)", "$1***$2"));

    /** 命中即视为口令列的列名，与 {@link #SECRET_FIELDS} 同源，用于 INSERT 的值列表定位。 */
    private static final Pattern SECRET_COLUMN_NAME = Pattern.compile("(?i)^(?:" + SECRET_FIELDS + ")$");

    /**
     * 只有以 INSERT 开头且长度不超过这个上界的语句才会被解析。审计在查询热路径上，宁可让
     * 一条超长语句只拿到正则脱敏的结果，也不要为它付一次完整的语法分析。
     */
    private static final int MAX_PARSED_SQL_LENGTH = 8000;

    /** A compiled secret shape and the replacement that keeps its structure. */
    private record Masker(Pattern pattern, String replacement) {
        Masker(String regex, String replacement) {
            this(Pattern.compile(regex), replacement);
        }

        String apply(String sql) {
            return pattern.matcher(sql).replaceAll(replacement);
        }
    }

    private final ConcurrentLinkedQueue<Map<String, Object>> buffer = new ConcurrentLinkedQueue<>();

    /**
     * 熔断冷却时长。DB 写审计失败常常是瞬时的（连接池耗尽、主备切换、表被临时锁住），
     * 一次失败就把这个实例的余生钉死在「只写文件」上，等于永久丢掉可查询的审计表。
     */
    private static final long DB_RETRY_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final AuditLogRepository auditLogRepository;
    private final boolean persistenceEnabled;

    /**
     * 半开熔断的状态位：0 表示闭合（可写），否则是最近一次写失败的 {@code System.nanoTime()}。
     * 每个 BYOK 连接都会 new 一个本类实例（见 {@code ByokDataSourceFactory}），所以状态只能放在
     * 实例上；一个 volatile long 足够——竞态最坏结果是冷却期内多试一次写，代价远小于加锁。
     */
    private volatile long dbFailedAtNanos;
    private final com.entropy.database.mcp.properties.DatabaseProperties properties;
    private final SqlAuditService sqlAuditService;

    public QueryAuditLoggerImpl(@Nullable AuditLogRepository auditLogRepository,
                                com.entropy.database.mcp.properties.DatabaseProperties properties,
                                SqlAuditService sqlAuditService) {
        this.auditLogRepository = auditLogRepository;
        this.persistenceEnabled = properties != null && properties.audit() != null && properties.audit().enabled();
        this.properties = properties;
        this.sqlAuditService = sqlAuditService;
        // No probe insert: writing a synthetic "_probe_" row to test the table put a junk record in
        // audit_log for every BYOK connection that was created. Availability is assumed here and
        // revoked by the failure handling in log(), which only works because
        // AuditLogRepository.insert propagates its failures instead of swallowing them.
        this.dbFailedAtNanos = 0L;
        if (auditLogRepository == null) {
            if (this.persistenceEnabled) {
                // entropy.mcp.database.audit.enabled says persist, but there is no repository to
                // persist into. Saying this at info level let the mismatch pass unnoticed, which is
                // exactly the failure mode that matters for an audit trail.
                log.warn("Audit persistence is enabled but no audit repository is wired: audit "
                        + "entries go to the audit log file and the in-memory buffer only, and are "
                        + "lost on restart. Set spring.datasource.url to persist them.");
            } else {
                log.info("No audit repository wired; audit logs will be written to file only");
            }
        }
    }

    /**
     * 半开判定：仓库存在，且熔断闭合或已过冷却期。过了冷却期就放一次写请求出去探路，
     * 成功则闭合熔断，失败则把时间戳推到当下，于是自然形成「每冷却期最多重试一次」的退避。
     */
    private boolean shouldTryDatabase() {
        if (auditLogRepository == null) {
            return false;
        }
        long failedAt = dbFailedAtNanos;
        return failedAt == 0L || System.nanoTime() - failedAt >= DB_RETRY_COOLDOWN_NANOS;
    }

    /**
     * Log audit entry asynchronously to avoid blocking query execution.
     */
    @Override
    @Async
    public void log(String tool, String sql, int rowCount, long durationMs, boolean success, @Nullable String connectionKey) {
        log(tool, sql, rowCount, durationMs, success, null, connectionKey);
    }

    @Override
    @Async
    public void log(String tool, String sql, int rowCount, long durationMs, boolean success, @Nullable String error, @Nullable String connectionKey) {
        String safeSql = maskSensitiveValues(sql);
        // JDBC 的报错信息里常常回显出错语句的片段，口令会顺着 error 从审计流出去，
        // 所以 error 走和 sql 完全一样的脱敏
        String safeError = maskSensitiveValues(error);
        auditLog.info(
            "mcp.db.audit tool={} sql=\"{}\" rows={} durationMs={} success={} error={} connection={}",
            tool,
            truncate(safeSql, properties.audit().sqlTruncateLength()),
            rowCount,
            durationMs,
            success,
            safeError != null ? safeError : "",
            connectionKey != null ? connectionKey : ""
        );

        Map<String, Object> entry = Map.of(
            "tool", tool,
            "sql", truncate(safeSql, properties.audit().entrySqlTruncateLength()),
            "rows", rowCount,
            "durationMs", durationMs,
            "success", success,
            "timestamp", Instant.now().toString(),
            "connectionKey", connectionKey != null ? connectionKey : ""
        );
        buffer.offer(entry);
        evictOld();

        // Record in SqlAuditService for slow query analysis and pattern stats
        if (sqlAuditService != null) {
            try {
                sqlAuditService.recordQuery(tool, safeSql, rowCount, durationMs, success, connectionKey);
            } catch (Exception e) {
                log.warn("Failed to record query in SqlAuditService: {}", e.getMessage(), e);
            }
        }

        // Persist to database asynchronously (only if default datasource is available)
        if (persistenceEnabled && shouldTryDatabase()) {
            try {
                auditLogRepository.insert(new AuditLogEntity(
                    null,
                    tool,
                    safeSql,
                    rowCount,
                    durationMs,
                    success,
                    safeError,
                    Instant.now(),
                    connectionKey
                ));
                if (dbFailedAtNanos != 0L) {
                    dbFailedAtNanos = 0L;
                    log.info("Audit log persistence recovered; resuming database writes");
                }
            } catch (Exception e) {
                // 0 是「闭合」的哨兵值，nanoTime() 理论上可能正好返回 0，这里用 1 兜底
                long now = System.nanoTime();
                dbFailedAtNanos = now == 0L ? 1L : now;
                log.warn("Failed to persist audit log to database, falling back to file only for the next {}s",
                        TimeUnit.NANOSECONDS.toSeconds(DB_RETRY_COOLDOWN_NANOS), e);
            }
        }
    }

    /**
     * Returns the most recent {@code limit} buffered audit log entries, newest first.
     * Uses toArray() to take a consistent snapshot, avoiding ConcurrentModificationException
     * that could occur with direct stream() on a concurrent queue during concurrent writes.
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<Map<String, Object>> getRecentLogs(int limit) {
        Map<String, Object>[] snapshot = buffer.toArray(new Map[0]);
        return java.util.Arrays.stream(snapshot)
            .sorted((a, b) -> ((String) b.get("timestamp")).compareTo((String) a.get("timestamp")))
            .limit(limit)
            .toList();
    }

    private synchronized void evictOld() {
        while (buffer.size() > MAX_BUFFER_SIZE) {
            buffer.poll();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Mask secrets in a SQL string before it reaches the audit log, the in-memory buffer or the
     * {@code audit_log} table. Statement structure is preserved so the audit trail stays readable:
     * only the value is replaced.
     *
     * <p>Package-private so the masking can be asserted directly.
     */
    static String maskSensitiveValues(String sql) {
        if (sql == null || sql.isEmpty()) return sql;
        String masked = sql;
        for (Masker masker : MASKERS) {
            masked = masker.apply(masked);
        }
        return maskInsertSecretValues(masked);
    }

    /**
     * {@code INSERT INTO users (name, password) VALUES ('bob', 'x')} 里的口令没有任何词法特征——
     * 列名和值被逗号分开，靠正则去数第几个值一定会在多行 VALUES、嵌套括号、含逗号的字面量上出错。
     * 这里改用已有依赖 JSQLParser 解析，按列的下标定位到值再替换。
     *
     * <p>解析失败、不是 INSERT、没有显式列名（位置无从对应）都原样返回上一步的正则结果：审计
     * 不能因为一条语句难解析就抛异常打断调用方。
     */
    private static String maskInsertSecretValues(String sql) {
        if (sql.length() > MAX_PARSED_SQL_LENGTH || !startsWithInsert(sql)) {
            return sql;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Insert insert) || !(insert.getSelect() instanceof Values values)) {
                return sql;
            }
            List<Column> columns = insert.getColumns();
            if (columns == null || columns.isEmpty()) {
                return sql;
            }
            boolean changed = false;
            for (List<Expression> row : valueRows(values)) {
                for (int i = 0; i < columns.size() && i < row.size(); i++) {
                    if (isSecretColumn(columns.get(i))) {
                        row.set(i, new StringValue("***"));
                        changed = true;
                    }
                }
            }
            return changed ? insert.toString() : sql;
        } catch (Exception e) {
            return sql;
        }
    }

    private static boolean startsWithInsert(String sql) {
        String head = sql.stripLeading();
        return head.regionMatches(true, 0, "INSERT", 0, "INSERT".length());
    }

    /**
     * 单行 {@code VALUES (...)} 的表达式列表本身就是一行值，多行 {@code VALUES (...), (...)} 的每个
     * 元素才是一行，两种形态在 JSQLParser 里都是 {@code ExpressionList}，按元素类型区分。
     */
    @SuppressWarnings("unchecked")
    private static List<List<Expression>> valueRows(Values values) {
        ExpressionList<?> expressions = values.getExpressions();
        List<List<Expression>> rows = new ArrayList<>();
        boolean multiRow = !expressions.isEmpty()
                && expressions.stream().allMatch(ExpressionList.class::isInstance);
        if (multiRow) {
            for (Object row : expressions) {
                rows.add((List<Expression>) row);
            }
        } else {
            rows.add((List<Expression>) expressions);
        }
        return rows;
    }

    private static boolean isSecretColumn(Column column) {
        String name = column.getColumnName();
        if (name == null || name.length() < 3) {
            return false;
        }
        String unquoted = name.replaceAll("^[\"'`\\[]|[\"'`\\]]$", "");
        return SECRET_COLUMN_NAME.matcher(unquoted).matches();
    }
}
