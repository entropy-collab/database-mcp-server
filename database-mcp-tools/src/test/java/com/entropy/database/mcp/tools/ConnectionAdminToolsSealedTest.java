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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.credential.CredentialCipher;
import com.entropy.database.mcp.credential.SealedCredential;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.properties.CredentialCipherProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两条注册路径之间的闸门。
 *
 * <p>只测「还没碰数据库就该被拒」的分支：真正注册成功的那条路要连库、跑连通性查询，
 * 用 mock 堆出来的断言只会证明 mock 被调过，实际由真实启动的端到端验证覆盖。
 *
 * <p>密文本身的解密与三道绑定在 {@code CredentialCipherTest} 里，那是纯函数，测起来更狠也更快。
 */
class ConnectionAdminToolsSealedTest {

    private final DynamicDataSourceManager manager = mock(DynamicDataSourceManager.class);

    private ConnectionAdminTools tools(CredentialCipher cipher, boolean requireSealed) {
        @SuppressWarnings("unchecked")
        ObjectProvider<CredentialCipher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cipher);
        return new ConnectionAdminTools(manager, provider,
                new CredentialCipherProperties(null, Duration.ofMinutes(15), requireSealed));
    }

    /**
     * {@code require-sealed-credentials=true} 时明文那条路必须闭死。
     *
     * <p>错误消息里必须点名 createSealedConnection：这条错误是给模型看的，只说"不允许"
     * 会让它换个工具继续瞎试。
     */
    @Test
    void plaintextRegistrationIsRefusedWhenSealedCredentialsAreRequired() {
        assertThatThrownBy(() -> tools(null, true).createNamedConnection(
                "orders", "jdbc:h2:mem:orders", "sa", "secret", null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("createSealedConnection")
                .extracting(e -> ((McpToolException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECURITY_VIOLATION);

        verify(manager, never()).acquire(any(), any());
    }

    /** 私钥没配时，密文那条路要指回明文那条路，而不是抛一句"密文无法解开"把人带偏。 */
    @Test
    void sealedRegistrationExplainsThatNoPrivateKeyIsConfigured() {
        assertThatThrownBy(() -> tools(null, false).createSealedConnection(
                "orders", "jdbc:h2:mem:orders", "irrelevant-ciphertext", null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("credential-cipher.private-key")
                .hasMessageContaining("createNamedConnection");

        verify(manager, never()).acquire(any(), any());
    }

    /** 密钥配了但密文是伪造的：报错要归一成"解不开"，且绝不能碰数据库。 */
    @Test
    void sealedRegistrationRejectsAForgedCiphertextBeforeTouchingTheDatabase() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance(CredentialCipher.KEY_ALGORITHM);
        generator.initialize(2048);
        var cipher = new CredentialCipher(generator.generateKeyPair().getPrivate());

        assertThatThrownBy(() -> tools(cipher, false).createSealedConnection(
                "orders", "jdbc:h2:mem:orders", "bm90LWEtcmVhbC1jaXBoZXJ0ZXh0", null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("密文无法解开");

        verify(manager, never()).acquire(any(), any());
    }

    /**
     * 工具那条路必须走 {@code openForRuntime}：一份不带绑定的密文——它在服务端配置那条路上是合法
     * 形态——在这里必须被拒。放行就等于允许调用方把 jdbcUrl 指向自己控制的库，让服务端解出明文口令
     * 当成登录凭证发过去。这条断言钉的是「调用点选了哪个入口」，不是密文校验本身。
     */
    @Test
    void sealedRegistrationRefusesACredentialWithoutBindings() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance(CredentialCipher.KEY_ALGORITHM);
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        String unbound = CredentialCipher.seal(keyPair.getPublic(), new SealedCredential(
                CredentialCipher.CURRENT_VERSION, null, "app_ro", "s3cret", null, null));

        assertThatThrownBy(() -> tools(new CredentialCipher(keyPair.getPrivate()), false)
                .createSealedConnection("orders", "jdbc:h2:mem:orders", unbound, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("无绑定");

        verify(manager, never()).acquire(any(), any());
    }
}
