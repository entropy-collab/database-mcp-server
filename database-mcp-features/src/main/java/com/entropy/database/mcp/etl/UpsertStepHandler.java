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
 * Handles UPSERT steps using dialect-specific buildUpsertSql.
 *
 * <p><b>方言实现内部是裸拼，校验责任在调用方——这是当前契约。</b>
 * {@code DatabaseDialect.buildUpsertSql(table, columns, keyColumns)} 的各实现把表名与列名直接
 * 字符串相加进 SQL，不做标识符校验。所以在调它<em>之前</em>必须先确认表名、列名、键列都是纯标识符。
 * 之所以不在 dialect 模块里补校验：那是零依赖叶子模块（见 0.4.0 的方言契约下沉），且它没有
 * 抛业务异常的能力；把闸放在调用方也让「谁负责校验」这件事在一个地方说清楚。
 */
public class UpsertStepHandler implements StepHandler {

    @Override
    public StepType supports() {
        return StepType.UPSERT;
    }

    @Override
    public long execute(ByokDataSourceContext source, ByokDataSourceContext target,
                        Step step, JobExecutionEngine engine) {
        var dialect = target.getDialect();
        JdbcTemplate jdbc = target.getEtlJdbcTemplate();
        List<String> keyColumns = engine.getListParam(step, "keyColumns", List.of());
        // 原先只有 normalizeTableName（大小写归一，不是校验）；同步版 EtlTools.upsertData 走
        // ByokDatabaseFacade.validateIdentifiers，异步版这条路径完全绕开了它。
        String tableName = EtlStepGuard.requireTargetTable(step, dialect);
        EtlStepGuard.requireIdentifiers(keyColumns, dialect, "keyColumns");

        engine.validateSourceSql(step.sourceSql());

        int batchSize = engine.batchSize(step);

        // Read the source in batches and upsert each batch; the column list is identical for every
        // batch, so the statement is rebuilt from the batch's own columns without drifting.
        // batchJdbc 是钉在本 step 事务连接上的模板：所有批次同一个事务，失败整体回滚。
        return EtlRowStream.copyInBatches(source.getEtlJdbcTemplate(), jdbc, step.sourceSql(), batchSize,
                engine.maxSourceRows(step),
                (batchJdbc, columns, batch) -> {
                    // 列名来自结果集标签，仍然会被 buildUpsertSql 裸拼进 SQL，所以同样过闸。
                    EtlStepGuard.requireIdentifiers(columns, dialect, "source column");
                    String upsertSql = dialect.buildUpsertSql(tableName, columns, keyColumns);
                    if (upsertSql == null) {
                        throw new UnsupportedOperationException(
                                "UPSERT not supported for dialect: " + dialect.getClass().getSimpleName());
                    }
                    return EtlSql.sum(batchJdbc.batchUpdate(upsertSql, batch, batch.size(),
                            EtlSql.bindColumns(columns)));
                });
    }
}
