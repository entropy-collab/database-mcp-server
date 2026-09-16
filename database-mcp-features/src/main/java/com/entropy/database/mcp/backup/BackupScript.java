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
package com.entropy.database.mcp.backup;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;

import java.sql.Statement;
import java.util.*;

/**
 * 备份脚本的文本侧：把行数据变成可重放的 SQL 脚本，以及把脚本文本切回单条语句。
 *
 * <p>这些方法不依赖备份服务的任何状态——没有数据源、没有元数据仓库、没有配置，输入只有「行数据 +
 * 方言」或「脚本文本」，所以它们独立于 {@link DatabaseBackupServiceImpl} 存在：那个类的职责是编排
 * 一次备份/恢复（取连接、探元数据、落库、重放事务），而这里只负责这条链路两端的文本转换。
 *
 * <p>字面量边界校验（{@link #assertLiteralBoundariesAgree}）也属于这里：它是文本这条路径上防止拼接
 * 歧义的守卫。脚本一旦落到 {@link BackupMetadata#sqlScript()}，重放时就只剩文本、没有列值可依据，
 * 所以生成侧与重放侧都要用同一份判据把边界会漂移的语句挡在外面。
 *
 * <p>包私有：调用方（备份服务与它的测试）都在同包内，脚本文本的拼法不是对外契约。
 */
final class BackupScript {

    private BackupScript() {
    }

