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
package com.entropy.database.mcp.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MetadataDiskStore} 的落盘与预热。
 *
 * <p>这些用例都显式调用 {@link MetadataDiskStore#flush()}，不依赖后台定时线程——定时只决定
 * 「什么时候写」，写的内容对不对才是这里要钉住的东西。
 */
class MetadataDiskStoreTest {

    private static final String SCOPE = "\u0000";

    @Test
    @DisplayName("落盘再读回，连接作用域与键都还原")
    void roundTripsScopedEntries(@TempDir Path dir) {
        MetadataDiskStore store = new MetadataDiskStore(dir, Duration.ofSeconds(1));
        store.start(() -> Map.of(
                "qditp" + SCOPE + "tables:QDITP", List.of(Map.of("TABLE_NAME", "T1")),
                "other" + SCOPE + "schemas", List.of("PUBLIC")));
        store.flush();

        Map<String, Object> reloaded = new MetadataDiskStore(dir, Duration.ofSeconds(1)).load();

        assertThat(reloaded).containsOnlyKeys("qditp" + SCOPE + "tables:QDITP", "other" + SCOPE + "schemas");
        assertThat(reloaded.get("other" + SCOPE + "schemas")).isEqualTo(List.of("PUBLIC"));
        store.shutdown();
    }

    @Test
    @DisplayName("每个连接一个文件，删一个连接的缓存就是删一个文件")
    void writesOneFilePerConnection(@TempDir Path dir) throws Exception {
        MetadataDiskStore store = new MetadataDiskStore(dir, Duration.ofSeconds(1));
        store.start(() -> Map.of(
                "a" + SCOPE + "schemas", List.of("S"),
                "b" + SCOPE + "schemas", List.of("S")));
        store.flush();

        try (var files = Files.list(dir)) {
            assertThat(files.toList()).hasSize(2);
        }
        store.shutdown();
    }

    /**
     * 连接名是调用方给的，会进文件名。{@code ../} 这种必须被折叠掉而不是当成路径片段用——
     * 否则「连接名」就是一个任意写文件的入口。
     */
    @Test
    @DisplayName("连接名里的路径分隔符被折叠，文件始终落在缓存目录内")
    void sanitizesConnectionNamesIntoTheCacheDirectory(@TempDir Path dir) {
        MetadataDiskStore store = new MetadataDiskStore(dir, Duration.ofSeconds(1));

        Path file = store.fileFor("../../etc/passwd");

        assertThat(file.getParent()).isEqualTo(dir);
        assertThat(file.getFileName().toString()).doesNotContain("/").doesNotContain("..");
    }

    @Test
    @DisplayName("不同连接名即使净化后同名，也不会写同一个文件")
    void differentNamesNeverShareAFile(@TempDir Path dir) {
        MetadataDiskStore store = new MetadataDiskStore(dir, Duration.ofSeconds(1));

        assertThat(store.fileFor("a/b")).isNotEqualTo(store.fileFor("a:b"));
    }

    @Test
    @DisplayName("目录不存在时 load 返回空，而不是报错")
    void loadFromMissingDirectoryIsEmpty(@TempDir Path dir) {
        assertThat(new MetadataDiskStore(dir.resolve("not-created"), Duration.ofSeconds(1)).load())
                .isEmpty();
    }

    @Test
    @DisplayName("读不懂的文件被跳过，其他连接照常预热")
    void skipsUnreadableFilesAndKeepsGoing(@TempDir Path dir) throws Exception {
        MetadataDiskStore store = new MetadataDiskStore(dir, Duration.ofSeconds(1));
        store.start(() -> Map.of("good" + SCOPE + "schemas", List.of("S")));
        store.flush();
        Files.writeString(dir.resolve("truncated.json"), "{\"connection\":\"x\",\"entr");

        assertThat(new MetadataDiskStore(dir, Duration.ofSeconds(1)).load())
                .containsOnlyKeys("good" + SCOPE + "schemas");
        store.shutdown();
    }

    @Test
    @DisplayName("缓存清空后重新落盘，旧文件被清掉——不会留下一份复活的过期元数据")
    void prunesFilesForScopesThatWentEmpty(@TempDir Path dir) throws Exception {
        Map<String, Object>[] snapshot = new Map[]{Map.of("gone" + SCOPE + "schemas", List.of("S"))};
        MetadataDiskStore store = new MetadataDiskStore(dir, Duration.ofSeconds(1));
        store.start(() -> snapshot[0]);
        store.flush();
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).hasSize(1);
        }

        snapshot[0] = Map.of();
        store.flush();

        try (var files = Files.list(dir)) {
            assertThat(files.toList()).isEmpty();
        }
        store.shutdown();
    }

    /**
     * 刻意不显式 flush，只调 {@code shutdown()}：曾经 {@code shutdown()} 先 {@code clear()} 再让磁盘
     * 存储做收尾刷盘，于是那次刷盘读到的是一个空快照，把磁盘上的副本覆盖成空——重启后无可预热。
     */
    @Test
    @DisplayName("DatabaseCacheImpl 带上磁盘存储时，shutdown 会落盘、重启即预热")
    void databaseCachePrewarmsFromDisk(@TempDir Path dir) {
        DatabaseCacheImpl warm = new DatabaseCacheImpl(100, Duration.ofMinutes(1),
                Duration.ofMinutes(1), new MetadataDiskStore(dir, Duration.ofSeconds(1)));
        new ConnectionScopedCache(warm, "qditp").putMetadata("schemas", List.of("QDITP"));
        warm.shutdown();

        DatabaseCacheImpl restarted = new DatabaseCacheImpl(100, Duration.ofMinutes(1),
                Duration.ofMinutes(1), new MetadataDiskStore(dir, Duration.ofSeconds(1)));

        assertThat(new ConnectionScopedCache(restarted, "qditp").getMetadata("schemas"))
                .isEqualTo(List.of("QDITP"));
        restarted.shutdown();
    }

    @Test
    @DisplayName("没有磁盘存储时行为不变，统计里也如实写明没开")
    void staysInMemoryWithoutADiskStore() {
        DatabaseCacheImpl cache = new DatabaseCacheImpl(100, Duration.ofMinutes(1), Duration.ofMinutes(1));

        cache.putMetadata("schemas", List.of("PUBLIC"));

        assertThat(cache.getMetadata("schemas")).isEqualTo(List.of("PUBLIC"));
        assertThat(cache.getStatistics()).containsEntry("metadataDiskPersistence", false);
    }
}
