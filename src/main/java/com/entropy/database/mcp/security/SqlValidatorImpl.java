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

import com.entropy.database.mcp.exception.McpSqlValidationException;
import com.entropy.database.mcp.properties.DatabaseProperties;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Fetch;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SqlValidatorImpl implements SqlValidator {
    private static final Logger log = LoggerFactory.getLogger(SqlValidatorImpl.class);
    private static final Set<String> ALLOWED_OPS = Set.of("SELECT", "DESCRIBE", "SHOW", "EXPLAIN");

    /**
     * MySQL/MariaDB executable comments. Everything inside {@code /*! ... }{@code *}{@code /} is
     * stripped by the parser but executed by the server, so a statement that parses as a plain
     * SELECT can carry a UNION, a write, or a version-gated payload past every rule below.
     */
    private static final String EXECUTABLE_COMMENT = "/*!";

    private final DatabaseProperties properties;

    // Thread-safe: CopyOnWriteArrayList provides snapshot-style iteration for concurrent reads
    private volatile List<String> maskColumns = new CopyOnWriteArrayList<>();

    // Effectively immutable after construction — read-only for all validate() calls
    private final Set<String> allowedOperations;

    private final Object tablesLock = new Object();
    // volatile ensures visibility of the reference swap; all access goes through tablesLock
    private volatile Set<String> allowedTables = new HashSet<>();

    public SqlValidatorImpl(DatabaseProperties properties) {
        this.properties = properties;
        this.allowedOperations = Collections.unmodifiableSet(new HashSet<>(ALLOWED_OPS));
        this.allowedTables = normalizeTables(properties.security().allowedTables());
    }

    @Override
    public int getMaxRows() { return properties.query().maxRows(); }
    @Override
    public void setMaxRows(int maxRows) { /* no-op, configured via properties */ }
    public int getMaxJoins() { return properties.security() != null ? properties.security().maxJoins() : 10; }
    @Override
    public void setMaxJoins(int maxJoins) { /* no-op, configured via properties */ }
    public int getMaxSubqueryDepth() { return properties.security() != null ? properties.security().maxSubqueryDepth() : 5; }
    @Override
    public void setMaxSubqueryDepth(int maxSubqueryDepth) { /* no-op, configured via properties */ }
    @Override
    public void validateSelect(String sql) { validate(sql, false); }
    @Override
    public void validateDdl(String sql) { validate(sql, true); }

    private void validate(String sql, boolean isDdl) {
        if (sql == null || sql.isBlank()) throw new McpSqlValidationException(sql, "SQL is empty");
        if (sql.contains(EXECUTABLE_COMMENT))
            throw new McpSqlValidationException(sql, "Executable comments are not allowed");
        Statement stmt;
        try { stmt = CCJSqlParserUtil.parse(sql.trim()); }
        catch (Exception e) { throw new McpSqlValidationException(sql, "SQL validation error", e); }
        String op = extractOp(stmt);
        if (!allowedOperations.contains(op.toUpperCase()) && !isDdl)
            throw new McpSqlValidationException(sql, "Operation not allowed: " + op);
        // Capture a consistent snapshot of allowedTables under lock
        Set<String> currentTables;
        synchronized (tablesLock) { currentTables = allowedTables; }
        if (!currentTables.isEmpty() && isSelect(stmt)) {
            Set<String> unauth = new LinkedHashSet<>();
            for (String table : extractTables(sql, stmt)) {
                if (!isWhitelisted(table, currentTables)) unauth.add(table);
            }
            if (!unauth.isEmpty()) throw new McpSqlValidationException(sql, "Tables not allowed: " + unauth);
        }
        int joins = extractJoinCount(stmt);
        if (joins > getMaxJoins()) throw new McpSqlValidationException(sql, "Exceeds max joins: " + joins + " > " + getMaxJoins());
        int depth = extractSubqueryDepth(stmt);
        if (depth > getMaxSubqueryDepth()) throw new McpSqlValidationException(sql, "Exceeds max subquery depth: " + depth + " > " + getMaxSubqueryDepth());
        int max = extractMaxRows(stmt);
        if (max > getMaxRows()) throw new McpSqlValidationException(sql, "Exceeds max rows: " + max);
    }

    private String extractOp(Statement stmt) {
        String cls = stmt.getClass().getSimpleName();
        if (cls.contains("Select")) return "SELECT";
        if (cls.contains("Describe")) return "DESCRIBE";
        if (cls.contains("Show")) return "SHOW";
        if (cls.contains("Explain")) return "EXPLAIN";
        return "UNKNOWN";
    }

    private boolean isSelect(Statement stmt) {
        return stmt instanceof Select;
    }

    /**
     * Collects every table the statement reads, including tables that only appear inside a
     * derived table, a WHERE/SELECT-list subquery, a set operation or a CTE body. Delegating to
     * {@link TablesNamesFinder} also strips aliases and skips CTE names, so a CTE cannot shadow a
     * whitelisted name to smuggle in a different table.
     *
     * <p>Traversal failure is treated as a validation failure rather than an empty result: an
     * unresolvable statement must not slip past the whitelist.</p>
     */
    private Set<String> extractTables(String sql, Statement stmt) {
        Set<String> found;
        try {
            found = new TablesNamesFinder().getTables(stmt);
        } catch (RuntimeException e) {
            throw new McpSqlValidationException(sql, "Unable to resolve table names for whitelist check", e);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : found) {
            if (name != null && !name.isBlank()) normalized.add(name.toUpperCase(Locale.ROOT));
        }
        return normalized;
    }

    /**
     * A schema-qualified reference is matched on its table name as well as its full name, so
     * {@code allowed-tables: [USERS]} accepts {@code app.users} while {@code allowed-tables: [APP]}
     * does not silently grant every table in that schema.
     */
    private boolean isWhitelisted(String table, Set<String> allowed) {
        if (allowed.contains(table)) return true;
        int dot = table.lastIndexOf('.');
        return dot >= 0 && dot + 1 < table.length() && allowed.contains(table.substring(dot + 1));
    }

    /** Whitelist entries are normalized so YAML may spell table names in any case. */
    private static Set<String> normalizeTables(Collection<String> tables) {
        Set<String> normalized = new HashSet<>();
        if (tables != null) {
            for (String t : tables) {
                if (t != null && !t.isBlank()) normalized.add(t.trim().toUpperCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    /**
     * Reads the row cap the client asked for, covering both {@code LIMIT n} and the ANSI/Oracle
     * {@code FETCH FIRST n ROWS ONLY}. Oracle is this server's primary dialect and has no
     * {@code LIMIT}, so ignoring FETCH would leave the rule inert there.
     *
     * @return the requested cap, or the configured maximum when the statement asks for no cap
     */
    private int extractMaxRows(Statement stmt) {
        if (!(stmt instanceof Select select)) return getMaxRows();
        Long requested = explicitRowLimit(select);
        if (requested == null) return getMaxRows();
        return requested > Integer.MAX_VALUE ? Integer.MAX_VALUE : requested.intValue();
    }

    private Long explicitRowLimit(Select select) {
        Limit limit = select.getLimit();
        if (limit != null) {
            Long value = longValueOf(limit.getRowCount());
            if (value != null) return value;
        }
        Fetch fetch = select.getFetch();
        if (fetch != null) {
            Long value = longValueOf(fetch.getExpression());
            if (value != null) return value;
            if (fetch.getRowCount() > 0) return fetch.getRowCount();
        }
        if (select instanceof ParenthesedSelect parenthesed) return explicitRowLimit(parenthesed.getSelect());
        return null;
    }

    private Long longValueOf(Expression expr) {
        return expr instanceof LongValue value ? value.getValue() : null;
    }

    /** Total joins across the whole statement, so joins inside a subquery are not free. */
    private int extractJoinCount(Statement stmt) {
        return stmt instanceof Select select ? joinCount(select) : 0;
    }

    private int joinCount(Select select) {
        if (select instanceof ParenthesedSelect parenthesed) return joinCount(parenthesed.getSelect());
        if (select instanceof SetOperationList operations) {
            int total = 0;
            if (operations.getSelects() != null) {
                for (Select branch : operations.getSelects()) total += joinCount(branch);
            }
            return total;
        }
        if (select instanceof PlainSelect plain) {
            int total = plain.getJoins() == null ? 0 : plain.getJoins().size();
            total += nestedSelects(plain).stream().mapToInt(this::joinCount).sum();
            return total;
        }
        return 0;
    }

    /**
     * Measures how deeply subqueries nest. Every parenthesised select reached from a FROM item, a
     * JOIN, the WHERE/HAVING tree or the SELECT list counts as one level, so a chain of
     * {@code IN (SELECT ...)} predicates behind {@code AND}/{@code OR} is counted rather than
     * collapsing to zero.
     */
    private int extractSubqueryDepth(Statement stmt) {
        return stmt instanceof Select select ? selectDepth(select, 0) : 0;
    }

    private int selectDepth(Select select, int depth) {
        if (select instanceof ParenthesedSelect parenthesed) return selectDepth(parenthesed.getSelect(), depth);
        if (select instanceof SetOperationList operations) {
            int max = depth;
            if (operations.getSelects() != null) {
                for (Select branch : operations.getSelects()) max = Math.max(max, selectDepth(branch, depth));
            }
            return max;
        }
        if (select instanceof PlainSelect plain) {
            int max = depth;
            for (Select nested : nestedSelects(plain)) max = Math.max(max, selectDepth(nested, depth + 1));
            max = Math.max(max, expressionDepth(plain.getWhere(), depth));
            max = Math.max(max, expressionDepth(plain.getHaving(), depth));
            if (plain.getSelectItems() != null) {
                for (SelectItem<?> item : plain.getSelectItems()) {
                    max = Math.max(max, expressionDepth(item.getExpression(), depth));
                }
            }
            return max;
        }
        return depth;
    }

    /** Selects nested directly under this select's FROM item, JOINs and WITH clause. */
    private List<Select> nestedSelects(PlainSelect plain) {
        List<Select> nested = new ArrayList<>();
        addIfSelect(nested, plain.getFromItem());
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) addIfSelect(nested, join.getRightItem());
        }
        if (plain.getWithItemsList() != null) {
            for (WithItem with : plain.getWithItemsList()) nested.add(with.getSelect());
        }
        return nested;
    }

    private void addIfSelect(List<Select> target, FromItem fromItem) {
        if (fromItem instanceof ParenthesedSelect parenthesed) target.add(parenthesed.getSelect());
    }

    /**
     * Walks the composite expression types that can hold a subquery. Unrecognised node types stop
     * the walk, which under-counts rather than over-counts; the depth limit is a cost guard, and
     * the table whitelist — the actual security boundary — uses {@link TablesNamesFinder} instead.
     */
    private int expressionDepth(Expression expr, int depth) {
        if (expr == null) return depth;
        if (expr instanceof Select nested) return selectDepth(nested, depth + 1);
        if (expr instanceof BinaryExpression binary) {
            return Math.max(expressionDepth(binary.getLeftExpression(), depth),
                    expressionDepth(binary.getRightExpression(), depth));
        }
        if (expr instanceof InExpression in) {
            return Math.max(expressionDepth(in.getLeftExpression(), depth),
                    expressionDepth(in.getRightExpression(), depth));
        }
        if (expr instanceof ExistsExpression exists) return expressionDepth(exists.getRightExpression(), depth);
        if (expr instanceof Parenthesis parenthesis) return expressionDepth(parenthesis.getExpression(), depth);
        if (expr instanceof NotExpression not) return expressionDepth(not.getExpression(), depth);
        if (expr instanceof SignedExpression signed) return expressionDepth(signed.getExpression(), depth);
        if (expr instanceof IsNullExpression isNull) return expressionDepth(isNull.getLeftExpression(), depth);
        if (expr instanceof CastExpression cast) return expressionDepth(cast.getLeftExpression(), depth);
        if (expr instanceof Between between) {
            int max = expressionDepth(between.getLeftExpression(), depth);
            max = Math.max(max, expressionDepth(between.getBetweenExpressionStart(), depth));
            return Math.max(max, expressionDepth(between.getBetweenExpressionEnd(), depth));
        }
        if (expr instanceof CaseExpression caseExpr) {
            int max = expressionDepth(caseExpr.getSwitchExpression(), depth);
            max = Math.max(max, expressionDepth(caseExpr.getElseExpression(), depth));
            if (caseExpr.getWhenClauses() != null) {
                for (WhenClause when : caseExpr.getWhenClauses()) {
                    max = Math.max(max, expressionDepth(when.getWhenExpression(), depth));
                    max = Math.max(max, expressionDepth(when.getThenExpression(), depth));
                }
            }
            return max;
        }
        if (expr instanceof ExpressionList<?> list) {
            int max = depth;
            for (Expression item : list) max = Math.max(max, expressionDepth(item, depth));
            return max;
        }
        if (expr instanceof Function function) return expressionDepth(function.getParameters(), depth);
        return depth;
    }

    @Override
    public List<String> getMaskColumns() { return List.copyOf(maskColumns); }
    @Override
    public void setMaskColumns(List<String> maskColumns) {
        synchronized (tablesLock) { this.maskColumns = new CopyOnWriteArrayList<>(maskColumns); }
    }
    @Override
    public Set<String> getAllowedTables() {
        synchronized (tablesLock) { return Collections.unmodifiableSet(allowedTables); }
    }
    @Override
    public void setAllowedTables(Set<String> allowedTables) {
        synchronized (tablesLock) { this.allowedTables = normalizeTables(allowedTables); }
    }
    @Override
    public Set<String> getAllowedOperations() { return allowedOperations; }
    @Override
    public void setAllowedOperations(Set<String> allowedOperations) { /* no-op, immutable */ }
}
