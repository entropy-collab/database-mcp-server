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
package com.entropy.database.mcp.repository;

import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.repository.QueryLimits;
import com.entropy.database.mcp.security.DataMaskingService;
import com.entropy.database.mcp.security.SqlValidator;
import com.entropy.database.mcp.session.McpToolContext;
import com.entropy.database.mcp.util.JdbcUrlMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Database read operations repository.
 * Handles all SELECT queries with proper pagination, caching, and dialect-driven SQL.
 */
public class DatabaseReadRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseReadRepository.class);
    public static final int DEFAULT_MAX_ROWS = 100;
    public static final int DEFAULT_MAX_RESULT_ROWS = 10000;
    public static final int DEFAULT_FETCH_SIZE = 100;
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    /** 方言没有 schema 概念（SQLite）时，结果里对「搜的是哪个 schema」的诚实回答。 */
    static final String NO_SCHEMA_CONCEPT = "(dialect has no schema concept)";
    /** 当前 schema 查不出来时的占位串，明确写成「查不到」而不是编一个看起来合法的名字。 */
    static final String UNKNOWN_SCHEMA = "(unresolved current schema)";

    /** 表名模糊回退的编辑距离阈值。 */
    static final int FUZZY_MAX_DISTANCE = 3;
    /** 短表名收紧阈值：名字越短，距离 3 就能匹配上几乎任何东西。 */
    static final int FUZZY_SHORT_NAME_LENGTH = 10;
    static final int FUZZY_SHORT_NAME_MAX_DISTANCE = 2;
    /** 模糊回退的返回条数上限：结果只是「你可能想找这几张」，多了反而没法判断。 */
    static final int FUZZY_MAX_RESULTS = 20;

    // ─── 元数据缓存键前缀 ─────────────────────────────────────────────────
    // 写键与失效匹配共用这几个常量。原来是两处各写一遍字面量（读路径拼 "columns:"、
    // 失效路径再拼一遍），一旦改名就只改一边，症状是「缓存照样命中旧结构、失效一条都没清掉」，
    // 且不会报错。
    /** 单表列元数据：{@code columns:<搜过的 schema>.<调用方给的表名>}。 */
    static final String KEY_COLUMNS = "columns:";
    /** 单表索引：{@code indexes:<入参 schema>.<调用方给的表名>}。 */
    static final String KEY_INDEXES = "indexes:";
    /** Schema 级清单，随建表/删表变化。 */
    static final String KEY_TABLES = "tables:";
    static final String KEY_TABLES_ALL = "tables_all:";
    static final String KEY_VIEWS = "views:";
    static final String KEY_SEQUENCES = "sequences:";

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;
    private final SqlValidator sqlValidator;
    private final DatabaseCache cache;
    private final DataMaskingService maskingService;
    private final int maxRows;
    private final int maxResultRows;
    private final int fetchSize;
    private final int queryTimeoutSeconds;

    public DatabaseReadRepository(JdbcTemplate jdbcTemplate,
                                  DatabaseDialect dialect,
                                  SqlValidator sqlValidator,
                                  DatabaseCache cache,
                                  DataMaskingService maskingService,
                                  QueryLimits limits) {
        this(jdbcTemplate, dialect, sqlValidator, cache, maskingService,
             limits.maxRows(), limits.maxResultRows(), DEFAULT_FETCH_SIZE,
             DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    public DatabaseReadRepository(JdbcTemplate jdbcTemplate,
                                  DatabaseDialect dialect,
                                  SqlValidator sqlValidator,
                                  DatabaseCache cache,
                                  DataMaskingService maskingService,
                                  int maxRows,
                                  int maxResultRows,
                                  int fetchSize,
                                  int queryTimeoutSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        this.sqlValidator = sqlValidator;
        this.cache = cache;
        this.maskingService = maskingService;
        this.maxRows = maxRows;
        this.maxResultRows = maxResultRows;
        this.fetchSize = fetchSize;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    // ─── Metadata Queries (cached via metadataCache) ──────────────────────

    /**
     * 丢掉与这几张表相关的元数据缓存，外加 schema 级清单。DDL 执行成功后调用。
     *
     * <p>为什么住在读仓储里：键是在这里拼出来的（{@link #KEY_COLUMNS} 等），失效匹配必须跟拼键
     * 用同一份知识。放到门面或工具层去猜键的形状，是让同一件事有两份定义。
     *
     * <p><b>匹配按「最后一段表名」且忽略大小写。</b>列元数据的键带的是<em>实际搜过的</em> schema
     * （{@code columns:QDITP.T}），而 DDL 语句通常不写 schema，所以 schema 那一段无法复原、只能
     * 跳过；表名那一段则是调用方当初传进来的原样字符串（{@code describeTable("nums")} 留下的是
     * {@code columns:PUBLIC.nums}），而 {@code SqlTables} 给的是大写，所以必须忽略大小写比。
     *
     * <p><b>schema 级清单一律清掉</b>，不去分辨这条 DDL 是不是 CREATE / DROP TABLE：{@code tables:}
     * 里含行数估算、{@code views:} / {@code sequences:} 也可能被同一批 DDL 改到，而重取它们只是
     * 一条字典视图查询。为了省这一条查询去做语句类型判定，判错的代价是「结构已变但清单还是旧的」，
     * 不值当。
     *
     * <p><b>查询结果缓存不在此列。</b>它的键是 {@code sha256(schema + "." + sql)}，无法按表匹配，
     * 要清只能整个清掉。这里不碰它——它的 TTL 是 30 秒级，而元数据是分钟级，前者的陈旧窗口本来
     * 就被 TTL 兜住了。需要立刻一起清的场景请显式调 {@code clearCache}。
     *
     * @param tables 受影响的表名，{@code SqlTables} 口径（已大写，可能带 schema 前缀）
     * @return 被清掉的条目数
     */
    public int evictMetadataForTables(Set<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return 0;
        }
        Set<String> bareNames = new java.util.HashSet<>();
        for (String name : tables) {
            String bare = bareTableName(name);
            if (!bare.isEmpty()) {
                bareNames.add(bare.toUpperCase(java.util.Locale.ROOT));
            }
        }
        if (bareNames.isEmpty()) {
            return 0;
        }
        int removed = cache.evictMetadataWhere(key -> isSchemaListingKey(key)
                || mentionsTable(key, bareNames));
        log.debug("Metadata evicted after DDL on tables {}: {} entries", bareNames, removed);
        return removed;
    }

    /** Schema 级清单键——建表/删表/建视图都会让它过期。 */
    private static boolean isSchemaListingKey(String key) {
        return key.startsWith(KEY_TABLES)
                || key.startsWith(KEY_TABLES_ALL)
                || key.startsWith(KEY_VIEWS)
                || key.startsWith(KEY_SEQUENCES);
    }

    /** 单表键（{@code columns:S.T} / {@code indexes:S.T}）的表名是否在集合里。 */
    private static boolean mentionsTable(String key, Set<String> bareNamesUpper) {
        if (!key.startsWith(KEY_COLUMNS) && !key.startsWith(KEY_INDEXES)) {
            return false;
        }
        return bareNamesUpper.contains(bareTableName(key).toUpperCase(java.util.Locale.ROOT));
    }

    /** 最后一个点之后的部分：{@code columns:QDITP.T} → {@code T}，{@code APP.USERS} → {@code USERS}。 */
    private static String bareTableName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTables(String schema) {
        String cacheKey = KEY_TABLES + schema;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        // tablesQuery resolves the schema itself and declares no placeholder (see the bind-parameter
        // contract on DatabaseDialect), so there is nothing to bind here.
        String sql = dialect.tablesQuery(schema);
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
        cache.putMetadata(cacheKey, result);
        return result;
    }

    /**
     * List tables across all schemas, optionally filtered by keyword.
     *
     * <p>子串匹配（{@code LIKE %keyword%}）一条都没命中时回退到编辑距离匹配。调用方多为模型，
     * 表名拼错一个字母就得到空数组，而空数组读起来跟「这个库真没有这张表」无法区分——回退把
     * 「拼错了」和「真没有」分开：拿到 {@code matchType=fuzzy} 就是前者。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchTables(String keyword) {
        String cacheKey = KEY_TABLES_ALL + keyword;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        String sql = dialect.searchTablesQuery(keyword);
        List<Map<String, Object>> result;
        if (keyword != null && !keyword.isBlank()) {
            result = jdbcTemplate.queryForList(sql, "%" + keyword + "%");
            if (result.isEmpty()) {
                result = fuzzySearchTables(keyword);
            }
        } else {
            result = jdbcTemplate.queryForList(sql);
        }
        cache.putMetadata(cacheKey, result);
        return result;
    }

    /**
     * 编辑距离回退：拉全量表名，按最小编辑距离挑出最像 {@code keyword} 的那几张。
     *
     * <p>全量表清单走 {@link #searchTables(String)} 的 null 分支，所以它自带缓存，连续几次拼错
     * 只查一次数据库。回退本身失败（权限、超时）时返回空列表——它是一次「顺手帮忙」，不该让
     * 原本只是没命中的搜索变成报错。
     */
    private List<Map<String, Object>> fuzzySearchTables(String keyword) {
        List<Map<String, Object>> allTables;
        try {
            allTables = searchTables(null);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Fuzzy fallback for table search [{}] could not list all tables; "
                    + "returning the empty exact-match result", keyword, e);
            return List.of();
        }
        String needle = keyword.toUpperCase();
        int threshold = needle.length() <= FUZZY_SHORT_NAME_LENGTH
                ? FUZZY_SHORT_NAME_MAX_DISTANCE
                : FUZZY_MAX_DISTANCE;

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> row : allTables) {
            String tableName = stringValue(row, "table_name");
            if (tableName == null) {
                continue;
            }
            int distance = minDistanceToNameOrSegment(needle, tableName.toUpperCase(), threshold);
            if (distance > threshold) {
                continue;
            }
            // 复制而不是改原 row：allTables 来自缓存，是并发共享的。
            Map<String, Object> match = new LinkedHashMap<>(row);
            match.put("matchType", "fuzzy");
            match.put("editDistance", distance);
            matches.add(match);
        }
        matches.sort(java.util.Comparator.comparingInt(m -> (Integer) m.get("editDistance")));
        return matches.size() > FUZZY_MAX_RESULTS
                ? List.copyOf(matches.subList(0, FUZZY_MAX_RESULTS))
                : List.copyOf(matches);
    }

    /**
     * {@code needle} 到整表名、以及到表名每个下划线分段的最小编辑距离。
     *
     * <p>只比整表名的话，用 {@code TXN} 找 {@code TBL_STL_TXN_DTL} 的距离是 12，任何合理阈值都排除它；
     * 只比分段的话，{@code TBL_STL_TXN_DTLX} 这种整体轻微拼错又会漏掉。两者取小覆盖这两类输入。
     */
    private static int minDistanceToNameOrSegment(String needle, String tableName, int threshold) {
        int best = boundedLevenshtein(needle, tableName, threshold);
        if (best == 0) {
            return 0;
        }
        for (String segment : tableName.split("_")) {
            if (segment.isEmpty()) {
                continue;
            }
            best = Math.min(best, boundedLevenshtein(needle, segment, threshold));
            if (best == 0) {
                return 0;
            }
        }
        return best;
    }

    /**
     * Levenshtein 距离，超过 {@code max} 就不再算下去，直接返回 {@code max + 1}。
     *
     * <p>回退要对全库表名逐个比一遍，而绝大多数表名跟关键词毫不相干。长度差就已经超阈值的那些
     * 一次乘法即可排除，剩下的靠每行最小值提前退出，避免为一次「顺手帮忙」跑满 O(n·m)。
     */
    private static int boundedLevenshtein(String a, String b, int max) {
        int overflow = max + 1;
        if (Math.abs(a.length() - b.length()) > max) {
            return overflow;
        }
        if (a.equals(b)) {
            return 0;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > max) {
                return overflow;
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        int distance = prev[b.length()];
        return distance > max ? overflow : distance;
    }

    /**
     * 大小写不敏感地取一列的字符串值。元数据查询的列名大小写由驱动决定（Oracle 给大写、
     * PostgreSQL 给小写），所以不能按固定写法取键。
     */
    private static String stringValue(Map<String, Object> row, String column) {
        Object direct = row.get(column);
        if (direct != null) {
            return direct.toString();
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (column.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                return entry.getValue().toString();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<String> listSchemas() {
        String cacheKey = "schemas";
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return (List<String>) cached;
        }
        String sql = dialect.schemasQuery();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        List<String> result = rows.stream()
                .map(row -> row.values().stream().findFirst().orElse(null))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
        cache.putMetadata(cacheKey, result);
        return result;
    }

    /**
     * 一张表的列元数据；表在本次搜索的 schema 下不存在时返回一条可诊断的 error 结果，而不是 null。
     *
     * <p>原来这里是 {@code Map.of("table", table, "schema", schema, ...)}。{@code Map.of} 不接受 null
     * 值，所以调用方省略 schema 时——SQL 已经跑完、列也查回来了——建结果那一步抛 NPE，而且是一个
     * {@code getMessage()} 为 null 的 NPE：MCP 层把它渲染成字面量 {@code null null}，
     * {@code PerformanceTimingAspect} 把审计记成 {@code success:false} 且错误文本为空，日志里
     * WARN/ERROR 一条都没有。线上实测：{@code describeTable(table=..., connection="qditp")} 恒
     * 失败 43ms，补上 {@code schema="QDITP"} 就正常返回 52 列。
     *
     * <p>两处修法各自独立：结果 map 改成允许 null 的 {@link LinkedHashMap}（并回填真实搜过的
     * schema），空结果集改成走 {@link #tableNotFound}。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> describeTable(String table, String schema) {
        // 缓存键用「真实搜过的 schema」而不是入参：省略与显式传同一个 schema 是同一次查询，
        // 用入参会留下 "columns:null.T" 与 "columns:QDITP.T" 两份内容相同的条目。
        String searchedSchema = resolveSearchedSchema(schema);
        String cacheKey = KEY_COLUMNS + searchedSchema + "." + table;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "Map<String, Object>");
        }
        // One placeholder, the dialect-normalized table name; the schema is resolved inside the
        // dialect. It used to be bound as a second argument, which broke on every dialect that
        // resolves the schema itself.
        String sql = dialect.columnsQuery(table, schema);
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(sql, normalizeTableName(table));
        if (columns.isEmpty()) {
            // 否定结果不进缓存：建表 / 授权之后立刻重试应当能看到变化。
            return tableNotFound(table, searchedSchema, schema);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("table", table);
        result.put("schema", searchedSchema);
        result.put("columnCount", columns.size());
        result.put("columns", columns);
        // 不可变：这个 map 会被缓存并发共享，而 McpToolExceptionAspect 会对返回值试着
        // putIfAbsent("connection", ...)。Map.of 时代那次写入本来就被拒并降级成 debug 日志，
        // 这里保持同样的行为，而不是让它去改一个共享的缓存对象。
        Map<String, Object> immutable = java.util.Collections.unmodifiableMap(result);
        cache.putMetadata(cacheKey, immutable);
        return immutable;
    }

    /**
     * 「表没找到」的返回形状，跟随仓库里已有的约定：
     * {@code DatabaseBackupServiceImpl} 的 {@code Map.of("error", "Table not found: " + tableName)}，
     * 以及它那条更细的增量备份分支（error + reason + hint）。
     *
     * <p>刻意不带 {@code columns} / {@code columnCount}：带上 {@code columnCount=0} 与空数组，
     * 调用方读到的就是一个「成功但这张表没有列」的结果，与真正的失败无法区分——这正是本次要修掉的
     * 那种沉默。{@code schemaSource} 说明这个 schema 是调用方给的还是方言兜的，
     * 命中默认值的调用方据此才能判断自己是不是问错了地方。
     */
    private Map<String, Object> tableNotFound(String table, String searchedSchema, String requestedSchema) {
        boolean defaulted = dialect.resolveSchema(requestedSchema) == null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", "Table not found: " + table + " (schema searched: " + searchedSchema + ")");
        result.put("table", table);
        result.put("schema", searchedSchema);
        result.put("schemaSource", defaulted ? "dialect-default" : "caller");
        result.put("hint", defaulted
                ? "未传 schema，已按 " + dialect.getDialectName() + " 方言的当前 schema（"
                        + searchedSchema + "）搜索。表在别的 schema 下就要显式传 schema；"
                        + "不确定时先用 listSchemas / searchTables 定位。"
                : "表名与 schema 都按 " + dialect.getDialectName()
                        + " 方言归一化后仍未命中。可能是表不存在、名字拼错，"
                        + "或当前账号对该表的元数据视图没有查询权限。");
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * 本次元数据查询真正搜的那个 schema 名。
     *
     * <p>方言早就会把省略的 schema 兜成会话当前 schema（{@code owner = USER}、
     * {@code table_schema = DATABASE()} …），但那是一个只有数据库看得懂的 SQL 表达式，调用方看不到
     * 它解析成了什么。这里把它问出来，好让结果与报错里能写上「实际搜的是 QDITP」。
     */
    private String resolveSearchedSchema(String requested) {
        String resolved = dialect.resolveSchema(requested);
        return resolved != null ? resolved : currentSchema();
    }

    /**
     * 会话当前 schema 名。解析成功后就一直留着，不再问第二次。
     *
     * <p>原来它是元数据缓存里的一条（键 {@code current_schema}），于是跟着 {@code metadata-ttl}
     * 一起过期——默认 5 分钟。但它是<b>连接级常量</b>：{@code SELECT USER FROM DUAL} /
     * {@code current_schema()} 在一条连接的整个生命周期里不会变，连接没了这个字段也跟着没了。
     * 放在缓存里的代价是每 5 分钟白跑一次查询，而且会被 {@code clearCache} 与 DDL 后的粗粒度
     * 失效连带清掉——清掉一个不可能变的值，重取它只是浪费。
     *
     * <p>{@code volatile} 而不是加锁：两个线程同时解析最坏情况是多跑一次只读查询、然后写入同一个值，
     * 没有正确性问题，不值得为此在读路径上放一把锁。
     *
     * <p>{@code null} 表示「还没解析成功过」。失败<b>不</b>记进来，见 {@link #currentSchema()}。
     */
    private volatile String resolvedCurrentSchema;

    /**
     * 会话当前 schema 名，连接内只解析一次。
     *
     * <p>取不到时返回占位串而不是抛错：这个值只用于把结果标注得可读，不该让一次
     * describeTable 因为标注失败而失败。
     *
     * <p><b>失败不记忆化。</b>{@link #UNKNOWN_SCHEMA} 可能来自一次瞬时故障（连接刚断、
     * 权限正在调整），把它固化下来会让这条连接之后所有结果都永久标错 schema，而且没有任何
     * 手段纠正——连 {@code clearCache} 都清不掉它了。所以失败时下次仍然重试。
     *
     * <p>{@link #NO_SCHEMA_CONCEPT} 也不记忆化，但理由相反：它是方言的静态事实
     * （SQLite 没有 schema 概念），一次 {@code null} 判断就能得出，没有必要占一个字段。
     */
    private String currentSchema() {
        String memoised = resolvedCurrentSchema;
        if (memoised != null) {
            return memoised;
        }
        String sql = dialect.currentSchemaQuery();
        if (sql == null) {
            // SQLite：没有 schema 概念，元数据查询里也没有 schema 谓词。
            return NO_SCHEMA_CONCEPT;
        }
        try {
            String name = jdbcTemplate.queryForObject(sql, String.class);
            String resolved = (name == null || name.isBlank()) ? UNKNOWN_SCHEMA : name;
            resolvedCurrentSchema = resolved;
            return resolved;
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Could not resolve the current schema of this {} connection with [{}]; "
                    + "metadata results will report it as {}", dialect.getDialectName(), sql, UNKNOWN_SCHEMA, e);
            return UNKNOWN_SCHEMA;
        }
    }


    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listIndexes(String table, String schema) {
        String cacheKey = KEY_INDEXES + schema + "." + table;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        // One placeholder, the dialect-normalized table name; the schema is resolved inside the dialect.
        String sql = dialect.indexesQuery(table, schema);
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, normalizeTableName(table));
        cache.putMetadata(cacheKey, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listViews(String schema) {
        String cacheKey = KEY_VIEWS + schema;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        String sql = dialect.viewsQuery(schema);
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, schema);
        cache.putMetadata(cacheKey, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSequences(String schema) {
        String cacheKey = KEY_SEQUENCES + schema;
        Object cached = cache.getMetadata(cacheKey);
        if (cached != null) {
            return checkType(cached, cacheKey, "List<Map<String, Object>>");
        }
        String sql = dialect.sequencesQuery(schema);
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, schema);
        cache.putMetadata(cacheKey, result);
        return result;
    }

    // ─── Query Execution (cached via queryCache for first page) ────────────

    @SuppressWarnings("unchecked")
    public PaginatedQueryResult executeQuery(String sql, int maxRows, String continuationToken) {
        return executeQueryDirect(sql, maxRows, continuationToken);
    }

    @SuppressWarnings("unchecked")
    private PaginatedQueryResult executeQueryDirect(String sql, int maxRows, String continuationToken) {
        if (maxRows < 0) {
            throw new com.entropy.database.mcp.exception.McpQueryException(
                com.entropy.database.mcp.exception.ErrorCode.QUERY_EXECUTION_FAILED,
                "maxRows must not be negative: " + maxRows);
        }
        // Inject SQLCommenter trace annotation for database-side query tracking
        String tracedSql = injectSqlComment(sql);

        // Prepare cache key and pagination params. The key must be derived from the ORIGINAL
        // sql: tracedSql carries a per-request trace_id, which would make every key unique
        // and the cache unreachable.
        int limit = Math.min(maxRows, this.maxRows);
        String schema = extractSchema(sql);
        String cacheKey = "query:" + sha256(schema + "." + sql) + ":" + limit;

        // Cache first-page queries only (no continuation token)
        if (continuationToken == null || continuationToken.isBlank()) {
            // Bloom filter pre-check: if definitely not present, skip cache lookup
            boolean possiblyCached = cache.mightContainQuery(schema + "." + sql);
            if (possiblyCached) {
                Object cached = cache.getQuery(cacheKey);
                if (cached != null) {
                    return (PaginatedQueryResult) cached;
                }
            }
        }

        // Execute query with pagination
        int offset = resolveOffset(continuationToken);
        String limitedSql = dialect.supportsLimit()
                ? dialect.applyLimit(tracedSql, limit, offset)
                : tracedSql;

        // Apply query timeout to PreparedStatement
        List<Map<String, Object>> rows = jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(limitedSql);
            ps.setFetchSize(fetchSize);
            if (queryTimeoutSeconds > 0) {
                ps.setQueryTimeout(queryTimeoutSeconds);
            }
            return ps;
        }, (ResultSet rs) -> {
            List<Map<String, Object>> results = new ArrayList<>();
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnLabel(i), rs.getObject(i));
                }
                results.add(row);
            }
            return results;
        });

        // Circuit breaker: reject if result exceeds maxResultRows
        if (rows.size() > maxResultRows) {
            throw new com.entropy.database.mcp.exception.McpQueryException(
                com.entropy.database.mcp.exception.ErrorCode.QUERY_RESULT_TOO_LARGE,
                "Query result exceeds max-result-rows limit: " + rows.size() + " > " + maxResultRows);
        }

        // A limit of 0 returns no rows, so rows.size() == limit must not be read as "there is more":
        // that would hand back the same offset again and loop the client over an empty page forever.
        boolean hasMore = limit > 0 && rows.size() == limit;
        String nextToken = null;
        if (hasMore) {
            nextToken = String.valueOf((long) offset + limit);
        }

        PaginatedQueryResult result = PaginatedQueryResult.from(rows, nextToken, hasMore);

        // Apply data masking before caching and returning
        List<Map<String, Object>> maskedRows = maskingService.maskResults(rows, sqlValidator.getMaskColumns());
        if (maskedRows != rows) {
            result = new PaginatedQueryResult(result.columns(), maskedRows, nextToken, hasMore);
        }

        // Cache first page results. The masked variant is stored under the same key it is read
        // from: a separate ":masked" key was never looked up, so masked pages never hit the cache.
        if (continuationToken == null || continuationToken.isBlank()) {
            cache.putQuery(cacheKey, result);
            cache.recordQueryKey(schema + "." + sql);
        }

        return result;
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    /**
     * Inject a SQLCommenter-style trace annotation into the SQL for database-side query tracking.
     * Format: {@code &#47;* trace_id=xxx tool=executeQuery *&#47;} - most databases ignore
     * unknown comments.
     */
    private String injectSqlComment(String sql) {
        if (sql == null || sql.isBlank()) return sql;
        // Don't re-inject if already annotated
        if (sql.contains("trace_id=")) return sql;
        McpToolContext current = McpToolContext.current().orElse(null);
        String traceId = current != null ? current.correlationId() : "unknown";
        String toolName = current != null && current.toolName() != null ? current.toolName() : "unknown";
        String comment = "/* trace_id=" + traceId + " tool=" + toolName + " */ ";
        // Find first non-whitespace character and insert after it (after SELECT, WITH, etc.)
        int firstNonWhitespace = 0;
        while (firstNonWhitespace < sql.length() && Character.isWhitespace(sql.charAt(firstNonWhitespace))) {
            firstNonWhitespace++;
        }
        return sql.substring(0, firstNonWhitespace) + comment + sql.substring(firstNonWhitespace);
    }

    private static String sha256(String input) {
        try {
            // MessageDigest is not thread-safe, so a fresh instance is required per call.
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16); // Use first 16 hex chars as cache key prefix
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private long parseCursor(String token) {
        if (token == null || token.isBlank()) return 0L;
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Turns a continuation token into a row offset. A negative token is treated as the start of the
     * result set — left as-is it would drop the OFFSET clause, re-serve page one and hand back an
     * even more negative next token, so a client following tokens would never advance. A token past
     * {@link Integer#MAX_VALUE} is clamped rather than narrowed, because the cast wraps: 2^32 would
     * become offset 0 and re-serve page one.
     */
    private int resolveOffset(String token) {
        long cursor = parseCursor(token);
        if (cursor <= 0) return 0;
        return cursor > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cursor;
    }

    /**
     * Extract schema name from SQL. Returns "PUBLIC" if no schema is found.
     * Handles patterns like "FROM schema.table" or "SELECT ... FROM schema.table".
     */
    private static String extractSchema(String sql) {
        if (sql == null || sql.isBlank()) return "PUBLIC";
        // Match schema.table pattern (case-insensitive, after FROM/INTO/UPDATE)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "\\b(?:FROM|INTO|UPDATE)\\s+([A-Z_][A-Z0-9_]*)\\.",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            String schema = matcher.group(1).toUpperCase();
            // Filter out SQL keywords that might be mistaken as schema names
            if (!isSqlKeyword(schema)) {
                return schema;
            }
        }
        return "PUBLIC";
    }

    private static boolean isSqlKeyword(String word) {
        return word.equals("DUAL") || word.equals("SELECT") || word.equals("WHERE")
            || word.equals("SET") || word.equals("VALUES") || word.equals("INSERT")
            || word.equals("DELETE") || word.equals("AND") || word.equals("OR")
            || word.equals("NOT") || word.equals("NULL") || word.equals("TRUE")
            || word.equals("FALSE") || word.equals("EXISTS") || word.equals("IN");
    }

    private String normalizeTableName(String table) {
        if (table == null) {
            return null;
        }
        return dialect.normalizeTableName(table);
    }

    @SuppressWarnings("unchecked")
    private <T> T checkType(Object cached, String cacheKey, String expectedType) {
        if (cached == null) {
            throw new IllegalStateException("Cache miss for key: " + cacheKey);
        }
        if (!(cached instanceof List) && !(cached instanceof Map)) {
            log.warn("Cache entry for {} has unexpected type {}, expected {}", cacheKey,
                    cached.getClass().getName(), expectedType);
            throw new IllegalStateException("Cache type mismatch for key: " + cacheKey
                    + " - got " + cached.getClass().getSimpleName());
        }
        return (T) cached;
    }

    /**
     * 连接元数据。
     *
     * <p>{@code url} 走 {@link JdbcUrlMasker}：{@code DatabaseMetaData.getURL()} 返回的是驱动构造时那个
     * URL，调用方塞在 {@code ?password=} 或 {@code user/pw@} 里的凭证会原路返回给 MCP 调用方——也就是进
     * 模型上下文、再进客户端保存的对话历史。这里是唯一一处会把原始 URL 交出去的读路径。
     */
    public Map<String, Object> getDatabaseInfo() {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            var meta = conn.getMetaData();
            return Map.of(
                "productName", meta.getDatabaseProductName(),
                "productVersion", meta.getDatabaseProductVersion(),
                "driverName", meta.getDriverName(),
                "driverVersion", meta.getDriverVersion(),
                "url", JdbcUrlMasker.mask(meta.getURL()),
                "user", meta.getUserName()
            );
        } catch (Exception e) {
            return Map.of("error", "Connection information unavailable");
        }
    }
}
