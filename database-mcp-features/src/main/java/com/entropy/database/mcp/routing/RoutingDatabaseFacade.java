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
package com.entropy.database.mcp.routing;

import com.entropy.database.mcp.backup.DatabaseBackupService;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.facade.DatabaseOperations;
import com.entropy.database.mcp.facade.MetaDataCallback;
import com.entropy.database.mcp.facade.TransactionalWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.entropy.database.mcp.authz.ConnectionAuthorizer;

/**
 * Routing facade that delegates to BYOK datasources only.
 * All connections are equal; there is no default connection.
 */
@Service
public class RoutingDatabaseFacade implements DatabaseOperations {

    private static final Logger log = LoggerFactory.getLogger(RoutingDatabaseFacade.class);

    private final DynamicDataSourceManager dynamicDataSourceManager;
    private final DatabaseBackupService backupService;

    /**
     * 按调用者的连接级授权。{@code null} 表示这次装配没有授权判定——见那个包级构造器。
     *
     * <p>它与 {@link #rejectIfReadonly} 是两道不同的闸：{@code readonly} 是连接<em>自身</em>的属性
     * （声明为只读的连接谁都写不了），这里判的是"就算连接可写，也得看是谁"。
     */
    private final ConnectionAuthorizer authorizer;

    /**
     * Per-connection facades, keyed by the resolved connection key.
     *
     * <p>A facade is a thin, immutable wrapper around a context, but it used to be re-allocated on
     * every single delegated call. Entries are replaced as soon as {@code acquire} hands back a
     * different context object, so a rebuilt pool is never served by a facade pointing at the old
     * one. Pool lifetime is unaffected: pools are closed by the manager's lease-eviction listener,
     * not by reachability from here.
     *
     * <p>条目的<em>移除</em>只发生在管理器的驱逐回调里（见 {@link #releaseFacade}）。这条路径必须存在：BYOK 的
     * 连接名是调用方通过 {@code createNamedConnection} 任意指定的，只增不删的话，历史上用过的每一个名字都会永久
     * 留下一个 entry，而每个 entry 经 {@code ByokDataSourceContext → ByokInfrastructure} 强引用一整套
     * {@code DatabaseCache}（queryCache + metadataCache + BloomFilter），还钉住一个已经 close 的
     * {@code HikariDataSource}；连接被驱逐之后没人再访问这个名字，Caffeine 那套 {@code expireAfterAccess} 也不会
     * 来清。所以这里的上界是「当前活着的连接数」，而不是「历史用过的连接名数」。
     */
    private final ConcurrentHashMap<String, CachedFacade> facades = new ConcurrentHashMap<>();

    /**
     * 一个缓存好的 facade 连同它包着的上下文。
     *
     * <p>上下文单独存一份，是为了在驱逐时能按对象身份数出「还有没有别的名字指向同一个物理连接」——别名与规范名
     * 共享同一个 {@link ByokDataSourceContext}，也就是共享同一份 {@code DatabaseCache}。只靠
     * {@code ByokDatabaseFacade} 自己问不出这件事：它只提供 {@code wraps(context)}，需要先有 context 才能比。
     */
    private record CachedFacade(ByokDataSourceContext context, ByokDatabaseFacade facade) {
    }

