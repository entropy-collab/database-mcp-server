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
package com.entropy.database.mcp.repository;

import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.domain.StandardizedPlan;

import java.util.List;
import java.util.Map;

/**
 * Execution plan analysis repository.
 * Provides standardized EXPLAIN PLAN functionality across dialects.
 */
public interface ExecutionPlanRepository {

    /**
     * Get standardized execution plan for a SQL query.
     */
    StandardizedPlan getExecutionPlan(String sql);

    /**
     * Get execution plan with performance warnings.
     */
    PlanAnalysis analyzeExecutionPlan(String sql);

    /**
     * 拿执行计划的原始行，一行一个计划步骤，列名随方言。
     *
     * <p>这是"怎么 EXPLAIN"的唯一出口：调用方只给 SELECT，方言差异全在实现里。
     * 之前调用方各自拼 {@code dialect.getExplainPlanSql(sql)} 再当查询执行，在 Oracle 上必然拿不到东西——
     * Oracle 的 {@code EXPLAIN PLAN FOR} 不返回结果集，而是往会话级临时表 {@code SYS.PLAN_TABLE$}
     * （实测 {@code TEMPORARY=Y / DURATION=SYS$SESSION}）写行，必须紧接着在<b>同一条物理连接</b>上
     * 查回来；跨连接查是空的。
     *
     * <p>「唯一出口」也包括品种判断：实现按 {@code DatabaseDialect.explainWritesToPlanTable()} 决定走
     * 一步还是两步，不再按 {@code dialect.getClass().getSimpleName()} 认 Oracle——类名判断对子类和
     * CGLIB 代理都是静默失效，正好把上面这个 bug 放回去。
     *
     * @param sql 要分析的 SELECT 语句
     * @return 计划行；方言不支持 EXPLAIN 时返回空列表
     */
    List<Map<String, Object>> explainPlanRows(String sql);
}
