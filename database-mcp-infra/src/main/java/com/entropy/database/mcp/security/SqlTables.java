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
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 从一条语句里取出它碰到的对象名。
 *
 * <p>抽出来是因为有两个消费者，而"这条 SQL 碰了哪些表"必须只有一份答案：表白名单
 * （{@link SqlValidatorImpl}）与表级授权（{@code authz} 包）如果各自实现一遍，两者就会在
 * 别名、CTE、派生表这些形状上慢慢分叉——而分叉的症状是"白名单拦住了、授权没拦住"，
 * 或者反过来，都不会有任何编译期或启动期的迹象。
 *
 * <h2>归一化</h2>
 * <p>一律 {@code toUpperCase(Locale.ROOT)}。带 schema 前缀的引用<b>保留前缀</b>
 * （{@code app.users} → {@code APP.USERS}）；要不要把前缀纳入匹配由调用方决定，
 * 这里不做取舍。
 */
public final class SqlTables {

    private static final Logger log = LoggerFactory.getLogger(SqlTables.class);

    private SqlTables() {
    }

    /**
     * 解析并取出对象名。
     *
     * @throws McpSqlValidationException 语句无法解析、或包含多于一条语句
     */
    public static Set<String> of(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new McpSqlValidationException(sql, "SQL is empty");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql.trim());
        } catch (Exception e) {
            throw new McpSqlValidationException(sql, "Unable to parse statement for table extraction", e);
        }
        // 必须走 parseStatements 并自己数：JSQLParser 5.x 的 parse("SELECT 1; DROP TABLE t")
        // 静默只返回第一条，于是堆叠语句的第二条会完全不出现在对象名集合里
        if (statements.size() != 1) {
            throw new McpSqlValidationException(
                    sql, "Expected a single statement but found " + statements.size());
        }
        return of(statements.get(0), sql);
    }

    /**
     * 从已解析的 AST 取对象名。
     *
     * <p>包括只出现在派生表、WHERE/SELECT 列表子查询、集合运算或 CTE 体里的表。委托
     * {@link TablesNamesFinder} 顺带剥掉别名并跳过 CTE 名，所以 CTE 不能用一个白名单里的
     * 名字把另一张表夹带进来。
     *
     * <p>遍历失败按校验失败处理，而不是当成空集合：一条解析不出对象名的语句不该因此
     * 畅通无阻地穿过白名单或授权判定。
     *
     * @param sql 只用于错误信息；对象名始终取自 AST
     */
    public static Set<String> of(Statement stmt, String sql) {
        Set<String> found;
        try {
            found = new TablesNamesFinder().getTables(stmt);
        } catch (RuntimeException e) {
            throw new McpSqlValidationException(sql, "Unable to resolve table names for whitelist check", e);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : found) {
            if (name != null && !name.isBlank()) {
                normalized.add(name.toUpperCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    /**
     * 同 {@link #of(String)}，但解析不出来时返回空集合。
     *
     * <p>给那些"取不到表名也必须有个结论"的调用方用——授权判定会据此回落到<b>更粗</b>的
     * 连接级判定，而不是把一个解析错误伪装成拒绝、也不是放行。回落只会更严：只有表级
     * 授权的主体会因此被拒，拿到连接级授权的主体不受影响。
     *
     * <p>为什么会解析不出来：写入路径允许厂商方言的尾巴（{@code SqlTailNormalizer} 会在
     * 校验阶段摘掉它再解析），而这里拿到的是调用方给的原文。
     */
    public static Set<String> ofOrEmpty(String sql) {
        try {
            return of(sql);
        } catch (RuntimeException e) {
            log.warn("取不到这条语句的对象名，授权判定回落到连接级（更严）：{}", e.getMessage());
            return Set.of();
        }
    }
}
