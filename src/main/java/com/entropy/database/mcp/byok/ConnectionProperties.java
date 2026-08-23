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
package com.entropy.database.mcp.byok;

import com.entropy.database.mcp.dialect.DialectUtils;

/**
 * BYOK connection properties.
 * Immutable DTO for database connection information provided by the caller.
 *
 * <p>Follows the Builder pattern (effective Java Item 2) to support fluent construction
 * with sensible defaults, mirroring how Spring's {@code DataSourceBuilder} works.
 */
public record ConnectionProperties(
    String jdbcUrl,
    String username,
    String password,
    String dialect,
    String driverClassName,
    Boolean readonly
) {
    public ConnectionProperties {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (password == null) {
            password = "";
        }
        if (dialect == null || dialect.isBlank()) {
            dialect = DialectUtils.inferDialect(jdbcUrl);
        }
        if (driverClassName == null || driverClassName.isBlank()) {
            driverClassName = DialectUtils.inferDriverClassName(jdbcUrl);
        }
        if (readonly == null) {
            readonly = false;
        }
    }

    /**
     * Validate that all required fields are present and well-formed.
     * Use this before passing to factory methods for early failure.
     */
    public void validate() {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("jdbcUrl must not be blank");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("username must not be blank");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("password must not be blank");
        }
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalStateException("jdbcUrl must start with 'jdbc:'");
        }
    }

    public String getCacheKey() {
        return normalizeJdbcUrl(jdbcUrl) + "|" + username + "|" + dialect;
    }

    /**
     * Normalize JDBC URL to a canonical form for deduplication.
     * Strips query parameters and fragments, keeping only the protocol + host + database path.
     * e.g. "jdbc:postgresql://host:5432/db?useSSL=false&stringtype=unspecified"
     *     → "jdbc:postgresql://host:5432/db"
     */
    static String normalizeJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) return jdbcUrl;
        int questionMark = jdbcUrl.indexOf('?');
        int hash = jdbcUrl.indexOf('#');
        int end = questionMark > 0 ? questionMark : (hash > 0 ? hash : jdbcUrl.length());
        return jdbcUrl.substring(0, end);
    }

    public static ConnectionProperties fromEnv() {
        String jdbcUrl = System.getenv("DB_JDBC_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        String dialect = System.getenv("DB_DIALECT");

        return builder()
                .jdbcUrl(jdbcUrl)
                .username(username)
                .password(password)
                .dialect(dialect)
                .build();
    }

    /**
     * Create a builder for fluent construction of ConnectionProperties.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link ConnectionProperties}.
     * Inspired by Spring's DataSourceBuilder.
     */
    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private String dialect;
        private String driverClassName;
        private Boolean readonly;

        private Builder() {}

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder dialect(String dialect) {
            this.dialect = dialect;
            return this;
        }

        public Builder driverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
            return this;
        }

        public Builder readonly(Boolean readonly) {
            this.readonly = readonly;
            return this;
        }

        public ConnectionProperties build() {
            return new ConnectionProperties(jdbcUrl, username, password, dialect, driverClassName, readonly);
        }
    }
}
