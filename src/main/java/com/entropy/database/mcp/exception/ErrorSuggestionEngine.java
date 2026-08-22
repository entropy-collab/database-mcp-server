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
package com.entropy.database.mcp.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Intelligent error suggestion engine.
 * Analyzes exception types and messages to provide actionable recovery suggestions.
 */
@Component
public class ErrorSuggestionEngine {

    private static final Logger log = LoggerFactory.getLogger(ErrorSuggestionEngine.class);

    // Pattern matching for common error types
    private static final Map<String, SuggestionRule> RULES = new LinkedHashMap<>();

    static {
        // Connection errors
        addRule("Connection is required", new SuggestionRule(
                "DB005", "Missing Connection Parameter",
                "请提供有效的连接名称",
                List.of(
                        "1. 确认 connectionName 参数已正确传递",
                        "2. 使用 listConnections 查看可用连接",
                        "3. 使用 createNamedConnection 创建新连接"
                )
        ));

        addRule("ORA-12170", new SuggestionRule(
                "DB005", "Oracle Connection Failed - TNS Resolution",
                "Oracle 连接失败，可能是网络或 TNS 配置问题",
                List.of(
                        "1. 检查数据库主机和端口是否可访问: telnet <host> <port>",
                        "2. 确认 JDBC URL 格式: jdbc:oracle:thin:@host:port:service_name",
                        "3. 检查防火墙规则是否允许连接",
                        "4. 验证数据库服务是否运行: sqlplus user/pass@connect_string"
                )
        ));

        addRule("Connection refused", new SuggestionRule(
                "DB005", "Database Connection Refused",
                "数据库连接被拒绝，可能原因：服务未启动、端口错误、防火墙拦截",
                List.of(
                        "1. 确认数据库服务正在运行",
                        "2. 检查端口是否正确（Oracle: 1521, MySQL: 3306, PostgreSQL: 5432）",
                        "3. 检查防火墙设置",
                        "4. 验证用户名密码是否正确"
                )
        ));

        addRule("Authentication failed", new SuggestionRule(
                "DB005", "Database Authentication Failed",
                "数据库认证失败，请检查用户名或密码",
                List.of(
                        "1. 确认用户名拼写正确",
                        "2. 检查密码是否正确（注意大小写）",
                        "3. 确认用户是否有远程连接权限",
                        "4. 尝试使用数据库客户端直接连接验证"
                )
        ));

        addRule("Connection not found", new SuggestionRule(
                "DB004", "Connection Not Found",
                "指定的连接名称不存在",
                List.of(
                        "1. 使用 listConnections 查看所有可用连接",
                        "2. 确认连接名称拼写正确",
                        "3. 使用 createNamedConnection 创建新连接",
                        "示例: createNamedConnection(name='mydb', jdbcUrl='jdbc:oracle:thin:@host:1521:ORCL', username='user', password='pass', dialect='oracle')"
                )
        ));

        // SQL errors
        addRule("bad SQL grammar", new SuggestionRule(
                "SQL001", "SQL Syntax Error",
                "SQL 语法错误，请检查 SQL 语句",
                List.of(
                        "1. 检查 SQL 语法是否正确",
                        "2. 确认表名和列名是否存在",
                        "3. 检查数据库方言兼容性（不同数据库语法略有差异）",
                        "4. 如果是 Oracle，确认是否需要加 DUAL 表",
                        "提示: 可使用 describeTable 工具确认表结构"
                )
        ));

        addRule("PreparedStatementCallback", new SuggestionRule(
                "SQL001", "PreparedStatement Error",
                "预编译语句执行失败",
                List.of(
                        "1. 检查参数绑定是否正确",
                        "2. 确认参数类型与列类型匹配",
                        "3. 检查是否有特殊字符需要转义",
                        "4. 查看完整异常信息获取具体错误位置"
                )
        ));

        addRule("ORA-00942", new SuggestionRule(
                "SQL001", "Table or View Does Not Exist",
                "表或视图不存在",
                List.of(
                        "1. 使用 describeTable 确认表是否存在",
                        "2. 检查表名大小写（Oracle 默认大写）",
                        "3. 确认用户是否有该表的访问权限",
                        "4. 检查是否需要添加 schema 前缀"
                )
        ));

        addRule("ORA-00904", new SuggestionRule(
                "SQL001", "Invalid Column Name",
                "列名无效",
                List.of(
                        "1. 使用 describeTable 查看表的实际列名",
                        "2. 检查列名拼写和大小写",
                        "3. 确认列名不包含特殊字符",
                        "4. Oracle 列名默认大写"
                )
        ));

        addRule("ORA-00911", new SuggestionRule(
                "SQL001", "Invalid Character in SQL",
                "SQL 中包含无效字符",
                List.of(
                        "1. 检查 SQL 末尾是否有分号（Oracle 不允许）",
                        "2. 确认没有中文标点符号",
                        "3. 检查特殊字符是否需要转义"
                )
        ));

        // Permission errors
        addRule("ORA-00942", new SuggestionRule(
                "SEC001", "Insufficient Privileges",
                "权限不足，无法访问指定对象",
                List.of(
                        "1. 联系 DBA 授予相应权限",
                        "2. 确认用户名有表的 SELECT/INSERT/UPDATE 权限",
                        "3. 检查是否需要通过角色授权"
                )
        ));

        // Timeout errors
        addRule("timeout", new SuggestionRule(
                "QRY001", "Query Timeout",
                "查询超时，执行时间过长",
                List.of(
                        "1. 检查查询是否缺少 WHERE 条件",
                        "2. 确认相关表是否有索引",
                        "3. 考虑分批查询大数据量表",
                        "4. 联系 DBA 检查执行计划"
                )
        ));

        // Lock errors
        addRule("ORA-00054", new SuggestionRule(
                "SEC001", "Resource Busy - Lock Conflict",
                "资源正忙，需要获取锁",
                List.of(
                        "1. 等待其他事务提交后重试",
                        "2. 使用 showLocks 查看当前锁情况",
                        "3. 联系 DBA 检查是否有长时间锁定的事务",
                        "4. 考虑使用 NOLOCK hint（如果适用）"
                )
        ));

        // Table full errors
        addRule("ORA-01653", new SuggestionRule(
                "ETL001", "Table Space Full",
                "表空间不足，无法扩展",
                List.of(
                        "1. 联系 DBA 增加表空间",
                        "2. 清理不必要的表数据",
                        "3. 检查是否有大对象占用空间",
                        "4. 考虑归档历史数据"
                )
        ));

        // Sequence errors
        addRule("ORA-02289", new SuggestionRule(
                "SQL001", "Sequence Does Not Exist",
                "序列不存在",
                List.of(
                        "1. 使用序列名前检查序列是否存在",
                        "2. 确认序列名大小写",
                        "3. 联系 DBA 创建所需序列"
                )
        ));
    }

