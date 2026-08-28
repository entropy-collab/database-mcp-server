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
package com.entropy.database.mcp.optimizer;

public enum OptimizationSuggestion {
    ADD_INDEX("添加索引", "Add index"),
    QUERY_REWRITE("查询重写", "Query rewrite"),
    STATISTICS_REFRESH("刷新统计信息", "Refresh statistics"),
    PARTITIONING("分区优化", "Partitioning"),
    JOIN_OPTIMIZATION("连接优化", "Join optimization"),
    NULL_HANDLING("空值处理", "Null handling"),
    PREDICATE_REFACTOR("谓词重构", "Predicate refactor"),
    HINT_GUIDE("Hint 建议", "Hint guide");

    private final String zh;
    private final String en;

    OptimizationSuggestion(String zh, String en) {
        this.zh = zh;
        this.en = en;
    }

    public String getZh() { return zh; }
    public String getEn() { return en; }
}
