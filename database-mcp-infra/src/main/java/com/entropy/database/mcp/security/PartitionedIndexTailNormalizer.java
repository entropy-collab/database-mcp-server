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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 摘掉建索引语句末尾的 {@code LOCAL} / {@code GLOBAL}——分区表上的索引分区方式。
 *
 * <h2>要解决的具体问题</h2>
 * 参照表一旦是分区表，它的索引通常都写 {@code LOCAL}（随表分区）。解析器的联合语法不认这个
 * 尾巴，于是建表能过、建索引过不去——同一份迁移脚本卡在一半。而这个尾巴与闸门关心的三件事
 * （语句类型、目标对象、有没有藏第二条语句）全都无关：它只决定索引的物理分区方式。
 *
 * <h2>为什么只认这两个词</h2>
 * Oracle 建索引后面还能跟 {@code PARALLEL}、{@code NOLOGGING}、{@code COMPRESS}、
 * {@code TABLESPACE xxx} 等一堆子句。这里刻意只认 {@code LOCAL} / {@code GLOBAL} 两个裸关键字：
 * 每多认一种就多一分误截的可能，而目前只有这一种在真实脚本里挡路。别的等有用例再加，
 * 加的时候是往这张表里添词、连带添用例，而不是把匹配放宽成「末尾任意关键字」。
 *
 * <p>同理，正则要求语句确实以 {@code CREATE [UNIQUE|BITMAP] INDEX} 开头。不这么限制的话，
 * 任何一条以 {@code LOCAL} 结尾的语句都会被截一刀——包括本该被拒的那些。
 */
final class PartitionedIndexTailNormalizer implements SqlTailNormalizer {

    /**
     * {@code CREATE [UNIQUE|BITMAP] INDEX ... LOCAL|GLOBAL [;]}
     *
     * <p>{@code DOTALL} 是必要的：迁移脚本里的建索引语句常常跨行。第一组用懒量词，好让末尾的
     * {@code LOCAL} 由后面那个分支来匹配而不是被 {@code .*} 吞掉。
     */
    private static final Pattern INDEX_WITH_PARTITION_SCOPE = Pattern.compile(
            "^(CREATE\\s+(?:(?:UNIQUE|BITMAP)\\s+)*INDEX\\b.*?)\\s+(?:LOCAL|GLOBAL)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public String name() {
        return "partitioned-index-scope";
    }

    @Override
    public Optional<String> stripUnparsableTail(String sql) {
        Matcher matcher = INDEX_WITH_PARTITION_SCOPE.matcher(sql);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        // group(1) 从下标 0 开始，所以返回值天然是原文前缀——调用方那道前缀检查这里必然通过，
        // 留着它是为了约束将来别的实现，不是为了防这一个。
        return Optional.of(matcher.group(1));
    }
}