    /**
     * @param backupService injected lazily because it resolves connections through the same
     *                      {@link DynamicDataSourceManager} this facade uses; the proxy keeps the
     *                      two service beans from constraining each other's initialisation order.
     */
    /**
     * @param backupService injected lazily because it resolves connections through the same
     *                      {@link DynamicDataSourceManager} this facade uses; the proxy keeps the
     *                      two service beans from constraining each other's initialisation order.
     * @param authorizer 按调用者的连接级授权
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RoutingDatabaseFacade(DynamicDataSourceManager dynamicDataSourceManager,
                                 @Lazy DatabaseBackupService backupService,
                                 ConnectionAuthorizer authorizer) {
        this.dynamicDataSourceManager = dynamicDataSourceManager;
        this.backupService = backupService;
        this.authorizer = authorizer;
        // 在构造器里订阅而不是 @PostConstruct：这个类也会被直接 new 出来用（测试、以及任何不经过容器的装配），
        // @PostConstruct 在那些路径上根本不会被调用，缓存就又退化成只增不删。
        // 传出去的是只捕获 facades 这一个 map 的 lambda，而不是 this::releaseFacade——回调可能在构造器还没走完
        // 时就被另一个线程触发，把 this 泄漏给外部代码会让它看到一个半成品对象。facades 是带初始化器的字段，
        // 执行到这一行时已经赋值完成。
        ConcurrentHashMap<String, CachedFacade> registry = this.facades;
        dynamicDataSourceManager.addEvictionListener(key -> releaseFacade(registry, key));
    }

    /**
     * 不带授权判定的装配。
     *
     * <p><b>包级可见，刻意不对外开放。</b>少传一个参数就等于关掉了按调用者的授权，而"装配方式决定
     * 安全行为"的重载恰恰是最容易被无意选中的那种。容器永远走上面那个构造器
     * （{@link ConnectionAuthorizer} 由 {@code DatabaseConfig} 声明成 {@code @Bean}），这个只给同包的测试用。
     */
    RoutingDatabaseFacade(DynamicDataSourceManager dynamicDataSourceManager,
                          @Lazy DatabaseBackupService backupService) {
        this(dynamicDataSourceManager, backupService, null);
    }

    /**
     * 连接名被驱逐时丢掉它的 facade。
     *
     * <p>static：理由见构造器。会被 Caffeine 的通知线程调用，所以只做 map 操作，不抛异常。
     *
     * <p>顺手清掉这个连接的查询/元数据缓存，但只在没有别的名字还指向同一个上下文时才清：别名与规范名共享同一份
     * {@code DatabaseCache}，别名过期时无条件 invalidateAll 会把规范名正在用的缓存一起冷掉。判据与管理器侧的
     * {@code closeIfUnreferenced} 一致——按对象身份数引用，而不是另开一个计数器。
     */
    private static void releaseFacade(ConcurrentHashMap<String, CachedFacade> facades, String key) {
        CachedFacade removed = facades.remove(key);
        if (removed == null) {
            return;
        }
        boolean stillShared = facades.values().stream()
                .anyMatch(other -> other.context() == removed.context());
        if (!stillShared) {
            // 池已经没人引用了（管理器那边同时在关它），这份缓存连同 BloomFilter 一起清掉，不必等 GC
            removed.facade().clearCache(key);
        }
        log.debug("Released cached facade for connection '{}' (cache cleared: {})", key, !stillShared);
    }

    // ─── Helper ────────────────────────────────────────────────────────────

    private ByokDataSourceContext resolveContext(String connection) {
        if (connection == null || connection.isBlank()) {
            Collection<String> keys = dynamicDataSourceManager.listConnectionKeys();
            if (keys.size() == 1) {
                connection = keys.iterator().next();
            } else if (keys.isEmpty()) {
                throw new IllegalArgumentException(buildConnectionRequiredMessage());
            } else {
                throw new IllegalArgumentException(buildConnectionRequiredMessage());
            }
        }
        try {
            return dynamicDataSourceManager.acquire(connection);
        } catch (IllegalArgumentException e) {
            // Preserve the original error message and append available connections
            String originalMsg = e.getMessage();
            Collection<String> registered = dynamicDataSourceManager.listConnectionKeys();
            String tip;
            if (registered.isEmpty()) {
                tip = "No connections registered. Call createNamedConnection first.";
            } else {
                String connectionList = registered.stream()
                        .map(name -> "  - " + name)
                        .collect(Collectors.joining("\n"));
                tip = String.format("\nAvailable connections:\n%s\nUse one of these names.", connectionList);
            }
            throw new IllegalArgumentException(originalMsg + tip, e);
        }
    }