    /**
     * Split a SQL script on top-level semicolons.
     *
     * <p>Quote- and comment-aware: a {@code ;} inside a string literal, a quoted identifier or a
     * {@code --} comment does not end a statement. A naive {@code split(";")} truncates any row
     * whose data contains a semicolon.
     */
    static List<String> splitStatements(String script) {
        if (script == null || script.isBlank()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;

        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (inSingleQuote) {
                current.append(c);
                if (c == '\'') {
                    // '' is an escaped quote, not a terminator
                    if (i + 1 < script.length() && script.charAt(i + 1) == '\'') {
                        current.append(script.charAt(++i));
                    } else {
                        inSingleQuote = false;
                    }
                }
                continue;
            }
            if (inDoubleQuote) {
                current.append(c);
                if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            switch (c) {
                case '\'' -> { inSingleQuote = true; current.append(c); }
                case '"' -> { inDoubleQuote = true; current.append(c); }
                case '-' -> {
                    if (i + 1 < script.length() && script.charAt(i + 1) == '-') {
                        inLineComment = true;
                        i++;
                    } else {
                        current.append(c);
                    }
                }
                case ';' -> {
                    addIfNotBlank(statements, current);
                    current.setLength(0);
                }
                default -> current.append(c);
            }
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder candidate) {
        String stmt = candidate.toString().strip();
        if (!stmt.isEmpty()) {
            statements.add(stmt);
        }
    }
    static List<String> generateInsertStatements(String tableName, List<String> columnNames,
                                                  List<Map<String, Object>> rows, DatabaseDialect dialect) {
        List<String> statements = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            sb.append("INSERT INTO ").append(dialect.quote(tableName)).append(" (");
            sb.append(quoteAll(dialect, columnNames));
            sb.append(") VALUES (");
            List<String> values = new ArrayList<>();
            Map<String, Object> lookup = caseInsensitive(row);
            for (String col : columnNames) {
                values.add(formatValue(lookup.get(col), dialect));
            }
            sb.append(String.join(", ", values)).append(");");
            String statement = sb.toString();
            // 生成时就拒掉边界不稳的语句：脚本一旦存进 BackupMetadata，就会被 quickRestore 用裸
            // Statement 逐条重放，那时已经没有列值、只剩文本，判不出来哪段本该是数据。
            assertLiteralBoundariesAgree(statement, dialect, "backup of " + tableName);
            statements.add(statement);
        }
        return statements;
    }

    static String quoteAll(DatabaseDialect dialect, List<String> columnNames) {
        return columnNames.stream().map(dialect::quote).reduce((a, b) -> a + ", " + b).orElse("*");
    }

    /**
     * Case-insensitive view of one result row.
     *
     * <p>Column labels come back in whatever case the driver reports — uppercase on Oracle and H2,
     * lowercase on MySQL and PostgreSQL — so every lookup keyed by a fixed spelling has to be
     * case-insensitive. Getting this wrong is silent: the value reads as null and the backup happily
     * writes NULL into the restore script.
     */
    static Map<String, Object> caseInsensitive(Map<String, Object> row) {
        Map<String, Object> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        normalized.putAll(row);
        return normalized;
    }
    /**
     * 把一个列值渲染成脚本里的 SQL 字面量。
     *
     * <p>转义规则交给方言（{@link DatabaseDialect#stringLiteral(String)}）：这里原来只做引号翻倍，而
     * MySQL/MariaDB 默认<b>没开</b> {@code NO_BACKSLASH_ESCAPES}，反斜杠在字面量里是转义符——一个以
     * 反斜杠结尾的列值会让 {@code '...\'} 的收尾引号被吃掉、字面量不闭合，后面的文本被并进字符串，
     * 足以改写语句边界。脚本存进 {@link BackupMetadata#sqlScript()} 后由 quickRestore 用裸
     * {@link Statement} 逐条重放，而行数据本身可以由 insertData 写入，属于二段式利用。
     *
     * <p>选的是方案 (b)：保留 SQL 文本，转义下沉到方言，并在生成时与重放前各复校一次
     * （{@link #assertLiteralBoundariesAgree}）。否决方案 (a)「改存参数化的 (列, 值) 结构」的理由是
     * {@link BackupMetadata} 只有一个 {@code sqlScript} 字段，backupSchema 存的是 DDL 文本、
     * restoreBackup/quickRestore/getBackup 以及已经落盘的历史记录全按文本走，换形状要连带改 metadata
     * 与整条还原链路，超出本次修复范围。
     */
    private static String formatValue(Object value, DatabaseDialect dialect) {
        if (value == null) return "NULL";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return dialect.stringLiteral(String.valueOf(value));
    }
    /**
     * 复校一条待重放语句：它的字符串字面量在「反斜杠是普通字符」和「反斜杠是转义符」两种语义下必须落在
     * 同一批位置上。
     *
     * <p>前提是 MySQL/MariaDB 默认未开 {@code NO_BACKSLASH_ESCAPES}：同一段文本，标准 SQL 侧
     * （Oracle、PostgreSQL 的 {@code standard_conforming_strings=on}、H2、SQL Server）在某个 {@code '}
     * 处收尾，MySQL 侧却因为前面那个反斜杠把它吃掉而继续往后吞。两侧不一致的语句就是"语句边界会漂移"
     * 的语句，重放时后续文本会被当成 SQL 的一部分执行，所以在生成与重放两处都拒掉，而不是去赌服务端的
     * {@code sql_mode}。
     *
     * <p>方言明确声明反斜杠是普通字符（{@link DatabaseDialect.BackslashInLiteral#LITERAL}，Oracle 已
     * 声明）时跳过：那里一个以反斜杠结尾的值是合法数据，拒了就是把好数据判成坏的。声明为
     * {@code ESCAPE} 的方言由 {@code stringLiteral} 连反斜杠一起翻倍，两侧自然一致，这里也就放行。
     * MySqlDialect 目前还没表态（本次改动不许动那个文件），所以在 MySQL 上这类值会被拒——宁可拒收，
     * 也不要生成一条不闭合的语句；后续给 MySqlDialect 补上 {@code ESCAPE} 声明即可放行。
     */
    static void assertLiteralBoundariesAgree(String statement, DatabaseDialect dialect, String context) {
        if (dialect.backslashInLiteral() == DatabaseDialect.BackslashInLiteral.LITERAL) {
            return;
        }
        if (literalSpans(statement, false).equals(literalSpans(statement, true))) {
            return;
        }
        throw new McpToolException(ErrorCode.DATA_VALIDATION_FAILED,
                "Refusing " + context + ": a value's backslash makes the SQL literal end at different "
                        + "places depending on the server's NO_BACKSLASH_ESCAPES setting, so replaying "
                        + "this statement could shift the statement boundary. Dialect "
                        + dialect.getDialectName() + " has not declared its backslash semantics.");
    }
    /**
     * 一条语句里所有单引号字面量的 [起, 止] 位置。
     *
     * <p>{@code backslashEscapes=true} 时把 {@code \x} 当成一个整体跳过，这正是 MySQL 未开
     * {@code NO_BACKSLASH_ESCAPES} 时的读法；{@code false} 时只认 {@code ''}，即标准 SQL 与
     * {@link #splitStatements(String)} 的读法。两者一致才说明这条语句怎么读都是同一批字面量。
     *
     * <p>不处理双引号标识符：备份生成的标识符都经过 {@code dialect.quote}，里面出现单引号的情形
     * （被引号包住的列名带 {@code '}）在两种语义下同样会被算成字面量起点，因此不影响"两侧是否一致"这个
     * 判据本身。
     */
    private static List<String> literalSpans(String sql, boolean backslashEscapes) {
        List<String> spans = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            if (sql.charAt(i) != '\'') {
                i++;
                continue;
            }
            int start = i++;
            while (i < sql.length()) {
                char c = sql.charAt(i);
                if (backslashEscapes && c == '\\' && i + 1 < sql.length()) {
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    break;
                }
                i++;
            }
            spans.add(start + ":" + Math.min(i, sql.length()));
            i++;
        }
        return spans;
    }
}
