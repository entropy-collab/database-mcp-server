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
 * plus normalization of the heterogeneous CDC watermarks (SCN / LSN / binlog
 * coordinate) that dialects return.
 */
public final class DialectUtils {

    /** Column names that carry a plain numeric watermark, matched case-insensitively. */
    private static final List<String> NUMERIC_LSN_COLUMNS =
            List.of("current_scn", "scn", "lsn", "current_lsn", "position", "binlog_position");

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
        try {
            long high = Long.parseUnsignedLong(text.substring(0, slash), 16);
            long low = Long.parseUnsignedLong(text.substring(slash + 1), 16);
            return (high << 32) | (low & 0xFFFFFFFFL);
        } catch (NumberFormatException e) {
            throw new McpValidationException(ErrorCode.DATA_VALIDATION_FAILED,
                    "Malformed PostgreSQL WAL LSN '%s': both halves must be hexadecimal".formatted(text), e);
        }
    }

    /**
     * Inverse of {@link #parsePostgresLsn(String)}: renders a normalized watermark back into the
     * {@code X/Y} text form so it can be bound to a {@code pg_lsn} comparison.
     */
    public static String formatPostgresLsn(long lsn) {
        return Long.toHexString(lsn >>> 32).toUpperCase() + "/" + Long.toHexString(lsn & 0xFFFFFFFFL).toUpperCase();
    }

    /**
     * Packs a MySQL binlog coordinate ({@code File} + {@code Position}, as returned by
     * {@code SHOW MASTER STATUS}) into a single monotonically increasing long: the binlog file
     * sequence number occupies the high 32 bits, the in-file offset the low 32 bits.
     *
     * @throws McpValidationException when the coordinate cannot be parsed
     */
    public static long parseMySqlBinlogLsn(Object file, Object position) {
        Long offset = toLong(position);
        if (offset == null) {
            throw new McpValidationException(ErrorCode.DATA_VALIDATION_FAILED,
                    "Cannot parse MySQL binlog position '%s'; expected a number".formatted(position));
        }
        long fileSequence = 0L;
        if (file != null) {
            String name = file.toString();
            int dot = name.lastIndexOf('.');
            String suffix = dot >= 0 ? name.substring(dot + 1) : name;
            if (!suffix.matches("\\d+")) {
                throw new McpValidationException(ErrorCode.DATA_VALIDATION_FAILED,
                        ("Cannot parse MySQL binlog file name '%s'; expected a numeric suffix "
                                + "such as mysql-bin.000042").formatted(name));
            }
            fileSequence = Long.parseLong(suffix);
        }
        return (fileSequence << 32) | (offset & 0xFFFFFFFFL);
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