    private String buildConnectionRequiredMessage() {
        Collection<String> registered = dynamicDataSourceManager.listConnectionKeys();
        if (registered.isEmpty()) {
            return """
                    Connection is required but not provided.
                    No connections are registered yet.
                    To get started:
                      1. Call createNamedConnection with: name, jdbcUrl, username, password, dialect
                      2. Then pass the connection name to this tool.
                    For help, call prompt("database-quick-start").""";
        }
        // Format connections as a clear list for the LLM
        String connectionList = registered.stream()
                .map(name -> "  - " + name)
                .collect(Collectors.joining("\n"));
        return """
                Connection is required but not provided.
                Available connections:
                %s
                You MUST pass one of these connection names to the tool.
                Example: pass connection="fcs_analyst_v2" to use the connection above.
                """.formatted(connectionList);
    }

    private String buildConnectionNotFoundMessage(String connection) {
        Collection<String> registered = dynamicDataSourceManager.listConnectionKeys();
        String tip;
        if (registered.isEmpty()) {
            tip = "No connections registered. Call createNamedConnection first.";
        } else {
            String connectionList = registered.stream()
                    .map(name -> "  - " + name)
                    .collect(Collectors.joining("\n"));
            tip = String.format("Available connections:\n%s\nUse one of these names instead.", connectionList);
        }
        return "Connection not found: " + connection + ". " + tip;
    }

    private ByokDatabaseFacade resolveFacade(String connection) {
        return facadeFor(resolveContext(connection));
    }

    /**
     * 读取路径的解析：解析出连接，再按这条 SQL 碰到的表决定放不放行。
     *
     * <p>只有<em>带 SQL</em> 的读会走这里。元数据类的读（{@link #listTables} 等）仍走
     * {@link #resolveFacade}，不判授权——它们暴露的是结构而不是数据，见 {@code ConnectionAuthorizer}
     * 关于覆盖范围的说明。
     */
    private ByokDatabaseFacade resolveReadFacade(String connection, String sql) {
        ByokDataSourceContext context = resolveContext(connection);
        if (authorizer != null) {
            authorizer.requireSqlRead(context.getKey(), sql);
        }
        return facadeFor(context);
    }

    /**
     * 写入路径的解析：先解析出真正会被写的那条连接，再决定放不放行。
     *
     * <p>{@code McpToolExceptionAspect} 也有一道 readonly 闸，但它判的是调用方<em>传进来的</em>连接名，而
     * {@code connection} 在多数 MCP 工具上是 {@code required = false}；省掉这个参数，切面拿到 null 就直接放行，
     * 而 {@link #resolveContext} 在只注册了一条连接时会把它补上——于是"省掉参数"曾经等于绕过只读保护写库，
     * 且单连接恰好是最常见的部署形态。闸门放在解析之后，就不存在"没传参数所以不知道该拦谁"这种输入形状。
     *
     * <p>另外这是普通方法调用而不是 advice，所以 {@link #copyRows} 经由 {@link #batchInsert} 的自调用同样被拦——
     * Spring AOP 的自调用绕过代理，把闸门做成切面会在这条路径上失效。
     *
     * <p>调用方给的名字与规范名任意一侧只读即按只读处理：别名与规范名各有一条登记，{@code readonly} 取自各自
     * 注册时的入参，两者可以不一致，而它们共享同一个物理池——不能让其中一个名字变成写入后门。
     *
     * @param check 按调用者的判定。三种粒度（只有连接名、带 SQL、带表名）只在这一个参数上不同，
     *              其余步骤必须逐字相同，所以不拆成三个方法各写一遍
     */
    private ByokDatabaseFacade resolveWriteFacade(
            String connection, BiConsumer<ConnectionAuthorizer, String> check) {
        ByokDataSourceContext context = resolveContext(connection);
        rejectIfReadonly(connection, context.getKey());
        // 按调用者判定同样放在解析之后，理由和上面那道闸完全一样：连接名解析完才不存在
        // "没传参数所以不知道该拦谁"这种输入形状。用规范名而不是调用方传进来的别名——
        // 策略针对的是物理连接，别名不该成为另一条授权路径
        if (authorizer != null) {
            check.accept(authorizer, context.getKey());
        }
        return facadeFor(context);
    }

