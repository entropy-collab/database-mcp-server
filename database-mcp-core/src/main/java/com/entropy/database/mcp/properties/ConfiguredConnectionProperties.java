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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Connections declared by the deployment instead of created at runtime by a caller.
 *
 * <p>This is the second usage mode of the server. The first is BYOK: the caller invokes
 * {@code createNamedConnection} with a JDBC URL and credentials, and gets a leased pool that expires.
 * That mode is wrong for a fixed set of databases the operator already knows about — it forces every
 * client to carry production credentials and to re-register them every hour.
 *
 * <p>Declaring them here creates <em>pinned</em> pools at startup that live for the life of the
 * process. Tools address them by name exactly like BYOK connections; nothing downstream can tell the
 * difference, because both go through the same {@code ByokDataSourceFactory} and therefore get the
 * same SQL validation, masking, auditing and statement timeouts.
 *
 * <pre>
 * entropy:
 *   mcp:
 *     database:
 *       connections:
 *         oracle-prod:
 *           jdbc-url: jdbc:oracle:thin:@//db-host:1521/ORCLPDB
 *           username: mcp_reader
 *           password: ${ORACLE_PASSWORD}   # 不要写明文
 *           readonly: true
 *           required: true                 # 连不上则启动失败
 * </pre>
 *
 * <p>Shares the {@code entropy.mcp.database} prefix with {@link DatabaseProperties}. Boot binds each
 * class against the keys it declares, so the two do not collide; keeping the prefix means the key
 * reads {@code entropy.mcp.database.connections.*} alongside the rest of the database configuration
 * rather than inventing a sibling namespace.
 */
@ConfigurationProperties(prefix = "entropy.mcp.database")
public record ConfiguredConnectionProperties(
        Map<String, Definition> connections
) {

    public ConfiguredConnectionProperties {
        connections = connections == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(connections));
    }

    public ConfiguredConnectionProperties() {
        this(Map.of());
    }

    /**
     * One declared connection.
     *
     * @param jdbcUrl         required; the same string a BYOK caller would pass
     * @param username        required
     * @param password        may be empty for URL-embedded or trusted-auth setups; use a
     *                        {@code ${ENV_VAR}} placeholder rather than a literal
     * @param dialect         optional; blank or {@code auto} means "infer from the JDBC URL", which is
     *                        always possible here because the URL is known up front
     * @param driverClassName optional; inferred from the URL when absent
     * @param readonly        rejects writes through this connection; defaults to {@code false}
     * @param required        when {@code true}, a connection that cannot be established aborts
     *                        startup. Defaults to {@code false} so that one unreachable database does
     *                        not stop the server from serving every other one.
     */
    public record Definition(
            String jdbcUrl,
            String username,
            String password,
            String dialect,
            String driverClassName,
            Boolean readonly,
            Boolean required
    ) {
        public Definition {
            // "auto" is meaningful for entropy.mcp.database.dialect, where no URL is known at
            // configuration time. Here it is the same thing as leaving it blank, and normalising it to
            // null lets the URL-based inference run instead of resolving to GenericDialect.
            if (dialect != null && (dialect.isBlank() || dialect.trim().equalsIgnoreCase("auto"))) {
                dialect = null;
            }
            readonly = Boolean.TRUE.equals(readonly);
            required = Boolean.TRUE.equals(required);
        }

        /** @throws IllegalStateException naming the connection, so the operator can find the typo */
        public void validate(String name) {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalStateException(
                        "entropy.mcp.database.connections." + name + ".jdbc-url is required");
            }
            if (!jdbcUrl.startsWith("jdbc:")) {
                throw new IllegalStateException(
                        "entropy.mcp.database.connections." + name + ".jdbc-url must start with 'jdbc:'");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalStateException(
                        "entropy.mcp.database.connections." + name + ".username is required");
            }
        }
    }
}
