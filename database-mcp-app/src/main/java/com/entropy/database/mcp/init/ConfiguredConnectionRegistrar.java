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
import com.entropy.database.mcp.properties.ConfiguredConnectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <h2>Logging</h2>
 * JDBC URLs are truncated at the first parameter delimiter before being logged. Driver parameters
 * routinely carry credentials ({@code ?password=}, {@code ;PWD=}), and this runs at INFO on every
 * boot, so the full URL must never reach the log.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "entropy.mcp.database.enabled", matchIfMissing = true)
public class ConfiguredConnectionRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredConnectionRegistrar.class);

    private final ConfiguredConnectionProperties properties;
    private final DynamicDataSourceManager dataSourceManager;

    public ConfiguredConnectionRegistrar(ConfiguredConnectionProperties properties,
                                         DynamicDataSourceManager dataSourceManager) {
        this.properties = properties;
        this.dataSourceManager = dataSourceManager;
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
            try {
                dataSourceManager.registerPinned(name, toConnectionProperties(definition));
                registered.add(name);
            } catch (RuntimeException e) {
                if (definition.required()) {
                    throw new IllegalStateException(
                            "Required connection '" + name + "' (" + safeUrl(definition.jdbcUrl())
                                    + ") could not be established: " + e.getMessage(), e);
                }
                log.warn("Optional connection '{}' ({}) could not be established, skipping: {}",
                        name, safeUrl(definition.jdbcUrl()), e.getMessage());
            }
        }
        log.info("Registered {} configured connection(s): {}", registered.size(), registered);
    }

    private static ConnectionProperties toConnectionProperties(
            ConfiguredConnectionProperties.Definition definition) {
        return ConnectionProperties.builder()
                .jdbcUrl(definition.jdbcUrl())
                .username(definition.username())
                .password(definition.password())
                .dialect(definition.dialect())
                .driverClassName(definition.driverClassName())
                .readonly(definition.readonly())
                .build();
    }

    /** JDBC URL with every parameter stripped: parameters routinely carry the password. */
    private static String safeUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "null";
        }
        int query = jdbcUrl.indexOf('?');
        int semicolon = jdbcUrl.indexOf(';');
        int cut = query < 0 ? semicolon : (semicolon < 0 ? query : Math.min(query, semicolon));
        return cut < 0 ? jdbcUrl : jdbcUrl.substring(0, cut) + "…";
    }
}
