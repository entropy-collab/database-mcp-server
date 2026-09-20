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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * MCP 工具开关表的建表与读写，跑在一个每个用例独立命名的内存 H2 上
 * （写法与 {@code JdbcUserStoreTest} 一致：那里测的也是 SQL 与判据，不是持久性）。
 *
 * <p>这张表回答的问题只有一个：「重启之后哪些工具还应该是停用的」。它答错的症状很安静——
 * 被停掉的工具重新出现在 {@code tools/list} 里，没有任何报错，而停掉它往往是为了止血。
 */
class ToolToggleRepositoryTest {

    private String url;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ToolToggleRepository store;

    @BeforeEach
    void setUp() {
        // 每个用例一个独立库名，否则用例之间会互相看到对方的行
        this.url = "jdbc:h2:mem:tool_toggle_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        this.dataSource = DataSourceBuilder.create().url(url).build();
        this.jdbc = new JdbcTemplate(dataSource);
        this.store = new ToolToggleRepository(jdbc, url);
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

    /** 建表走 {@code CREATE TABLE IF NOT EXISTS}，每次启动都会跑一遍，所以它必须可重复执行。 */
    @Test
    void initializeIsIdempotent() {
        store.save("executeQuery", true, "zhangsan");

        assertThatCode(() -> {
            store.initialize();
            store.initialize();
        }).doesNotThrowAnyException();

        assertThat(store.disabledToolNames()).containsExactly("executeQuery");
    }

    @Test
    void savesAndReadsBackTheDisabledSet() {
        store.save("executeQuery", true, "zhangsan");
        store.save("batchQuery", true, "zhangsan");

        // ORDER BY tool_name：清单会直接显示给运维，顺序不该随插入顺序变
        assertThat(store.disabledToolNames()).containsExactly("batchQuery", "executeQuery");
    }

    /**
     * 启用回来的工具留一行 {@code disabled = 0}，而不是把行删掉。
     *
     * <p>那一行的 {@code updated_by}/{@code updated_at} 是「谁在什么时候把它放回去的」唯一记录；
     * 删行等于把这条线索扔了。
     */
    @Test
    void enablingKeepsTheRowAndFlipsTheFlag() {
        store.save("executeQuery", true, "zhangsan");

        store.save("executeQuery", false, "lisi");

        assertThat(store.disabledToolNames()).isEmpty();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT disabled, updated_by FROM mcp_tool_toggle WHERE tool_name = ?", "executeQuery");
        assertThat(((Number) row.get("DISABLED")).intValue()).isZero();
        assertThat(row.get("UPDATED_BY")).isEqualTo("lisi");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mcp_tool_toggle", Integer.class))
                .as("启用不该删行")
                .isEqualTo(1);
    }

    /** 重复保存同一个工具只更新那一行：UPDATE 先行、INSERT 兜底（没有跨库的 UPSERT 写法）。 */
    @Test
    void savingTheSameToolTwiceUpdatesInPlace() {
        store.save("executeQuery", true, "zhangsan");
        store.save("executeQuery", true, "lisi");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mcp_tool_toggle", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT updated_by FROM mcp_tool_toggle WHERE tool_name = ?", String.class,
                "executeQuery")).isEqualTo("lisi");
    }

    /** 操作者与时间都得落下来：没有这两列就回答不了"这个工具是谁停的"。 */
    @Test
    void recordsTheActorAndTheTimestamp() {
        store.save("executeQuery", true, "zhangsan");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT updated_at, updated_by FROM mcp_tool_toggle WHERE tool_name = ?",
                "executeQuery");
        assertThat(row.get("UPDATED_BY")).isEqualTo("zhangsan");
        assertThat((Timestamp) row.get("UPDATED_AT")).isNotNull();
    }

    /**
     * {@code jdbc:h2:mem:} 必须被判成内存库。
     *
     * <p>这条判据决定了 {@link ToolToggleRepository#initialize()} 会不会打那条「重启即全部恢复暴露」
     * 的 WARN。判错的症状是运维以为停用活过了重启，而它没有，且没有任何报错。
     */
    @Test
    void inMemoryUrlsAreDetected() {
        assertThat(JdbcUrls.isInMemory(url)).as(url).isTrue();
        for (String candidate : List.of(
                "jdbc:h2:mem:toggles",
                "JDBC:H2:MEM:TOGGLES",
                "jdbc:hsqldb:mem:toggles",
                "jdbc:sqlite::memory:",
                "jdbc:derby:memory:toggles")) {
            assertThat(JdbcUrls.isInMemory(candidate)).as(candidate).isTrue();
        }
    }

    /** 反面：文件库与外部库都会留存，不该被告警。判据只看 URL，因为产品名区分不出这两者。 */
    @Test
    void fileAndExternalUrlsAreNotInMemory() {
        for (String candidate : List.of(
                "jdbc:h2:file:/var/lib/mcp/state",
                "jdbc:h2:/var/lib/mcp/state",
                "jdbc:postgresql://localhost:5432/mcp",
                "")) {
            assertThat(JdbcUrls.isInMemory(candidate)).as(candidate).isFalse();
        }
    }

    /** 文件库上关掉再打开必须读得回来——这才是正式环境用的形态，也是这张表存在的理由。 */
    @Test
    void survivesAReopenOnAFileDatabase(@TempDir Path dir) {
        String fileUrl = "jdbc:h2:file:" + dir.resolve("toggles");
        DataSource first = DataSourceBuilder.create().url(fileUrl).build();
        try {
            ToolToggleRepository writing = new ToolToggleRepository(new JdbcTemplate(first), fileUrl);
            writing.initialize();
            writing.save("executeQuery", true, "zhangsan");
        } finally {
            close(first);
        }

        DataSource second = DataSourceBuilder.create().url(fileUrl).build();
        try {
            ToolToggleRepository reopened = new ToolToggleRepository(new JdbcTemplate(second), fileUrl);
            // 第二次 initialize 落在一张已经存在的表上，这一步顺带再证一次建表的幂等
            reopened.initialize();
            assertThat(reopened.disabledToolNames()).containsExactly("executeQuery");
        } finally {
            close(second);
        }
    }

    /** 表名是 DDL、查询、以及回放 WARN 共用的那一个常量，跨包可见（回放的 WARN 要报出表名）。 */
    @Test
    void tableNameConstantMatchesTheDdl() {
        assertThat(ToolToggleRepository.TABLE_NAME).isEqualTo("mcp_tool_toggle");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + ToolToggleRepository.TABLE_NAME, Integer.class)).isZero();
    }
}
