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
package com.entropy.database.mcp.optimizer;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites {@code x NOT IN (SELECT ...)} into a correlated {@code NOT EXISTS}.
 *
 * <p>The rewrite is performed on the JSQLParser AST, never by string surgery. String concatenation
 * cannot get this right: the subquery's own {@code WHERE} has to be merged with the new correlation
 * predicate (not appended as a second {@code WHERE}), the {@code NOT EXISTS} must replace the whole
 * comparison rather than leave its left operand behind, the correlated reference needs a real outer
 * qualifier instead of a made-up alias, and a subquery containing function calls or nested
 * parentheses defeats any {@code [^)]+}-style match.
 *
 * <h2>为什么必须带 NULL 守卫</h2>
 * <p>{@code x NOT IN (SELECT y FROM b)} 与 {@code NOT EXISTS (SELECT 1 FROM b WHERE y = x)} 并不
 * 等价，差别全在三值逻辑上：子查询结果里只要出现一个 NULL，原式对每一行都是 UNKNOWN（返回 0 行），
 * 而裸的 NOT EXISTS 会把所有行都返回；外层列本身为 NULL 时原式同样是 UNKNOWN，NOT EXISTS 却为真；
 * 但子查询为空是例外——{@code NULL NOT IN (空集)} 是 TRUE。这里的 {@code transformedSql} 是直接交给
 * 模型去执行的，所以不能只快不对。因此改写结果是完整等价式的三段合取：
 *
 * <pre>{@code
 * (a.x IS NOT NULL OR NOT EXISTS (SELECT 1 FROM b WHERE <原 where>))
 *   AND NOT EXISTS (SELECT 1 FROM b WHERE <原 where> AND b.y = a.x)
 *   AND NOT EXISTS (SELECT 1 FROM b WHERE <原 where> AND b.y IS NULL)
 * }</pre>
 *
 * <p>三段整体再包一层括号，否则原式处在 OR 之下时会被改写破坏优先级。
 *
 * <p>The rewrite is deliberately conservative. Anything it does not fully understand — a set
 * operation, a multi-column projection, a non-column comparison, an unparseable statement — leaves
 * the SQL untouched, because handing a model syntactically invalid SQL is worse than handing it no
 * suggestion at all.
 */
final class NotInToNotExistsRewriter {

    private static final Logger log = LoggerFactory.getLogger(NotInToNotExistsRewriter.class);

    private NotInToNotExistsRewriter() {
    }