    private static void addRule(String pattern, SuggestionRule rule) {
        RULES.put(pattern.toLowerCase(), rule);
    }

    /**
     * Analyze an exception and generate actionable suggestions.
     *
     * @param exception the exception that occurred
     * @param toolName the MCP tool name that failed
     * @return a list of suggested actions, empty if no suggestions match
     */
    public List<String> analyze(Exception exception, String toolName) {
        if (exception == null) {
            return Collections.emptyList();
        }

        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return Collections.emptyList();
        }

        String lowerMessage = message.toLowerCase();

        // Try to find a matching rule
        for (Map.Entry<String, SuggestionRule> entry : RULES.entrySet()) {
            if (lowerMessage.contains(entry.getKey())) {
                return generateSuggestions(entry.getValue(), toolName, exception);
            }
        }

        // Fallback for generic exceptions
        if (isConnectionException(exception)) {
            return generateGenericConnectionSuggestions(toolName);
        }

        if (isSqlException(exception)) {
            return generateGenericSqlSuggestions(toolName, message);
        }

        return Collections.emptyList();
    }

    /**
     * Generate formatted suggestions for the user.
     */
    private List<String> generateSuggestions(SuggestionRule rule, String toolName, Exception exception) {
        List<String> suggestions = new ArrayList<>();

        // Add header with context
        suggestions.add(String.format("🔧 %s (工具: %s)", rule.getTitle(), toolName));
        suggestions.add(String.format("💡 %s", rule.getSummary()));
        suggestions.add("");
        suggestions.add("📋 建议操作:");

        // Add numbered suggestions
        for (int i = 0; i < rule.getSteps().size(); i++) {
            suggestions.add(String.format("  %d. %s", i + 1, rule.getSteps().get(i)));
        }

        // Add root cause if available
        suggestions.add("");
        suggestions.add(String.format("📌 根因: %s", exception.getClass().getSimpleName()));

        return suggestions;
    }

    private List<String> generateGenericConnectionSuggestions(String toolName) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add(String.format("🔧 连接问题 (工具: %s)", toolName));
        suggestions.add("💡 数据库连接失败，请检查以下项目:");
        suggestions.add("");
        suggestions.add("📋 建议操作:");
        suggestions.add("  1. 使用 listConnections 查看可用连接");
        suggestions.add("  2. 确认连接名称拼写正确");
        suggestions.add("  3. 检查数据库服务是否运行");
        suggestions.add("  4. 验证网络连接是否正常");
        suggestions.add("  5. 联系 DBA 确认数据库状态");
        return suggestions;
    }

    private List<String> generateGenericSqlSuggestions(String toolName, String message) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add(String.format("🔧 SQL 执行问题 (工具: %s)", toolName));
        suggestions.add(String.format("💡 错误信息: %s", truncate(message, 200)));
        suggestions.add("");
        suggestions.add("📋 建议操作:");
        suggestions.add("  1. 检查 SQL 语法是否正确");
        suggestions.add("  2. 使用 describeTable 确认表结构");
        suggestions.add("  3. 检查表和列名是否存在");
        suggestions.add("  4. 确认数据库方言兼容性");
        return suggestions;
    }

    private boolean isConnectionException(Exception e) {
        String className = e.getClass().getName().toLowerCase();
        return className.contains("connection")
                || className.contains("datasource")
                || className.contains("jdbc")
                || e.getMessage() != null && e.getMessage().toLowerCase().contains("connection");
    }

    private boolean isSqlException(Exception e) {
        String className = e.getClass().getName().toLowerCase();
        return className.contains("sql")
                || className.contains("grammar")
                || className.contains("constraint")
                || className.contains("database");
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...";
    }

    /**
     * Inner class to hold suggestion rule data.
     */
    private static class SuggestionRule {
        private final String errorCode;
        private final String title;
        private final String summary;
        private final List<String> steps;

        SuggestionRule(String errorCode, String title, String summary, List<String> steps) {
            this.errorCode = errorCode;
            this.title = title;
            this.summary = summary;
            this.steps = steps;
        }

        String getErrorCode() { return errorCode; }
        String getTitle() { return title; }
        String getSummary() { return summary; }
        List<String> getSteps() { return steps; }
    }
}
