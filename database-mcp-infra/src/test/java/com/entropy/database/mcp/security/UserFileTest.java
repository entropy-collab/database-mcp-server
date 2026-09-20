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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭据文件的解析与校验。
 *
 * <p>这个文件格式是调用者身份的<b>唯一入口</b>——每一种格式错误与违规都必须被断言钉住，
 * 而不是靠"文件格式简单所以大概不会写错"。特别是"原文口令被拒"与"权限过宽被拒"：
 * 两者的失败形态都是"文件写好了、服务起不来"，而在那个时刻的唯一线索就是异常信息。
 */
class UserFileTest {

    private static final String VALID_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Test
    void parsesAValidFile() {
        var entries = UserFile.parse(List.of(
                "# this is a comment",
                "",
                "zhangsan:user:" + VALID_HASH,
                "  claude-agent : agent : " + VALID_HASH,
                "# another comment",
                "etl-job:service:" + VALID_HASH), "test");

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).username()).isEqualTo("zhangsan");
        assertThat(entries.get(0).type()).isEqualTo("user");
        assertThat(entries.get(0).passwordHash()).isEqualTo(VALID_HASH);
        assertThat(entries.get(1).username()).isEqualTo("claude-agent");
        assertThat(entries.get(1).type()).isEqualTo("agent");
        assertThat(entries.get(2).type()).isEqualTo("service");
    }

    @Test
    void typeIsCaseInsensitive() {
        var entries = UserFile.parse(List.of("x:USER:" + VALID_HASH), "test");

        assertThat(entries.getFirst().type()).isEqualTo("user");
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> UserFile.parse(List.of("x:admin:" + VALID_HASH), "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("主体类型")
                .hasMessageContaining("admin");
    }

    @Test
    void rejectsPlaintextPassword() {
        assertThatThrownBy(() -> UserFile.parse(List.of("x:user:hunter2"), "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bcrypt");
    }

    @Test
    void rejectsEmptyUsername() {
        assertThatThrownBy(() -> UserFile.parse(List.of(":user:" + VALID_HASH), "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("用户名不能为空");
    }

    @Test
    void rejectsDuplicateUsername() {
        assertThatThrownBy(() -> UserFile.parse(List.of(
                "x:user:" + VALID_HASH,
                "x:agent:" + VALID_HASH), "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void rejectsMalformedLine() {
        assertThatThrownBy(() -> UserFile.parse(List.of("just-a-name"), "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("三段");
    }

    @Test
    void emptyFileProducesNoEntries() {
        assertThat(UserFile.parse(List.of(), "test")).isEmpty();
        assertThat(UserFile.parse(List.of("# only a comment"), "test")).isEmpty();
    }

    @Test
    void loadRejectsNonexistentFile() {
        assertThatThrownBy(() -> UserFile.load(Path.of("/tmp/does-not-exist-" + System.nanoTime())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void loadAcceptsOwnerOnlyPermissions(@TempDir Path dir) throws IOException {
        var file = dir.resolve("users");
        Files.writeString(file, "alice:user:" + VALID_HASH);
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            return;
        }
        var entries = UserFile.load(file);

        assertThat(entries).hasSize(1);
    }

    @Test
    void loadRejectsGroupOrWorldReadable(@TempDir Path dir) throws IOException {
        var file = dir.resolve("users");
        Files.writeString(file, "alice:user:" + VALID_HASH);
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException e) {
            return;
        }
        assertThatThrownBy(() -> UserFile.load(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限过宽")
                .hasMessageContaining("chmod 600");
    }

    /** bcrypt 的三种前缀都接受——不同工具生成的哈希可能以 {@code $2b$} 或 {@code $2y$} 开头。 */
    @Test
    void acceptsAllBcryptPrefixes() {
        for (String prefix : List.of("$2a$", "$2b$", "$2y$")) {
            String hash = prefix + "10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
            var entries = UserFile.parse(List.of("x:user:" + hash), "test");
            assertThat(entries).hasSize(1);
        }
    }

    /** 错误信息必须包含行号——凭据文件可能有几十行，"某一行非法"不够定位。 */
    @Test
    void errorMessageIncludesLineNumber() {
        assertThatThrownBy(() -> UserFile.parse(List.of(
                "good:user:" + VALID_HASH,
                "bad:user:plaintext"), "myfile.txt"))
                .hasMessageContaining("myfile.txt")
                .hasMessageContaining("第 2 行");
    }
}
