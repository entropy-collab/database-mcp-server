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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 密文凭证的边界。
 *
 * <p>这套断言保护的是三道绑定：<b>连接名</b>、<b>jdbcUrl</b>、<b>有效期</b>。少任何一道，密文就退化成
 * 一枚可无限重放的口令——而它是要经过模型和对话历史的，也就是经过一段擦不掉的存储。
 * 其中 jdbcUrl 那道最容易被当成"多余的严格"删掉，所以这里专门写清了它拦的是什么攻击。
 *
 * <p>「绑定<b>存在</b>」和「绑定<b>相符</b>」是两件事，两件都在下面：运行时那条路要求存在，
 * 服务端配置那条路不要求。以前这里只测了"相符"，于是无绑定密文被静默放行了整整一个版本。
 */
class CredentialCipherTest {

    private static final String URL = "jdbc:postgresql://db-host:5432/orders";
    private static final String NAME = "orders-prod";
    private static final String USER = "app_ro";
    private static final String PASSWORD = "s3cret";

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;
    private static CredentialCipher cipher;

    @BeforeAll
    static void generateKeys() throws Exception {
        keyPair = generate(2048);
        otherKeyPair = generate(2048);
        cipher = new CredentialCipher(keyPair.getPrivate());
    }

    private static KeyPair generate(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(CredentialCipher.KEY_ALGORITHM);
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String seal(String name, String username, String jdbcUrl, Duration ttl) {
        return CredentialCipher.seal(keyPair.getPublic(), new SealedCredential(
                CredentialCipher.CURRENT_VERSION, name, username, PASSWORD,
                CredentialCipher.fingerprint(jdbcUrl),
                ttl == null ? null : Instant.now().plus(ttl)));
    }

    @Nested
    class RoundTrip {

        @Test
        void opensACredentialSealedWithTheMatchingPublicKey() {
            SealedCredential opened = cipher.openForRuntime(
                    seal(NAME, USER, URL, Duration.ofMinutes(5)), NAME, URL, Duration.ofMinutes(15));

            assertThat(opened.username()).isEqualTo(USER);
            assertThat(opened.password()).isEqualTo(PASSWORD);
            assertThat(opened.expiresAt()).isAfter(Instant.now());
        }

        /** 同一段明文封两次必须得到不同密文：OAEP 的随机填充在起作用，密文不可作为口令的指纹。 */
        @Test
        void producesADifferentCiphertextEveryTime() {
            assertThat(seal(NAME, USER, URL, Duration.ofMinutes(5)))
                    .isNotEqualTo(seal(NAME, USER, URL, Duration.ofMinutes(5)));
        }

        /** 连接名是个引用标签，运维手写时大小写极易不一致，这里刻意宽容。 */
        @Test
        void matchesTheConnectionNameCaseInsensitively() {
            assertThat(cipher.openForRuntime(seal(NAME, USER, URL, Duration.ofMinutes(5)),
                    NAME.toUpperCase(), URL, Duration.ofMinutes(15)).username())
                    .isEqualTo(USER);
        }

        /** 口令绝不能出现在 toString 里：这个对象天生在出错路径上被传递。 */
        @Test
        void doesNotRenderThePasswordInToString() {
            SealedCredential opened = cipher.openForRuntime(
                    seal(NAME, USER, URL, Duration.ofMinutes(5)), NAME, URL, Duration.ofMinutes(15));

            assertThat(opened.toString()).doesNotContain(PASSWORD).contains("password=****");
        }
    }

    @Nested
    class Binding {

        /**
         * 不绑 jdbcUrl 的话，拿到密文的人可以把它指向自己控制的假数据库，服务端会解出明文口令
         * 并作为登录凭证发过去——服务端亲手把口令交出去。这条断言就是那道闸。
         */
        @Test
        void refusesToOpenAgainstADifferentJdbcUrl() {
            String sealed = seal(NAME, USER, URL, Duration.ofMinutes(5));

            assertThatThrownBy(() -> cipher.openForRuntime(sealed, NAME,
                    "jdbc:postgresql://attacker-host:5432/orders", Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("jdbcUrl");
        }

        /** 逐字符比对，不做任何归一化：放宽一点就等于放开重定向。 */
        @Test
        void treatsAnyJdbcUrlDifferenceAsAMismatch() {
            String sealed = seal(NAME, USER, URL, Duration.ofMinutes(5));

            assertThatThrownBy(() -> cipher.openForRuntime(sealed, NAME, URL + "?ssl=true", Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class);
        }

        @Test
        void refusesToOpenUnderADifferentConnectionName() {
            String sealed = seal(NAME, USER, URL, Duration.ofMinutes(5));

            assertThatThrownBy(() -> cipher.openForRuntime(sealed, "someone-elses-db", URL, Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("连接名");
        }

        /**
         * 无绑定密文在<b>运行时</b>那条路上必须被拒。
         *
         * <p>这里以前钉的是反过来的行为（「两处绑定都为 null 就不限制」），那是个 fail-open：
         * 攻击者拿一份无绑定密文，把 jdbcUrl 指向自己控制的假库，服务端就会解出明文口令并投递过去。
         * 「密文没声明绑定」是拒绝的理由，不是跳过校验的理由。
         */
        @Test
        void refusesAnUnboundCredentialOnTheRuntimePath() {
            String sealed = unbound();

            assertThatThrownBy(() -> cipher.openForRuntime(
                    sealed, "any-name", "jdbc:h2:mem:whatever", Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("无绑定");
        }

        /** 只有 jdbcUrl 那道缺失也一样拒：连接名对上了不代表这份密文该往这台库送。 */
        @Test
        void refusesACredentialWithoutTheUrlBindingOnTheRuntimePath() {
            String sealed = CredentialCipher.seal(keyPair.getPublic(), new SealedCredential(
                    CredentialCipher.CURRENT_VERSION, NAME, USER, PASSWORD, null,
                    Instant.now().plusSeconds(300)));

            assertThatThrownBy(() -> cipher.openForRuntime(sealed, NAME, URL, Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("jdbcUrl");
        }

        /**
         * 同一份无绑定密文在<b>启动</b>那条路上要能开——这是服务端配置里密文的正常形态：
         * 它不经过模型、不进对话历史，且必须扛住进程重启，所以不强制绑定也不强制有效期。
         */
        @Test
        void allowsAnUnboundCredentialOnTheStartupPath() {
            assertThat(cipher.openForStartup(unbound(), "any-name", "jdbc:h2:mem:whatever", null)
                    .username()).isEqualTo(USER);
        }

        /** 运行时入口不接受「不要求有效期」：那等于把这条路悄悄降级成长期有效的密文。 */
        @Test
        void refusesToRunTheRuntimePathWithoutATtlCeiling() {
            String sealed = seal(NAME, USER, URL, Duration.ofMinutes(5));

            assertThatThrownBy(() -> cipher.openForRuntime(sealed, NAME, URL, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** 既不绑连接名也不绑 URL、也没有有效期的密文——服务端配置那条路的形态。 */
        private String unbound() {
            return CredentialCipher.seal(keyPair.getPublic(), new SealedCredential(
                    CredentialCipher.CURRENT_VERSION, null, USER, PASSWORD, null, null));
        }
    }

    @Nested
    class Expiry {

        @Test
        void rejectsAnExpiredCredential() {
            String sealed = seal(NAME, USER, URL, Duration.ofSeconds(-1));

            assertThatThrownBy(() -> cipher.openForRuntime(sealed, NAME, URL, Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("已过期");
        }

        /** 过期一律拒绝，与"这条路是否强制有效期"无关（启动那条路就是不强制）。 */
        @Test
        void rejectsAnExpiredCredentialEvenWhenNoTtlIsRequired() {
            String sealed = seal(NAME, USER, URL, Duration.ofSeconds(-1));

            assertThatThrownBy(() -> cipher.openForStartup(sealed, NAME, URL, null))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("已过期");
        }

        /** 运维随手封一个一年期的密文，就把"短窗口"这条防线自己废了。 */
        @Test
        void rejectsATtlLongerThanTheServerCeiling() {
            String sealed = seal(NAME, USER, URL, Duration.ofDays(365));

            assertThatThrownBy(() -> cipher.openForRuntime(sealed, NAME, URL, Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("max-ttl");
        }

        @Test
        void rejectsAnEndlessCredentialOnThePathThatRequiresATtl() {
            String sealed = seal(NAME, USER, URL, null);

            assertThatThrownBy(() -> cipher.openForRuntime(sealed, NAME, URL, Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("有效期");
        }
    }

    @Nested
    class CryptoFailures {

        /**
         * 所有解密失败必须归一成同一句话：区分"填充不对"和"内容不对"就是把填充预言
         * （Bleichenbacher 式）攻击所需的信号送出去，而这个入口是可被反复调用的 MCP 工具。
         */
        @Test
        void reportsTheSameErrorForEveryKindOfUndecryptableInput() {
            String sealedForAnotherKey = CredentialCipher.seal(otherKeyPair.getPublic(),
                    new SealedCredential(CredentialCipher.CURRENT_VERSION, NAME, USER, PASSWORD,
                            CredentialCipher.fingerprint(URL), Instant.now().plusSeconds(300)));
            String garbage = Base64.getEncoder().encodeToString(new byte[256]);
            String notBase64 = "!!!not base64!!!";

            for (String input : new String[]{sealedForAnotherKey, garbage, notBase64}) {
                assertThatThrownBy(() -> cipher.openForRuntime(input, NAME, URL, Duration.ofMinutes(15)))
                        .isInstanceOf(McpToolException.class)
                        .hasMessageContaining("密文无法解开");
            }
        }

        @Test
        void rejectsABlankCiphertext() {
            assertThatThrownBy(() -> cipher.openForRuntime("   ", NAME, URL, Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class);
        }

        /**
         * 「解得开但载荷不是合法 JSON」与「根本解不开」对外必须是同一句话。
         *
         * <p>这两种情况在实现里走了两个不同的 catch——分开只是为了日志：JSON 解析那一步的输入是
         * <b>已经解密出来的口令 JSON</b>，Jackson 的异常消息会带上源片段，所以那一侧只记异常类型名。
         * 但对外的消息必须一模一样，否则「填充错误」和「JSON 非法」就成了两种可区分的响应，
         * 填充预言攻击要的正是这一位信号。这条断言钉的就是「日志分开，对外不分」。
         */
        @Test
        void reportsTheSameErrorWhenThePayloadDecryptsButIsNotTheExpectedJson() throws Exception {
            Cipher raw = Cipher.getInstance(CredentialCipher.TRANSFORMATION);
            raw.init(Cipher.ENCRYPT_MODE, keyPair.getPublic());
            String decryptableButNotJson = Base64.getEncoder().encodeToString(
                    raw.doFinal("not-json-at-all".getBytes(StandardCharsets.UTF_8)));

            Throwable undecryptable = catchThrowable(() ->
                    cipher.openForRuntime("garbage", NAME, URL, Duration.ofMinutes(15)));
            Throwable badPayload = catchThrowable(() ->
                    cipher.openForRuntime(decryptableButNotJson, NAME, URL, Duration.ofMinutes(15)));

            assertThat(badPayload).isInstanceOf(McpToolException.class).hasNoCause();
            assertThat(badPayload.getMessage()).isEqualTo(undecryptable.getMessage());
        }

        /**
         * 异常上不能挂 cause：MCP 的错误渲染会把 cause 的 message 一并回给调用方，
         * 于是 {@code "Padding error in decryption"} 会漏出去，把上面那条"归一成同一句话"当场作废。
         * 这是 0.4.0 端到端验证时真实观察到的输出。
         */
        @Test
        void doesNotAttachTheUnderlyingCauseToTheError() {
            assertThatThrownBy(() -> cipher.openForRuntime("garbage", NAME, URL, Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class)
                    .hasNoCause();
        }

        /** 错误码要落在 SECURITY_VIOLATION 上，审计侧靠它把这类事件挑出来。 */
        @Test
        void classifiesFailuresAsASecurityViolation() {
            assertThatThrownBy(() -> cipher.openForRuntime("garbage", NAME, URL, Duration.ofMinutes(15)))
                    .isInstanceOf(McpToolException.class)
                    .extracting(e -> ((McpToolException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SECURITY_VIOLATION);
        }
    }

    @Nested
    class Keys {

        /** 1024 位 RSA 已被实际分解过，而这里保护的是生产库口令：拒绝，不是警告。 */
        @Test
        void refusesAKeyBelowTheMinimumSize() throws Exception {
            KeyPair weak = generate(1024);

            assertThatThrownBy(() -> new CredentialCipher(weak.getPrivate()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1024");
        }

        /** openssl 默认输出带 PEM 头尾，要求运维手工剔掉只会招来"删多了一个字符"的故障。 */
        @Test
        void acceptsPemWrappedKeyMaterial() {
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";

            assertThat(CredentialCipher.readPrivateKey(pem)).isEqualTo(keyPair.getPrivate());
        }

        @Test
        void rejectsKeyMaterialThatIsNotBase64() {
            assertThatThrownBy(() -> CredentialCipher.readPrivateKey("这不是密钥"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Capacity {

        /** 2048 位密钥只装得下 190 字节明文，超了要在封装时就报错，而不是留到服务端解密才发现。 */
        @Test
        void failsAtSealTimeWhenThePayloadDoesNotFit() {
            var oversized = new SealedCredential(CredentialCipher.CURRENT_VERSION, NAME, USER,
                    "x".repeat(200), CredentialCipher.fingerprint(URL), Instant.now().plusSeconds(300));

            assertThatThrownBy(() -> CredentialCipher.seal(keyPair.getPublic(), oversized))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超过该密钥的上限");
        }

        @Test
        void reportsTheDocumentedCapacityForA2048BitKey() {
            assertThat(CredentialCipher.maxPayloadBytes(keyPair.getPublic())).isEqualTo(190);
        }

        /** 单字母键名的意义就在这里：2048 位下还能留出可用的口令长度。 */
        @Test
        void leavesRoomForARealisticCredential() {
            assertThat(CredentialCipher.seal(keyPair.getPublic(), new SealedCredential(
                    CredentialCipher.CURRENT_VERSION, "orders-prod-replica", "mcp_reader_account",
                    "Y3-quite-a-long-database-password!", CredentialCipher.fingerprint(URL),
                    Instant.now().plusSeconds(900))))
                    .isNotBlank();
        }
    }
}
