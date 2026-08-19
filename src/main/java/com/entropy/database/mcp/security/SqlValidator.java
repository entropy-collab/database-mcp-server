package com.entropy.database.mcp.security;

import com.entropy.database.mcp.exception.SqlValidationException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@ConfigurationProperties(prefix = "entropy.mcp.database.security")
public class SqlValidator {
    private static final Logger log = LoggerFactory.getLogger(SqlValidator.class);
    private static final Set<String> ALLOWED_OPS = Set.of("SELECT", "DESCRIBE", "SHOW", "EXPLAIN");

    private Set<String> allowedTables = new HashSet<>();
    private Set<String> allowedOperations = new HashSet<>(ALLOWED_OPS);
    private int maxRows = 1000;
    private List<String> maskColumns = new ArrayList<>();

    public void validateSelect(String sql) { validate(sql, false); }
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
        int max = extractMaxRows(stmt);
        if (max > maxRows) throw new SqlValidationException(sql, "Exceeds max rows: " + max);
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
        return maxRows;
    }

    public List<String> getMaskColumns() { return maskColumns; }
    public void setMaskColumns(List<String> maskColumns) { this.maskColumns = maskColumns; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public Set<String> getAllowedTables() { return allowedTables; }
    public void setAllowedTables(Set<String> allowedTables) { this.allowedTables = allowedTables; }
    public Set<String> getAllowedOperations() { return allowedOperations; }
    public void setAllowedOperations(Set<String> allowedOperations) { this.allowedOperations = allowedOperations; }
}
