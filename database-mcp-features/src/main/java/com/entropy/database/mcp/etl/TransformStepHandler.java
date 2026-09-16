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

import java.util.ArrayList;
import java.util.List;

/**
 * Handles TRANSFORM steps: applies column mappings and transforms, then inserts into target table.
 *
 * <p>Note: the insert runs on the <em>source</em> context, matching the behaviour this handler has
 * always had; {@code targetConnection} is not honoured for TRANSFORM.
 */
public class TransformStepHandler implements StepHandler {

    @Override
    public StepType supports() {
        return StepType.TRANSFORM;
    }

    @Override
    public long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                        Step step, JobExecutionEngine engine) {
        List<String> columnMapping = engine.getListParam(step, "columnMapping", List.of());
        String whereClause = engine.getStringParam(step, "whereClause", null);

        var dialect = source.getDialect();

        engine.validateSourceSql(step.sourceSql());

        // 三道校验都在 EtlStepGuard 里：列映射两侧必须是纯标识符、whereClause 必须过 WHERE 白名单、
        // 目标表名必须是合法标识符。同步版 EtlTools.transformAndInsert 一直有这三道，异步版曾全部遗漏——
        // 当时 "(SELECT PASSWORD FROM USERS):X" 是个合法映射，whereClause 也能直接闭合谓词追加 SQL。
        // 引擎在派发前已经过一遍闸；这里再走一遍是因为 handler 也可以被直接调用（测试与将来的调用方），
        // 而且解析结果本身就是下面建 SQL 要用的东西，不算重复逻辑。
        EtlStepGuard.ColumnMapping mapping = EtlStepGuard.parseColumnMapping(columnMapping, dialect);
        EtlStepGuard.requireSafeWhereClause(whereClause);
        List<String> sourceColumns = mapping.sourceColumns();
        List<String> targetColumns = mapping.targetColumns();
        List<String> transforms = mapping.transforms();

        StringBuilder selectSql = new StringBuilder("SELECT ");
        List<String> selectExprs = new ArrayList<>();
        for (int i = 0; i < sourceColumns.size(); i++) {
            // quote 而非裸拼：校验已经保证是纯标识符，quote 再挡住关键字冲突与大小写敏感的目标库。
            String src = dialect.quote(sourceColumns.get(i));
            String transform = transforms.get(i);
            String expr = switch (transform) {
                case "upper" -> "UPPER(" + src + ")";
                case "lower" -> "LOWER(" + src + ")";
                case "trim" -> "TRIM(" + src + ")";
                case "int" -> "CAST(" + src + " AS INTEGER)";
                case "long" -> "CAST(" + src + " AS BIGINT)";
                case "double" -> "CAST(" + src + " AS DOUBLE)";
                default -> src;
            };
            selectExprs.add(expr + " AS " + dialect.quote(targetColumns.get(i)));
        }
        selectSql.append(String.join(", ", selectExprs));
        selectSql.append(" FROM (").append(step.sourceSql()).append(") AS _src");
        if (whereClause != null && !whereClause.isBlank()) {
            selectSql.append(" WHERE ").append(whereClause);
        }

        String targetTable = EtlStepGuard.requireTargetTable(step, dialect);
        int batchSize = engine.batchSize(step);

        // Column labels come from the SELECT above, which aliases every expression to its target
        // column, so the batch keys are exactly the columns the INSERT names.
        // 读写同一个 context，所以整个 step 只占一条连接、跑在一个事务里（见 EtlRowStream）。
        return EtlRowStream.copyInBatches(source, source, selectSql.toString(), batchSize,
                engine.maxSourceRows(step),
                (sink, batch, columns) ->
                        sink.batchInsert(EtlSql.insertInto(dialect, targetTable, columns), batch, columns));
    }
}
