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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库用户表的读写。
 *
 * <p>跑在一个每个用例独立命名的内存 H2 上——这个类测的是 SQL 与校验，不是持久性，
 * 持久性由 {@link #inMemoryUrlsAreDetected()} 那条判据负责保证它会被告警出来，
 * 以及 {@link #survivesAReopenOnAFileDatabase(Path)} 负责证明文件库真的读得回来。
 *
 * <p>最要紧的两条断言是 {@code create} 拒绝原文口令、以及 {@code list} 不返回口令哈希：
 * 前者是"能读到这张表的人等于拿到所有人凭据"的第一道门，后者是那份哈希会经 {@code /api/**}
 * 出去的唯一出口。
 *
 * <p>生产上这张表与审计流水共用 {@code spring.datasource}，但测试里各自建库：共用是部署决策，
 * 不该让一个单元测试去依赖另一个组件的建表时机。
 */
class JdbcUserStoreTest {

    private static final String VALID_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private DataSource dataSource;
    private JdbcUserStore store;

    @BeforeEach
    void setUp() {
        // 每个用例一个独立库名，否则用例之间会互相看到对方的行
        String url = "jdbc:h2:mem:users_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        this.dataSource = DataSourceBuilder.create().url(url).build();
        this.store = new JdbcUserStore(new JdbcTemplate(dataSource), url);
        store.initialize();
    }

    @AfterEach
    void tearDown() {
        close(dataSource);
    }

    private static void close(DataSource candidate) {
        if (candidate instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 测试收尾，关不掉也不该盖住真正的失败
            }
        }
    }

    @Test
    void createsAndFindsAnIdentity() {
        store.create("zhangsan", "user", VALID_HASH, Set.of());

        var found = store.find("zhangsan");

        assertThat(found).isPresent();
        assertThat(found.get().type()).isEqualTo("user");
        assertThat(found.get().passwordHash()).isEqualTo(VALID_HASH);
        assertThat(found.get().enabled()).isTrue();
    }

    @Test
    void findReturnsEmptyForUnknown() {
        assertThat(store.find("nobody")).isEmpty();
    }

    @Test
    void rejectsPlaintextPassword() {
        assertThatThrownBy(() -> store.create("x", "user", "hunter2", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bcrypt");
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> store.create("x", "admin", VALID_HASH, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主体类型");
    }

    @Test
    void rejectsBlankUsername() {
        assertThatThrownBy(() -> store.create("  ", "user", VALID_HASH, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名不能为空");
    }

    @Test
    void rejectsDuplicateUsername() {
        store.create("x", "user", VALID_HASH, Set.of());

        assertThatThrownBy(() -> store.create("x", "agent", VALID_HASH, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void typeIsNormalizedToLowerCase() {
        store.create("x", "AGENT", VALID_HASH, Set.of());

        assertThat(store.find("x").orElseThrow().type()).isEqualTo("agent");
    }

    /** 停用而不是删除：删掉之后审计流水里那个用户名再也对不上任何记录。 */
    @Test
    void disableKeepsTheRowButFlagsIt() {
        store.create("x", "user", VALID_HASH, Set.of());

        assertThat(store.disable("x")).isTrue();

        assertThat(store.find("x")).isPresent();
        assertThat(store.find("x").orElseThrow().enabled()).isFalse();
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void disableReportsWhenNothingMatched() {
        assertThat(store.disable("nobody")).isFalse();
    }

    /** 列表会经 /api/** 出去，所以它不能带口令哈希。 */
    @Test
    void listDoesNotExposePasswordHashes() {
        store.create("a", "user", VALID_HASH, Set.of());
        store.create("b", "agent", VALID_HASH, Set.of());

        var rows = store.list();

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(JdbcUserStore.Row::username).containsExactly("a", "b");
        assertThat(rows).allSatisfy(row -> assertThat(row.passwordHash()).isEmpty());
    }

    @Test
    void initializeIsIdempotent() {
        store.create("x", "user", VALID_HASH, Set.of());

        store.initialize();

        assertThat(store.count()).isEqualTo(1);
    }

    /**
     * 内存库必须被识别出来。
     *
     * <p>这条判据决定了那段"重启后所有人都不见了"的启动告警会不会出现，而登录失败与密码记错
     * 在症状上无法区分——本项目已经在审计上为同一类静默失效吃过一次亏。
     */
    @Test
    void inMemoryUrlsAreDetected() {
        for (String url : List.of(
                "jdbc:h2:mem:test",
                "JDBC:H2:MEM:TEST",
                "jdbc:hsqldb:mem:test",
                "jdbc:sqlite::memory:",
                "jdbc:derby:memory:test")) {
            assertThat(JdbcUserStore.isInMemory(url)).as(url).isTrue();
        }
        for (String url : List.of(
                "jdbc:h2:file:/var/lib/mcp/users",
                "jdbc:postgresql://localhost:5432/mcp",
                "")) {
            assertThat(JdbcUserStore.isInMemory(url)).as(url).isFalse();
        }
    }

    /** 文件库也要能跑通——这是正式阶段真正会用的形态。 */
    @Test
    void survivesAReopenOnAFileDatabase(@TempDir Path dir) {
        String url = "jdbc:h2:file:" + dir.resolve("state");
        var first = DataSourceBuilder.create().url(url).build();
        try {
            var writing = new JdbcUserStore(new JdbcTemplate(first), url);
            writing.initialize();
            writing.create("zhangsan", "user", VALID_HASH, Set.of());
        } finally {
            close(first);
        }

        // 关掉再打开：文件库的意义就在于这一步能读回来
        var second = DataSourceBuilder.create().url(url).build();
        try {
            var reopened = new JdbcUserStore(new JdbcTemplate(second), url);
            reopened.initialize();
            assertThat(reopened.find("zhangsan")).isPresent();
        } finally {
            close(second);
        }
    }

    /** 权限往返：写进去两个，读出来还是那两个。这是"库里的身份能拿到 ROLE_ADMIN"的落库端。 */
    @Test
    void rolesSurviveARoundTrip() {
        store.create("adminqd", "user", VALID_HASH, Set.of("ROLE_ADMIN", "ROLE_DBA"));

        assertThat(store.find("adminqd").orElseThrow().roles())
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_DBA");
        assertThat(store.list()).singleElement()
                .satisfies(row -> assertThat(row.roles()).contains("ROLE_ADMIN"));
    }

    /**
     * 不给权限就是没有权限。
     *
     * <p>这条钉住的是升级语义：{@code null} 与空集都不该让一个身份凭空拿到任何东西，
     * 否则老库一升级，表里所有人都变成管理员。
     */
    @Test
    void absentRolesMeanNoAuthorities() {
        store.create("noroles", "user", VALID_HASH, null);
        store.create("emptyroles", "agent", VALID_HASH, Set.of());

        assertThat(store.find("noroles").orElseThrow().roles()).isEmpty();
        assertThat(store.find("emptyroles").orElseThrow().roles()).isEmpty();
    }

    /** 白名单之外的角色在写入时就失败——拼错一个字母的症状否则是"配了但不生效"。 */
    @Test
    void rejectsRolesOutsideTheWhitelist() {
        assertThatThrownBy(() -> store.create("x", "user", VALID_HASH, Set.of("ROLE_SUPERUSER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROLE_SUPERUSER")
                .hasMessageContaining("ROLE_ADMIN");

        assertThat(store.count()).isZero();
    }

    /**
     * 大小写归一后仍在白名单内就接受，但 {@code ROLE_} 前缀不能省。
     *
     * <p>后半条是刻意的：允许 {@code admin} 与 {@code ROLE_ADMIN} 两种拼法，等于同一个权限有两个名字，
     * 而其中一个在 {@code hasRole} 那边是不生效的那个。
     */
    @Test
    void rolesAreCaseNormalizedButThePrefixIsMandatory() {
        store.create("lowercase", "user", VALID_HASH, Set.of("role_admin"));

        assertThat(store.find("lowercase").orElseThrow().roles()).containsExactly("ROLE_ADMIN");

        assertThatThrownBy(() -> store.create("noprefix", "user", VALID_HASH, Set.of("admin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROLE_ 前缀");
    }

    /** 字符串 ↔ Set 的解析被 find 与 list 共用，所以直接断言它自己。 */
    @Test
    void parsesAndFormatsTheRolesColumn() {
        assertThat(JdbcUserStore.parseRoles("ROLE_ADMIN,ROLE_DBA"))
                .containsExactly("ROLE_ADMIN", "ROLE_DBA");
        // 手写 SQL 塞进来的脏格式：空白、空片段、小写
        assertThat(JdbcUserStore.parseRoles(" role_admin , ,ROLE_DBA "))
                .containsExactly("ROLE_ADMIN", "ROLE_DBA");
        assertThat(JdbcUserStore.parseRoles(null)).isEmpty();
        assertThat(JdbcUserStore.parseRoles("")).isEmpty();
        assertThat(JdbcUserStore.parseRoles("   ")).isEmpty();
        // 读路径不做白名单校验：一行脏数据该让那个用户少一个权限，而不是一登录就 500
        assertThat(JdbcUserStore.parseRoles("ROLE_WHATEVER")).containsExactly("ROLE_WHATEVER");

        assertThat(JdbcUserStore.formatRoles(Set.of("ROLE_ADMIN"))).isEqualTo("ROLE_ADMIN");
        assertThat(JdbcUserStore.formatRoles(Set.of())).isEmpty();
        assertThat(JdbcUserStore.formatRoles(null)).isEmpty();
        assertThat(JdbcUserStore.parseRoles(
                JdbcUserStore.formatRoles(List.of("ROLE_ADMIN", "ROLE_DBA"))))
                .containsExactly("ROLE_ADMIN", "ROLE_DBA");
    }

    /**
     * 旧表（没有 roles 列）被 initialize 补上列，而旧行的权限仍然是空。
     *
     * <p>长春与青岛的 H2 文件库都是这个结构，所以这条不是假想：{@code CREATE TABLE IF NOT EXISTS}
     * 对已存在的表什么都不做，少这一步的症状是每次登录都 {@code Column "ROLES" not found}。
     */
    @Test
    void addsTheRolesColumnToAPreExistingTable(@TempDir Path dir) {
        String url = "jdbc:h2:file:" + dir.resolve("legacy");
        var dataSource = DataSourceBuilder.create().url(url).build();
        try {
            var jdbc = new JdbcTemplate(dataSource);
            // 手工建一张升级前的表：四列，没有 roles
            jdbc.execute("""
                    CREATE TABLE mcp_user (
                        username       VARCHAR(128) PRIMARY KEY,
                        principal_type VARCHAR(16)  NOT NULL,
                        password_hash  VARCHAR(72)  NOT NULL,
                        enabled        SMALLINT     NOT NULL
                    )
                    """);
            jdbc.update("INSERT INTO mcp_user (username, principal_type, password_hash, enabled)"
                    + " VALUES ('legacy', 'user', ?, 1)", VALID_HASH);

            var upgraded = new JdbcUserStore(jdbc, url);
            upgraded.initialize();

            assertThat(upgraded.find("legacy").orElseThrow().roles()).isEmpty();
            // 补列之后新身份才能带权限，这一步顺带证明列真的是可写的而不只是查得到
            upgraded.create("fresh", "user", VALID_HASH, Set.of("ROLE_ADMIN"));
            assertThat(upgraded.find("fresh").orElseThrow().roles()).containsExactly("ROLE_ADMIN");

            // 再跑一次不能失败：ALTER 只该发生一次，否则第二次启动就会报"列已存在"
            upgraded.initialize();
            assertThat(upgraded.count()).isEqualTo(2);
        } finally {
            close(dataSource);
        }
    }
}
