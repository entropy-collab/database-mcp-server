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
package com.entropy.database.mcp.credential;

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * 密文凭证的开封与封装：服务端持<b>私钥</b>解密，运维侧持<b>公钥</b>加密。
 *
 * <h2>为什么方向和 Druid 相反</h2>
 * Druid 的 {@code ConfigTools} 是用私钥加密、把公钥写进配置来解密（签名式 RSA 反用）。
 * 那套方向下，任何拿到配置文件的人都持有解密所需的全部材料，密文只是「不易被肉眼看懂」，
 * 拦不住任何愿意跑一行代码的人——它防的是运维截图和日志误贴，不是攻击者。本服务的威胁模型不同：
 * 密文要经过<b>模型和对话历史</b>，也就是要经过一段我们无法擦除的存储。所以必须反过来——
 * 私钥只在服务端进程里（{@code entropy.mcp.security.credential-cipher.private-key}，走环境变量注入），
 * 公钥可以随便发给谁；拿到密文的人无法还原口令。
 *
 * <h2>为什么用 OAEP 而不是 PKCS#1 v1.5</h2>
 * {@code RSA/ECB/PKCS1Padding} 存在 Bleichenbacher 式的填充预言攻击：攻击者反复投喂改造过的密文，
 * 从「解密失败」和「解密成功但内容不对」的差异里逐字节恢复明文。本服务的解密入口正是一个
 * 可被反复调用的 MCP 工具，这条攻击面是真实存在的。OAEP(SHA-256) 是标准的抗填充预言方案。
 * 另外所有解密失败都归一成同一句错误（见 {@link #decrypt}），不给攻击者任何可区分信号。
 *
 * <h2>容量</h2>
 * RSA-OAEP(SHA-256) 单次明文上限是 {@code keyBytes - 2*32 - 2}：2048 位密钥 = 190 字节，
 * 3072 位 = 318 字节。载荷 JSON 的键刻意用单字母，把骨架压到 40 字节上下，
 * 2048 位下大约还剩 100 字节给「连接名 + 账号 + 口令」。超了会在封装阶段直接报错，
 * 而不是在服务端解密时才发现。
 *
 * <h2>两个开封入口，不是一个</h2>
 * 绑定（连接名 + jdbcUrl 指纹）在<b>运行时</b>那条路上是必需项，在<b>服务端配置</b>那条路上是可选项。
 * 这个差别刻意做成两个方法（{@link #openForRuntime} / {@link #openForStartup}）而不是一个带布尔开关
 * 或「看 maxTtl 是否为 null」的参数搭便车：后者曾经就是个 fail-open 缺陷——密文里不带绑定字段时
 * 整段校验被跳过，于是任何人拿一份无绑定密文就能把 jdbcUrl 指向自己控制的假库，把服务端变成
 * 「解密并投递口令」的服务。安全条件必须由调用点<b>显式</b>选择，且选错时是编译期能看见的方法名，
 * 而不是某个参数的取值。
 *
 * <h2>没有做一次性消费</h2>
 * 同一份密文在有效期内可以重复使用。做成一次性需要服务端记住已用过的密文（进程内存记录会在重启后
 * 全部失效，等于所有密文提前作废；外置存储又要引入表结构，见项目里「进程内状态外置」那笔待办）。
 * 更实际的问题是：BYOK 连接的租约会过期，过期后调用方需要用同一份凭证重新注册——一次性密文会让
 * 「一小时后接着干活」变成必须回去找运维。因此选择了「短有效期 + 连接名绑定 + URL 绑定」这组约束，
 * 把重放限制在「同一台库、同一个连接名、几分钟内」，代价是这个窗口内确实可重放。
 */
public final class CredentialCipher {

    /** OAEP 的 MGF1 与摘要都取 SHA-256；写全串是为了不依赖 JCE provider 的默认值。 */
    public static final String TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    public static final String KEY_ALGORITHM = "RSA";

    /** 当前载荷格式版本。解出别的值一律拒绝，不做「尽力兼容」。 */
    public static final int CURRENT_VERSION = 1;

    /**
     * 密钥长度下限。1024 位 RSA 已在可负担的算力范围内被分解过，而这里保护的是生产库口令。
     * 拒绝比警告好：警告会被忽略，而这行配置一旦跑起来就不会再有人回头看。
     */
    public static final int MINIMUM_KEY_BITS = 2048;

    /** URL 指纹取 SHA-256 的前 16 字节。留 128 位抗碰撞，同时只占 22 个 base64url 字符。 */
    private static final int FINGERPRINT_BYTES = 16;

    /**
     * 载荷的 JSON 读写器。
     *
     * <p>刻意关掉 {@code INCLUDE_SOURCE_IN_LOCATION}：开着时 Jackson 会把源片段拼进异常消息
     * （{@code at [Source: (byte[])"{"v":1,"p":"…"}"]}），而解析这一步的输入是<b>已成功解密的口令
     * JSON</b>。整个类为了不让口令进日志做了 toString 覆写、异常不挂 cause、明文字节清零，
     * 一条带源片段的 WARN 就能把这些全部作废。日志里只留异常类型名（见 {@link #decrypt}）。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CredentialCipher.class);

    private final PrivateKey privateKey;

    /**
     * @param privateKey PKCS#8 私钥，通常由 {@link #readPrivateKey} 解析而来
     * @throws IllegalArgumentException 密钥不是 RSA，或长度低于 {@link #MINIMUM_KEY_BITS}
     */
    public CredentialCipher(PrivateKey privateKey) {
        this.privateKey = requireStrongRsaKey(privateKey);
    }

    /**
     * 运行时注册（{@code createSealedConnection}）这条路的开封入口：绑定与有效期都是<b>必需项</b>。
     *
     * <p>这条路的密文经过模型和对话历史，也就是经过一段擦不掉的存储，等同一枚 bearer token。
     * 所以密文里必须同时封着连接名与 jdbcUrl 指纹：缺任何一个就直接拒绝，而不是「没封就不校验」。
     * 后者是最典型的 fail-open——攻击者只要用一份不带绑定的密文，就能把 jdbcUrl 指向自己控制的
     * 假数据库，让服务端解出明文口令并当成登录凭证发过去。
     *
     * @param sealedBase64   base64 密文（标准字母表；允许含空白与换行，会先剔除）
     * @param connectionName 本次要注册的连接名，必须与密文里封的那个一致（不区分大小写）
     * @param jdbcUrl        本次要连的 JDBC URL，必须与封装时的完全一致（逐字节比对指纹）
     * @param maxTtl 有效期上限，必须非 {@code null}；密文的剩余有效期不得超过它
     * @throws McpToolException 解密失败、版本不符、缺绑定、绑定不符或已过期
     */
    public SealedCredential openForRuntime(String sealedBase64, String connectionName, String jdbcUrl,
                                           Duration maxTtl) {
        if (maxTtl == null) {
            // 传 null 只会是调用点写错。真按「不要求有效期」执行，就等于悄悄把运行时那条路降级成
            // 长期有效的密文，而这正是这个方法存在的理由的反面。
            throw new IllegalArgumentException(
                    "运行时注册必须给出有效期上限（entropy.mcp.security.credential-cipher.max-ttl）。");
        }
        return open(sealedBase64, connectionName, jdbcUrl, maxTtl, true);
    }

    /**
     * 服务端配置（{@code entropy.mcp.database.connections.*.password}）这条路的开封入口：
     * 允许密文不带任何绑定，也允许不带有效期。
     *
     * <p>为什么只有这条路可以放宽：这里的密文写在服务端配置里，不经过模型、不进对话历史，
     * 能拿到它的人本来就已经能读服务端配置；而它必须扛住进程重启，一旦带上短有效期，
     * 重启就等于全部连接作废。放宽的是「密文自身是否声明绑定」，不放宽「声明了就必须相符」——
     * 带绑定的配置密文照样逐字节核对。
     *
     * @param maxTtl 有效期上限；{@code null} 表示不要求密文带有效期。带了就仍按上限与过期时刻校验
     * @throws McpToolException 解密失败、版本不符、绑定不符或已过期
     */
    public SealedCredential openForStartup(String sealedBase64, String connectionName, String jdbcUrl,
                                           Duration maxTtl) {
        return open(sealedBase64, connectionName, jdbcUrl, maxTtl, false);
    }

    /**
     * 启动路开封的旧名字。
     *
     * @deprecated 用 {@link #openForStartup}（配置那条路）或 {@link #openForRuntime}（工具那条路）。
     *         保留这个别名只是因为「哪条路允许无绑定」这件事以前不在方法名里，调用点还没全部迁完；
     *         它刻意等价于放宽的那一条，因为迁移期里把某个调用点静默<b>收紧</b>成运行时语义
     *         会让本来能启动的部署起不来，而反过来只是维持现状。
     */
    @Deprecated(since = "0.5.3", forRemoval = true)
    public SealedCredential open(String sealedBase64, String connectionName, String jdbcUrl,
                                Duration maxTtl) {
        return openForStartup(sealedBase64, connectionName, jdbcUrl, maxTtl);
    }

    /**
     * 解开一份密文凭证，并核对它与本次调用的绑定关系。
     *
     * @param requireBindings {@code true} 时密文必须自带连接名与 jdbcUrl 指纹（运行时那条路）；
     *                        {@code false} 时允许缺失（服务端配置那条路）。这个开关不对外暴露，
     *                        外面只有两个语义明确的入口，免得又出现「调用方传错一个参数就 fail-open」
     */
    private SealedCredential open(String sealedBase64, String connectionName, String jdbcUrl,
                                Duration maxTtl, boolean requireBindings) {
        SealedCredential credential = decrypt(sealedBase64);

        if (credential.version() != CURRENT_VERSION) {
            throw reject("密文的格式版本是 " + credential.version() + "，本服务只认 " + CURRENT_VERSION
                    + "。请用当前版本的封装工具重新生成。");
        }
        if (credential.username() == null || credential.username().isBlank()) {
            throw reject("密文里没有数据库账号。请重新封装。");
        }
        if (credential.password() == null || credential.password().isEmpty()) {
            throw reject("密文里没有数据库口令。请重新封装。");
        }

        if (requireBindings) {
            // 「没封绑定」在这条路上是拒绝的理由，不是跳过校验的理由。两条分开报，是因为运维要
            // 知道该补哪一个——这不给攻击者任何额外信号：能读到这句话的人本来就已经持有这份密文。
            if (credential.connectionName() == null || credential.connectionName().isBlank()) {
                throw reject("密文里没有绑定连接名，运行时注册这条路不接受无绑定的密文。"
                        + "请让运维用 CredentialSealCli seal --name 重新封装。");
            }
            if (credential.jdbcUrlFingerprint() == null || credential.jdbcUrlFingerprint().isBlank()) {
                throw reject("密文里没有绑定 jdbcUrl，运行时注册这条路不接受无绑定的密文——"
                        + "不绑 URL 的密文可以被指向任意一台数据库，等于让服务端把口令交出去。"
                        + "请让运维用 CredentialSealCli seal --url 重新封装。");
            }
        }

        // 连接名绑定：不区分大小写，因为连接名只是个引用标签，而运维手写时大小写极易不一致；
        // 但两边都不允许为空——空名意味着「这份密文可以叫任何名字」。
        if (credential.connectionName() != null
                && !credential.connectionName().equalsIgnoreCase(connectionName)) {
            throw reject("密文绑定的连接名与本次入参不一致：请用封装时约定的连接名注册。");
        }

        // URL 绑定走严格相等（对指纹而言就是逐字节相等），不做任何归一化。
        // 归一化会引入「哪些差异算同一个 URL」的隐性等价类，而这里放宽一点就等于允许把密文
        // 重定向到另一台主机——那正是本机制要防的攻击。宁可让运维复制封装时回显的那一串。
        if (credential.jdbcUrlFingerprint() != null
                && !credential.jdbcUrlFingerprint().equals(fingerprint(jdbcUrl))) {
            throw reject("密文绑定的 jdbcUrl 与本次入参不一致：密文只能用于封装时指定的那个数据库地址，"
                    + "请核对 URL 是否被改写（含参数、大小写与空白）。");
        }

        if (maxTtl != null) {
            if (credential.expiresAt() == null) {
                throw reject("密文没有有效期，运行时注册这条路不接受长期有效的密文。"
                        + "请封装时带上有效期（不超过 " + maxTtl + "）。");
            }
            if (credential.expiresAt().isAfter(Instant.now().plus(maxTtl))) {
                throw reject("密文的有效期超过服务端上限 " + maxTtl
                        + "（entropy.mcp.security.credential-cipher.max-ttl）。请缩短后重新封装。");
            }
        }
        // 已过期一律拒绝，与 maxTtl 是否要求有效期无关：服务端配置那条路不强制带有效期，
        // 但一旦运维给了有效期，过期后就该失效——否则「我给它设了 7 天」会变成一句空话。
        if (credential.expiresAt() != null && !credential.expiresAt().isAfter(Instant.now())) {
            throw reject("密文已过期（" + credential.expiresAt() + "）。请重新封装一份。");
        }
        return credential;
    }

    /**
     * 只解密、不做绑定校验。
     *
     * <p>所有失败——base64 不合法、填充不对、私钥不匹配、JSON 解不出来——都归一成同一句错误。
     * 区分它们会给出填充预言攻击所需的信号（详见类注释）。原始异常只进日志，不进返回值。
     *
     * <p>解密与 JSON 解析分成两个 {@code catch}，纯粹是为了日志能记多少：解密失败时输入还是密文，
     * 异常消息里没有秘密，可以整条记下来；而 JSON 解析这一步的输入是<b>已经解密出来的口令 JSON</b>，
     * Jackson 的异常消息会带上出错位置附近的源片段，一记就把口令写进日志。所以那一侧只记异常类型名。
     * 分开只影响日志，<b>不影响对外那句话</b>——两侧抛的都是同一句，否则「填充错误」和「JSON 非法」
     * 就成了两种可区分的响应，填充预言攻击要的正是这一位信号。
     */
    private SealedCredential decrypt(String sealedBase64) {
        if (sealedBase64 == null || sealedBase64.isBlank()) {
            throw reject("密文为空。");
        }
        byte[] plaintext = null;
        try {
            try {
                byte[] ciphertext = Base64.getDecoder()
                        .decode(sealedBase64.replaceAll("\\s", ""));
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, privateKey);
                plaintext = cipher.doFinal(ciphertext);
            } catch (RuntimeException | GeneralSecurityException e) {
                log.warn("Sealed credential could not be decrypted: {}", e.toString());
                throw undecryptable();
            }
            try {
                return JSON.readValue(plaintext, Payload.class).toCredential();
            } catch (RuntimeException | java.io.IOException e) {
                // 只记类型名：这一步的异常消息可能含明文口令片段（见 JSON 字段的注释）。
                log.warn("Sealed credential decrypted but its payload is not a valid v{} envelope: {}",
                        CURRENT_VERSION, e.getClass().getName());
                throw undecryptable();
            }
        } finally {
            // 明文口令的字节数组不留在堆上等 GC。这不是万能的（String 化之后仍在堆里），
            // 但省一份副本就少一处可能被 heap dump 带走的痕迹。
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /**
     * 解不开时的那唯一一句话。
     *
     * <p>刻意不挂 cause：MCP 的错误渲染会把 cause 的 message 一并回给调用方，那就等于把
     * 「填充错误」和「解出来但不是合法 JSON」这两种情况区分开——正是填充预言攻击需要的那一位信号。
     * 诊断信息留给运维看日志。
     */
    private static McpToolException undecryptable() {
        return new McpToolException(ErrorCode.SECURITY_VIOLATION,
                "密文无法解开：它不是用本服务的公钥加密的，或者已被截断/改写。");
    }

    /**
     * 用公钥封装一份密文凭证。给运维侧的封装工具用，服务端运行期不会调用。
     *
     * @throws IllegalArgumentException 载荷超出该密钥的容量上限（错误消息里给出确切字节数）
     */
    public static String seal(PublicKey publicKey, SealedCredential credential) {
        requireStrongRsaKey(publicKey);
        byte[] plaintext;
        try {
            plaintext = JSON.writeValueAsBytes(Payload.of(credential));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法序列化密文载荷", e);
        }
        int capacity = maxPayloadBytes(publicKey);
        if (plaintext.length > capacity) {
            throw new IllegalArgumentException("载荷 " + plaintext.length + " 字节，超过该密钥的上限 "
                    + capacity + " 字节。请缩短连接名/账号，或改用更长的密钥（3072 位可容纳 318 字节）。");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("封装失败：" + e.getMessage(), e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** OAEP(SHA-256) 下该密钥单次可加密的明文字节数。 */
    public static int maxPayloadBytes(PublicKey publicKey) {
        int keyBytes = (((RSAKey) publicKey).getModulus().bitLength() + 7) / 8;
        return keyBytes - 2 * 32 - 2;
    }

    /**
     * JDBC URL 的指纹：SHA-256 前 {@value #FINGERPRINT_BYTES} 字节的 base64url（无填充）。
     *
     * <p>存指纹而不是整串 URL，是因为 RSA 载荷只有一百多字节，而 JDBC URL 动辄七八十字节——
     * 塞进去就没有口令的位置了。指纹只用于「是不是同一个 URL」这个是非判断，不需要可还原。
     *
     * <p>输入按<b>原样</b>参与摘要，只去掉首尾空白：任何归一化都会放宽绑定
     * （详见 {@link #openForRuntime}）。
     */
    public static String fingerprint(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(jdbcUrl.trim().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Arrays.copyOf(digest, FINGERPRINT_BYTES));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 解析 PKCS#8 私钥。接受纯 base64，也接受带 {@code -----BEGIN PRIVATE KEY-----} 头尾的 PEM——
     * 后者是 {@code openssl} 的默认输出，要求运维手工剔掉头尾只会招来「删多了一个字符」的故障。
     */
    public static PrivateKey readPrivateKey(String encoded) {
        byte[] der = decodeKeyMaterial(encoded, "私钥");
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(
                    "私钥无法解析：需要 PKCS#8 格式的 RSA 私钥（openssl genpkey 的默认输出）。", e);
        }
    }

    /** 解析 X.509 公钥；格式宽容度同 {@link #readPrivateKey}。 */
    public static PublicKey readPublicKey(String encoded) {
        byte[] der = decodeKeyMaterial(encoded, "公钥");
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(
                    "公钥无法解析：需要 X.509(SubjectPublicKeyInfo) 格式的 RSA 公钥。", e);
        }
    }

    private static byte[] decodeKeyMaterial(String encoded, String what) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException(what + "为空。");
        }
        String base64 = encoded
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + "不是合法的 base64（PEM 头尾会被自动剔除）。", e);
        }
    }

    private static <K extends java.security.Key> K requireStrongRsaKey(K key) {
        if (!(key instanceof RSAKey rsaKey)) {
            throw new IllegalArgumentException("需要 RSA 密钥，实际是 "
                    + (key == null ? "null" : key.getAlgorithm()) + "。");
        }
        int bits = rsaKey.getModulus().bitLength();
        if (bits < MINIMUM_KEY_BITS) {
            throw new IllegalArgumentException("RSA 密钥只有 " + bits + " 位，下限是 "
                    + MINIMUM_KEY_BITS + " 位。");
        }
        return key;
    }

    private static McpToolException reject(String message) {
        return new McpToolException(ErrorCode.SECURITY_VIOLATION, message);
    }

    /**
     * 密文里的线上格式。
     *
     * <p>键名全部压成单字母：RSA-2048 + OAEP(SHA-256) 一次只能装 190 字节明文，用可读的长键名
     * （{@code connectionName} 之类）会让 JSON 骨架自己吃掉六十多字节，直接把可用口令长度压到不实用。
     *
     * <p>{@code exp} 用 epoch 秒而不是 ISO 字符串：省 10 字节，也省掉对 jackson-datatype-jsr310 的依赖
     * （core 只声明了 jackson-databind）。
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 是给「未来版本多加了字段」留的余地——
     * 版本号本身仍会在开封时（{@link #openForRuntime} / {@link #openForStartup}）被严格拒绝，
     * 所以这不是在放松校验。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Payload(
            @JsonProperty("v") int v,
            @JsonProperty("n") String n,
            @JsonProperty("u") String u,
            @JsonProperty("p") String p,
            @JsonProperty("h") String h,
            @JsonProperty("exp") Long exp
    ) {
        static Payload of(SealedCredential credential) {
            return new Payload(credential.version(), credential.connectionName(),
                    credential.username(), credential.password(), credential.jdbcUrlFingerprint(),
                    credential.expiresAt() == null ? null : credential.expiresAt().getEpochSecond());
        }

        SealedCredential toCredential() {
            return new SealedCredential(v, n, u, p, h,
                    exp == null ? null : Instant.ofEpochSecond(exp));
        }
    }
}
