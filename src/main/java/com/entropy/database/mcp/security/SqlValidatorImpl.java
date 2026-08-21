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

import com.entropy.database.mcp.exception.SqlValidationException;
import com.entropy.database.mcp.properties.DatabaseProperties;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SqlValidatorImpl implements SqlValidator {
    private static final Logger log = LoggerFactory.getLogger(SqlValidatorImpl.class);
    private static final Set<String> ALLOWED_OPS = Set.of("SELECT", "DESCRIBE", "SHOW", "EXPLAIN");

    private final DatabaseProperties properties;

    private Set<String> allowedTables = new HashSet<>();
    private Set<String> allowedOperations = new HashSet<>(ALLOWED_OPS);
    private List<String> maskColumns = new ArrayList<>();

    public SqlValidatorImpl(DatabaseProperties properties) {
        this.properties = properties;
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
        if (sql == null || sql.isBlank()) throw new SqlValidationException(sql, "SQL is empty");
        Statement stmt;
        try { stmt = CCJSqlParserUtil.parse(sql.trim()); }
        catch (Exception e) { throw new SqlValidationException(sql, "Invalid SQL: " + e.getMessage(), e); }
        String op = extractOp(stmt);
        if (!allowedOperations.contains(op.toUpperCase()) && !isDdl)
            throw new SqlValidationException(sql, "Operation not allowed: " + op);
        if (!allowedTables.isEmpty() && isSelect(stmt)) {
            Set<String> tables = extractTables(stmt);
            Set<String> unauth = new HashSet<>(tables);
            unauth.removeAll(allowedTables);
            if (!unauth.isEmpty()) throw new SqlValidationException(sql, "Tables not allowed: " + unauth);
        }
        int joins = extractJoinCount(stmt);
        if (joins > getMaxJoins()) throw new SqlValidationException(sql, "Exceeds max joins: " + joins + " > " + getMaxJoins());
        int depth = extractSubqueryDepth(stmt);
        if (depth > getMaxSubqueryDepth()) throw new SqlValidationException(sql, "Exceeds max subquery depth: " + depth + " > " + getMaxSubqueryDepth());
        int max = extractMaxRows(stmt);
        if (max > getMaxRows()) throw new SqlValidationException(sql, "Exceeds max rows: " + max);
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
        return stmt instanceof PlainSelect || stmt.getClass().getSimpleName().contains("Select");
    }

    private Set<String> extractTables(Statement stmt) {
        Set<String> tables = new HashSet<>();
        if (stmt instanceof PlainSelect) {
            PlainSelect ps = (PlainSelect) stmt;
            var from = ps.getFromItem();
            if (from != null) {
                String t = from.toString().split("\\.")[0];
                if (!t.matches("\\d+")) tables.add(t.toUpperCase());
            }
            if (ps.getJoins() != null) {
                for (var j : ps.getJoins()) {
                    String t = j.getRightItem().toString().split("\\.")[0];
                    if (!t.matches("\\d+")) tables.add(t.toUpperCase());
                }
            }
        }
        return tables;
    }

    private int extractMaxRows(Statement stmt) {
        if (stmt instanceof PlainSelect) {
            PlainSelect ps = (PlainSelect) stmt;
            if (ps.getLimit() != null && ps.getLimit().getRowCount() != null) {
                Object rowCountObj = ps.getLimit().getRowCount();
                if (rowCountObj instanceof Long) {
                    return ((Long) rowCountObj).intValue();
                }
            }
        }
        return getMaxRows();
    }

    private int extractJoinCount(Statement stmt) {
        if (stmt instanceof PlainSelect ps) {
            return ps.getJoins() != null ? ps.getJoins().size() : 0;
        }
        return 0;
    }

    private int extractSubqueryDepth(Statement stmt) {
        return extractSubqueryDepth(stmt, 0);
    }

    private int extractSubqueryDepth(Statement stmt, int currentDepth) {
        if (stmt instanceof PlainSelect ps) {
            // FROM 子句中的子查询 (ParenthesedSelect)
            if (ps.getFromItem() instanceof ParenthesedSelect parenthesed) {
                Statement subStmt = (Statement) parenthesed.getSelectBody();
                int subDepth = extractSubqueryDepth(subStmt, currentDepth + 1);
                if (subDepth > currentDepth) currentDepth = subDepth;
            }
            // WHERE 子句中的子查询
            if (ps.getWhere() != null) {
                int whereDepth = extractSubqueryDepth(ps.getWhere(), currentDepth);
                if (whereDepth > currentDepth) currentDepth = whereDepth;
            }
            // JOIN 子句中的子查询
            if (ps.getJoins() != null) {
                for (var join : ps.getJoins()) {
                    if (join.getRightItem() instanceof ParenthesedSelect parenthesed) {
                        Statement subStmt = (Statement) parenthesed.getSelectBody();
                        int joinDepth = extractSubqueryDepth(subStmt, currentDepth + 1);
                        if (joinDepth > currentDepth) currentDepth = joinDepth;
                    }
                }
            }
        }
        return currentDepth;
    }

    private int extractSubqueryDepth(net.sf.jsqlparser.expression.Expression expr, int currentDepth) {
        if (expr instanceof net.sf.jsqlparser.expression.operators.relational.InExpression in) {
            if (in.getRightExpression() instanceof ParenthesedSelect parenthesed) {
                Statement subStmt = (Statement) parenthesed.getSelectBody();
                return extractSubqueryDepth(subStmt, currentDepth + 1);
            }
        }
        if (expr instanceof net.sf.jsqlparser.expression.operators.relational.ExistsExpression exists) {
            if (exists.getRightExpression() instanceof ParenthesedSelect parenthesed) {
                Statement subStmt = (Statement) parenthesed.getSelectBody();
                return extractSubqueryDepth(subStmt, currentDepth + 1);
            }
        }
        return currentDepth;
    }

    @Override
    public List<String> getMaskColumns() { return maskColumns; }
    @Override
    public void setMaskColumns(List<String> maskColumns) { this.maskColumns = maskColumns; }
    @Override
    public Set<String> getAllowedTables() { return allowedTables; }
    @Override
    public void setAllowedTables(Set<String> allowedTables) { this.allowedTables = allowedTables; }
    @Override
    public Set<String> getAllowedOperations() { return allowedOperations; }
    @Override
    public void setAllowedOperations(Set<String> allowedOperations) { this.allowedOperations = allowedOperations; }
}
