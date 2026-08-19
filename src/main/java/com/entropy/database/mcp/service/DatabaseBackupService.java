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
package com.entropy.database.mcp.service;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.security.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;

/**
 * Database backup and restore service.
 */
@Service
public class DatabaseBackupService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;
    private final SqlValidator sqlValidator;
    private final DatabaseHealthMonitor healthMonitor;

    public DatabaseBackupService(JdbcTemplate jdbcTemplate,
                                 DatabaseDialect dialect,
                                 SqlValidator sqlValidator,
                                 DatabaseHealthMonitor healthMonitor) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        this.sqlValidator = sqlValidator;
        this.healthMonitor = healthMonitor;
    }

    /**
     * Backup table schema (DDL statements).
     */
    public Map<String, Object> backupSchema(String tableName) {
        var result = new LinkedHashMap<String, Object>();
        try {
            List<Map<String, Object>> tables;
            if (tableName != null && !tableName.isBlank()) {
                tables = List.of(Map.of("table_name", tableName));
            } else {
                tables = jdbcTemplate.queryForList(
                    dialect.tablesQuery(null), 
                    jdbcTemplate.getDataSource() == null ? null : 
                    jdbcTemplate.queryForObject("SELECT USER FROM DUAL", String.class)
                );
            }
            
            List<Map<String, Object>> ddlStatements = new ArrayList<>();
            
            for (Map<String, Object> table : tables) {
                String tableName_ = (String) table.get("table_name");
                try {
                    String ddl = getCreateTableSql(tableName_, null);
                    if (ddl != null) {
                        ddlStatements.add(Map.of(
                            "table", tableName_,
                            "ddl", ddl
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Failed to get DDL for table: {}", tableName_, e);
                }
            }
            
            result.put("tables", ddlStatements.size());
            result.put("statements", ddlStatements);
            result.put("format", "sql");
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Backup table data as INSERT statements.
     */
    public Map<String, Object> backupData(String tableName, int maxRows) {
        var result = new LinkedHashMap<String, Object>();
        try {
            String columnsSql = dialect.columnsQuery(tableName, null);
            List<Map<String, Object>> columnInfo = jdbcTemplate.queryForList(
                columnsSql, 
                jdbcTemplate.queryForObject("SELECT USER FROM DUAL", String.class),
                tableName.toUpperCase()
            );
            
            if (columnInfo.isEmpty()) {
                result.put("error", "Table not found: " + tableName);
                return result;
            }
            
            List<String> columnNames = new ArrayList<>();
            List<String> columnTypes = new ArrayList<>();
            for (Map<String, Object> col : columnInfo) {
                columnNames.add((String) col.get("column_name"));
                columnTypes.add((String) col.get("data_type"));
            }
            
            // Query data
            String selectSql = "SELECT " + String.join(", ", columnNames) + 
                             " FROM " + tableName + " WHERE ROWNUM <= " + maxRows;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql);
            
            // Generate INSERT statements
            List<String> insertStatements = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                StringBuilder sb = new StringBuilder();
                sb.append("INSERT INTO ").append(tableName).append(" (");
                sb.append(String.join(", ", columnNames));
                sb.append(") VALUES (");
                
                List<String> values = new ArrayList<>();
                for (int i = 0; i < columnNames.size(); i++) {
                    Object val = row.get(columnNames.get(i));
                    values.add(formatInsertValue(val, columnTypes.get(i)));
                }
                sb.append(String.join(", ", values));
                sb.append(");");
                insertStatements.add(sb.toString());
            }
            
            result.put("table", tableName);
            result.put("rows", rows.size());
            result.put("statements", insertStatements);
            result.put("format", "sql");
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Compare schema between two tables (for migration analysis).
     */
    public Map<String, Object> diffSchema(String sourceTable, String targetTable) {
        var result = new LinkedHashMap<String, Object>();
        try {
            var sourceCols = getTableColumns(sourceTable, null);
            var targetCols = getTableColumns(targetTable, null);
            
            Set<String> sourceColNames = sourceCols.keySet();
            Set<String> targetColNames = targetCols.keySet();
            
            // Columns only in source
            List<String> onlyInSource = new ArrayList<>(sourceColNames);
            onlyInSource.removeAll(targetColNames);
            
            // Columns only in target
            List<String> onlyInTarget = new ArrayList<>(targetColNames);
            onlyInTarget.removeAll(sourceColNames);
            
            // Common columns with type differences
            List<Map<String, Object>> typeDiffs = new ArrayList<>();
            for (String col : sourceColNames) {
                if (targetColNames.contains(col)) {
                    String srcType = String.valueOf(sourceCols.get(col).get("data_type"));
                    String tgtType = String.valueOf(targetCols.get(col).get("data_type"));
                    if (!Objects.equals(srcType, tgtType)) {
                        typeDiffs.add(Map.of(
                            "column", col,
                            "source_type", srcType,
                            "target_type", tgtType
                        ));
                    }
                }
            }
            
            result.put("source_table", sourceTable);
            result.put("target_table", targetTable);
            result.put("only_in_source", onlyInSource);
            result.put("only_in_target", onlyInTarget);
            result.put("type_differences", typeDiffs);
            result.put("compatible", onlyInSource.isEmpty() && onlyInTarget.isEmpty() && typeDiffs.isEmpty());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private String getCreateTableSql(String tableName, String schema) {
        try {
            // Try Oracle-specific approach first
            String sql = """
                SELECT DBMS_METADATA.GET_DDL('TABLE', :table, :schema) AS ddl
                FROM DUAL
                """;
            return jdbcTemplate.queryForObject(sql, new Object[]{tableName, schema}, String.class);
        } catch (Exception e) {
            // Fallback: generate basic CREATE TABLE
            return generateBasicCreateTable(tableName, schema);
        }
    }

    private String generateBasicCreateTable(String tableName, String schema) {
        try {
            String columnsSql = dialect.columnsQuery(tableName, schema);
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                columnsSql, 
                schema != null ? schema.toUpperCase() : null,
                tableName.toUpperCase()
            );
            
            StringBuilder ddl = new StringBuilder();
            ddl.append("CREATE TABLE ").append(tableName).append(" (\n");
            
            List<String> columnDefs = new ArrayList<>();
            for (Map<String, Object> col : columns) {
                String colName = (String) col.get("column_name");
                String dataType = (String) col.get("data_type");
                Integer length = col.get("data_length") != null ? 
                    ((Number) col.get("data_length")).intValue() : null;
                String nullable = "Y".equals(col.get("nullable")) ? "" : " NOT NULL";
                
                String def = "    " + colName + " " + dataType;
                if (length != null) {
                    def += "(" + length + ")";
                }
                def += nullable;
                columnDefs.add(def);
            }
            
            ddl.append(String.join(",\n", columnDefs));
            ddl.append("\n);");
            return ddl.toString();
        } catch (Exception e) {
            log.warn("Failed to generate DDL for table: {}", tableName, e);
            return null;
        }
    }

    private Map<String, Map<String, Object>> getTableColumns(String tableName, String schema) {
        try {
            String columnsSql = dialect.columnsQuery(tableName, schema);
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                columnsSql,
                schema != null ? schema.toUpperCase() : null,
                tableName.toUpperCase()
            );
            
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (Map<String, Object> col : columns) {
                result.put((String) col.get("column_name"), col);
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String formatInsertValue(Object value, String dataType) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String) {
            return "'" + ((String) value).replace("'", "''") + "'";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    /**
     * Validate table name contains only safe characters.
     */
    private void validateTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name cannot be empty");
        }
        if (!tableName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
    }

    /**
     * Validate and sanitize table name for SQL construction.
     */
    private String validateTableNameForSql(String tableName) {
        validateTableName(tableName);
        return tableName.toUpperCase();
    }
}
