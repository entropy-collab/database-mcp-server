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
package com.entropy.database.mcp.security;

import com.entropy.database.mcp.contract.SqlTailNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 依次询问各个 {@link SqlTailNormalizer}，并强制它们只能截后缀。
 *
 * <h2>为什么把约束做在框架侧</h2>
 * 这个缝放松的是写入路径的闸门，所以「实现只会截后缀」不能是实现的承诺，必须是调用方能验证的
 * 事实。下面两条检查合起来把实现的能力限死：
 * <ol>
 *   <li><b>必须是严格前缀</b>——返回值拼上被截掉的那段必须逐字节等于原文。实现因此无法改写
 *       语句内容，{@code DROP TABLE t} 变不成 {@code SELECT 1}。</li>
 *   <li><b>被截掉的那段只能是关键字与空白</b>（见 {@link #SAFE_TAIL}）。这一条挡住的是把第二条
 *       语句藏进尾巴：{@code ... LOCAL; DROP TABLE t} 的尾巴含 {@code DROP} 后面的字母，
 *       正则不匹配，于是整条被拒。末尾允许一个分号，因为它后面只允许空白，无法再跟一条语句。</li>
 * </ol>
 *
 * <p>注意第二条挡的不只是分号：引号、注释起止符、括号、逗号一概不在字符类里，所以尾巴里塞不进
 * 字符串字面量或注释，也就没有「用注释吃掉后半句」这条路。
 *
 * <h2>违约就是拒绝，不是纠正</h2>
 * 任何一条检查不过，这里不去猜实现想干什么，直接当作它没认出这条语句（并留一条 warn 日志指名
 * 是哪个实现违约）。校验失败的默认结局仍然是拒绝执行。
 */
final class SqlTailNormalizers {

    private static final Logger log = LoggerFactory.getLogger(SqlTailNormalizers.class);

    /**
     * 被截掉的尾巴允许的形状：可有前导空白，随后是以字母开头的「字母与空白」序列，末尾可以有
     * 一个分号和空白。前导空白是必要的——实现返回的前缀通常止于关键字之前的那个空格。
     *
     * <p>刻意不允许数字与下划线：目前要摘的尾巴都是裸关键字（{@code LOCAL} / {@code GLOBAL}），
     * 放开标识符字符只会扩大截断面而没有对应的用例。将来若有 {@code PARTITIONS 8} 这类带数字的
     * 尾巴，那时再连同用例一起放宽，而不是现在先留口子。
     */
    private static final Pattern SAFE_TAIL = Pattern.compile("\\s*[A-Za-z][A-Za-z\\s]*;?\\s*");

    private final List<SqlTailNormalizer> normalizers;

    SqlTailNormalizers(List<SqlTailNormalizer> normalizers) {
        this.normalizers = normalizers.stream()
                .sorted(Comparator.comparingInt(SqlTailNormalizer::priority))
                .toList();
    }

    /**
     * @param sql 已 trim、解析失败的单条语句原文
     * @return 可以拿去重新解析的前缀；empty 表示没有实现认出它，或认出的那个违约了
     */
    Optional<String> stripTail(String sql) {
        for (SqlTailNormalizer normalizer : normalizers) {
            Optional<String> candidate;
            try {
                candidate = normalizer.stripUnparsableTail(sql);
            } catch (RuntimeException e) {
                // 实现抛异常不能把整条校验带崩：它的职责只是「认不认识」。
                log.warn("SQL tail normalizer '{}' threw, ignoring it", normalizer.name(), e);
                continue;
            }
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String stripped = candidate.get();
            if (isWithinContract(normalizer, sql, stripped)) {
                log.debug("SQL tail normalizer '{}' stripped {} trailing char(s) for validation",
                        normalizer.name(), sql.length() - stripped.length());
                return Optional.of(stripped);
            }
        }
        return Optional.empty();
    }

    private static boolean isWithinContract(SqlTailNormalizer normalizer, String sql, String stripped) {
        if (stripped.isBlank() || stripped.length() >= sql.length() || !sql.startsWith(stripped)) {
            log.warn("SQL tail normalizer '{}' returned something that is not a strict prefix of the "
                    + "input; ignoring it", normalizer.name());
            return false;
        }
        String tail = sql.substring(stripped.length());
        if (!SAFE_TAIL.matcher(tail).matches()) {
            log.warn("SQL tail normalizer '{}' tried to strip a tail that is not keywords-only; "
                    + "ignoring it", normalizer.name());
            return false;
        }
        return true;
    }
}
