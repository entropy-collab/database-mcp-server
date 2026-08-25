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
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
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
            if (expression instanceof Parenthesis parenthesis) {
                parenthesis.setExpression(apply(parenthesis.getExpression()));
                return parenthesis;
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

            EqualsTo correlation = new EqualsTo(innerQualified, outerQualified);
            Expression existingWhere = subSelect.getWhere();
            subSelect.setWhere(existingWhere == null
                    ? correlation
                    : new AndExpression(new Parenthesis(existingWhere), correlation));

            List<SelectItem<?>> projection = new ArrayList<>(1);
            projection.add(new SelectItem<>(new LongValue(1)));
            subSelect.setSelectItems(projection);

            ExistsExpression notExists = new ExistsExpression();
            notExists.setNot(true);
            notExists.setRightExpression(parenthesed);
            changed = true;
            return notExists;
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
