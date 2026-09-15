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
package com.entropy.database.mcp.init;

import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.credential.CredentialCipher;
import com.entropy.database.mcp.credential.SealedCredential;
import com.entropy.database.mcp.properties.ConfiguredConnectionProperties;
import com.entropy.database.mcp.util.JdbcUrlMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registers the connections declared under {@code entropy.mcp.database.connections} as pinned pools
 * at startup.
 *
 * <h2>Failure handling</h2>
 * A connection marked {@code required: true} that cannot be established aborts startup — throwing
 * from an {@link ApplicationRunner} makes {@code SpringApplication.run} fail and closes the context.
 * Anything else is logged at WARN and skipped, so one unreachable database does not stop the server
 * from serving the others. The skipped name is simply absent from {@code listConnections}, and a
 * caller may still create it at runtime through the BYOK path.
 *
 * <h2>密文口令</h2>
 * {@code password} 以 {@value #SEALED_PASSWORD_PREFIX} 开头时，其余部分按密文凭证解开（详见
 * {@link CredentialCipher}）。这就是 Druid {@code ConfigTools} 那个场景：配置文件、镜像层、
 * 配置中心快照里不留明文口令。与运行时那条路的区别是——这里的密文<b>不要求带有效期</b>：
 * 它不经过模型，且要能扛住任意时刻的重启，一个会过期的配置项等于给自己埋一颗定时炸弹。
 * 但如果运维确实封了有效期，过期后照样拒绝（并且 {@code required: true} 时启动失败）。
 *
 * <p>解密失败一律视为致命，与 {@code required} 无关：密文解不开是部署错误（私钥换了、密文抄漏了），
 * 不是「这台库暂时连不上」，跳过它只会让人以为连接名写错了。
 *
 * <h2>Logging</h2>
 * JDBC URLs go through {@link JdbcUrlMasker} before being logged. Driver parameters routinely carry
 * credentials ({@code ?password=}, {@code ;PWD=}) and Oracle-style URLs can carry {@code user/pw@},
 * and this runs at INFO on every boot, so the raw URL must never reach the log.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "entropy.mcp.database.enabled", matchIfMissing = true)
public class ConfiguredConnectionRegistrar implements ApplicationRunner {

    /** 口令值的密文标记。选前缀而不是新配置项：一眼能看出这一行是密文，且不用改配置结构。 */
    public static final String SEALED_PASSWORD_PREFIX = "sealed:";

    private static final Logger log = LoggerFactory.getLogger(ConfiguredConnectionRegistrar.class);

    private final ConfiguredConnectionProperties properties;
    private final DynamicDataSourceManager dataSourceManager;
    private final ObjectProvider<CredentialCipher> credentialCipher;

    public ConfiguredConnectionRegistrar(ConfiguredConnectionProperties properties,
                                         DynamicDataSourceManager dataSourceManager,
                                         ObjectProvider<CredentialCipher> credentialCipher) {
        this.properties = properties;
        this.dataSourceManager = dataSourceManager;
        this.credentialCipher = credentialCipher;
    }


    @Override
    public void run(ApplicationArguments args) {
        Map<String, ConfiguredConnectionProperties.Definition> declared = properties.connections();
        if (declared.isEmpty()) {
            log.debug("No connections declared under entropy.mcp.database.connections; "
                    + "callers must use createNamedConnection");
            return;
        }

        List<String> registered = new ArrayList<>();
        for (var entry : declared.entrySet()) {
            String name = entry.getKey();
            var definition = entry.getValue();
            // Validation failures are always fatal, required or not: a malformed jdbc-url is a
            // deployment mistake that will not fix itself, and swallowing it would leave the operator
            // wondering why the connection never appeared.
            definition.validate(name);
            // 凭证解析（含密文解密）刻意放在 try 之外：解不开的密文是部署错误，
            // required=false 也不该被降级成一句 WARN 跳过。try 里只包"连不上库"这一种失败。
            ConnectionProperties connectionProperties = toConnectionProperties(name, definition);
            try {
                dataSourceManager.registerPinned(name, connectionProperties);
                registered.add(name);
            } catch (RuntimeException e) {
                if (definition.required()) {
                    throw new IllegalStateException(
                            "Required connection '" + name + "' ("
                                    + JdbcUrlMasker.mask(definition.jdbcUrl())
                                    + ") could not be established: " + e.getMessage(), e);
                }
                log.warn("Optional connection '{}' ({}) could not be established, skipping: {}",
                        name, JdbcUrlMasker.mask(definition.jdbcUrl()), e.getMessage());
            }
        }
        log.info("Registered {} configured connection(s): {}", registered.size(), registered);
    }

    private ConnectionProperties toConnectionProperties(
            String name, ConfiguredConnectionProperties.Definition definition) {
        return ConnectionProperties.builder()
                .jdbcUrl(definition.jdbcUrl())
                .username(definition.username())
                .password(resolvePassword(name, definition))
                .dialect(definition.dialect())
                .driverClassName(definition.driverClassName())
                .readonly(definition.readonly())
                .build();
    }

    /**
     * 明文口令原样返回；{@value #SEALED_PASSWORD_PREFIX} 开头的走解密。
     *
     * <p>解密后额外核对账号：密文里封的账号必须与配置里写的一致。配置里保留 {@code username} 是为了
     * 「这条连接用哪个账号」在配置文件里一眼可见（运维审计要的就是这个），而绑定检查保证这一行不是
     * 摆设——不核对的话，配置写 {@code mcp_reader}、密文里其实是 {@code sys}，谁都看不出来。
     */
    private String resolvePassword(String name, ConfiguredConnectionProperties.Definition definition) {
        String password = definition.password();
        if (password == null || !password.startsWith(SEALED_PASSWORD_PREFIX)) {
            return password;
        }
        CredentialCipher cipher = credentialCipher.getIfAvailable();
        if (cipher == null) {
            throw new IllegalStateException("entropy.mcp.database.connections." + name
                    + ".password 是密文（" + SEALED_PASSWORD_PREFIX + "…），但没有配置解密私钥。"
                    + "请设置 entropy.mcp.security.credential-cipher.private-key。");
        }
        // 走启动路：配置里的密文不要求带有效期（进程重启必须还能用），也允许没有连接名/URL 绑定——
        // 这条路的密文不经过模型，威胁模型与 createSealedConnection 那条路不同。
        SealedCredential credential = cipher.openForStartup(
                password.substring(SEALED_PASSWORD_PREFIX.length()), name, definition.jdbcUrl(), null);

        if (!credential.username().equalsIgnoreCase(definition.username())) {
            throw new IllegalStateException("entropy.mcp.database.connections." + name
                    + " 的密文里封装的数据库账号与配置里声明的 username 不一致。");
        }
        return credential.password();
    }
}
