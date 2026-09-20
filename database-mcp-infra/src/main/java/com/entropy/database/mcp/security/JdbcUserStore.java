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

import com.entropy.database.mcp.util.JdbcUrls;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 调用者身份的数据库实现：可以不重启增删用户。
 *
 * <p>与 {@link UserFile} 二选一，由配置决定；两个都配会在启动时失败，见
 * {@link SecurityConfig#userDetailsService}。
 *
 * <h2>与审计共用 {@code spring.datasource}</h2>
 * <p>这个键原本只是「审计库」，现在是<b>服务端状态库</b>：审计流水与调用者身份都住在这里，
 * 后续的授权策略数据也会进来。共用一个物理库是部署决策，代价与收益都要写明：
 * <ul>
 *   <li><b>收益</b>：一处配置、一个连接池、一份备份。也因此这个类直接用自动装配的
 *       {@link JdbcTemplate}，和 {@code AuditLogRepository} 完全一致；如果各自持一个
 *       {@code DataSource}，容器里出现第二个 {@code DataSource} bean 会让 Boot 的
 *       {@code DataSourceAutoConfiguration}（{@code @ConditionalOnMissingBean}）整体退让，
 *       反而把审计持久化搞坏。</li>
 *   <li><b>代价</b>：两件事的生命周期被绑在一起。{@code AuditLogRepository.deleteOlderThan}
 *       轮转审计历史时动的是同一个库；把 {@code spring.datasource.url} 关掉（想只留文件审计）
 *       会连带让这张身份表消失，症状是"除管理员以外全都登不上"。</li>
 * </ul>
 *
 * <p><b>没配 {@code spring.datasource.url} 时这个 bean 整个不装配</b>，身份回落到凭据文件或
 * 只有管理员。这和审计的 opt-in 判据完全相同，也是同一个理由：条件挂在属性上而不是
 * {@code @ConditionalOnBean}，因为后者对被扫描的组件是装配顺序相关的。
 *
 * <h2>建表</h2>
 * <p>{@code CREATE TABLE IF NOT EXISTS} 覆盖 H2 / PostgreSQL / MySQL。Oracle 与 SQL Server 没有这个
 * 子句（写上去是语法错误而不是被忽略），这两种库上请先手工建表：
 * <pre>
 * CREATE TABLE mcp_user (
 *   username       VARCHAR(128) PRIMARY KEY,
 *   principal_type VARCHAR(16)  NOT NULL,
 *   password_hash  VARCHAR(72)  NOT NULL,
 *   enabled        SMALLINT     NOT NULL,
 *   roles          VARCHAR(256)
 * )
 * </pre>
 * 刻意<b>不</b>复刻 {@code AuditLogRepository} 那套按产品分五份的 DDL：那张表有自增列和保留字
 * 列名，才不得不分；这张表只有主键与四个普通列，一份标准 DDL 就够，多写四份是凭空多四处
 * 会漂移的真相。
 *
 * <h2>加列（roles）</h2>
 * <p>{@code roles} 是后加的列，所以 {@link #initialize()} 在建表之后还要补一步"列不存在则加列"——
 * 早于这个版本建起来的库（长春与青岛的 H2 文件库都是）只有前四列，而 {@code CREATE TABLE IF NOT
 * EXISTS} 对已存在的表什么都不做，新列不会凭空出现，症状是每次登录都 {@code Column "ROLES" not found}。
 *
 * <p><b>为什么用 {@link java.sql.DatabaseMetaData} 判断而不是 {@code ADD COLUMN IF NOT EXISTS}</b>：
 * 后者 H2 与 PostgreSQL 支持、MySQL 不支持（是语法错误），而上面刚说了这张表只维护一份标准 DDL、
 * 要同时覆盖这三种库。用元数据判断是唯一能同时满足"一份 DDL"和"跨库"的做法。
 *
 * <h2>为什么没有缓存</h2>
 * <p>HTTP Basic + {@code STATELESS} 意味着每个请求都要查一次身份。对一个本地嵌入式库来说那是微秒级，
 * 而加缓存会让"禁用一个用户"到"真的登不上"之间出现一段窗口——那正是要靠数据库换来的即时性。
 * 如果哪天它真的成为瓶颈，那是可测量的，届时再加并把 TTL 写进文档。
 */
@Component
@ConditionalOnProperty(name = "spring.datasource.url")
class JdbcUserStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserStore.class);

    static final String TABLE_NAME = "mcp_user";

    /** 权限列名。加列判断、ALTER 与错误信息都用它，避免三处字面量漂移。 */
    static final String ROLES_COLUMN = "roles";

    /**
     * 可授予的权限名。
     *
     * <p>与环境变量管理员拿到的那两个完全一致（见 {@code SecurityConfig#userDetailsService}）：
     * 库里的身份能拿到的上限就是管理员的那一套，不多一个。
     *
     * <p>收成白名单的理由与 {@code Credentials.PRINCIPAL_TYPES}、{@code ToolAuthzProperties.ROLES}
     * 相同：拼错一个字母的症状是"配了但不生效"——{@code ROLE_ADNIM} 照样能登录、照样是一条合法的
     * authority，只是任何 {@code hasRole} 都匹配不上，运维看到的是"给了管理员权限但还是 403"。
     * 所以必须在写入时就失败。
     */
    static final Set<String> GRANTABLE_AUTHORITIES = Set.of("ROLE_ADMIN", "ROLE_DBA");

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS mcp_user (
                username       VARCHAR(128) PRIMARY KEY,
                principal_type VARCHAR(16)  NOT NULL,
                password_hash  VARCHAR(72)  NOT NULL,
                enabled        SMALLINT     NOT NULL,
                roles          VARCHAR(256)
            )
            """;

    private final String url;
    private final JdbcTemplate jdbc;

    JdbcUserStore(JdbcTemplate jdbc, @Value("${spring.datasource.url}") String url) {
        this.jdbc = jdbc;
        this.url = url;
    }

    /**
     * 建表，并在库只存在于进程内存时告警。
     *
     * <p>这条 WARN 与 {@code AuditLogInitializer} 那条是<b>两件事</b>，不是重复：那条说的是审计历史会丢，
     * 这条说的是调用者身份会丢。共用一个库之后两者一起丢，而运维需要分别知道各丢了什么。
     *
     * <p>身份丢失尤其难查：它表现为"除管理员以外全都登不上"，和口令记错在症状上无法区分。
     * <b>NEVER 把这条降级成 info 或删掉。</b>
     */
    @PostConstruct
    void initialize() {
        if (isInMemory(url)) {
            log.warn("""
                    ================================================================
                    调用者身份表位于【内存】数据库（{}）：
                    进程一停，所有通过接口添加的身份全部丢失，只剩管理员，
                    而症状与"口令记错了"无法区分。
                    这适合测试；正式环境请指向 jdbc:h2:file:... 或一个外部库。
                    ================================================================""", url);
        }
        jdbc.execute(CREATE_TABLE_SQL);
        addRolesColumnIfMissing();
        log.info("调用者身份表已就绪（{}），当前 {} 个身份", url, count());
    }

    /**
     * 旧表补 {@code roles} 列。
     *
     * <p>幂等：靠元数据判断，已经有这一列就什么都不做，因此每次启动都跑一遍是安全的。
     * 为什么不用 {@code ADD COLUMN IF NOT EXISTS} 见类注释。
     *
     * <p>表名大小写两种都探一遍：H2 把未加引号的标识符折成大写，PostgreSQL 折成小写，
     * 而 {@code getColumns} 的表名参数是<b>区分大小写的模式</b>，只传一种会在另一种库上查空，
     * 于是每次启动都执行一次 ALTER、第二次就报"列已存在"。
     */
    private void addRolesColumnIfMissing() {
        Boolean present = jdbc.execute((ConnectionCallback<Boolean>) conn -> {
            var metaData = conn.getMetaData();
            for (String tablePattern : List.of(TABLE_NAME, TABLE_NAME.toUpperCase(Locale.ROOT))) {
                try (var columns = metaData.getColumns(null, null, tablePattern, null)) {
                    while (columns.next()) {
                        if (ROLES_COLUMN.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        });
        if (Boolean.TRUE.equals(present)) {
            return;
        }
        jdbc.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + ROLES_COLUMN + " VARCHAR(256)");
        // 这条 INFO 是"迁移发生过"的唯一痕迹：加列本身不改任何一行数据，事后从表里看不出
        // 这张表曾经没有这一列，而"旧身份为什么一个权限都没有"要靠它才答得上来（答案是本来就没有）
        log.info("已为旧版 {} 表补上 {} 列：这个库是在权限列引入之前建的，"
                + "已有身份的权限保持为空，需要谁有权限就通过 /api/users 重新建一个", TABLE_NAME, ROLES_COLUMN);
    }

    /**
     * 一条身份记录。{@code passwordHash} 只在认证时用到，列表接口不该返回它。
     *
     * @param roles 授予的权限，{@code null} 与空串都读成空集
     */
    record Row(String username, String type, String passwordHash, boolean enabled, Set<String> roles) {

        Row {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }

    Optional<Row> find(String username) {
        var rows = jdbc.query(
                "SELECT username, principal_type, password_hash, enabled, roles FROM mcp_user WHERE username = ?",
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) != 0,
                        parseRoles(rs.getString(5))),
                username);
        return rows.stream().findFirst();
    }

    /** 列表不带口令哈希：这个结果会经 {@code /api/**} 出去。权限要带上——谁是管理员正是这个列表要回答的事。 */
    List<Row> list() {
        return jdbc.query(
                "SELECT username, principal_type, enabled, roles FROM mcp_user ORDER BY username",
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), "", rs.getInt(3) != 0,
                        parseRoles(rs.getString(4))));
    }

    int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM mcp_user", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 新增身份。口令必须是 bcrypt 哈希——校验规则与凭据文件共用（见 {@link Credentials}）。
     *
     * @param roles 授予的权限，{@code null} 视为不授予任何权限
     * @throws IllegalArgumentException 类型未知、口令不是哈希、角色不在白名单，或用户名已存在
     */
    void create(String username, String type, String passwordHash, Set<String> roles) {
        String name = username == null ? "" : username.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String normalizedType = Credentials.requireKnownType(type, "新增 '" + name + "'");
        Credentials.requireBcrypt(passwordHash, "新增 '" + name + "'");
        var granted = requireGrantableRoles(roles, "新增 '" + name + "'");
        if (find(name).isPresent()) {
            throw new IllegalArgumentException("用户名 '" + name + "' 已存在");
        }
        jdbc.update("INSERT INTO mcp_user (username, principal_type, password_hash, enabled, roles)"
                + " VALUES (?, ?, ?, 1, ?)", name, normalizedType, passwordHash, formatRoles(granted));
        // 权限进日志："这个身份有没有管理员权限"是事后审计最常问的一件事，而表里的当前值答不了
        // "它是什么时候拿到的"
        log.info("新增调用者身份 {}:{}，授予权限 {}", normalizedType, name,
                granted.isEmpty() ? "（无）" : granted);
    }

    /**
     * 停用一个身份，而不是删除。
     *
     * <p>删除会让审计流水里那个用户名再也对不上任何记录——而审计流水现在就在同一个库里，
     * 这个理由因此更硬：同一份数据里不该出现指向不存在主体的记录。
     *
     * @return 是否真的改到了一行
     */
    boolean disable(String username) {
        int updated = jdbc.update("UPDATE mcp_user SET enabled = 0 WHERE username = ?", username);
        if (updated > 0) {
            log.info("已停用调用者身份 {}", username);
        }
        return updated > 0;
    }

    /**
     * 把 {@code roles} 列的字符串读成权限集合。
     *
     * <p>{@code null}（旧表补列之后的既有行）与空串（本类写入空集时的形态）都是空集——升级一个旧库
     * <b>不能</b>让原有身份凭空拿到权限，这是这次改动最要紧的一条不变量。
     *
     * <p><b>读路径刻意不做白名单校验</b>，只做归一。种子脚本与运维都可能直接写 SQL 塞进一个
     * {@code ROLE_WHATEVER}，若在这里抛异常，症状是那个用户一登录就 500；而放过去的代价只是多一条
     * 谁都匹配不上的 authority。写入口（{@link #requireGrantableRoles}）才是该拦住它的地方。
     */
    static Set<String> parseRoles(String stored) {
        if (stored == null || stored.isBlank()) {
            return Set.of();
        }
        // LinkedHashSet 保序：日志与 /api/users 的输出顺序稳定，diff 起来才有意义
        var parsed = new LinkedHashSet<String>();
        for (String piece : stored.split(",")) {
            String normalized = piece.strip().toUpperCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                parsed.add(normalized);
            }
        }
        return parsed;
    }

    /**
     * 权限集合写回 {@code roles} 列的形态。
     *
     * <p>空集写空串而不是 {@code null}：两者读回来都是空集，而空串不必操心各家驱动对
     * "无类型的 null 参数"的处理差异。
     */
    static String formatRoles(Collection<String> roles) {
        return roles == null ? "" : String.join(",", roles);
    }

    /**
     * 归一并校验待授予的权限。
     *
     * <p>归一规则：去空白、转大写。所以 {@code role_admin} 与 {@code ROLE_Admin} 都会被接受并折成
     * {@code ROLE_ADMIN}。<b>但不接受省略 {@code ROLE_} 前缀的写法</b>（{@code admin}）：
     * Spring Security 里 {@code hasRole("ADMIN")} 与 authority {@code ROLE_ADMIN} 的对应关系已经
     * 够容易记错了，再允许两种拼法并存就等于让同一个权限有两个名字，而其中一个是不生效的那个。
     *
     * @param context 出现在异常信息里，好让调用方知道是哪一次写入被拒
     */
    static Set<String> requireGrantableRoles(Collection<String> roles, String context) {
        if (roles == null) {
            return Set.of();
        }
        var granted = new LinkedHashSet<String>();
        for (String role : roles) {
            String normalized = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            if (!GRANTABLE_AUTHORITIES.contains(normalized)) {
                throw new IllegalArgumentException(context
                        + "：权限 '" + role + "' 不在允许范围内；可选值 " + GRANTABLE_AUTHORITIES
                        + "（必须写全 ROLE_ 前缀）");
            }
            granted.add(normalized);
        }
        return granted;
    }

    /**
     * 按 JDBC URL 判断库是否只存在于进程内存里。
     *
     * <p>判据本身住在 {@link JdbcUrls#isInMemory}：服务端状态库现在有三个持久化组件
     * （审计流水、调用者身份、MCP 工具开关）要问同一个问题，而它们分属三个包，各留一份拷贝
     * 就意味着「换一种内存库」时只改到其中一两处。这里保留方法是为了让本类的测试仍然断言
     * 「身份表会不会告警」这件事，而不是让判据有第二份实现。
     */
    static boolean isInMemory(String url) {
        return JdbcUrls.isInMemory(url);
    }
}
