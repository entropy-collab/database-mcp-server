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
package com.entropy.database.mcp.cli;

import com.entropy.database.mcp.credential.CredentialCipher;
import com.entropy.database.mcp.credential.SealedCredential;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 运维侧的密钥生成与凭证封装工具。服务端运行期用不到它，它也不启动任何 Spring 上下文。
 *
 * <h2>怎么运行</h2>
 * 打出来的是 Spring Boot 可执行 jar，主类被 repackage 固定成应用入口，所以要借 {@code PropertiesLauncher}
 * 换主类：
 * <pre>
 * # 1. 生成密钥对（私钥写文件给服务端，公钥打在 stdout 留在运维手上）
 * java -cp database-mcp-server-0.5.2.jar \
 *      -Dloader.main=com.entropy.database.mcp.cli.CredentialSealCli \
 *      org.springframework.boot.loader.launch.PropertiesLauncher keygen --out mcp-credential-private.key
 *
 * # 2. 封装一份凭证（口令从标准输入读，不走命令行参数）
 * java -cp database-mcp-server-0.5.2.jar \
 *      -Dloader.main=com.entropy.database.mcp.cli.CredentialSealCli \
 *      org.springframework.boot.loader.launch.PropertiesLauncher \
 *      seal --public-key public.key --name orders-prod \
 *           --url 'jdbc:postgresql://db:5432/orders' --username app_ro --ttl 15m
 * </pre>
 *
 * <h2>口令为什么只从标准输入读</h2>
 * 命令行参数在同一台机器上对所有用户可见（{@code ps -ef}、{@code /proc/<pid>/cmdline}），
 * 还会落进 shell 历史。把 {@code --password} 做成参数就是把「不让口令泄漏」这件事在第一步就破功，
 * 所以这里没有这个参数：有终端时走无回显读取，被管道喂入时读一行。
 *
 * <h2>私钥为什么不走标准输出</h2>
 * 同一个理由的另一半。私钥是这套机制的<b>全部</b>安全性所在（拿到它就能解开所有密文），而 stdout
 * 会留在 CI 的构建日志、{@code tee} 出来的文件和终端回滚缓冲里——这些地方谁都不会回头去清。
 * 所以 {@code keygen} 把私钥直接写进 {@code --out} 指定的文件并把权限收到 {@code 600}，
 * stdout 只留公钥和一行「私钥在哪」。这里没有做「打到 stdout 供管道重定向」的选项：
 * 那等于把「会不会泄漏」交给每个调用者的 shell 写法，而这条路上一次疏忽的代价是全部凭证。
 */
public final class CredentialSealCli {

    private static final int DEFAULT_KEY_BITS = 3072;

    /** 没给 {@code --out} 时私钥的落点。刻意是个相对路径：写在当前目录，不去猜运维的目录结构。 */
    private static final String DEFAULT_PRIVATE_KEY_FILE = "mcp-credential-private.key";

    /** 私钥文件权限：只有属主可读写。 */
    private static final String PRIVATE_KEY_PERMISSIONS = "rw-------";

