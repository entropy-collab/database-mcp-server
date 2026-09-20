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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 调用者身份清单：一个进程外的凭据文件。
 *
 * <p><b>为什么是文件，而不是 {@code application.yml} 里的一段 list。</b>
 * {@link SecurityConfig#requireNonBlankPassword} 那段注释已经定过一次同类的调子——管理员密码
 * 刻意不走 Spring {@code Environment}，就是为了让它没法写进 yml 并被提交进仓库。这里的口令是
 * bcrypt 哈希而非原文，但哈希仍然可以离线爆破，而且用户清单会随人数增长。把它留在版本控制之外
 * 是同一个决定的延续，不是新立的规矩。
 *
 * <p><b>为什么带类型。</b>每行的第二个字段是主体类型（{@code user} / {@code agent} /
 * {@code service}）。MCP 的调用方经常不是人——可能是一个 AI agent，也可能是定时任务。
 * 类型从第一天就显式写出来，是因为它之后会成为授权判定里的主体类型，而"所有登录用户可读"和
 * "所有 agent 可读"是两回事；等到有了策略才去补类型，已有的清单就没法区分了。
 *
 * <p><b>文件格式。</b>每行 {@code username:type:bcrypt-hash}，{@code #} 起头是注释，空行忽略。
 * bcrypt 哈希里不会出现 {@code :}，所以冒号分隔是安全的；用户名里出现冒号会被当成格式错误拒掉。
 *
 * <pre>
 * # 生成哈希：htpasswd -nbBC 10 "" 'your-password' | tr -d ':\n' | sed 's/^\$2y/\$2a/'
 * zhangsan:user:$2a$10$N9qo8uLOickgx2ZMRZoMye...
 * claude-agent:agent:$2a$10$XURPShQNCsLjp1ESc2...
 * </pre>
 *
 * <p><b>不接受原文口令。</b>哪怕只是为了本地方便。一个能读到这个文件的人等于拿到了所有人的
 * 凭据，而这个服务背后挂着带 DDL 权限的连接。
 */
final class UserFile {

    private UserFile() {
    }

    /**
     * 一条身份。
     *
     * @param username     登录名，也是授权判定里的主体 id
     * @param type         主体类型
     * @param passwordHash bcrypt 哈希
     */
    record Entry(String username, String type, String passwordHash) {}

    /**
     * 读取并校验凭据文件。
     *
     * <p>任何一行有问题就整体失败，不做"跳过坏行继续"：一条被静默跳过的记录，症状是某个人
     * 突然登不上，而日志里只有一行 warn。启动失败是当场能看见的信号。
     *
     * @throws IllegalStateException 文件不存在、读不了、权限过宽，或任意一行格式非法
     */
    static List<Entry> load(Path path) {
        requireNotGroupOrWorldReadable(path);
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "读取用户凭据文件失败: " + path + "（entropy.mcp.security.users-file）", e);
        }
        return parse(lines, path.toString());
    }

    /** 解析逻辑与文件 IO 分开，好让格式的每种错法都能被单测钉住。 */
    static List<Entry> parse(List<String> lines, String source) {
        var entries = new ArrayList<Entry>();
        var seen = new LinkedHashSet<String>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).strip();
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            int lineNumber = i + 1;
            String[] parts = raw.split(":", 3);
            if (parts.length != 3) {
                throw invalid(source, lineNumber,
                        "每行必须是 username:type:bcrypt-hash 三段，实际分出 " + parts.length + " 段");
            }
            String username = parts[0].strip();
            String type = parts[1].strip().toLowerCase(Locale.ROOT);
            String hash = parts[2].strip();

            if (username.isEmpty()) {
                throw invalid(source, lineNumber, "用户名不能为空");
            }
            try {
                type = Credentials.requireKnownType(type, "");
                Credentials.requireBcrypt(hash, "");
            } catch (IllegalArgumentException e) {
                // 校验规则与数据库里的用户表共用（见 Credentials），这里只补上"哪一行"
                throw invalid(source, lineNumber, e.getMessage().replaceFirst("^：", ""));
            }
            if (!seen.add(username)) {
                // 重复的用户名下，"哪一条生效"取决于加载顺序，而那不该是安全行为的决定因素
                throw invalid(source, lineNumber, "用户名 '" + username + "' 重复");
            }
            entries.add(new Entry(username, type, hash));
        }
        return List.copyOf(entries);
    }

    /**
     * 拒绝同组或其他人可读的凭据文件。
     *
     * <p>这个文件里是全部调用者的口令哈希，而这个服务背后挂着带 DDL 权限的连接。检查放在启动期，
     * 因为文件权限是部署时最容易漏的一步，而漏掉之后没有任何运行时症状。
     *
     * <p>非 POSIX 文件系统（Windows）拿不到权限位，此时跳过而不是失败——在那里硬性要求会让服务
     * 直接起不来，而这项检查本身是加固而非正确性。
     */
    private static void requireNotGroupOrWorldReadable(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "用户凭据文件不存在: " + path + "（entropy.mcp.security.users-file）");
        }
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(path);
        } catch (UnsupportedOperationException e) {
            return;
        } catch (IOException e) {
            throw new IllegalStateException("读取凭据文件权限失败: " + path, e);
        }
        var tooOpen = new LinkedHashSet<PosixFilePermission>();
        for (var permission : List.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE)) {
            if (permissions.contains(permission)) {
                tooOpen.add(permission);
            }
        }
        if (!tooOpen.isEmpty()) {
            throw new IllegalStateException(
                    "用户凭据文件 " + path + " 的权限过宽（" + tooOpen + "）：它包含全部调用者的口令哈希。"
                    + "请执行 chmod 600 " + path);
        }
    }

    private static IllegalStateException invalid(String source, int lineNumber, String reason) {
        return new IllegalStateException(source + " 第 " + lineNumber + " 行非法：" + reason);
    }
}