    /**
     * @return the rewritten SQL, or the input unchanged when no safe rewrite is possible
     */
    static String rewrite(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof PlainSelect outer) || outer.getWhere() == null) {
                return sql;
            }
            Rewrite rewrite = new Rewrite(outer.getFromItem());
            Expression newWhere = rewrite.apply(outer.getWhere());
            if (!rewrite.changed) {
                return sql;
            }
            outer.setWhere(newWhere);
            return outer.toString();
        } catch (Exception e) {
            log.debug("NOT IN rewrite skipped for [{}]: {}", sql, e.getMessage());
            return sql;
        }
    }

    /** Walks the WHERE tree, replacing every safely convertible {@code NOT IN} subquery. */
    private static final class Rewrite {

        private final FromItem outerFrom;
        private boolean changed;

        private Rewrite(FromItem outerFrom) {
            this.outerFrom = outerFrom;
        }

        private Expression apply(Expression expression) {
            if (expression instanceof AndExpression and) {
                and.setLeftExpression(apply(and.getLeftExpression()));
                and.setRightExpression(apply(and.getRightExpression()));
                return and;
            }
            if (expression instanceof OrExpression or) {
                or.setLeftExpression(apply(or.getLeftExpression()));
                or.setRightExpression(apply(or.getRightExpression()));
                return or;
            }
            if (expression instanceof ParenthesedExpressionList<?> group && group.size() == 1) {
                return parens(apply((Expression) group.get(0)));
            }
            if (expression instanceof InExpression in) {
                return applyToIn(in);
            }
            return expression;
        }

        private Expression applyToIn(InExpression in) {
            if (!in.isNot()) {
                return in;
            }
            if (!(in.getRightExpression() instanceof ParenthesedSelect parenthesed)) {
                return in;
            }
            PlainSelect subSelect = plainSelectOf(parenthesed);
            if (subSelect == null) {
                return in;
            }
            List<SelectItem<?>> items = subSelect.getSelectItems();
            if (items == null || items.size() != 1) {
                return in;
            }
            if (!(items.get(0).getExpression() instanceof Column innerColumn)) {
                return in;
            }
            if (!(in.getLeftExpression() instanceof Column outerColumn)) {
                return in;
            }

            Column innerQualified = qualify(innerColumn, subSelect.getFromItem());
            Column outerQualified = qualify(outerColumn, outerFrom);
            if (innerQualified == null || outerQualified == null) {
                return in;
            }

            // 在改动 subSelect 之前先拷两份：一份用来判断子查询是否为空，一份用来探测 NULL，
            // 三份都要保留原始的 WHERE。通过重新解析文本来深拷，JSQLParser 没有公开的 AST 克隆。
            PlainSelect anyRowSelect = copyOf(subSelect);
            PlainSelect nullProbeSelect = copyOf(subSelect);
            if (anyRowSelect == null || nullProbeSelect == null) {
                return in;
            }

            Expression originalWhere = subSelect.getWhere();

            // 第一段：相关化的 NOT EXISTS，等价于「没有任何 y 等于 x」
            subSelect.setWhere(mergeWhere(originalWhere, new EqualsTo(innerQualified, outerQualified)));
            subSelect.setSelectItems(oneProjection());
            Expression noMatch = notExists(parenthesed);

            // 第二段：子查询列出现过 NULL 就整体为 UNKNOWN，原式返回 0 行
            IsNullExpression innerIsNull = new IsNullExpression();
            innerIsNull.setLeftExpression(innerQualified);
            nullProbeSelect.setWhere(mergeWhere(nullProbeSelect.getWhere(), innerIsNull));
            nullProbeSelect.setSelectItems(oneProjection());
            Expression noNullInSubquery = notExists(parenthesize(nullProbeSelect));

            // 第三段：外层列为 NULL 时原式是 UNKNOWN —— 但子查询为空是例外，
            // NULL NOT IN (空集) 是 TRUE，所以这个守卫要放过「子查询一行都没有」的情况
            IsNullExpression outerIsNotNull = new IsNullExpression();
            outerIsNotNull.setLeftExpression(outerQualified);
            outerIsNotNull.setNot(true);
            anyRowSelect.setSelectItems(oneProjection());
            Expression outerGuard = parens(
                    new OrExpression(outerIsNotNull, notExists(parenthesize(anyRowSelect))));

            changed = true;
            // 整体加括号：原式可能位于 OR 之下，裸的 AND 链会改变优先级
            return parens(new AndExpression(
                    new AndExpression(outerGuard, noMatch), noNullInSubquery));
        }

        /**
         * 括号节点。JSQLParser 5.x 起 {@code Parenthesis} 已废弃且无法手工构造
         * （无参构造出来的实例是空列表，setExpression 会 IndexOutOfBounds），
         * 括号统一用单元素的 {@code ParenthesedExpressionList} 表示。
         */
        private static Expression parens(Expression expression) {
            return new ParenthesedExpressionList<>(expression);
        }

        private static ParenthesedSelect parenthesize(PlainSelect select) {
            ParenthesedSelect parenthesed = new ParenthesedSelect();
            parenthesed.setSelect(select);
            return parenthesed;
        }

        private static Expression mergeWhere(Expression existing, Expression added) {
            return existing == null ? added : new AndExpression(parens(existing), added);
        }

        private static List<SelectItem<?>> oneProjection() {
            List<SelectItem<?>> projection = new ArrayList<>(1);
            projection.add(new SelectItem<>(new LongValue(1)));
            return projection;
        }

        private static Expression notExists(ParenthesedSelect subquery) {
            ExistsExpression notExists = new ExistsExpression();
            notExists.setNot(true);
            notExists.setRightExpression(subquery);
            return notExists;
        }

        /**
         * @return an independent copy of {@code select}, or {@code null} when re-parsing its own
         *         rendering does not come back as a plain select — in which case no rewrite happens
         *         rather than one that silently shares AST nodes
         */
        private static PlainSelect copyOf(PlainSelect select) {
            try {
                Statement parsed = CCJSqlParserUtil.parse(select.toString());
                return parsed instanceof PlainSelect plain ? plain : null;
            } catch (Exception e) {
                log.debug("Subquery copy failed for [{}]: {}", select, e.getMessage());
                return null;
            }
        }

        private static PlainSelect plainSelectOf(ParenthesedSelect parenthesed) {
            Select inner = parenthesed.getSelect();
            return inner instanceof PlainSelect plain ? plain : null;
        }

        /**
         * Qualifies an unqualified column with its {@code FROM} table so that the correlation
         * predicate cannot be captured by the wrong scope once it moves inside the subquery.
         *
         * @return the qualified column, or {@code null} when the {@code FROM} item is not a plain
         *         table and the column therefore cannot be qualified safely
         */
        private static Column qualify(Column column, FromItem fromItem) {
            if (column.getTable() != null) {
                return column;
            }
            if (!(fromItem instanceof Table table)) {
                return null;
            }
            Alias alias = table.getAlias();
            Table qualifier = alias != null && alias.getName() != null
                    ? new Table(alias.getName())
                    : new Table(table.getFullyQualifiedName());
            return new Column(qualifier, column.getColumnName());
        }
    }
}