    /** 拿不到对象名的写入（如 {@link #inTransaction}）：只能判到连接这一级。 */
    private ByokDatabaseFacade resolveWriteFacade(String connection) {
        return resolveWriteFacade(connection, (authz, key) -> authz.requireWrite(key));
    }

    private ByokDatabaseFacade resolveWriteFacadeForSql(String connection, String sql) {
        return resolveWriteFacade(connection, (authz, key) -> authz.requireSqlWrite(key, sql));
    }

    private ByokDatabaseFacade resolveWriteFacadeForTable(String connection, String table) {
        return resolveWriteFacade(connection, (authz, key) -> authz.requireTableWrite(key, table));
    }

    private void rejectIfReadonly(String requestedName, String resolvedKey) {
        String readonlyName = null;
        if (dynamicDataSourceManager.isReadonly(resolvedKey)) {
            readonlyName = resolvedKey;
        } else if (requestedName != null && !requestedName.isBlank()
                && dynamicDataSourceManager.isReadonly(requestedName)) {
            readonlyName = requestedName;
        }
        if (readonlyName == null) {
            return;
        }
        throw new McpToolException(
                ErrorCode.CONNECTION_READONLY,
                "Connection '" + readonlyName + "' is registered as read-only, so write operations "
                        + "are rejected. Use a read-only tool, or a connection registered without "
                        + "readonly=true.",
                readonlyName);
    }

    private ByokDatabaseFacade facadeFor(ByokDataSourceContext context) {
        return facades.compute(context.getKey(), (key, cached) ->
                        cached != null && cached.facade().wraps(context)
                                ? cached
                                : new CachedFacade(context, new ByokDatabaseFacade(context)))
                .facade();
    }

