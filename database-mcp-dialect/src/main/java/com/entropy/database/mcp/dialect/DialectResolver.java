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
package com.entropy.database.mcp.dialect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves {@link DatabaseDialect} implementations by name or JDBC URL.
 *
 * <p>Every dialect — including the eight that ship with the server — arrives through
 * {@link DialectProvider} and {@link ServiceLoader}. This class holds no dialect table: it builds
 * two lookup maps at construction (name/alias → dialect, JDBC URL prefix → dialect) and does
 * nothing but consult them.
 *
 * <p>That is a deliberate change from the earlier design, which had two parallel {@code switch}
 * statements over the built-in dialects and checked the ServiceLoader only before the first one.
 * The consequences of that design were: adding a dialect meant editing three places that could
 * drift apart, and a third-party dialect could never be found by {@code dialect=auto} because URL
 * detection had no extension point.
 *
 * <h2>Precedence</h2>
 * A third-party provider claiming a name or URL prefix that a built-in already owns wins, and the
 * override is logged. Among providers of the same tier the first one wins, which is classpath
 * order — so two third-party providers fighting over one name is a configuration error, not
 * something this class tries to arbitrate.
 *
 * <h2>Sharing</h2>
 * Providers are loaded once and their dialects handed out by reference; callers used to receive a
 * fresh instance per {@code resolve}. Dialects are stateless (they hold only {@code static final}
 * SQL text), and {@code DialectStatelessnessTest} keeps them that way.
 */
public class DialectResolver {

    private static final Logger log = LoggerFactory.getLogger(DialectResolver.class);

    /** Name reserved for the fallback dialect; also the name {@code GenericDialect} reports. */
    private static final String GENERIC = "generic";

    /** Requests the dialect be inferred from the live connection's JDBC URL. */
    private static final String AUTO = "auto";

    private final Map<String, DatabaseDialect> byName;
    private final Map<String, DatabaseDialect> byUrlPrefix;
    private final DatabaseDialect fallback;

    public DialectResolver() {
        this(ServiceLoader.load(DialectProvider.class));
    }

    /**
     * Visible for testing so a fake provider can be injected without a {@code META-INF/services}
     * file on the test classpath.
     */
    DialectResolver(Iterable<DialectProvider> providers) {
        Map<String, Registration> names = new LinkedHashMap<>();
        Map<String, Registration> prefixes = new LinkedHashMap<>();

        for (DialectProvider provider : providers) {
            DatabaseDialect dialect = provider.getDialect();
            if (dialect == null) {
                log.warn("Ignoring dialect provider {}: getDialect() returned null",
                        provider.getClass().getName());
                continue;
            }
            claim(names, provider.getName(), provider, dialect, "name");
            for (String alias : provider.getAliases()) {
                claim(names, alias, provider, dialect, "alias");
            }
            for (String prefix : provider.getJdbcUrlPrefixes()) {
                claim(prefixes, prefix, provider, dialect, "JDBC URL prefix");
            }
        }

        this.byName = flatten(names);
        // Longest prefix first: a longer, more specific prefix must never be shadowed by a shorter
        // one that happens to be its head (e.g. jdbc:sqlserver: vs a hypothetical jdbc:sql:).
        Map<String, DatabaseDialect> orderedPrefixes = new TreeMap<>(
                Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        orderedPrefixes.putAll(flatten(prefixes));
        this.byUrlPrefix = Collections.unmodifiableMap(orderedPrefixes);
        this.fallback = byName.getOrDefault(GENERIC, new GenericDialect());
    }

    /**
     * Resolve a dialect by its registered name.
     *
     * @param dialectName a name or alias, {@code auto} to infer from the connection, or
     *                    {@code null}/blank for the fallback
     * @param dataSource  only consulted when {@code dialectName} is {@code auto}
     * @return the matching dialect, or {@link GenericDialect} when nothing matches
     */
    public DatabaseDialect resolve(String dialectName, DataSource dataSource) {
        if (dialectName == null || dialectName.isBlank()) {
            return fallback;
        }
        String key = normalize(dialectName);
        if (AUTO.equals(key)) {
            return detectFromJdbcUrl(dataSource);
        }
        DatabaseDialect dialect = byName.get(key);
        if (dialect == null) {
            log.warn("Unknown dialect '{}', falling back to '{}'. Registered: {}",
                    dialectName, GENERIC, byName.keySet());
            return fallback;
        }
        return dialect;
    }

    /** @return every registered name and alias, for diagnostics and error messages */
    public Set<String> registeredNames() {
        return byName.keySet();
    }

    // ─── JDBC URL detection ────────────────────────────────────────────────

    private DatabaseDialect detectFromJdbcUrl(DataSource dataSource) {
        if (dataSource == null) {
            return fallback;
        }
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        } catch (Exception e) {
            // dialect=auto on an unreachable datasource must not abort startup: the generic dialect
            // still answers every metadata query, just without product-specific SQL.
            log.warn("Cannot read the JDBC URL for dialect auto-detection, using '{}': {}",
                    GENERIC, e.getMessage());
            return fallback;
        }
        return forJdbcUrl(url);
    }

    /**
     * Matches {@code url} against the registered prefixes.
     *
     * <p>Package-private rather than private so {@code DialectResolverTest} can exercise URL
     * matching without a live {@link DataSource}.
     */
    DatabaseDialect forJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            return fallback;
        }
        String lower = normalize(url);
        for (Map.Entry<String, DatabaseDialect> entry : byUrlPrefix.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return fallback;
    }

    // ─── Registry construction ─────────────────────────────────────────────

    private record Registration(DatabaseDialect dialect, boolean builtIn, String providerClass) {
    }

    private static void claim(Map<String, Registration> target, String rawKey,
                              DialectProvider provider, DatabaseDialect dialect, String kind) {
        if (rawKey == null || rawKey.isBlank()) {
            log.warn("Ignoring blank {} declared by dialect provider {}",
                    kind, provider.getClass().getName());
            return;
        }
        String key = normalize(rawKey);
        String providerClass = provider.getClass().getName();
        Registration existing = target.get(key);
        if (existing == null) {
            target.put(key, new Registration(dialect, provider.isBuiltIn(), providerClass));
            return;
        }
        if (existing.builtIn() && !provider.isBuiltIn()) {
            log.info("Dialect {} '{}' overridden by {} (was the built-in {})",
                    kind, key, providerClass, existing.providerClass());
            target.put(key, new Registration(dialect, false, providerClass));
            return;
        }
        log.warn("Dialect {} '{}' is already claimed by {}; ignoring {}",
                kind, key, existing.providerClass(), providerClass);
    }

    private static Map<String, DatabaseDialect> flatten(Map<String, Registration> registrations) {
        Map<String, DatabaseDialect> flat = new LinkedHashMap<>();
        registrations.forEach((key, registration) -> flat.put(key, registration.dialect()));
        return Map.copyOf(flat);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
