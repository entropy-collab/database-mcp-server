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
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 种子数据本身，以及"它不能被带进生产"这道检查。
 *
 * <p>第一个用例直接执行仓库里的 {@code db/users-seed.sql}，因此它同时验证三件事：脚本语法对、
 * 十条记录的类型与启停状态符合预期、以及那些口令哈希真的能通过 {@link Credentials} 的校验。
 * 把种子数据当成"反正只是测试数据"而不测，结果是某天 demo 起不来，而没人知道是脚本坏了。
 *
 * <p>其余用例盯的是那道生产检查。它是一道<b>安全检查</b>，而静默失效的安全检查比没有更糟——
 * 它让人以为有人在看着。
 */
class SeedUserGuardTest {

    private static final String SEED_SCRIPT = "db/users-seed.sql";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcUserStore store;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:seed_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        this.dataSource = DataSourceBuilder.create().url(url).build();
        this.jdbc = new JdbcTemplate(dataSource);
        this.store = new JdbcUserStore(jdbc, url);
        store.initialize();
    }

    @AfterEach
    void tearDown() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 测试收尾
            }
        }
    }

    /** 仓库里的种子脚本要真的能跑，而且跑出来的东西符合它自己的描述。 */
    @Test
    void theSeedScriptCreatesTenIdentities() {
        jdbc.execute(readSeedScript());

        var rows = store.list();

        assertThat(rows).hasSize(10);
        assertThat(rows).allSatisfy(row ->
                assertThat(row.username()).startsWith(SeedUserGuard.SEED_PREFIX));
        assertThat(rows).extracting(JdbcUserStore.Row::type)
                .containsOnly("user", "agent", "service");
        // 有且只有一个停用的，用来验证"停用后立刻登不上"
        assertThat(rows.stream().filter(row -> !row.enabled()).map(JdbcUserStore.Row::username))
                .containsExactly("demo-leaver");
    }

    /** 脚本可以重复执行——MERGE 而不是 INSERT，否则第二次会撞主键。 */
    @Test
    void theSeedScriptIsIdempotent() {
        String script = readSeedScript();

        jdbc.execute(script);
        jdbc.execute(script);

        assertThat(store.count()).isEqualTo(10);
    }

    /** 种子里的口令哈希必须通过与凭据文件同一套校验，否则 /api/users 和种子会各认一套格式。 */
    @Test
    void seededHashesPassTheSharedCredentialCheck() {
        jdbc.execute(readSeedScript());

        for (var row : store.list()) {
            var stored = store.find(row.username()).orElseThrow();
            assertThat(Credentials.isBcrypt(stored.passwordHash()))
                    .as(row.username())
                    .isTrue();
        }
    }

    @Test
    void guardPassesWhenThereAreNoSeedIdentities() {
        var guard = new SeedUserGuard(store, productionEnvironment(), "admin");

        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    /** 生产 profile + 种子身份 = 启动失败。这是这个类存在的全部理由。 */
    @Test
    void guardRefusesToStartInProductionWithSeedIdentities() {
        jdbc.execute(readSeedScript());
        var guard = new SeedUserGuard(store, productionEnvironment(), "admin");

        assertThatThrownBy(guard::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("demo-dba")
                .hasMessageContaining("production");
    }

    /** 非生产只告警，不阻断：本地演示正是它的用途。 */
    @Test
    void guardOnlyWarnsOutsideProduction() {
        jdbc.execute(readSeedScript());
        var guard = new SeedUserGuard(store, new MockEnvironment(), "admin");

        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    /**
     * 停用种子身份不足以通过检查。
     *
     * <p>停用可以被任何有 {@code /api/users} 权限的人改回去，所以这道检查看的是记录是否存在。
     */
    @Test
    void disablingSeedIdentitiesIsNotEnough() {
        jdbc.execute(readSeedScript());
        jdbc.update("UPDATE mcp_user SET enabled = 0 WHERE username LIKE 'demo-%'");
        var guard = new SeedUserGuard(store, productionEnvironment(), "admin");

        assertThatThrownBy(guard::check).isInstanceOf(IllegalStateException.class);
    }

    /** 失败信息要给出可执行的清理语句——启动失败时那是运维唯一的线索。 */
    @Test
    void failureMessageTellsYouHowToClean() {
        String message = SeedUserGuard.describe(List.of("demo-dba"), "production");

        assertThat(message).contains("DELETE FROM mcp_user WHERE username LIKE 'demo-%'");
    }

    /**
     * 表里出现与管理员同名的记录 = 启动失败，不分 profile。
     *
     * <p>{@code UserAdminService} 拒绝创建这种记录，但直接写 SQL 绕得过去——而种子脚本就是直接写
     * SQL。绕过去之后那一行永远不会被读到（认证时先比管理员、命中就返回），症状是
     * "我明明改了 admin 的口令，为什么还是旧的"，而且没有任何报错。
     */
    @Test
    void guardRefusesAShadowedAdminRow() {
        jdbc.update("INSERT INTO mcp_user (username, principal_type, password_hash, enabled)"
                + " VALUES ('admin', 'user', '$2a$10$aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1)");
        var guard = new SeedUserGuard(store, new MockEnvironment(), "admin");

        assertThatThrownBy(guard::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("与 entropy.mcp.security.admin-username 同名")
                .hasMessageContaining("永远不会生效");
    }

    /** 改过 admin-username 之后，被遮蔽的判据要跟着那个名字走，而不是硬编码的 "admin"。 */
    @Test
    void shadowCheckFollowsTheConfiguredAdminName() {
        assertThatThrownBy(() -> SeedUserGuard.requireNoShadowedAdmin(List.of("root"), "root"))
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> SeedUserGuard.requireNoShadowedAdmin(List.of("admin"), "root"))
                .doesNotThrowAnyException();
    }

    /** 种子里不该有管理员：全权限账号的口令必须来自环境变量，不能躺在仓库里。 */
    @Test
    void theSeedScriptDoesNotContainAnAdmin() {
        jdbc.execute(readSeedScript());

        assertThatCode(() -> SeedUserGuard.requireNoShadowedAdmin(
                store.list().stream().map(JdbcUserStore.Row::username).toList(), "admin"))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment productionEnvironment() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        return environment;
    }

    private static String readSeedScript() {
        try (InputStream in = SeedUserGuardTest.class.getClassLoader()
                .getResourceAsStream(SEED_SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上找不到 " + SEED_SCRIPT);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + SEED_SCRIPT + " 失败", e);
        }
    }
}
