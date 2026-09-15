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
package com.entropy.database.mcp.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把 JDBC URL 处理成可以安全出现在日志、工具返回值和异常消息里的形式。
 *
 * <p><b>为什么需要这个类</b>：0.4.0 之前有两份各自为政的实现——
 * {@code DynamicDataSourceManagerImpl.maskKey} 与 {@code ConfiguredConnectionRegistrar.safeUrl}。
 * 前者有两个可绕过的口子，而它的输出会经 {@code ConnectionMetadata.jdbcUrlMasked} 直接回给
 * MCP 调用方（{@code listConnections} / {@code describeConnection} / {@code getPoolStats}），
 * 也就是进模型上下文、再进客户端保存的对话历史：
 * <ul>
 *   <li>URL 里没有 {@code @} 时原样返回，于是
 *       {@code jdbc:mysql://host/db?password=secret} 与
 *       {@code jdbc:sqlserver://host;password=secret} 的密码被整段回显。
 *       {@code ByokProperties.UrlGuard} 的黑名单只拦代码执行类参数，不拦 {@code password}，
 *       所以这种 URL 是能注册进来的。</li>
 *   <li>{@code jdbc:oracle:thin:user/pw@//host:1521/svc} 这种形态里
 *       {@code indexOf("//")} 落在 {@code indexOf('@')} 之后，拼出的前缀把明文原样带上，
 *       结果是 {@code jdbc:oracle:thin:user/pw@//****@//host:1521/svc}。</li>
 * </ul>
 *
 * <p><b>参数一律走白名单</b>：凭证可以藏在无数种参数名里（{@code password}、{@code pwd}、
 * {@code PWD}、{@code passwd}、{@code trustStorePassword}、{@code accessToken}…），黑名单漏一个就是
 * 一次泄漏——上面第一个口子正是这个形状。所以这里只保留<b>已知安全且对辨识连接有用</b>的键，
 * 其余全部丢弃并以 {@code …} 标记，宁可少显示也不冒险。
 *
 * <p><b>为什么放在 core 的 util 而不是 byok</b>：{@code byok} 包已经依赖 {@code repository}
 * （{@code ByokInfrastructure} 持有 {@code DatabaseReadRepository}），而
 * {@code DatabaseReadRepository.getDatabaseInfo} 也要用这个掩码，放在 {@code byok} 会形成
 * {@code byok ↔ repository} 的包循环，被 ArchUnit R5 拦下。它本身只是纯字符串函数，不含任何
 * BYOK 或仓储概念，放在两者下方的 core 才是它该在的位置。
 *
 * <p><b>先掩 userinfo、再切参数段</b>（0.4.x 之前是反过来的，那个顺序有第三个口子）：口令是任意字节，
 * 可以含 {@code ?}。先按第一个 {@code ?} / {@code ;} 切，{@code jdbc:mysql://app:p?w@host/db} 的 base
 * 段就变成 {@code jdbc:mysql://app:p}——不含 {@code @}，userinfo 掩码原样放过，于是回显口令前缀
 * {@code app:p}。现在先定位 userinfo 终点（{@link #userInfoEnd}），掩掉之后才从 {@code @} 之后去找参数
 * 分隔符，口令里含什么分隔符都不再影响切分。
 *
 * <p>反过来，{@code @} 也可能<b>属于参数值</b>而不是 userinfo（Azure SQL 的
 * {@code ;user=me@srv} 是标准写法）。无条件「掩到最后一个 {@code @}」会把主机名一起吃掉，让
 * {@code listConnections} 失去唯一的辨识价值，所以 {@link #userInfoEnd} 从后往前逐个候选判定：候选段里
 * 出现 {@code =} 就说明它是 {@code key=value} 参数而不是 userinfo，跳过它继续往前找。两种极端形态
 * （口令含 {@code =} 又含 {@code ?}）由 {@link #redactCredentialLookingAuthority} 兜底：宁可多掩一次
 * 主机名，也不回显疑似凭证的 {@code //user:pw} 段。
 *
 * <p>刻意与 {@code ConnectionProperties.normalizeJdbcUrl} 分开：那份服务于「两个连接是否共用一个
 * Hikari 池」的指纹计算，判错会导致串库；这份服务于展示，判错会导致泄密。两者的白名单目前内容相近，
 * 但改动理由完全不同，合并会让其中一个的约束被另一个的需求悄悄改掉。
 */
public final class JdbcUrlMasker {

    private static final String REDACTED = "****";

    /**
     * 允许原样显示的参数键（小写）：只有这些既不承载凭证、又能帮运维分辨「这个连接连的是哪个库」。
     * 新增条目前请确认该参数在所有目标驱动上都不可能承载凭证。
     */
    private static final Set<String> DISPLAY_SAFE_PARAMETER_KEYS = Set.of(
            "databasename",   // SQL Server / DB2
            "database",       // jTDS / ClickHouse 等
            "currentschema",  // PostgreSQL / DB2
            "schema",
            "searchpath",
            "servertimezone",
            "usessl",
            "ssl",
            "sslmode",
            "encrypt",
            "characterencoding",
            "charset"
    );

    private JdbcUrlMasker() {
    }

    /**
     * 隐去 URL 里的凭证：{@code //user:pw@host} 与 {@code :user/pw@//host} 两种 userinfo 形态都替换成
     * {@code ****}，参数段只保留 {@link #DISPLAY_SAFE_PARAMETER_KEYS}。
     *
     * <p>处理顺序是「先掩 userinfo、再切参数」，理由见类注释：反过来会在口令含 {@code ?} / {@code ;}
     * 时把口令前缀留在 base 段里回显。
     *
     * @param jdbcUrl 原始 URL，可为 {@code null}
     * @return 可安全外发的字符串；{@code null} 入参返回字面量 {@code "null"}，便于直接拼进日志
     */
    public static String mask(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "null";
        }
        if (jdbcUrl.isBlank()) {
            return jdbcUrl;
        }

        int at = userInfoEnd(jdbcUrl);
        // scanFrom：参数分隔符只在 userinfo 之后才有意义。userinfo 未识别出来时（没有 @，或所有 @ 都
        // 判定为参数值）退回从头扫，与 0.4.x 行为一致。
        int scanFrom = at < 0 ? 0 : at;
        int split = firstParameterDelimiter(jdbcUrl, scanFrom);
        String tail = split < 0 ? jdbcUrl.substring(scanFrom) : jdbcUrl.substring(scanFrom, split);
        String maskedBase = at < 0
                ? (jdbcUrl.indexOf('@') >= 0 ? redactCredentialLookingAuthority(tail) : tail)
                : jdbcUrl.substring(0, authorityStart(jdbcUrl, at)) + REDACTED + tail;
        if (split < 0) {
            return maskedBase;
        }

        char opener = jdbcUrl.charAt(split);
        String separator = opener == '?' ? "&" : ";";
        // 两种风格可能混用（如 ";databaseName=db?foo=1"），所以两种分隔符都切。
        String[] rawParameters = jdbcUrl.substring(split + 1).split("[;&]");

        List<String> kept = new ArrayList<>();
        boolean dropped = false;
        for (String raw : rawParameters) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (DISPLAY_SAFE_PARAMETER_KEYS.contains(parameterKey(token))) {
                kept.add(token);
            } else {
                dropped = true;
            }
        }

        StringBuilder result = new StringBuilder(maskedBase);
        if (!kept.isEmpty()) {
            result.append(opener).append(String.join(separator, kept));
        }
        if (dropped) {
            // 明确告诉读者「这里还有参数，被我摘了」，避免误以为连接真的没带参数
            result.append(kept.isEmpty() ? opener + "…" : separator + "…");
        }
        return result.toString();
    }

    /**
     * userinfo 段终点（那个 {@code @} 的下标），识别不出来时返回 -1。
     *
     * <p>取<b>最后</b>一个 {@code @} 起判：主机名不含 {@code @}，而口令可能含。候选段
     * （{@link #authorityStart} 到 {@code @}）里出现 {@code =} 时说明这个 {@code @} 属于
     * {@code key=value} 参数值（Azure SQL 的 {@code ;user=me@srv}），跳过它继续往前找上一个 {@code @}，
     * 于是 {@code //app:pw@host/db?user=a@b} 这种混合形态也能落到真正的 userinfo 上。
     *
     * <p>{@code jdbc:oracle:thin:@host:1521/svc} 这种「有 @ 但 userinfo 为空」的常见写法必须原样返回，
     * 否则会凭空多出一个 {@code ****}，让人以为 URL 里藏了凭证——由 {@code start >= at} 判掉。
     */
    private static int userInfoEnd(String jdbcUrl) {
        for (int at = jdbcUrl.lastIndexOf('@'); at > 0; at = jdbcUrl.lastIndexOf('@', at - 1)) {
            int start = authorityStart(jdbcUrl, at);
            if (start <= 0 || start >= at) {
                continue;
            }
            int equals = jdbcUrl.indexOf('=', start);
            if (equals < 0 || equals > at) {
                return at;
            }
        }
        return -1;
    }

    /**
     * userinfo 段起点：URL 里有 {@code //} 且在 {@code @} 之前时（{@code jdbc:mysql://app:pw@host}）
     * 从 {@code //} 之后算；否则从 {@code @} 之前最后一个 {@code :} 之后算
     * （{@code jdbc:oracle:thin:app/pw@//host}，这里 {@code indexOf("//")} 落在 {@code @} 之后）。
     */
    private static int authorityStart(String jdbcUrl, int at) {
        int slashSlash = jdbcUrl.indexOf("//");
        return (slashSlash >= 0 && slashSlash < at) ? slashSlash + 2 : jdbcUrl.lastIndexOf(':', at) + 1;
    }

    /**
     * 兜底：base 段里没识别出 userinfo，但整串里存在 {@code @}，说明切分可能被口令里的 {@code =} 和
     * {@code ?} 同时骗过（{@code //app:p?w=x@host}）。此时只要 authority 段形如 {@code x:y} 且 {@code y}
     * 不是纯数字端口，就按「疑似凭证」整段掩掉。
     *
     * <p>代价是 {@code //[::1]/db} 这类无端口 IPv6 会被多掩一次，所以只在整串含 {@code @} 时才启用——
     * 没有 {@code @} 就不可能有 userinfo，{@code host:port} 该原样显示。宁可偶尔多掩一次主机名，
     * 也不能回显口令片段。
     */
    private static String redactCredentialLookingAuthority(String base) {
        int slashSlash = base.indexOf("//");
        if (slashSlash < 0) {
            return base;
        }
        int start = slashSlash + 2;
        int pathStart = base.indexOf('/', start);
        int end = pathStart < 0 ? base.length() : pathStart;
        if (start >= end) {
            return base;
        }
        String authority = base.substring(start, end);
        int colon = authority.lastIndexOf(':');
        if (colon <= 0) {
            return base;
        }
        String afterColon = authority.substring(colon + 1);
        if (!afterColon.isEmpty() && afterColon.chars().allMatch(Character::isDigit)) {
            return base; // host:port，不是凭证
        }
        return base.substring(0, start) + REDACTED + base.substring(end);
    }

    /** 从 {@code from} 起第一个参数分隔符（{@code ?} 或 {@code ;}）的下标，都没有时返回 -1。 */
    private static int firstParameterDelimiter(String jdbcUrl, int from) {
        int query = jdbcUrl.indexOf('?', from);
        int semicolon = jdbcUrl.indexOf(';', from);
        // 下标 0 不算：整串就是一个分隔符时（"?"、";;;"）没有 base 段可掩，原样返回更诚实。
        if (query <= 0) {
            return semicolon > 0 ? semicolon : -1;
        }
        if (semicolon <= 0) {
            return query;
        }
        return Math.min(query, semicolon);
    }

    private static String parameterKey(String parameter) {
        int equals = parameter.indexOf('=');
        String key = equals < 0 ? parameter : parameter.substring(0, equals);
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
