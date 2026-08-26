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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
     *
     * <p>This is a value-object self-check only: it has no access to configuration and callers may
     * skip it. The security guard on the JDBC URL (H2 {@code INIT}/{@code RUNSCRIPT}, MySQL
     * {@code allowLoadLocalInfile}, driver and host policy) therefore lives in
     * {@code DynamicDataSourceManagerImpl}, the single chokepoint every connection registration
     * passes through, and is configured by
     * {@code entropy.mcp.database.byok.url-guard}. Do not re-implement it here.
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

    /**
     * Content fingerprint used to deduplicate physically identical connections.
     *
     * <p>The credential digest MUST be part of the fingerprint. Without it, a caller who knows
     * only {@code jdbcUrl + username} would be treated as an alias of an existing pool and would
     * inherit that pool's already-authenticated connections without ever presenting a valid
     * password — an authentication bypass.
     *
     * <p>{@code readonly} is included as well: two logical connections that differ only in their
     * read-only intent must not share a pool.
     */
    public String getCacheKey() {
        return normalizeJdbcUrl(jdbcUrl) + "|" + username + "|" + dialect
                + "|" + credentialDigest() + "|" + readonly;
    }

    /**
     * SHA-256 digest of the password, so the fingerprint never carries the secret in clear text.
     */
    private String credentialDigest() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((password == null ? "" : password).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS platform requirements; unreachable in practice.
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    /**
     * 把 JDBC URL 归一成用于去重的规范形式：<b>保留</b>全部参数的语义，只消除无意义差异
     * （参数顺序、键的大小写、键值两侧的空白、空参数段）。
     *
     * <p>为什么不能「截掉参数」：这个结果直接进 {@link #getCacheKey()}，而指纹相同就意味着两个连接名
     * 共用同一个 Hikari 池。参数里有<em>语义</em>参数——SQL Server / DB2 的 {@code ;databaseName=...}、
     * PostgreSQL 的 {@code ?currentSchema=...}、MySQL 的 {@code ?connectionTimeZone=...}——一旦被丢弃，
     * {@code ;databaseName=a} 与 {@code ;databaseName=b} 会被判成同一个物理连接，两个不同的库共用一个池，
     * 调用方以为在读 a 其实读到 b，这是数据串库。旧实现「{@code ?} 之后整段丢弃」已经在 PostgreSQL 的
     * {@code ?currentSchema=a} / {@code ?currentSchema=b} 上踩到了这个坑；而分号风格的参数旧实现完全没识别，
     * 所以口径必须往<em>保守</em>方向改：宁可少去重一次（多开一个池，只是多占资源），也绝不合并语义不同的 URL。
     *
     * <p>覆盖两种参数风格：{@code ?k=v&k2=v2}（MySQL / PostgreSQL）与 {@code ;k=v;k2=v2}
     * （SQL Server / DB2 / H2）。切分点取第一个 {@code ?} 或 {@code ;}，其前面整段是 base——分号风格的
     * base 本身带 {@code //host:port}，不能把 host 当成参数处理。
     *
     * <p>同一个键出现多次时保持原有顺序不排序：多数驱动是「后者覆盖前者」，
     * {@code ?currentSchema=a&currentSchema=b} 与 {@code ?currentSchema=b&currentSchema=a} 生效值不同，
     * 排序会把它们抹成同一个指纹。这里也不再丢弃 {@code #} 之后的内容，理由同上——凡是丢弃就有合并风险。
     *
     * <p>e.g. {@code "jdbc:sqlserver://host:1433;encrypt=true;databaseName=db"}
     *     → {@code "jdbc:sqlserver://host:1433;databasename=db;encrypt=true"}
     */
    static String normalizeJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) return jdbcUrl;

        int split = firstParameterDelimiter(jdbcUrl);
        if (split < 0) {
            return jdbcUrl;
        }
        String base = jdbcUrl.substring(0, split);
        char opener = jdbcUrl.charAt(split);
        String separator = opener == '?' ? "&" : ";";

        // 两种风格可能混用（如 ";databaseName=db?foo=1"），所以两种分隔符都切。
        // 空参数段（";;" 或结尾的 ";"）没有语义，丢掉是安全的。
        java.util.List<String> params = new java.util.ArrayList<>();
        for (String raw : jdbcUrl.substring(split + 1).split("[;&]")) {
            String normalized = normalizeParameter(raw);
            if (!normalized.isEmpty()) {
                params.add(normalized);
            }
        }
        if (params.isEmpty()) {
            return base;
        }

        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        boolean duplicateKeys = params.stream()
                .map(ConnectionProperties::parameterKey)
                .anyMatch(key -> !seenKeys.add(key));
        java.util.List<String> ordered = duplicateKeys ? params : params.stream().sorted().toList();

        return base + opener + String.join(separator, ordered);
    }

    /**
     * 第一个参数分隔符（{@code ?} 或 {@code ;}）的下标，都没有时返回 -1。
     * 取两者中更靠前的那个，避免把 {@code ";databaseName=db?foo=1"} 的分号段误当成 base。
     */
    private static int firstParameterDelimiter(String jdbcUrl) {
        int query = jdbcUrl.indexOf('?');
        int semicolon = jdbcUrl.indexOf(';');
        if (query <= 0) return semicolon > 0 ? semicolon : -1;
        if (semicolon <= 0) return query;
        return Math.min(query, semicolon);
    }

    /** 键统一小写、键值两侧去空白；值本身<b>原样保留</b>，因为值就是语义。 */
    private static String normalizeParameter(String rawParameter) {
        String token = rawParameter.trim();
        if (token.isEmpty()) {
            return "";
        }
        int equals = token.indexOf('=');
        if (equals < 0) {
            return token.toLowerCase(java.util.Locale.ROOT);
        }
        String key = token.substring(0, equals).trim().toLowerCase(java.util.Locale.ROOT);
        String value = token.substring(equals + 1).trim();
        return key + "=" + value;
    }

    private static String parameterKey(String normalizedParameter) {
        int equals = normalizedParameter.indexOf('=');
        return equals < 0 ? normalizedParameter : normalizedParameter.substring(0, equals);
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
