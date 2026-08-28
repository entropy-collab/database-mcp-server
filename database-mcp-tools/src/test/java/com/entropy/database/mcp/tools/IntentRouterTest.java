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

import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.tools.SampleTools.HealthLikeTools;
import com.entropy.database.mcp.tools.SampleTools.QueryLikeTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code suggestTools} 的候选集必须来自 {@link ToolCatalog}，且只做建议、不做拦截。
 */
class IntentRouterTest {

    private final ToolCatalog catalog = new ToolCatalog(
            FixedObjectProvider.of(new QueryLikeTools(), new HealthLikeTools()));

    private final IntentRouter router = new IntentRouter(catalog);

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> suggestions(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("suggestions");
    }

    private static List<String> names(Map<String, Object> result) {
        return suggestions(result).stream().map(s -> (String) s.get("name")).toList();
    }

    @Test
    void matchesChineseIntentAgainstToolSummary() {
        Map<String, Object> result = router.suggestTools("帮我执行一条查询看看数据", null);

        assertThat(names(result)).first().isEqualTo("executeSample");
        assertThat(result.get("confidence")).isIn("high", "medium");
    }

    @Test
    void matchesEnglishTagKeyword() {
        Map<String, Object> result = router.suggestTools("need a flashback read", null);

        assertThat(names(result)).first().isEqualTo("flashbackSample");
    }

    @Test
    void reportsTotalRegisteredToolCount() {
        assertThat(router.suggestTools("查询", null).get("totalTools")).isEqualTo(catalog.size());
    }

    @Test
    void returnsEmptySuggestionsWhenNothingMatches() {
        Map<String, Object> result = router.suggestTools("zzzz qqqq", null);

        assertThat(suggestions(result)).isEmpty();
        assertThat(result.get("confidence")).isEqualTo("none");
    }

    @Test
    void clampsLimitToBounds() {
        assertThat(suggestions(router.suggestTools("read query health", 1))).hasSize(1);
        // 0 与负数收敛到 1，而不是返回空结果
        assertThat(suggestions(router.suggestTools("read query health", 0))).hasSize(1);
    }

    @Test
    void neverSuggestsItself() {
        ToolCatalog withRouter = new ToolCatalog(FixedObjectProvider.of(
                new QueryLikeTools(), new IntentRouter(catalog)));

        Map<String, Object> result = new IntentRouter(withRouter)
                .suggestTools("routing discovery 推荐工具", null);

        assertThat(names(result)).doesNotContain("suggestTools");
    }

    @Test
    void rejectsBlankIntent() {
        assertThatThrownBy(() -> router.suggestTools("  ", null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("intent");
    }

    @Test
    void tokenizesMixedChineseAndLatin() {
        assertThat(IntentRouter.tokenize("导出csv文件")).contains("导出", "csv", "文件");
        // 过短的拉丁词被丢弃，避免 of/in 之类噪声参与匹配
        assertThat(IntentRouter.tokenize("in of the table")).containsExactly("the", "table");
    }
}
