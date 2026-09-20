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

import com.entropy.database.mcp.contract.SqlTailNormalizer;
import com.entropy.database.mcp.exception.McpSqlValidationException;
import com.entropy.database.mcp.properties.DatabaseProperties;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.RenameTableStatement;
import net.sf.jsqlparser.statement.alter.sequence.AlterSequence;
import net.sf.jsqlparser.statement.comment.Comment;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.sequence.CreateSequence;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.AlterView;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.refresh.RefreshMaterializedViewStatement;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
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

    /**
     * {@code validateDdl} 白名单。
     *
     * <p><b>先说清这个方法名的误导性</b>：它校验的不是「DDL」，而是「一切会写库的语句」。
     * {@code DatabaseWriteRepository.executeDdl}（:42）与 {@code ByokWriteRepository}（:36）
     * 把 {@code insertData} / {@code upsertData} / ETL 的每一条写入都送进这里，
     * {@code CrossDatabaseTools}（:324、:346）还送进 Oracle 的 CREATE/DROP DATABASE LINK。
     * 所以白名单必须同时容纳 DML；只放 DDL 会把所有写工具一并打死。
     *
     * <p>此前这条路是把操作类型闸门整段短路掉的（{@code !isDdl} 才检查），等于「只要走这条路就什么都能执行」。
     * 这条路可被 MCP 调用方直接触发，而 app 模块把 h2 以 runtime 作用域打进了可执行 jar，于是
     * {@code createNamedConnection("jdbc:h2:mem:x")} + {@code CREATE ALIAS x FOR 'java.lang.Runtime.getRuntime'}
     * + {@code SELECT x()} 就是一条完整的服务端 JVM 内命令执行链。
     * {@code ByokProperties.UrlGuard} 的 {@code "create alias"} 关键字只作用于 JDBC URL，拦不到这里。
     *
     * <p>用白名单而不是黑名单，判断依据是「是不是我认识的类型」：解析成
     * {@code CreateFunctionalStatement}（CREATE FUNCTION / PROCEDURE / TRIGGER / ALIAS 都归这里）、
     * {@code Grant}、{@code AlterSystemStatement}、{@code UnsupportedStatement}、
     * 或任何未列出的类型，一律拒绝。语句压根解析不出来时 {@link #onlyStatementOf} 已经先抛了，
     * 所以「没想到的形态」在两条路上都落在拒绝那一侧。
     *
     * <p>用精确类相等而不是 {@code instanceof}：{@code CreateFunction} / {@code CreateProcedure}
     * 继承自 {@code CreateFunctionalStatement}，若来日某个安全类型被派生出危险子类，
     * 精确匹配会把子类挡在外面，而 {@code instanceof} 会放它进来。
     */
    private static final Set<Class<? extends Statement>> ALLOWED_DDL_STATEMENTS = Set.of(
            // DML —— 写工具走的就是这条路，详见上面的说明
            Insert.class,
            Update.class,
            Delete.class,
            Merge.class,
            Upsert.class,
            // 作用于数据对象的 DDL
            CreateTable.class,
            CreateIndex.class,
            CreateView.class,
            AlterView.class,
            CreateSequence.class,
            AlterSequence.class,
            Alter.class,
            Truncate.class,
            Comment.class,
            RenameTableStatement.class,
            RefreshMaterializedViewStatement.class,
            Drop.class);


    /**
     * {@code DROP} 允许作用的对象类型。{@code Drop} 一个类覆盖了 {@code DROP TABLE} 到
     * {@code DROP USER} 的全部形态，只放行类本身等于连带放行了删角色、删函数、删表空间，
     * 所以这里按 {@code Drop.getType()} 再收一道。
     */
    private static final Set<String> DROPPABLE_OBJECT_TYPES = Set.of(
            "TABLE", "INDEX", "VIEW", "MATERIALIZED VIEW", "SEQUENCE", "SYNONYM");

    private final DatabaseProperties properties;

    // Thread-safe: CopyOnWriteArrayList provides snapshot-style iteration for concurrent reads
    private volatile List<String> maskColumns = new CopyOnWriteArrayList<>();

    // Effectively immutable after construction — read-only for all validate() calls
    private final Set<String> allowedOperations;

    private final Object tablesLock = new Object();
    // volatile ensures visibility of the reference swap; all access goes through tablesLock
    private volatile Set<String> allowedTables = new HashSet<>();

    /**
     * 解析失败时的补救链。当前只装内置的那一个，**不做 ServiceLoader 发现**——理由见
     * {@link SqlTailNormalizer} 的类注释：这个缝放松的是写入路径的闸门，与 {@code DialectProvider}
     * 的信任模型不同，不该让「jar 在 classpath 上」就等于「有权放宽校验」。
     */
    private final SqlTailNormalizers tailNormalizers =
            new SqlTailNormalizers(List.of(new PartitionedIndexTailNormalizer()));

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
        String trimmed = sql.trim();
        try { stmt = onlyStatementOf(trimmed); }
        catch (Exception e) { stmt = parseAfterStrippingTail(sql, trimmed, isDdl, e); }
        String op = extractOp(stmt);
        if (isDdl) {
            requireAllowedDdl(sql, stmt);
        } else if (!allowedOperations.contains(op.toUpperCase())) {
            throw new McpSqlValidationException(sql, "Operation not allowed: " + op);
        }
        // 顶层类名是 Select 不代表这条语句只读，两条路都要过这道检查：DDL 路径同样可能
        // 递给一条 SELECT（现有测试就钉了这一点），而 CTE 里的 DML 恰恰伪装成 Select。
        if (stmt instanceof Select select) {
            requireReadOnlySelect(sql, select);
        }
        // Capture a consistent snapshot of allowedTables under lock
        Set<String> currentTables;
        synchronized (tablesLock) { currentTables = allowedTables; }
        // 表白名单对所有语句类型生效。此前只在 isSelect(stmt) 时检查，于是配了 allowed-tables 的
        // 部署方以为「只能碰这几张表」，而 insertData / upsertData / executeDdl 的目标表完全不受约束。
        // TablesNamesFinder 对 Insert/Update/Delete/Create/Drop 一样能取到被操作对象名。
        if (!currentTables.isEmpty()) {
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

    /**
     * 解析出唯一一条语句。必须走 {@code parseStatements}：JSQLParser 5.x 的
     * {@code parse("SELECT 1; DROP TABLE t")} 不再抛异常，而是静默只返回第一条语句，
     * 于是堆叠语句会带着未被审查的第二条原样交给 JDBC。
     *
     * @throws JSQLParserException 语句无法解析
     * @throws IllegalArgumentException 输入包含多于一条语句
     */
    private static Statement onlyStatementOf(String sql) throws JSQLParserException {
        Statements statements = CCJSqlParserUtil.parseStatements(sql);
        if (statements.size() != 1) {
            throw new IllegalArgumentException("Expected a single statement but found " + statements.size());
        }
        return statements.get(0);
    }

    /**
     * 解析失败后的唯一一次补救：请 {@link SqlTailNormalizer} 摘掉厂商方言的尾巴再解析一次。
     *
     * <p><b>成功路径零变化</b>：只有 {@link #onlyStatementOf} 抛了才会走到这里，解析得动的语句
     * 完全不经过这条路。
     *
     * <p><b>只在写入路径上生效</b>：{@code isDdl == false}（也就是 validateSelect）直接照旧抛。
     * 查询没有厂商 DDL 尾巴这类问题，放松只读路径的解析要求没有收益，只有风险。
     *
     * <p>摘完之后走的是**完整的既有校验管线**：下游的语句类型白名单仍看解析出来的 AST 类型，
     * 表白名单仍从 AST 取对象名——{@link #extractTables} 的 {@code sql} 参数只用于错误信息。
     * 也就是说这条路放松的只是「能不能解析成功」，不是「解析成功之后准不准」。异常信息里带的
     * 始终是调用方给的原文。
     */
    private Statement parseAfterStrippingTail(String sql, String trimmed, boolean isDdl, Exception cause) {
        if (!isDdl) {
            throw new McpSqlValidationException(sql, "SQL validation error", cause);
        }
        Optional<String> stripped = tailNormalizers.stripTail(trimmed);
        if (stripped.isEmpty()) {
            throw new McpSqlValidationException(sql, "SQL validation error", cause);
        }
        try {
            return onlyStatementOf(stripped.get());
        } catch (Exception retryFailed) {
            // 摘掉尾巴仍然解析不出来：报原始失败原因，那个对调用方更有用。
            throw new McpSqlValidationException(sql, "SQL validation error", cause);
        }
    }

    private String extractOp(Statement stmt) {
        String cls = stmt.getClass().getSimpleName();
        if (cls.contains("Select")) return "SELECT";
        if (cls.contains("Describe")) return "DESCRIBE";
        if (cls.contains("Show")) return "SHOW";
        // Oracle 的 EXPLAIN PLAN FOR 会写入 PLAN_TABLE，不是只读语句，
        // 因此与 MySQL 的 EXPLAIN SELECT 区别对待，只允许后者。
        if (stmt instanceof ExplainStatement explain) {
            return explain.getOption(ExplainStatement.OptionType.PLAN_FOR) != null ? "EXPLAIN PLAN" : "EXPLAIN";
        }
        if (cls.contains("Explain")) return "EXPLAIN";
        return "UNKNOWN";
    }

    /**
     * 只放行 {@link #ALLOWED_DDL_STATEMENTS} 里的语句类型，{@code DROP} 再按对象类型收一道。
     *
     * @throws McpSqlValidationException 语句类型不在白名单内
     */
    private void requireAllowedDdl(String sql, Statement stmt) {
        // SELECT 交到写入路径上是无害的——只要它真的只读，而这一点由 requireReadOnlySelect
        // 保证（CTE 内 DML、SELECT INTO、FOR UPDATE 都会在那里被拒）。放行它有两个实际理由：
        // 结构限制（join 数、子查询深度、行数上限）与表白名单对这条路同样要生效，而不是在这里
        // 提前拒掉；而 INSERT ... SELECT 这类语句的 SELECT 部分本就属于写入语义的一部分。
        if (stmt instanceof Select) {
            return;
        }
        if (!ALLOWED_DDL_STATEMENTS.contains(stmt.getClass())) {
            throw new McpSqlValidationException(sql,
                    "DDL statement not allowed: " + stmt.getClass().getSimpleName());
        }
        if (stmt instanceof Drop drop) {
            String type = drop.getType() == null ? "" : drop.getType().trim().toUpperCase(Locale.ROOT);
            if (!DROPPABLE_OBJECT_TYPES.contains(type)) {
                throw new McpSqlValidationException(sql, "DROP target not allowed: " + drop.getType());
            }
        }
    }

    /**
     * 确认一条解析成 {@code Select} 的语句真的只读。
     *
     * <p>{@link #extractOp} 只看顶层类名，jsqlparser 5.3 下这三种写法都会被判成 SELECT 而放行
     * （以下形态均已用该版本实测）：
     * <ul>
     *   <li>{@code WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x} —— 顶层解析成
     *       {@code PlainSelect}，CTE 体是 {@code ParenthesedDelete}，PostgreSQL 会真的删数据。
     *       5.3 的 {@code WithItem} 是泛型的（{@code K extends ParenthesedStatement}），
     *       所以这里比对 {@code getParenthesedStatement()} 的实际类型；<b>不能</b>调
     *       {@code WithItem.getSelect()} —— 体是 DML 时它抛的是 {@code ClassCastException}，
     *       会把一条本该被拒绝的语句变成 500。</li>
     *   <li>{@code SELECT * INTO newtbl FROM users} —— {@code getIntoTables()} 返回 {@code [newtbl]}，
     *       SQL Server / PostgreSQL 上会建表写数据。</li>
     *   <li>{@code SELECT ... FOR UPDATE} —— {@code getForMode()} 返回 {@code UPDATE}。不改数据，
     *       但会在标着 {@code readOnlyHint=true} 的工具上持有行锁并阻塞业务写入，属于只读承诺之外的副作用。
     *       {@code ForMode} 挂在 {@code Select} 基类上而不是 {@code PlainSelect}。</li>
     * </ul>
     *
     * <p>递归下去而不只看顶层：集合运算的每个分支、括号子查询、以及每层自己的 CTE 列表都要过。
     */
    private void requireReadOnlySelect(String sql, Select select) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (item == null) continue;
                if (!(item.getParenthesedStatement() instanceof ParenthesedSelect body)) {
                    throw new McpSqlValidationException(sql,
                            "Data-modifying CTE is not allowed: " + item.getAliasName());
                }
                requireReadOnlySelect(sql, body);
            }
        }

        if (select.getForMode() != null || select.getForUpdateTable() != null) {
            throw new McpSqlValidationException(sql, "Row-locking clauses are not allowed on a read path");
        }
        if (select instanceof ParenthesedSelect parenthesed) {
            requireReadOnlySelect(sql, parenthesed.getSelect());
            return;
        }
        if (select instanceof SetOperationList operations && operations.getSelects() != null) {
            for (Select branch : operations.getSelects()) requireReadOnlySelect(sql, branch);
            return;
        }
        if (select instanceof PlainSelect plain) {
            boolean intoTables = plain.getIntoTables() != null && !plain.getIntoTables().isEmpty();
            if (intoTables || plain.getIntoTempTable() != null) {
                throw new McpSqlValidationException(sql, "SELECT ... INTO writes data and is not allowed");
            }
            for (Select nested : nestedSelects(plain)) requireReadOnlySelect(sql, nested);
        }
    }

    /**
     * 对象名提取委托给 {@link SqlTables}——表白名单与表级授权必须对"这条 SQL 碰了哪些表"
     * 给出同一个答案，见那个类的说明。
     */
    private Set<String> extractTables(String sql, Statement stmt) {
        return SqlTables.of(stmt, sql);
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
            // JSQLParser 5.x 起 FETCH 的行数一律进 getExpression()，getRowCount() 已废弃
            Long value = longValueOf(fetch.getExpression());
            if (value != null) return value;
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
