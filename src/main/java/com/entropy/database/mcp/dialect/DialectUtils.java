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
package com.entropy.database.mcp.dialect;

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpValidationException;

import java.util.List;
import java.util.Map;

/**
 * Utility methods for dialect and driver class inference from JDBC URLs,
 * plus normalization of the heterogeneous CDC watermarks (Oracle SCN, PostgreSQL WAL LSN,
 * MySQL Unix-second timestamp) that dialects return.
 */
public final class DialectUtils {

    /**
     * Column names that carry a plain numeric watermark, matched case-insensitively.
     *
     * <p>{@code position} / {@code binlog_position} 已移除：现在没有方言用它们做位点别名，而
     * {@code SHOW MASTER STATUS} 的结果行恰好带一个 {@code Position} 列——留着它就意味着一个 binlog
     * 偏移会被当成合法位点悄悄读走（例如 512 被解释成「Unix 秒 512」），这正是位点单位串味的入口。
     */
    private static final List<String> NUMERIC_LSN_COLUMNS =
            List.of("current_scn", "scn", "lsn", "current_lsn");

    private DialectUtils() {
    }

    public static String inferDialect(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "generic";
        }
        if (jdbcUrl.startsWith("jdbc:oracle:")) return "oracle";
        if (jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.startsWith("jdbc:mariadb:")) return "mysql";
        if (jdbcUrl.startsWith("jdbc:postgresql:")) return "postgres";
        if (jdbcUrl.startsWith("jdbc:sqlserver:")) return "sqlserver";
        if (jdbcUrl.startsWith("jdbc:sqlite:")) return "sqlite";
        if (jdbcUrl.startsWith("jdbc:db2:")) return "db2";
        if (jdbcUrl.startsWith("jdbc:h2:")) return "h2";
        return "generic";
    }

    public static String inferDriverClassName(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        if (jdbcUrl.startsWith("jdbc:oracle:")) return "oracle.jdbc.OracleDriver";
        if (jdbcUrl.startsWith("jdbc:mysql:")) return "com.mysql.cj.jdbc.Driver";
        if (jdbcUrl.startsWith("jdbc:mariadb:")) return "org.mariadb.jdbc.Driver";
        if (jdbcUrl.startsWith("jdbc:postgresql:")) return "org.postgresql.Driver";
        if (jdbcUrl.startsWith("jdbc:sqlserver:")) return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        if (jdbcUrl.startsWith("jdbc:sqlite:")) return "org.sqlite.JDBC";
        if (jdbcUrl.startsWith("jdbc:db2:")) return "com.ibm.db2.jdbc.DB2Driver";
        if (jdbcUrl.startsWith("jdbc:h2:")) return "org.h2.Driver";
        return null;
    }

    // ─── Schema resolution in metadata SQL ────────────────────────────────

    /**
     * Renders the schema side of a metadata predicate.
     *
     * <p>The metadata queries keep a fixed placeholder count (see the bind-parameter contract on
     * {@link DatabaseDialect}), so the schema cannot occupy a {@code ?}: it is either a SQL string
     * literal or the dialect's "current schema" expression. A {@code null}, blank or non-identifier
     * schema degrades to {@code currentSchemaExpression} rather than producing
     * {@code TABLE_SCHEMA IS NULL}, which matches no row in any information schema.
     *
     * @param schema                  the requested schema, may be {@code null}
     * @param currentSchemaExpression dialect expression for the session schema, e.g.
     *                                {@code current_schema()}, {@code DATABASE()} or {@code USER}
     * @return a SQL expression that is safe to concatenate into the predicate
     */
    public static String schemaExpression(String schema, String currentSchemaExpression) {
        if (!isPlainIdentifier(schema)) {
            return currentSchemaExpression;
        }
        return "'" + schema.trim() + "'";
    }

    /**
     * Whether {@code name} is a bare identifier, i.e. carries no quote, backslash, whitespace or
     * statement separator that could escape a SQL literal.
     *
     * <p>Deliberately stricter than any dialect's own identifier rules: the value is destined for
     * string concatenation, so anything unusual is rejected instead of escaped.
     */
    public static boolean isPlainIdentifier(String name) {
        return name != null && name.trim().matches("[A-Za-z_][A-Za-z0-9_$#]*");
    }

    // ─── CDC watermark normalization ──────────────────────────────────────

    /**
     * Reads a plain numeric watermark (Oracle SCN, SQL Server LSN, ...) from a result row.
     *
     * @return the parsed value, or {@code null} when no numeric watermark column is present
     */
    public static Long findNumericLsn(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().toLowerCase();
            if (NUMERIC_LSN_COLUMNS.contains(key)) {
                Long value = toLong(entry.getValue());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Same as {@link #findNumericLsn(Map)} but fails loudly instead of guessing: an unparseable
     * watermark must never be silently replaced by a hash or by {@code 0}, because both are
     * indistinguishable from a real position and corrupt incremental CDC reads.
     *
     * @throws McpValidationException when the row carries no parseable numeric watermark
     */
    public static long requireNumericLsn(Map<String, Object> row, String dialectName) {
        Long value = findNumericLsn(row);
        if (value == null) {
            throw new McpValidationException(ErrorCode.DATA_VALIDATION_FAILED,
                    "Cannot parse a CDC watermark from the '%s' result %s. Expected one of %s to hold a numeric value."
                            .formatted(dialectName, row, NUMERIC_LSN_COLUMNS));
        }
        return value;
    }

    /**
     * Parses a PostgreSQL WAL LSN of the form {@code X/Y} (two hexadecimal halves) into the same
     * 64-bit value {@code pg_wal_lsn_diff('X/Y', '0/0')} would yield: {@code (high << 32) | low}.
     *
     * @throws McpValidationException when the text is not a valid {@code pg_lsn}
     */
    public static long parsePostgresLsn(String lsn) {
        String text = lsn == null ? null : lsn.trim();
        if (text == null || text.isEmpty()) {
            throw new McpValidationException(ErrorCode.DATA_VALIDATION_FAILED,
                    "PostgreSQL WAL LSN is missing; expected the 'X/Y' form returned by pg_current_wal_lsn()");
        }
        int slash = text.indexOf('/');
        if (slash <= 0 || slash == text.length() - 1) {
            throw new McpValidationException(ErrorCode.DATA_VALIDATION_FAILED,
                    "Malformed PostgreSQL WAL LSN '%s'; expected the 'X/Y' form returned by pg_current_wal_lsn()"
                            .formatted(text));
        }
        long high;
        long low;
        try {
            high = Long.parseUnsignedLong(text.substring(0, slash), 16);
            low = Long.parseUnsignedLong(text.substring(slash + 1), 16);
        } catch (NumberFormatException e) {
            throw new McpValidationException(ErrorCode.DATA_VALIDATION_FAILED,
                    "Malformed PostgreSQL WAL LSN '%s': both halves must be hexadecimal".formatted(text), e);
        }
        // 打包只有 64 位可用，每半各 32 位。任一半的高位非零时，`high << 32` 会把它移出边界、
        // `low & 0xFFFFFFFF` 会把它截掉，得到的数值仍然「看起来是个合法位点」，却可能小于上一次的
        // 位点——保序被破坏后，增量读会跳过或重放变更，而且无从察觉。所以这里按本类「绝不猜」的
        // 惯例直接报错，而不是静默回绕。
        requireFitsIn32Bits(high, "high", text);
        requireFitsIn32Bits(low, "low", text);
        return (high << 32) | low;
    }

    /** Rejects a WAL LSN half that does not fit into the 32 bits reserved for it. */
    private static void requireFitsIn32Bits(long half, String halfName, String lsnText) {
        if ((half >>> 32) != 0) {
            throw new McpValidationException(ErrorCode.DATA_VALIDATION_FAILED,
                    ("PostgreSQL WAL LSN '%s' does not fit the 64-bit packing: the %s half is %s, "
                            + "which exceeds 32 bits. Truncating it would break watermark ordering.")
                            .formatted(lsnText, halfName, Long.toHexString(half).toUpperCase()));
        }
    }

    /**
     * Inverse of {@link #parsePostgresLsn(String)}: renders a normalized watermark back into the
     * {@code X/Y} text form so it can be bound to a {@code pg_lsn} comparison.
     */
    public static String formatPostgresLsn(long lsn) {
        return Long.toHexString(lsn >>> 32).toUpperCase() + "/" + Long.toHexString(lsn & 0xFFFFFFFFL).toUpperCase();
    }

    /** Numeric coercion used by the watermark parsers: {@code Number} or an all-digit string. */
    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.matches("\\d+")) {
                try {
                    return Long.parseLong(trimmed);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
