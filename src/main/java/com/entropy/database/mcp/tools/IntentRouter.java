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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.tools.ToolCatalog.ToolDescriptor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具推荐器：把一句自然语言诉求映射到本服务真实注册的 MCP 工具上。
 *
 * <p>候选集完全来自 {@link ToolCatalog}（运行期反射得到的真实工具名、分组、标签、摘要），
 * 因此不存在会漂移的静态工具名清单。
 *
 * <p><b>破坏性变更（0.1.4）</b>：本类曾是 {@code ToolWhitelistAspect} 的判定依据，在
 * {@code entropy.mcp.intent.filter.enabled=true} 时会对不在推荐列表里的工具调用抛
 * {@code SEC003 TOOL_FILTERED}。该拦截语义已彻底移除，原因有二：
 * <ul>
 *   <li>它不省任何 token —— {@code tools/list} 仍把全部工具交给模型，只是在调用落地后才拒绝；</li>
 *   <li>它依赖硬编码工具名表，已与真实方法名漂移，一旦启用就会大面积误杀。</li>
 * </ul>
 * 真正的暴露面收敛改由 {@link ToolExposureFilter} 在启动期裁剪工具清单实现。配置项
 * {@code entropy.mcp.intent.filter.enabled} 与错误码 {@code SEC003} 同步删除，
 * 升级时若外部配置里仍有该键，它将被忽略。
 */
@Component
public class IntentRouter extends McpToolBase {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 30;

    /** 命中工具名或标签的权重，明显高于仅命中摘要正文。 */
    private static final int STRONG_MATCH_WEIGHT = 3;
    private static final int WEAK_MATCH_WEIGHT = 1;

    private static final int HIGH_CONFIDENCE_SCORE = 6;
    private static final int MEDIUM_CONFIDENCE_SCORE = 3;

    /** 参与匹配的最短拉丁词长度，过滤 of/in 之类噪声。 */
    private static final int MIN_LATIN_TOKEN_LENGTH = 3;

    private final ToolCatalog catalog;

    public IntentRouter(ToolCatalog catalog) {
        this.catalog = catalog;
    }

    @McpTool(name = "suggestTools", description = """
            【推荐工具】根据一句自然语言诉求，从本服务真实注册的工具里挑出最相关的若干个，返回工具名与用途摘要。
            前置条件：无，不访问数据库，不需要 connection。
            使用场景：不确定该调哪个工具时先问一次；工具数量多、名称相近（如 getStatistics 与 getPoolStats、backupTable 与 exportQueryToTable）时用它缩小范围。
            返回字段：intent（回显输入）、confidence（high/medium/low/none，反映匹配强度而非结果正确性）、totalTools（当前实际注册的工具总数）、suggestions（数组，每项含 name、group、summary、tags、score）。
            注意：这是建议而非授权，返回列表之外的工具同样可以直接调用；confidence=none 表示没有任何关键词命中，此时 suggestions 为空数组，应改用更具体的措辞重试。
            不要用于：获取表结构或字段（用 describeTable）；执行查询（用 executeQuery）；查看连接清单（用 listConnections）。
            标签：[read, meta, discovery, routing]
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> suggestTools(
            @McpToolParam(description = """
                    自然语言诉求，中文或英文均可，例如"想看某张表的字段和索引"、"备份一张表"、"export csv"。
                    直接传 SQL 语句意义不大：匹配依据是工具用途描述，不是 SQL 语义。
                    """) String intent,
            @McpToolParam(description = "返回条数上限，默认 8，最大 30；超出范围会被收敛到边界值。",
                    required = false) Integer limit) {
        validateRequired(intent, "intent");
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        Set<String> tokens = tokenize(intent);
        List<Map<String, Object>> suggestions = new ArrayList<>();
        int topScore = 0;

        List<Scored> scored = new ArrayList<>();
        for (ToolDescriptor descriptor : catalog.descriptors()) {
            // 跳过自身，避免"推荐工具"把自己排进结果里
            if (descriptor.name().equals("suggestTools")) {
                continue;
            }
            int score = score(descriptor, tokens);
            if (score > 0) {
                scored.add(new Scored(descriptor, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.descriptor().name()));

        for (Scored s : scored.stream().limit(effectiveLimit).toList()) {
            topScore = Math.max(topScore, s.score());
            suggestions.add(Map.of(
                    "name", s.descriptor().name(),
                    "group", s.descriptor().group(),
                    "summary", s.descriptor().summary(),
                    "tags", s.descriptor().tags(),
                    "score", s.score()));
        }

        return success(Map.of(
                "intent", intent,
                "confidence", confidenceOf(topScore),
                "totalTools", catalog.size(),
                "suggestions", suggestions));
    }

    private record Scored(ToolDescriptor descriptor, int score) {
    }

    private static int score(ToolDescriptor descriptor, Set<String> tokens) {
        String name = descriptor.name().toLowerCase();
        String text = descriptor.searchableText();
        int score = 0;
        for (String token : tokens) {
            if (name.contains(token) || descriptor.tags().contains(token)
                    || descriptor.group().contains(token)) {
                score += STRONG_MATCH_WEIGHT;
            } else if (text.contains(token)) {
                score += WEAK_MATCH_WEIGHT;
            }
        }
        return score;
    }

    private static String confidenceOf(int topScore) {
        if (topScore >= HIGH_CONFIDENCE_SCORE) {
            return "high";
        }
        if (topScore >= MEDIUM_CONFIDENCE_SCORE) {
            return "medium";
        }
        return topScore > 0 ? "low" : "none";
    }

    /**
     * 切词：拉丁串按非字母数字切分并丢掉过短的词，CJK 串取全部二字连续片段。
     *
     * <p>取二字片段而不是整串，是因为中文诉求里的有效关键词长度不定（"备份"、"表结构"、
     * "慢查询"），二字片段能同时覆盖这几种情况，且不需要引入分词依赖。
     */
    static Set<String> tokenize(String input) {
        Set<String> tokens = new LinkedHashSet<>();
        String lower = input.toLowerCase();
        for (String word : lower.split("[^\\p{IsHan}a-z0-9]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (word.codePoints().anyMatch(Character::isIdeographic)) {
                collectHanTokens(word, tokens);
            } else if (word.length() >= MIN_LATIN_TOKEN_LENGTH) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    private static void collectHanTokens(String word, Set<String> tokens) {
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isIdeographic(c)) {
                han.append(c);
            } else {
                flushHan(han, tokens);
                if (Character.isLetterOrDigit(c)) {
                    // 中英混排片段里的拉丁/数字部分单独成词，如 "csv导出" 里的 csv
                    int start = i;
                    while (i < word.length() && !Character.isIdeographic(word.charAt(i))) {
                        i++;
                    }
                    String latin = word.substring(start, i);
                    if (latin.length() >= MIN_LATIN_TOKEN_LENGTH) {
                        tokens.add(latin);
                    }
                    i--;
                }
            }
        }
        flushHan(han, tokens);
    }

    private static void flushHan(StringBuilder han, Set<String> tokens) {
        if (han.length() == 1) {
            tokens.add(han.toString());
        }
        for (int i = 0; i + 2 <= han.length(); i++) {
            tokens.add(han.substring(i, i + 2));
        }
        han.setLength(0);
    }
}
