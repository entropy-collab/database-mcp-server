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
package com.entropy.database.mcp.toggle;

import com.entropy.database.mcp.util.JdbcUrls;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MCP 工具运行期开关的落库端：哪些工具被管理员停用了。
 *
 * <p>停用的语义是「工具从 {@code tools/list} 消失」，真正执行这件事的是
 * {@code ToolToggleRegistry}（它对 {@code McpStatelessSyncServer} 做 add/removeTool）。
 * 本类只负责把那个决定记下来，好让它活过一次重启。
 *
 * <h2>为什么不在 {@code com.entropy.database.mcp.tools} 包里</h2>
 * <p>ArchUnit 的 R1 规则（{@code ArchitectureRulesTest}，baseline 0）禁止 {@code tools..} 包依赖
 * {@link JdbcTemplate}：那条规则存在的理由是工具必须经门面拿数据，而不是自己抓一个模板去连业务库。
 * 本类连的<b>不是</b>业务库而是服务端状态库（{@code spring.datasource.url}，与 {@code audit_log}、
 * {@code mcp_user} 同一个），所以它不属于那条规则要防的形状——但规则按包判定，把它放进 {@code tools}
 * 只会得到一次「R1 regressed」。换个包比给规则开一个例外划算：例外会被下一个人复制。
 *
 * <h2>没配库的形态</h2>
 * <p>这个 bean 挂在 {@code spring.datasource.url} 上，与 {@code AuditLogRepository} /
 * {@code JdbcUserStore} 完全一致。没配那个键时本 bean 整个不装配，开关仍然可用但只活在内存里；
 * 那条「重启后回到 {@code entropy.mcp.tools} 声明的状态」的启动 WARN 由 {@code ToolToggleRegistry}
 * 打出——它是唯一同时知道「有没有仓储」和「内存里现在停了几个」的地方。
 *
 * <h2>建表</h2>
 * <p>{@code CREATE TABLE IF NOT EXISTS} 覆盖 H2 / PostgreSQL / MySQL。Oracle 与 SQL Server 没有这个
 * 子句（写上去是 ORA-00922 这类语法错误而不是被忽略），这两种库上请先手工建表：
 * <pre>
 * -- Oracle
 * CREATE TABLE mcp_tool_toggle (
 *   tool_name  VARCHAR2(128) PRIMARY KEY,
 *   disabled   NUMBER(1)     NOT NULL,
 *   updated_at TIMESTAMP,
 *   updated_by VARCHAR2(128)
 * )
 * -- SQL Server（TIMESTAMP 在 SQL Server 是行版本号而不是时间类型，必须写 DATETIME2）
 * CREATE TABLE mcp_tool_toggle (
 *   tool_name  VARCHAR(128) PRIMARY KEY,
 *   disabled   SMALLINT     NOT NULL,
 *   updated_at DATETIME2,
 *   updated_by VARCHAR(128)
 * )
 * </pre>
 * 和 {@code JdbcUserStore} 一样只维护一份标准 DDL，不复刻 {@code AuditLogRepository} 那套按产品分
 * 五份的做法：那张表有自增列和保留字列名才不得不分，这张表只有主键与三个普通列。
 *
 * <h2>为什么是 UPDATE 再 INSERT，而不是 UPSERT</h2>
 * <p>「插入或更新」没有跨库写法：PostgreSQL 是 {@code ON CONFLICT}、MySQL 是
 * {@code ON DUPLICATE KEY}、H2/Oracle/SQL Server 是各自口味的 {@code MERGE}。五份 SQL 换来的收益
 * 只是省一次往返，而这张表一次运维动作最多写几十行。
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class ToolToggleRepository {

    private static final Logger log = LoggerFactory.getLogger(ToolToggleRepository.class);

    /**
     * 表名。DDL、查询与日志共用，避免多处字面量漂移。
     *
     * <p>public 是因为回放时的 WARN 要把表名告诉运维，而 {@code ToolToggleRegistry} 在另一个包。
     */
    public static final String TABLE_NAME = "mcp_tool_toggle";

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS mcp_tool_toggle (
                tool_name  VARCHAR(128) PRIMARY KEY,
                disabled   SMALLINT     NOT NULL,
                updated_at TIMESTAMP,
                updated_by VARCHAR(128)
            )
            """;

    private final JdbcTemplate jdbc;
    private final String url;

    public ToolToggleRepository(JdbcTemplate jdbc, @Value("${spring.datasource.url}") String url) {
        this.jdbc = jdbc;
        this.url = url;
    }

    /**
     * 建表，并在库只存在于进程内存时告警。
     *
     * <p>这条 WARN 与 {@code AuditLogInitializer} / {@code JdbcUserStore} 那两条是<b>三件事</b>：
     * 同一个物理库一起丢，但丢的东西不同，运维需要分别知道各丢了什么。这里丢的是「哪些工具被停用」，
     * 症状是<b>重启后被停掉的工具又出现在 {@code tools/list} 里</b>——在「停掉它是为了止血」的场景下
     * 这等于止血失效，而且没有任何报错。<b>NEVER 把这条降级成 info 或删掉。</b>
     */
    @PostConstruct
    public void initialize() {
        if (JdbcUrls.isInMemory(url)) {
            log.warn("""
                    ================================================================
                    MCP 工具开关表位于【内存】数据库（{}）：
                    进程一停，所有通过 /api/tools 停用的工具全部恢复暴露，
                    没有任何报错，表现为"明明停掉了的工具又回到 tools/list 里"。
                    这适合测试；正式环境请指向 jdbc:h2:file:... 或一个外部库。
                    ================================================================""", url);
        }
        jdbc.execute(CREATE_TABLE_SQL);
        log.info("MCP 工具开关表已就绪（{}），库里有 {} 个工具被标记为停用", url, disabledToolNames().size());
    }

    /**
     * 库里被标记为停用的工具名，字典序。
     *
     * <p>只读 {@code disabled = 1} 的行：被启用回来的工具留一行 {@code disabled = 0} 而不是删掉，
     * 那一行的 {@code updated_by} / {@code updated_at} 是「谁在什么时候把它放回去的」唯一记录。
     */
    public Set<String> disabledToolNames() {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT tool_name FROM mcp_tool_toggle WHERE disabled = 1 ORDER BY tool_name",
                String.class));
    }

    /**
     * 记下一个工具的当前开关状态。
     *
     * @param toolName MCP 工具名
     * @param disabled 是否停用
     * @param actor    操作者，取不到身份时调用方传 {@code unknown}（判定见
     *                 {@code ToolToggleRegistry.currentActor}）
     */
    public void save(String toolName, boolean disabled, String actor) {
        Timestamp now = Timestamp.from(Instant.now());
        int flag = disabled ? 1 : 0;
        int updated = jdbc.update(
                "UPDATE mcp_tool_toggle SET disabled = ?, updated_at = ?, updated_by = ? WHERE tool_name = ?",
                flag, now, actor, toolName);
        if (updated == 0) {
            jdbc.update("INSERT INTO mcp_tool_toggle (tool_name, disabled, updated_at, updated_by)"
                    + " VALUES (?, ?, ?, ?)", toolName, flag, now, actor);
        }
    }
}
