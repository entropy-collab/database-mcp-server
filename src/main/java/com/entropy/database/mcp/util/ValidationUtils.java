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
package com.entropy.database.mcp.util;

import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.exception.ErrorCode;

import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation utilities for database operations.
 * Centralizes validation logic to avoid duplication across tool classes.
 */
public final class ValidationUtils {

    private ValidationUtils() {
    }

    public static void validateIdentifier(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " cannot be blank");
        }
        if (!value.matches("[A-Za-z][A-Za-z0-9_$#]*")) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " contains invalid characters: " + value);
        }
    }

    public static void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "host cannot be blank");
        }
        if (!host.matches("[A-Za-z0-9._-]+")) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "host contains invalid characters: " + host);
        }
    }

    public static void validatePort(String port) {
        if (port == null || port.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "port cannot be blank");
        }
        try {
            int p = Integer.parseInt(port);
            if (p < 1 || p > 65535) {
                throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "port out of range: " + p);
            }
        } catch (NumberFormatException e) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "port must be a number: " + port, e);
        }
    }

    public static void validateServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "serviceName cannot be blank");
        }
        if (!serviceName.matches("[A-Za-z0-9._]+")) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, "serviceName contains invalid characters: " + serviceName);
        }
    }

    /** Pattern for an ISO-8601 calendar date; the value must also be a real date. */
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * Validate a date that is going to be inlined into SQL (Oracle {@code DATE 'yyyy-mm-dd'}).
     *
     * <p>Shape and calendar validity are both checked, so {@code 2026-02-30} or
     * {@code 2026-01-01' OR '1'='1} cannot reach the statement.
     */
    public static void validateIsoDate(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " cannot be blank");
        }
        if (!ISO_DATE_PATTERN.matcher(value).matches()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " must be in YYYY-MM-DD format: " + value);
        }
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " is not a valid calendar date: " + value, e);
        }
    }

    public static void requireNotBlank(String value, String paramName) {        if (value == null || value.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " cannot be blank");
        }
    }

    public static void requireNotNull(Object value, String paramName) {
        if (value == null) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " cannot be null");
        }
    }

    public static void requireNotEmpty(Collection<?> collection, String paramName) {
        if (collection == null || collection.isEmpty()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " cannot be empty");
        }
    }

    public static void requirePositive(int value, String paramName) {
        if (value <= 0) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " must be positive");
        }
    }

    public static void requireMinLength(String value, int minLength, String paramName) {
        if (value == null || value.length() < minLength) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " must be at least " + minLength + " characters");
        }
    }

    public static void requireMaxLength(String value, int maxLength, String paramName) {
        if (value != null && value.length() > maxLength) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " must be at most " + maxLength + " characters");
        }
    }

    public static void requireInRange(int value, int min, int max, String paramName) {
        if (value < min || value > max) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " must be between " + min + " and " + max);
        }
    }

    // ─── WHERE clause validation ─────────────────────────────────────────────
    //
    // A character blacklist cannot decide whether a fragment is a safe predicate: '-' plus a
    // quote is enough to close a literal and open a comment. So the clause is parsed and its
    // expression tree is walked against a whitelist of node types instead.

    /** SQL comment openers. The parser silently strips them, so anything after them is invisible. */
    private static final Pattern COMMENT_TOKEN = Pattern.compile("--|/\\*|\\*/");

    /** Wrapper the clause is parsed inside; DUAL keeps it parseable without a real table. */
    private static final String WHERE_PROBE_PREFIX = "SELECT 1 FROM DUAL WHERE ";

    /** Binary nodes a predicate may use: boolean connectives, comparisons and LIKE. */
    private static final Set<Class<?>> ALLOWED_BINARY_NODES = Set.of(
            AndExpression.class, OrExpression.class,
            EqualsTo.class, NotEqualsTo.class,
            GreaterThan.class, GreaterThanEquals.class,
            MinorThan.class, MinorThanEquals.class,
            LikeExpression.class);

    /** Literal nodes a predicate may compare against. */
    private static final Set<Class<?>> ALLOWED_LITERAL_NODES = Set.of(
            StringValue.class, LongValue.class, DoubleValue.class, NullValue.class,
            DateValue.class, TimeValue.class, TimestampValue.class);

    /**
     * Validate a WHERE clause fragment for SQL injection safety.
     *
     * <p>The clause is parsed as {@code SELECT 1 FROM DUAL WHERE <clause>} and every node of the
     * resulting predicate must be on a whitelist: column references, literals, comparison
     * operators, {@code AND}/{@code OR}/{@code NOT}, {@code IN} lists, {@code BETWEEN},
     * {@code IS NULL} and {@code LIKE}. Subqueries, function calls, set operations, extra FROM or
     * JOIN items, trailing clauses, SQL comments, multiple statements and anything that fails to
     * parse are rejected.
     */
    public static void validateWhereClause(String whereClause, String paramName) {
        if (whereClause == null || whereClause.isBlank()) {
            return;
        }
        if (whereClause.contains(";")) {
            throw reject(paramName, "contains disallowed semicolon");
        }
        if (COMMENT_TOKEN.matcher(whereClause).find()) {
            throw reject(paramName, "contains a SQL comment, which is not allowed in a WHERE clause");
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(WHERE_PROBE_PREFIX + whereClause);
        } catch (Exception e) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    paramName + " is not a parseable SQL predicate: " + whereClause, e);
        }
        if (!(statement instanceof PlainSelect probe)) {
            throw reject(paramName, "must be a single predicate, not a compound statement");
        }
        assertNothingButAPredicate(probe, whereClause, paramName);
        assertAllowedExpression(probe.getWhere(), paramName);
    }

    /**
     * Guards against a clause that closes the predicate and appends its own SQL: the probe must
     * still be {@code SELECT 1 FROM DUAL WHERE ...} and nothing else.
     *
     * <p>逐个枚举 PlainSelect 的可选子句 getter 只在写下它的那一天是完整的：漏掉一个 getter，
     * 整条子句就会原样通过校验，而调用方（如 EtlTools）会把它字面拼进 SQL。实测被漏掉的
     * {@code START WITH ... CONNECT BY}（含子查询）、{@code FOR UPDATE}、{@code WINDOW}
     * 就是这样进来的。所以这里反过来做 fail-closed 判定：把解析出的谓词重新序列化，要求它
     * 覆盖入参的全部文本；凡是被解析器归到谓词之外的东西——包括 JSQLParser 以后新增的语法
     * ——都会表现为「入参里多出一段文本」而被拒绝，无需再维护 getter 清单。
     */
    private static void assertNothingButAPredicate(PlainSelect probe, String whereClause, String paramName) {
        if (probe.getWhere() == null) {
            throw reject(paramName, "does not contain a predicate");
        }
        String reserialized = canonicalize(WHERE_PROBE_PREFIX + probe.getWhere());
        if (!reserialized.equals(canonicalize(WHERE_PROBE_PREFIX + whereClause))) {
            throw reject(paramName, "must contain only a predicate, without any further SQL clause");
        }
        // FROM/JOIN 无法从 WHERE 之后追加，但保留这一层断言的成本近乎为零，
        // 且能挡住未来某个版本把 WHERE 之后的文本重新归到 FROM 里的解析行为。
        if (probe.getJoins() != null
                || !(probe.getFromItem() instanceof net.sf.jsqlparser.schema.Table table)
                || !"DUAL".equalsIgnoreCase(table.getName())) {
            throw reject(paramName, "must contain only a predicate, without any further SQL clause");
        }
    }

    /**
     * 规范化到「可与 JSQLParser 输出逐字比较」的形式：只抹掉空白与大小写差异，因为解析器会把
     * {@code 1=1} 输出成 {@code 1 = 1}、把关键字统一成大写。字符串字面量里的空白与大小写在
     * 比较双方同样被抹掉，所以不会让两个不同的字面量互相冒充。
     */
    private static String canonicalize(String sql) {
        StringBuilder canonical = new StringBuilder(sql.length());
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (!Character.isWhitespace(c)) {
                canonical.append(Character.toUpperCase(c));
            }
        }
        return canonical.toString();
    }

    /** Recursively whitelists the predicate's expression tree. */
    private static void assertAllowedExpression(Expression expression, String paramName) {
        if (expression == null) {
            throw reject(paramName, "contains an empty expression");
        }
        if (ALLOWED_LITERAL_NODES.contains(expression.getClass()) || expression instanceof Column) {
            return;
        }
        if (ALLOWED_BINARY_NODES.contains(expression.getClass())
                && expression instanceof net.sf.jsqlparser.expression.BinaryExpression binary) {
            assertAllowedExpression(binary.getLeftExpression(), paramName);
            assertAllowedExpression(binary.getRightExpression(), paramName);
            return;
        }
        switch (expression) {
            case Parenthesis parenthesis -> assertAllowedExpression(parenthesis.getExpression(), paramName);
            case NotExpression not -> assertAllowedExpression(not.getExpression(), paramName);
            case SignedExpression signed -> assertAllowedExpression(signed.getExpression(), paramName);
            case IsNullExpression isNull -> assertAllowedExpression(isNull.getLeftExpression(), paramName);
            case Between between -> {
                assertAllowedExpression(between.getLeftExpression(), paramName);
                assertAllowedExpression(between.getBetweenExpressionStart(), paramName);
                assertAllowedExpression(between.getBetweenExpressionEnd(), paramName);
            }
            case InExpression in -> {
                assertAllowedExpression(in.getLeftExpression(), paramName);
                // Only a literal value list is allowed on the right-hand side; a ParenthesedSelect
                // lands here for `IN (SELECT ...)` and is not an ExpressionList, so it is rejected.
                if (!(in.getRightExpression() instanceof ExpressionList<?> values)) {
                    throw reject(paramName, "may only use IN with a literal value list");
                }
                for (Object item : (List<?>) values) {
                    assertAllowedExpression((Expression) item, paramName);
                }
            }
            default -> throw reject(paramName, "contains a disallowed SQL expression: "
                    + expression.getClass().getSimpleName() + " (" + expression + ")");
        }
    }

    private static McpValidationException reject(String paramName, String detail) {
        return new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED, paramName + " " + detail);
    }
}
