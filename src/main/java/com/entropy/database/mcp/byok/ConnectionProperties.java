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

    public String getCacheKey() {
        return jdbcUrl + "|" + username + "|" + dialect;
    }

    public static ConnectionProperties fromEnv() {
        // For primary datasource configuration from environment variables
        String jdbcUrl = System.getenv("DB_JDBC_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        String dialect = System.getenv("DB_DIALECT");
        
        if (jdbcUrl == null) {
            // Try Spring Boot style
            jdbcUrl = System.getenv("SPRING_DATASOURCE_PRIMARY_JDBC_URL");
        }
        if (username == null) {
            username = System.getenv("SPRING_DATASOURCE_PRIMARY_USERNAME");
        }
        if (password == null) {
            password = System.getenv("SPRING_DATASOURCE_PRIMARY_PASSWORD");
        }
        
        return new ConnectionProperties(jdbcUrl, username, password, dialect, null, false);
    }
}
