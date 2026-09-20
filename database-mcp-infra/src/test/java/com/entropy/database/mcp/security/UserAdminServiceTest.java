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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 身份运维入口的三条硬规则。
 *
 * <p>这一层本身很薄，但它挡着三件出错代价很高的事：口令原文落库、管理员被库里的同名记录影子化、
 * 以及管理员被停用之后没人能改回来。每一条都有一个断言钉住。
 */
class UserAdminServiceTest {

    private static final String ADMIN = "admin";

    private DataSource dataSource;
    private JdbcUserStore store;
    private UserAdminService service;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:admin_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        this.dataSource = DataSourceBuilder.create().url(url).build();
        this.store = new JdbcUserStore(new JdbcTemplate(dataSource), url);
        store.initialize();
        this.encoder = new BCryptPasswordEncoder();
        this.service = new UserAdminService(store, encoder, ADMIN);
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

    /**
     * 口令原文进来、哈希落库，而且哈希真的能校验回原文。
     *
     * <p>只断言"存的不是原文"不够——那样把口令写成一个常量也能过。必须确认存的是<b>这个</b>口令的哈希。
     */
    @Test
    void hashesThePasswordBeforeStoringIt() {
        service.create("zhangsan", "user", "hunter2", List.of());

        var row = store.find("zhangsan").orElseThrow();

        assertThat(row.passwordHash()).isNotEqualTo("hunter2");
        assertThat(row.passwordHash()).startsWith("$2");
        assertThat(encoder.matches("hunter2", row.passwordHash())).isTrue();
    }

    @Test
    void createReturnsAViewWithoutAnyPasswordField() {
        var view = service.create("zhangsan", "AGENT", "hunter2", List.of());

        assertThat(view.username()).isEqualTo("zhangsan");
        assertThat(view.type()).isEqualTo("agent");
        assertThat(view.enabled()).isTrue();
        // View 是 record，字段就是全部；没有口令字段这件事由编译期保证，这里断言序列化出去的内容
        assertThat(view.toString()).doesNotContain("hunter2");
    }

    /**
     * 库里不能出现与管理员同名的记录。
     *
     * <p>管理员口令来自环境变量、不进库。允许同名就会出现两处定义，而"谁生效"取决于查找顺序——
     * 那不该是安全行为的决定因素。
     */
    @Test
    void refusesToCreateARecordShadowingTheAdmin() {
        assertThatThrownBy(() -> service.create(ADMIN, "user", "whatever", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("管理员");

        assertThat(store.count()).isZero();
    }

    /** 停用管理员会把自己锁在门外，而改回来的接口也在门里面。 */
    @Test
    void refusesToDisableTheAdmin() {
        assertThatThrownBy(() -> service.disable(ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能停用管理员");
    }

    @Test
    void rejectsBlankPassword() {
        assertThatThrownBy(() -> service.create("x", "user", "  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("口令不能为空");
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> service.create("x", "superuser", "hunter2", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主体类型");
    }

    @Test
    void disableReportsWhetherAnythingChanged() {
        service.create("x", "user", "hunter2", List.of());

        assertThat(service.disable("x")).isTrue();
        assertThat(service.disable("nobody")).isFalse();
    }

    /** 列表里没有管理员——它不在库里；也没有口令哈希。 */
    @Test
    void listExcludesTheAdminAndAnyHashes() {
        service.create("a", "user", "hunter2", List.of());
        service.create("b", "service", "hunter3", List.of());

        var views = service.list();

        assertThat(views).extracting(UserAdminService.View::username).containsExactly("a", "b");
        assertThat(views.toString()).doesNotContain("$2");
    }

    /**
     * 带 roles 建出来的身份要能落库，而且列表里看得见。
     *
     * <p>"列表里看得见"不是锦上添花：运维分不清谁是管理员的时候，这个接口就回答不了"为什么他打不开面板"。
     */
    @Test
    void createStoresTheGrantedRolesAndShowsThemInTheList() {
        var view = service.create("adminqd", "user", "hunter2", List.of("ROLE_ADMIN", "ROLE_DBA"));

        assertThat(view.roles()).containsExactly("ROLE_ADMIN", "ROLE_DBA");
        assertThat(store.find("adminqd").orElseThrow().roles())
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_DBA");
        assertThat(service.list()).singleElement()
                .satisfies(listed -> assertThat(listed.roles()).contains("ROLE_ADMIN"));
    }

    /** 不传 roles（JSON 里没这个字段就是 null）等于不授权，与升级前完全一致。 */
    @Test
    void omittedRolesGrantNothing() {
        var view = service.create("plain", "agent", "hunter2", null);

        assertThat(view.roles()).isEmpty();
        assertThat(store.find("plain").orElseThrow().roles()).isEmpty();
    }

    /** 未知角色在这一层就被拒，控制器把它翻成 400；静默忽略会变成"配了但不生效"。 */
    @Test
    void rejectsRolesOutsideTheWhitelist() {
        assertThatThrownBy(() -> service.create("x", "user", "hunter2", List.of("ROLE_ROOT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROLE_ROOT");

        assertThat(store.count()).isZero();
    }
}