    // ─── Read Operations ───────────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> listTables(String schema, String connection) {
        return resolveFacade(connection).listTables(schema, connection);
    }

    @Override
    public List<Map<String, Object>> searchTables(String keyword, String connection) {
        return resolveFacade(connection).searchTables(keyword, connection);
    }

    @Override
    public List<String> listSchemas(String connection) {
        return resolveFacade(connection).listSchemas(connection);
    }

    @Override
    public Map<String, Object> describeTable(String table, String schema, String connection) {
        return resolveFacade(connection).describeTable(table, schema, connection);
    }

    @Override
    public List<Map<String, Object>> listIndexes(String table, String schema, String connection) {
        return resolveFacade(connection).listIndexes(table, schema, connection);
    }

    @Override
    public List<Map<String, Object>> listViews(String schema, String connection) {
        return resolveFacade(connection).listViews(schema, connection);
    }

    @Override
    public List<Map<String, Object>> listSequences(String schema, String connection) {
        return resolveFacade(connection).listSequences(schema, connection);
    }

    @Override
    public PaginatedQueryResult executeQuery(
            String sql, int maxRows, String continuationToken, String connection) {
        return resolveReadFacade(connection, sql).executeQuery(sql, maxRows, continuationToken, connection);
    }

    @Override
    public List<Map<String, Object>> executeNamedQuery(
            String sql, Map<String, Object> params, String connection) {
        return resolveReadFacade(connection, sql).executeNamedQuery(sql, params, connection);
    }

    @Override
    public List<Map<String, Object>> queryRows(String sql, String connection, Object... args) {
        return resolveReadFacade(connection, sql).queryRows(sql, connection, args);
    }

    @Override
    public Map<String, Object> getDatabaseInfo(String connection) {
        return resolveFacade(connection).getDatabaseInfo(connection);
    }

    // ─── Execution Plan ────────────────────────────────────────────────────

    @Override
    public com.entropy.database.mcp.domain.PlanAnalysis explainPlan(String sql, String connection) {
        return resolveReadFacade(connection, sql).explainPlan(sql, connection);
    }

    @Override
    public List<Map<String, Object>> explainPlanRows(String sql, String connection) {
        return resolveReadFacade(connection, sql).explainPlanRows(sql, connection);
    }

    // ─── Write Operations ──────────────────────────────────────────────────

    @Override
    public Map<String, Object> executeDdl(String sql, String connection) {
        return resolveWriteFacadeForSql(connection, sql).executeDdl(sql, connection);
    }

    @Override
    public int executeUpdate(String sql, String connection, Object... args) {
        return resolveWriteFacadeForSql(connection, sql).executeUpdate(sql, connection, args);
    }

    @Override
    public long batchInsert(String table, List<String> columns, List<List<Object>> rows,
                            int batchSize, String connection) {
        return resolveWriteFacadeForTable(connection, table)
                .batchInsert(table, columns, rows, batchSize, connection);
    }

    @Override
    public long batchUpsert(String table, List<String> keyColumns, List<String> columns,
                            List<List<Object>> rows, int batchSize, String connection) {
        return resolveWriteFacadeForTable(connection, table)
                .batchUpsert(table, keyColumns, columns, rows, batchSize, connection);
    }

    @Override
    public <T> T inTransaction(String connection, TransactionalWork<T> work) {
        return resolveWriteFacade(connection).inTransaction(connection, work);
    }

    // ─── Backup Operations ─────────────────────────────────────────────────

    @Override
    public Map<String, Object> backupSchema(String tableName, String connection) {
        return backupService.backupSchema(tableName, connection);
    }

    @Override
    public Map<String, Object> backupData(String tableName, int maxRows, String connection) {
        return backupService.backupData(tableName, maxRows, connection);
    }

    @Override
    public Map<String, Object> diffSchema(String sourceTable, String targetTable, String connection) {
        return backupService.diffSchema(sourceTable, targetTable, connection);
    }

    // ─── Cross-connection Operations ───────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>The source result is read in full into memory before the first row is written: the read
     * side goes through {@link #queryRows}, which applies no pagination. {@code batchSize} therefore
     * only controls the write side. Callers moving large tables must bound {@code sourceSql}
     * themselves.
     */
    @Override
    public long copyRows(String sourceSql, String sourceConnection,
                         String targetTable, List<String> targetColumns,
                         int batchSize, String targetConnection) {
        List<Map<String, Object>> sourceRows = queryRows(sourceSql, sourceConnection);
        if (sourceRows.isEmpty()) {
            return 0L;
        }
        List<String> columns = targetColumns != null && !targetColumns.isEmpty()
                ? targetColumns
                : List.copyOf(sourceRows.get(0).keySet());
        List<List<Object>> rows = new ArrayList<>(sourceRows.size());
        for (Map<String, Object> sourceRow : sourceRows) {
            List<Object> values = new ArrayList<>(columns.size());
            for (String column : columns) {
                values.add(sourceRow.get(column));
            }
            rows.add(values);
        }
        return batchInsert(targetTable, columns, rows, batchSize, targetConnection);
    }

    @Override
    public void clearCache(String connection) {
        resolveFacade(connection).clearCache(connection);
    }

    // ─── Statistics ────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> getStatistics(String connection) {
        return resolveFacade(connection).getStatistics(connection);
    }

    @Override
    public DatabaseDialect getDialect(String connection) {
        return resolveFacade(connection).getDialect(connection);
    }

    @Override
    public <T> T withMetaData(String connection, MetaDataCallback<T> callback) {
        return resolveFacade(connection).withMetaData(connection, callback);
    }
}