    private CredentialSealCli() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        switch (command) {
            case "keygen" -> keygen(args);
            case "seal" -> seal(args);
            default -> {
                usage();
                // 非零退出码，让脚本里的 `set -e` 能发现「命令名打错了」。
                System.exit(args.length == 0 ? 1 : 2);
            }
        }
    }

    private static void keygen(String[] args) throws Exception {
        // 位数仍接受裸参数（`keygen 4096`）：这是这个子命令原来的唯一参数形态，
        // 而 parseOptions 只认 --xxx。--bits 同时也收，两种写法都不至于让人当场卡住。
        int bits = DEFAULT_KEY_BITS;
        Path out = Path.of(DEFAULT_PRIVATE_KEY_FILE);
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(requireValue(args, ++i, "--out"));
                case "--bits" -> bits = Integer.parseInt(requireValue(args, ++i, "--bits"));
                default -> {
                    if (args[i].startsWith("--")) {
                        throw new IllegalArgumentException("无法识别的参数：" + args[i]);
                    }
                    bits = Integer.parseInt(args[i]);
                }
            }
        }
        if (bits < CredentialCipher.MINIMUM_KEY_BITS) {
            throw new IllegalArgumentException("密钥长度下限是 " + CredentialCipher.MINIMUM_KEY_BITS + " 位");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(CredentialCipher.KEY_ALGORITHM);
        generator.initialize(bits);
        KeyPair pair = generator.generateKeyPair();
        Base64.Encoder encoder = Base64.getEncoder();

        // 私钥先落盘（带权限），再打印任何东西：写失败时 stdout 上不该出现「已生成」这种误导。
        writePrivateKeyFile(out, "MCP_CREDENTIAL_PRIVATE_KEY="
                + encoder.encodeToString(pair.getPrivate().getEncoded()) + System.lineSeparator());

        System.out.println("# 私钥已写入 " + out.toAbsolutePath() + "（权限 "
                + PRIVATE_KEY_PERMISSIONS + "），文件内容就是一行环境变量赋值，可直接 source 或喂给密钥管理器。");
        System.out.println("# 它不会被打印到这里：stdout 会留在 CI 日志与终端回滚缓冲里。注入服务端后请删掉该文件。");
        System.out.println();
        System.out.println("# 公钥（X.509, base64）——运维用它封装凭证，可以随意传播");
        System.out.println(encoder.encodeToString(pair.getPublic().getEncoded()));
        System.out.println();
        System.out.println("# 服务端配置：entropy.mcp.security.credential-cipher.private-key=${MCP_CREDENTIAL_PRIVATE_KEY}");
    }

    /**
     * 写私钥文件：<b>先</b>用 600 创建，<b>再</b>写内容。
     *
     * <p>顺序是关键——先 {@code Files.write} 再 {@code setPosixFilePermissions} 会留下一个短暂的
     * 「私钥已在磁盘上、权限还是 umask 默认」的窗口，同机器上的其他用户在那个窗口里读得到。
     * 已存在的同名文件先删掉重建，因为创建时给的属性对已存在的文件不生效，旧权限会被沿用。
     *
     * <p>非 POSIX 文件系统（Windows、某些容器挂载）上 {@code asFileAttribute} 会抛
     * {@link UnsupportedOperationException}。那里降级成「用 File 的属主位尽力收紧 + 打一句提示」，
     * 而不是直接崩：崩了运维多半会退回「私钥打到 stdout」那种更糟的做法。
     */
    private static void writePrivateKeyFile(Path path, String content) throws java.io.IOException {
        Files.deleteIfExists(path);
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(PRIVATE_KEY_PERMISSIONS)));
        } catch (UnsupportedOperationException e) {
            Files.createFile(path);
            java.io.File file = path.toFile();
            boolean hardened = file.setReadable(false, false)
                    && file.setWritable(false, false)
                    && file.setReadable(true, true)
                    && file.setWritable(true, true);
            if (!hardened) {
                System.out.println("# 警告：当前文件系统不支持 POSIX 权限，未能把 " + path.toAbsolutePath()
                        + " 收紧到仅属主可读。请自行确认它不可被其他用户读取。");
            }
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void seal(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        String publicKeySource = required(options, "public-key");
        String name = required(options, "name");
        String jdbcUrl = required(options, "url");
        String username = required(options, "username");
        Duration ttl = options.containsKey("ttl") ? parseDuration(options.get("ttl")) : null;

        char[] password = readPassword();
        try {
            SealedCredential credential = new SealedCredential(
                    CredentialCipher.CURRENT_VERSION, name, username, new String(password),
                    CredentialCipher.fingerprint(jdbcUrl),
                    ttl == null ? null : Instant.now().plus(ttl));
            String sealed = CredentialCipher.seal(readKeyMaterial(publicKeySource), credential);

            // 把绑定内容原样回显：URL 差一个字符就会被服务端拒绝，而运维手里往往有好几个相似的 URL。
            System.out.println("# 绑定的连接名：" + name);
            System.out.println("# 绑定的 jdbcUrl（调用方必须逐字符一致）：" + jdbcUrl);
            System.out.println("# 数据库账号：" + username);
            System.out.println("# 有效期：" + (ttl == null
                    ? "无（只能用于服务端配置 entropy.mcp.database.connections，运行时注册会拒绝）"
                    : ttl + "，到 " + Instant.now().plus(ttl)));
            System.out.println();
            System.out.println(sealed);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** 有终端就无回显读，否则读一行——后者是给 CI/脚本用 {@code echo … | java …} 的形态。 */
    private static char[] readPassword() throws Exception {
        var console = System.console();
        if (console != null) {
            char[] password = console.readPassword("数据库口令（不回显）：");
            if (password == null || password.length == 0) {
                throw new IllegalArgumentException("口令不能为空");
            }
            return password;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null || line.isEmpty()) {
                throw new IllegalArgumentException("没有从标准输入读到口令");
            }
            return line.toCharArray();
        }
    }

    /** 参数值既可以是密钥文件路径，也可以是 base64 字符串本身。 */
    private static java.security.PublicKey readKeyMaterial(String source) throws Exception {
        Path path = Path.of(source);
        String material = Files.exists(path) ? Files.readString(path) : source;
        return CredentialCipher.readPublicKey(material);
    }

    /** {@code 15m} / {@code 2h} / {@code 30s} / {@code PT15M} 都接受。 */
    static Duration parseDuration(String value) {
        String text = value.trim();
        if (text.regionMatches(true, 0, "P", 0, 1)) {
            return Duration.parse(text);
        }
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return switch (Character.toLowerCase(text.charAt(text.length() - 1))) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException(
                    "无法识别的时长：" + value + "（用 15m / 2h / 7d 或 ISO-8601 的 PT15M）");
        };
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数：" + argument);
            }
            String key = argument.substring(2);
            if (key.equals("password")) {
                throw new IllegalArgumentException(
                        "口令不接受命令行参数（ps 可见、会进 shell 历史），请从标准输入提供。");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("--" + key + " 后面缺少取值");
            }
            options.put(key, args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数 --" + key);
        }
        return value;
    }

    /** keygen 自己解析参数（不走 parseOptions，它只认 --xxx），所以取值缺失要自己挡。 */
    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " 后面缺少取值");
        }
        return args[index];
    }

    private static void usage() {
        System.out.println("""
                用法：
                  keygen [bits] [--out <私钥文件>]        生成 RSA 密钥对（默认 3072 位，下限 2048）
                                                         私钥写入 --out 指定的文件（默认
                                                         ./mcp-credential-private.key，权限 600），
                                                         绝不打印到标准输出；公钥打在标准输出上
                  seal --public-key <文件或base64>        封装一份密文凭证，口令从标准输入读
                       --name <连接名>
                       --url <jdbcUrl>
                       --username <数据库账号>
                       [--ttl 15m]                       不传 ttl 的密文只能用于服务端配置，
                                                         运行时注册（createSealedConnection）会拒绝
                """);
    }
}
