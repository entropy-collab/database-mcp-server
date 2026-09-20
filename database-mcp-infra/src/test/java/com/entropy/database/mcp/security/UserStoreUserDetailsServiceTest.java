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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import javax.sql.DataSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态库里的身份加载成 {@link org.springframework.security.core.userdetails.UserDetails} 时，
 * {@code roles} 列要真的变成 authority。
 *
 * <p>这一步是整条链路上最容易悄悄断掉的一环：断掉的形态不是报错，而是那个身份能过认证、能调
 * {@code /mcp}，但 {@code /api/**} 与只读面板（都要 {@code ROLE_ADMIN}）一律 403——
 * 和"口令没配对"在运维视角下长得很像。所以这里直接断言 authority 集合，而不是只断言能登录。
 *
 * <p>不启 Spring 容器：{@code SecurityConfig.UserStoreUserDetailsService} 是包级可见的记录，
 * 而管理员口令来自真实环境变量，为了这几条断言去装一整个上下文反而把判据埋进启动细节里。
 */
class UserStoreUserDetailsServiceTest {

    private static final String VALID_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** 与 {@code SecurityConfig.userDetailsService} 里造出来的那个管理员同形：它的权限不来自表。 */
    private static final McpPrincipal ADMIN =
            new McpPrincipal("admin", "user", VALID_HASH, Set.of("ROLE_ADMIN", "ROLE_DBA"));

    private DataSource dataSource;
    private JdbcUserStore store;
    private SecurityConfig.UserStoreUserDetailsService service;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:details_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        this.dataSource = DataSourceBuilder.create().url(url).build();
        this.store = new JdbcUserStore(new JdbcTemplate(dataSource), url);
        store.initialize();
        this.service = new SecurityConfig.UserStoreUserDetailsService(ADMIN, store);
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

    /** 表里带 ROLE_ADMIN 的身份，加载出来就该有 ROLE_ADMIN——这正是 /api/** 与面板的准入条件。 */
    @Test
    void rolesFromTheTableBecomeAuthorities() {
        store.create("adminqd", "user", VALID_HASH, Set.of("ROLE_ADMIN", "ROLE_DBA"));

        var loaded = service.loadUserByUsername("adminqd");

        assertThat(loaded.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_DBA");
        assertThat(loaded).isInstanceOf(McpPrincipal.class);
        assertThat(((McpPrincipal) loaded).subjectRef()).isEqualTo("user:adminqd");
    }

    /** 没给 roles 的身份仍然一个权限都没有：升级一个旧库不能让原有身份凭空变成管理员。 */
    @Test
    void identitiesWithoutRolesStayUnprivileged() {
        store.create("plain", "agent", VALID_HASH, Set.of());

        var loaded = service.loadUserByUsername("plain");

        assertThat(loaded.getAuthorities()).isEmpty();
    }

    /** 管理员仍然短路在表之前，权限来自环境变量那条路，不受表影响。 */
    @Test
    void theAdminIsResolvedWithoutTouchingTheTable() {
        var loaded = service.loadUserByUsername("admin");

        assertThat(loaded).isSameAs(ADMIN);
        assertThat(loaded.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN");
    }

    /** 停用与不存在报同一个异常：区分开会让这里变成一个"用户名是否存在"的探测器。 */
    @Test
    void disabledAndUnknownAreIndistinguishable() {
        store.create("gone", "user", VALID_HASH, Set.of("ROLE_ADMIN"));
        store.disable("gone");

        assertThatThrownBy(() -> service.loadUserByUsername("gone"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("未知调用者");
        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("未知调用者");
    }
}
