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

import java.time.Instant;

/**
 * 一份密文凭证解开后的内容。
 *
 * <p>为什么要把连接名、账号、URL 指纹和有效期都跟口令封在同一份密文里，而不是只封口令：
 * <ul>
 *   <li>{@code connectionName} —— 密文会经过模型、留在对话历史里，等同一枚 bearer token。
 *       不绑连接名的话，任何拿到密文的人都能把它注册成自己的连接名，再用其他工具读走这套凭证
 *       能读到的所有数据。</li>
 *   <li>{@code jdbcUrlFingerprint} —— 这一条最要紧。不绑 URL 时，攻击者可以拿着密文调
 *       {@code createSealedConnection}，把 jdbcUrl 指向自己控制的假数据库；服务端会解密出明文口令
 *       并作为登录凭证发给那台假库，等于服务端亲手把口令交出去。绑定之后，密文只能连回封装时
 *       指定的那台库。</li>
 *   <li>{@code expiresAt} —— 密文没有一次性消费机制（原因见 {@link CredentialCipher} 类注释），
 *       所以窗口期内可重放；有效期把这个窗口收到分钟级。</li>
 * </ul>
 *
 * <p>两处绑定字段允许为 {@code null}，但「允许」只落在<b>服务端配置</b>那条路上，而这件事不靠调用方
 * 传对参数来保证：{@link CredentialCipher#openForRuntime} 会把缺绑定当成拒绝的理由，
 * {@link CredentialCipher#openForStartup} 才接受。以前这个区别只写在注释里、代码上是「字段为 null
 * 就整段跳过校验」，结果任何一份无绑定密文都能被指向攻击者控制的数据库——所以现在放宽由方法名承担，
 * 读这个 record 的人不需要自己推断哪条路能放宽。
 *
 * @param version            格式版本，当前只认 {@link CredentialCipher#CURRENT_VERSION}；
 *                           未来换算法或换字段时靠它 fail fast，而不是让旧密文解出半个对象
 * @param connectionName     绑定的连接名；{@code null} 只有 {@code openForStartup} 那条路接受
 * @param username           数据库账号，随口令一起封装，调用方无需也无法另外指定
 * @param password           数据库口令明文，只在服务端进程内存活
 * @param jdbcUrlFingerprint {@link CredentialCipher#fingerprint} 的结果；
 *                           {@code null} 只有 {@code openForStartup} 那条路接受
 * @param expiresAt          过期时刻；{@code null} 表示永不过期（同样只有服务端配置那条路接受）
 */
public record SealedCredential(
        int version,
        String connectionName,
        String username,
        String password,
        String jdbcUrlFingerprint,
        Instant expiresAt
) {

    /**
     * 隐去口令。
     *
     * <p>record 的隐式 {@code toString()} 会打印 {@code password=<明文>}，而这个对象天生就在出错路径上
     * 被传递（绑定不符、已过期），任何一句把它塞进异常消息或日志的写法都会泄密。
     */
    @Override
    public String toString() {
        return "SealedCredential[version=" + version
                + ", connectionName=" + connectionName
                + ", username=" + username
                + ", password=****"
                + ", jdbcUrlFingerprint=" + jdbcUrlFingerprint
                + ", expiresAt=" + expiresAt + "]";
    }
}
