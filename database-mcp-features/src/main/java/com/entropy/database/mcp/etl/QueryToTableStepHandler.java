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
import com.entropy.database.mcp.repository.EtlRowStream;
import com.entropy.database.mcp.repository.EtlSql;

/**
 * Handles QUERY_TO_TABLE steps: executes a source SQL and inserts results into a target table.
 *
 * <p>Reads the source in batches rather than into one list: this is the bulk data-movement step,
 * so the source table's size must not decide peak heap usage. The whole step is one transaction —
 * 见 {@link EtlRowStream}：分批提交会在中途失败时把半份数据留在目标表里。
 */
public class QueryToTableStepHandler implements StepHandler {

    @Override
    public StepType supports() {
        return StepType.QUERY_TO_TABLE;
    }

    @Override
    public long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                        Step step, JobExecutionEngine engine) {
        var dialect = target.getDialect();

        engine.validateSourceSql(step.sourceSql());

        // requireTargetTable = 校验 + 归一。原先只有 normalizeTableName（大小写归一），
        // 带空格/分号/引号/-- 的表名会原样进 INSERT INTO。
        String targetTable = EtlStepGuard.requireTargetTable(step, dialect);
        int batchSize = engine.batchSize(step);

        return EtlRowStream.copyInBatches(source, target, step.sourceSql(), batchSize,
                engine.maxSourceRows(step),
                // sink 是钉在本 step 事务连接上的批量写入口：所有批次同一个事务，失败整体回滚。
                (sink, batch, columns) ->
                        sink.batchInsert(EtlSql.insertInto(dialect, targetTable, columns), batch, columns));
    }
}
