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
package com.entropy.database.mcp.repository;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.util.ValidationUtils;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Statement construction and binding shared by the writing step handlers.
 *
 * <p>Each handler used to build its own {@code INSERT} string and its own positional setter, and
 * the copies had already drifted. One place also means the column list that names the placeholders
 * is the same list that binds them.
 *
 * <p>住在 infra 而不是 features 的 etl 包：{@link #bindColumns} 返回的
 * {@link ParameterizedPreparedStatementSetter} 是 Spring JDBC 类型，能力包不允许出现它
 * （ArchUnit R6）。因此 {@link #bindColumns} 与 {@link #sum} 是包私有的，只由
 * {@code EtlRowStream} 的批量写入口使用；{@link #insertInto} 保持 public，因为 handler 仍要用它
 * 拼语句——它产出的只是一个 {@code String}。
 */
public final class EtlSql {

    private EtlSql() {
    }

    /**
     * {@code INSERT INTO "table" (quoted columns) VALUES (?, ?, ...)}.
     *
     * <p>表名以前是裸拼的：列名走了 {@code quote}，表名直接字符串相加，而调用点只做了
     * {@code dialect.normalizeTableName()}——那只是大小写归一，不是校验。所以这里自己再断言一次
     * 并同样 {@code quote}。校验在能力层的 ETL 过闸点（features 的 {@code EtlStepGuard}）已经做过，
     * 这一层是纵深防御：它是最后一个还知道「这段文本要变成表名」的地方。
     */
    public static String insertInto(DatabaseDialect dialect, String table, List<String> columns) {
        requireIdentifier(table, dialect, "targetTable");
        String columnList = String.join(", ", columns.stream().map(dialect::quote).toList());
        String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        return "INSERT INTO " + dialect.quote(table) + " (" + columnList + ") VALUES (" + placeholders + ")";
    }

    /**
     * Bind one row positionally in {@code columns} order — the same order
     * {@link #insertInto} emitted the placeholders in.
     *
     * <p>包私有：返回类型是 Spring JDBC 的类型，只有本包的批量写入口
     * （{@code EtlRowStream} 的 {@code BatchSink} 实现）该看到它。
     */
    static ParameterizedPreparedStatementSetter<Map<String, Object>> bindColumns(List<String> columns) {
        return (ps, row) -> {
            for (int i = 0; i < columns.size(); i++) {
                ps.setObject(i + 1, row.get(columns.get(i)));
            }
        };
    }

    /**
     * Total rows reported by a batched update.
     *
     * <p>包私有，理由同 {@link #bindColumns}：{@code int[][]} 是 {@code batchUpdate} 的返回形状，
     * 能拿到它的只有本包里真正调 {@code batchUpdate} 的那一处。
     */
    static long sum(int[][] updateCounts) {
        return Arrays.stream(updateCounts).flatMapToInt(Arrays::stream).sum();
    }

    /**
     * 只接受纯标识符，返回去空白后的值。
     *
     * <p>与能力层过闸点的规则保持一致：方言为 null 时退回
     * {@link ValidationUtils#validateIdentifier}，它比方言规则更严（不允许下划线开头），
     * 宁可误拒也不放行——这条路径只在没有方言可用时走到。
     */
    private static String requireIdentifier(String value, DatabaseDialect dialect, String paramName) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isBlank()) {
            throw reject(paramName, "不能为空");
        }
        if (dialect == null) {
            ValidationUtils.validateIdentifier(trimmed, paramName);
            return trimmed;
        }
        if (!dialect.isValidIdentifier(trimmed)) {
            throw reject(paramName, "不是合法标识符: " + value);
        }
        return trimmed;
    }

    private static McpValidationException reject(String paramName, String detail) {
        return new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                paramName + " " + detail);
    }
}
