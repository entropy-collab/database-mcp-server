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

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP Prompts that guide AI models on how to use the database MCP server.
 * <p>
 * Prompts appear in {@code prompts/list} and can be invoked by clients
 * to receive structured guidance for common workflows.
 */
@Component
public class PromptTools {

    private static final Logger log = LoggerFactory.getLogger(PromptTools.class);

    @McpPrompt(
            name = "database-quick-start",
            title = "数据库快速上手",
            description = "连接数据库并跑出第一条查询的分步指引。尚未配置任何数据库连接时使用。"
    )
    public McpSchema.GetPromptResult quickStart(
            @McpArg(name = "databaseType", description = "数据库类型，取值：h2、oracle、mysql、postgres、sqlserver、sqlite、db2", required = false) String databaseType) {

        String type = (databaseType != null && !databaseType.isBlank()) ? databaseType.toLowerCase() : "your database";

        String guide = """
                本 MCP 服务不预置任何数据库，必须先自行注册连接。

                第 1 步：注册命名连接
                调用 createNamedConnection，参数如下：
                  - name：自定义连接名，后续所有工具用它引用这个库（如 "my-db"）
                  - jdbcUrl：JDBC 连接串（如 "jdbc:postgresql://localhost:5432/mydb"）
                  - username：数据库用户名
                  - password：数据库密码
                  - dialect：%s（取值 h2、oracle、mysql、postgres、sqlserver、sqlite、db2、generic；留空则按 jdbcUrl 自动推断）

                第 2 步：确认连接已就绪
                连接注册是异步的。调用 describeConnection 确认状态后再继续，否则查询可能报连接不存在。

                第 3 步：探查库结构
                先 listSchemas 拿 Schema 清单，再 listTables 看表，最后 describeTable 看字段类型。
                表名拼写不确定时用 searchTables 跨 Schema 模糊搜索。

                第 4 步：执行查询
                SQL 里不含外部输入值时用 executeQuery；条件里带用户提供的值时一律用 executeQueryWithFilter（命名占位符 :name，防注入）。

                提示：已注册多个连接时，每个工具都要显式传 connection 参数。
                """.formatted(type);

        return new McpSchema.GetPromptResult(
                "数据库快速上手指南",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(guide)
                ))
        );
    }

    @McpPrompt(
            name = "connect-to-database",
            title = "注册数据库连接",
            description = "按数据库类型给出正确的 JDBC 连接串格式，并说明 BYOK 连接的注册与校验步骤。"
    )
    public McpSchema.GetPromptResult connectToDatabase(
            @McpArg(name = "databaseType", description = "数据库类型，取值：h2、oracle、mysql、postgres、sqlserver、sqlite、db2", required = true) String databaseType) {

        String urlTemplate = switch (databaseType.toLowerCase()) {
            case "h2" -> "jdbc:h2:mem:testdb（内存库）或 jdbc:h2:file:/path/to/db（文件库）";
            case "oracle" -> "jdbc:oracle:thin:@//host:port/service_name";
            case "mysql" -> "jdbc:mysql://host:port/database?useSSL=false&serverTimezone=UTC";
            case "postgres", "postgresql" -> "jdbc:postgresql://host:port/database";
            case "sqlserver" -> "jdbc:sqlserver://host:port;databaseName=database";
            case "sqlite" -> "jdbc:sqlite:/path/to/database.db";
            case "db2" -> "jdbc:db2://host:port/database";
            default -> "jdbc:<driver>://host:port/database";
        };

        String guide = """
                连接 %s 数据库的步骤：

                1. 准备 JDBC 连接串：
                   %s

                2. 调用 createNamedConnection，参数：
                   - name：自定义连接名（如 "%s-prod"）
                   - jdbcUrl：第 1 步得到的连接串
                   - username：数据库用户名
                   - password：数据库密码
                   - dialect：%s

                3. 校验连接：
                   用第 2 步的连接名调用 describeConnection。注册是异步的，状态就绪后再执行查询。
                """.formatted(databaseType.toLowerCase(), urlTemplate, databaseType.toLowerCase(), databaseType.toLowerCase());

        return new McpSchema.GetPromptResult(
                "注册 " + databaseType.toUpperCase() + " 数据库连接",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(guide)
                ))
        );
    }

    @McpPrompt(
            name = "query-workflow",
            title = "查询工作流",
            description = "探查并查询数据库的推荐流程，说明 listSchemas、listTables、describeTable、executeQuery、executeQueryWithFilter 如何配合使用。"
    )
    public McpSchema.GetPromptResult queryWorkflow() {

        String guide = """
                数据库探查与查询的推荐流程：

                1. 注册连接（若尚未注册）
                   调用 createNamedConnection，再用 describeConnection 确认已就绪。

                2. 列出 Schema
                   调用 listSchemas，拿到可用 Schema 名称。

                3. 列出表
                   调用 listTables，传入 Schema 名与连接名，返回表名、表类型与行数估算。
                   表名拼写不确定时改用 searchTables 跨 Schema 模糊搜索。

                4. 查看表结构
                   调用 describeTable，传入表名与连接名，返回列名、数据类型、是否可空。

                5. 执行查询
                   SQL 中不含外部输入值：调用 executeQuery。
                   条件里带用户提供的值：调用 executeQueryWithFilter，sql 用命名占位符（"SELECT * FROM t WHERE id = :id"），params 传 {"id": 123}，可防 SQL 注入。

                6. 分页取大结果集
                   executeQuery 传 maxRows 控制单页行数；返回的 hasMore=true 时，把 continuationToken 传回去取下一页。

                7. 多条独立查询
                   用 batchQuery 一次并发执行（最多 5 条），避免串行等待。

                提示：写 SQL 前先调用 getDatabaseInfo 确认数据库类型，再按对应方言写分页与函数语法。
                """;

        return new McpSchema.GetPromptResult(
                "数据库查询工作流",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(guide)
                ))
        );
    }

    @McpPrompt(
            name = "dba-guided-tasks",
            title = "DBA 运维任务导航",
            description = """
                常见 DBA 操作的分类导航：健康巡检、性能调优、备份恢复、数据质量。
                用户询问数据库维护、故障排查或优化时使用。
                """)
    public McpSchema.GetPromptResult dbaGuidedTasks() {
        String guide = """
                ## 数据库运维任务导航

                先调用 listConnections 看有哪些库可用，再选择下面的分类。
                注意：本节大部分工具依赖 Oracle 数据字典（dba_* / v$ 视图），需要 DBA 权限；非 Oracle 库上多数会报「不支持该方言」。

                ### 1. 健康巡检
                - checkHealth：连通性探活，全方言支持
                - listActiveSessions：当前活动会话
                - showLocks：锁竞争明细
                - showBlockingTree：阻塞链（定位是谁堵住了谁）
                - getPoolStats：连接池指标
                排查顺序建议：showBlockingTree 找到阻塞源头 → showLocks 看具体锁对象 → listActiveSessions 确认会话身份 → 必要时 killSession（不可恢复，未提交事务会回滚）。

                ### 2. 性能调优
                - assessQueryRisk：先给 SQL 打风险分（不执行 SQL）
                - explainPlan：取数据库原生执行计划（riskLevel=high 时必须先做这步）
                - interpretPlan：把计划文本翻成可读解读
                - recommendIndexes：按表名给索引建议
                - suggestRewrites：按 SQL 给重写建议（纯静态分析，不连库）
                - analyzeQuery：一站式跑完计划 + 索引 + 重写建议
                想一次拿全结论用 analyzeQuery；只要原始计划用 explainPlan。

                ### 3. 备份与恢复
                - backupTable：导出单表数据
                - backupSchema：导出单表 DDL（仅 Oracle 支持）
                - listBackups / getBackup：查备份清单与详情
                - restoreBackup / quickRestore：从备份恢复（清空目标表用 DELETE，单事务，失败可回滚）
                - deleteBackup / cleanupBackups：删除备份，不可恢复

                ### 4. 数据质量与资产
                - checkTableQuality：跑空值率与重复行检查（内置检查始终执行，customRules 是追加）
                - listQualityRuleTemplates：先看可用规则模板再执行检查
                - getQualityAlertSummary：看告警汇总
                - generateCatalog / scanSchema / searchAssets：生成数据目录、扫描入库、检索已有资产
                - classifyColumn / listSensitiveColumns：敏感字段识别

                需要哪一类？告诉我目标库和表名，我来执行。
                """;

        return new McpSchema.GetPromptResult(
                "DBA 运维任务导航",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(guide)
                ))
        );
    }

    @McpPrompt(
            name = "sql-best-practices",
            title = "SQL 编写规范检查",
            description = """
                如何写出安全、高效的 SQL：参数化、分页、索引利用、方言兼容与常见反模式。
                起草查询或复核 SQL 的安全性与性能时使用。
                """)
    public McpSchema.GetPromptResult sqlBestPractices() {
        String guide = """
                ## SQL 编写规范检查清单

                ### 安全
                1. 一律用参数化查询——条件里带外部输入值时用 executeQueryWithFilter，不要用 executeQuery 拼字符串
                2. 严禁把用户输入拼进 SQL——用 :name 命名占位符，实参放 params
                3. 表名、列名必须是合法标识符（字母、数字、下划线），本服务会做校验，非法标识符直接拒绝
                4. 始终设置 maxRows，避免大结果集拖垮内存

                ### 性能
                5. 先评估再执行——assessQueryRisk 打分，riskLevel=high 必须先 explainPlan
                6. 大结果集走分页——executeQuery 的 maxRows + continuationToken
                7. 只查需要的列，避免 SELECT *
                8. 不要无 WHERE 全表扫描
                9. 避免 N+1——多条独立查询用 batchQuery 并发（最多 5 条），不要循环调用

                ### 方言兼容
                10. 先 getDatabaseInfo 确认数据库类型，再按对应方言写语法
                11. 分页语法不同：MySQL / PostgreSQL 用 LIMIT OFFSET，Oracle 用 ROWNUM 或 FETCH FIRST n ROWS ONLY，SQL Server 用 TOP 或 OFFSET FETCH
                12. information_schema 只有 MySQL / PostgreSQL / SQL Server 有；Oracle 要查 user_tables / all_tab_columns。跨方言取元数据请直接用 listTables / describeTable，不要自己写 SQL

                ### 下一步
                需要我做哪一项：
                - 复核某条 SQL 是否符合上述规范
                - 取某条 SQL 的执行计划并解读
                - 给某张慢表推荐索引
                """;

        return new McpSchema.GetPromptResult(
                "SQL 编写规范",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(guide)
                ))
        );
    }

    @McpPrompt(
            name = "etl-pipeline-guide",
            title = "ETL 数据管道指南",
            description = """
                搭建 ETL 管道的分步指引：表到表转换写入、查询结果落表、批量幂等写入、跨库搬数与异步作业。
                导入数据或构建数据加工流程时使用。
                """)
    public McpSchema.GetPromptResult etlPipelineGuide() {
        String guide = """
                ## ETL 数据管道工作流

                前置条件：ETL 系列工具需要开启 entropy.mcp.gateway.enabled=true，否则这些工具不会注册。
                所有方案都需要先 createNamedConnection 注册连接，并用 describeConnection 确认就绪。

                ### 方案 A：表到表转换写入（同库）
                调用 transformAndInsert：
                - connectionName：连接名
                - sourceTable / targetTable：源表与目标表（目标表须已存在）
                - columnMapping：列映射数组，每项格式 源列:目标列[:转换]，转换可选 upper、lower、trim、int、long、double
                - whereClause：可选过滤条件，只写 WHERE 之后的部分
                示例 columnMapping：["id:ID", "name:FULL_NAME:upper"]

                ### 方案 B：查询结果落表（同库，自动分页）
                调用 exportQueryToTable：
                - sourceSql：源 SELECT 语句，会被分页执行
                - targetTable：目标表，列名需与源结果集一致
                适合带 JOIN、聚合的复杂查询结果物化。注意翻页有上限，触顶时返回的 rowCount 是部分结果。

                ### 方案 C：跨库搬数
                调用 insertQueryResult：
                - sourceConnectionName / sourceSql：源库连接名与查询
                - targetConnectionName / targetTable：目标库连接名与目标表
                注意源结果整体读入内存，batchSize 只作用于写入侧，大表请自行按时间分片多次调用。

                ### 方案 D：外部数据写入
                - insertData：纯插入，rows 传行数组（每行是列名到值的 Map），列集合以第一行为准
                - upsertData：按 keyColumns 匹配，存在则更新、不存在则插入，可重复执行不产生重复数据

                ### 方案 E：多步骤异步作业
                调用 submitEtlJob 提交作业定义（含 id、name、steps），步骤 type 取值：query_to_table、query_to_json、read、transform、ddl、upsert、export，支持 dependsOn 声明依赖。
                提交后立即返回，必须用 getJobStatus 轮询状态；listJobs 看全部作业。
                注意 stopJob 当前只登记停止请求，不会真正中断正在执行的作业。

                ### 写入前后建议
                写入前用 validateDataQuality 或 checkTableQuality 校验源数据；改结构前用 backupData 做数据快照。

                需要哪个方案？告诉我源和目标，我来生成具体调用。
                """;

        return new McpSchema.GetPromptResult(
                "ETL 数据管道指南",
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(guide)
                ))
        );
    }
}
