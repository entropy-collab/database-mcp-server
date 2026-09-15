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
package com.entropy.database.mcp.etl;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Handles DDL steps: executes DDL statement list.
 *
 * <p>每条语句都先过 {@code JobExecutionEngine.validateDdl} → {@code SqlValidator.validateDdl}。
 * 这个 handler 原先直接 {@code jdbcTemplate.update(ddl)}，一道闸都不过——而 tools 层做同一件事的
 * {@code DdlExecutionTools.executeDdlBatch} 一直是逐条校验的。语句白名单（例如拒掉能定义 Java
 * 函数的 {@code CREATE ALIAS}）属于 {@code SqlValidator} 的实现细节，这里只负责把语句送进去。
 */
public class DdlStepHandler implements StepHandler {

    @Override
    public StepType supports() {
        return StepType.DDL;
    }

    @Override
    public long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                        Step step, JobExecutionEngine engine) {
        JdbcTemplate jdbcTemplate = source.getDdlJdbcTemplate();
        List<String> statements = engine.getListParam(step, "statements", List.of());

        // 先全部校验再执行：DDL 多半不在一个事务里，逐条边校验边执行会在第 N 条被拒时
        // 留下前 N-1 条已生效的结构变更，而调用方看到的只是一条校验失败。
        for (String ddl : statements) {
            engine.validateDdl(ddl);
        }

        int totalAffected = 0;
        for (String ddl : statements) {
            totalAffected += jdbcTemplate.update(ddl);
        }
        return totalAffected;
    }
}
