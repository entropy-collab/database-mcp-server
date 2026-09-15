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
package com.entropy.database.mcp.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 密文凭证机制的配置。
 *
 * <pre>
 * entropy:
 *   mcp:
 *     security:
 *       credential-cipher:
 *         private-key: ${MCP_CREDENTIAL_PRIVATE_KEY}   # 只走环境变量，不要写进文件
 *         max-ttl: 15m
 *         require-sealed-credentials: true             # 关掉明文口令那条路
 * </pre>
 *
 * <p>{@code private-key} 为空即代表整个机制关闭：
 * {@code CredentialCipher} bean 不会被创建，{@code createSealedConnection} 直接报错并指回
 * {@code createNamedConnection}。这样默认部署形态不变，不需要任何人先去生成密钥才能用起来。
 *
 * @param privateKey               PKCS#8 RSA 私钥（base64 或带头尾的 PEM 都可以），至少 2048 位。
 *                                 私钥是这套机制的全部安全性所在，务必只经环境变量注入
 * @param maxTtl                   运行时注册这条路允许的最长剩余有效期。密文里的有效期超过它会被拒——
 *                                 否则运维随手封一个一年期的密文，就把「短窗口」这条防线自己废掉了。
 *                                 默认 15 分钟：留给「运维生成 → 发给使用者 → 使用者贴给模型」这条人工
 *                                 链路的时间，同时短到密文在对话历史里过期得比人翻回去看更快
 * @param requireSealedCredentials {@code true} 时 {@code createNamedConnection}（明文口令）直接拒绝，
 *                                 运行时注册只剩密文一条路。默认 {@code false}：这是个会让现有客户端
 *                                 立刻报错的开关，得由部署方明确选择
 */
@ConfigurationProperties(prefix = "entropy.mcp.security.credential-cipher")
public record CredentialCipherProperties(
        String privateKey,
        Duration maxTtl,
        boolean requireSealedCredentials
) {

    public static final Duration DEFAULT_MAX_TTL = Duration.ofMinutes(15);

    /** {@code @ConstructorBinding} 的必要性见 {@link BackupProperties}：少了它整段配置会被静默忽略。 */
    @ConstructorBinding
    public CredentialCipherProperties {
        if (privateKey != null && privateKey.isBlank()) {
            privateKey = null;
        }
        // 非正数当作"没配"，而不是当作"立刻过期"：0 或负值只会是配置笔误，
        // 而按字面执行会让所有密文都被拒，且错误信息完全指不到这行配置上。
        if (maxTtl == null || maxTtl.isZero() || maxTtl.isNegative()) {
            maxTtl = DEFAULT_MAX_TTL;
        }
    }

    public CredentialCipherProperties() {
        this(null, DEFAULT_MAX_TTL, false);
    }

    /** 机制是否启用：私钥在就启用。 */
    public boolean enabled() {
        return privateKey != null;
    }
}
