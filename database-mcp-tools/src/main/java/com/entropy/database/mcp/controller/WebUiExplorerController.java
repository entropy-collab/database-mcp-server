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
package com.entropy.database.mcp.controller;

import com.entropy.database.mcp.tools.BackupTools;
import com.entropy.database.mcp.tools.CatalogTools;
import com.entropy.database.mcp.tools.CdcTools;
import com.entropy.database.mcp.tools.EtlTools;
import com.entropy.database.mcp.tools.LineageTools;
import com.entropy.database.mcp.tools.OptimizationTools;
import com.entropy.database.mcp.tools.QualityTools;
import com.entropy.database.mcp.tools.QueryAnalysisTools;
import com.entropy.database.mcp.tools.QueryTools;
import com.entropy.database.mcp.tools.SchemaTools;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读运维页面的「浏览」半边：schema 元数据、数据资产与敏感列、血缘、ETL 作业、备份、CDC、
 * 数据质量、SQL 体检。{@link WebUiController} 那边是进程内状态（连接、池、审计、指标）与
 * DBA 诊断视图，两个类共用 {@code /api/ui} 前缀（Spring 按具体路径匹配，不冲突），
 * 因此 {@code SecurityConfig} 里 {@code /api/**} 那条 Ant 规则自动覆盖本类的全部路径，
 * <b>不需要、也不应该为这批端点改任何安全配置</b>。
 *
 * <h2>为什么单独一个类</h2>
 * <p>{@link WebUiController} 的构造器已经有 10 个参数，本类要用的工具 bean 又是 10 个。
 * 塞在一起之后没人能一眼看出「哪个端点用哪个 bean」，而且一个工具 bean 的可缺失性
 * （见下面的 {@link EtlTools}）会牵连到另一半端点的可用性判断。分成两个类之后，
 * 唯一必须留在 {@link WebUiController} 的是 DBA 那组新 view：清单常量
 * {@code DBA_VIEWS} 与 {@code /api/ui/dba/views} 只能有一份，前端从那一份取。
 *
 * <h2>这批端点大部分会真的连业务库，而且失败是常态</h2>
 * <p>{@code /schema/**}、{@code /catalog/**}、{@code /lineage/**}、{@code /cdc/**}、
 * {@code /quality/**}、{@code /sql/**} 全部会对被注册的连接发 SQL 或读数据字典。
 * 连接名不存在、连不上、账号没权限、方言不提供对应 SQL（{@code EXPLAIN}、CDC 的 LSN
 * 这类只有部分方言有实现）时，工具抛 {@code McpToolException}，在 HTTP 上就是
 * <b>500 + 那条消息</b>。这里<b>刻意不 catch</b>：把「方言不支持」和「权限不足」压成
 * 200 + 空结果，页面就会永远显示「这个库没有索引 / 没有血缘 / 没有敏感字段」，
 * 而真实原因只留在服务端日志里。页面靠状态码与消息区分这几种失败，属于预期行为，不需要报警。
 *
 * <p>相对的，<b>调用方少传必填参数一律是 400</b>（{@link #requireParam}）：不挡的话请求会走到
 * 工具的必填校验，同样抛 {@code McpToolException} → 500，而「参数没传」和「服务器坏了」
 * 在页面上的处理不同（前者不该弹重试、不该报警）。
 *
 * <h2>{@code /lineage/config} 必须和血缘图一起展示</h2>
 * <p>{@link #lineageConfig()} 报的 {@code foreignKeyEnabled} 是血缘功能的实际前提：
 * 血缘边只从外键约束推导，所以<b>库里没有外键、或这个开关被关掉时，血缘图就是空的</b>，
 * 而空图长得和「这张表确实没有上下游」完全一样。页面必须把这份配置和空图一起显示，
 * 否则一张空图无法自解释——运维会去查连接、查权限，而真实原因是一个配置项。
 * 同理 {@code enabled}、{@code maxTraversalDepth}、{@code maxTablesPerGraph}：
 * 后两个会让深图与大图被静默截断。
 *
 * <h2>{@code /sql/**} 保持 GET，SQL 走查询参数</h2>
 * <p>这个页面的安全边界就是「只发 GET」：全站没有一个写路径，前端也不需要 CSRF token。
 * 把 SQL 改成 POST body 会更干净，但会打破那条边界，因此刻意不改。代价必须写清楚：
 * <ul>
 *   <li><b>URL 长度上限</b>：Tomcat 的 {@code maxHttpRequestHeaderSize}（常见默认约 8KB）
 *       约束的是整个请求行 + 头部，超长 SQL 会以 400 或截断的形式失败，而不是「分析结果不对」；
 *       粘一段几千行的报表 SQL 进去就会撞到；</li>
 *   <li><b>SQL 会进 access log</b>：查询参数会被网关、反向代理、容器的访问日志原样记下来，
 *       连带 SQL 里的字面量（可能含手机号、证件号这类值）。POST body 通常不记，query string 一定记。</li>
 * </ul>
 * 这两条都不是这批端点新引入的能力（同样的分析作为 MCP 工具一直可调），但传输方式是这里选的，
 * 所以评估部署风险时算在这里。
 */
@RestController
@RequestMapping("/api/ui")
public class WebUiExplorerController {

    private final SchemaTools schemaTools;
    private final QueryTools queryTools;
    private final CatalogTools catalogTools;
    private final LineageTools lineageTools;
    private final BackupTools backupTools;
    private final CdcTools cdcTools;
    private final QualityTools qualityTools;
    private final QueryAnalysisTools queryAnalysisTools;
    private final OptimizationTools optimizationTools;

    /**
     * <b>唯一可能缺席的工具 bean。</b>{@link EtlTools} 类上是
     * {@code @ConditionalOnProperty(name = "entropy.mcp.gateway.enabled", havingValue = "true")}
     * 且没有 {@code matchIfMissing}，所以 gateway 关掉（或键没配）时容器里根本没有这个 bean。
     * 必须按可缺失注入：写成必需依赖的话，关掉 gateway 的部署会在启动时就因为
     * {@code NoSuchBeanDefinitionException} 起不来——为了页面上两个作业端点让整个服务启动失败，
     * 是最坏的一种坏法。缺席时 {@link #gatewayDisabled} 给出 200 + {@code enabled=false}，
     * 而不是 500：「这个部署没开 ETL」是一个配置事实，不是错误。
     */
    private final @Nullable EtlTools etlTools;

    /**
     * 只用来报 {@code entropy.mcp.gateway.enabled} 的<b>生效值</b>，读法与
     * {@link WebUiController#info()} 一致（{@code getProperty(键, "false")} + {@code parseBoolean}）。
     * 刻意不新增 {@code @ConfigurationProperties}：这个键在仓库里从来没有对应的配置类，
     * 为一个布尔值新增一个绑定类只会多出第二个「默认值是什么」的说法。
     */
    private final Environment environment;

    public WebUiExplorerController(SchemaTools schemaTools,
                                   QueryTools queryTools,
                                   CatalogTools catalogTools,
                                   LineageTools lineageTools,
                                   BackupTools backupTools,
                                   CdcTools cdcTools,
                                   QualityTools qualityTools,
                                   QueryAnalysisTools queryAnalysisTools,
                                   OptimizationTools optimizationTools,
                                   @Nullable EtlTools etlTools,
                                   Environment environment) {
        this.schemaTools = schemaTools;
        this.queryTools = queryTools;
        this.catalogTools = catalogTools;
        this.lineageTools = lineageTools;
        this.backupTools = backupTools;
        this.cdcTools = cdcTools;
        this.qualityTools = qualityTools;
        this.queryAnalysisTools = queryAnalysisTools;
        this.optimizationTools = optimizationTools;
        this.etlTools = etlTools;
        this.environment = environment;
    }

    // ─── Schema 浏览 ───────────────────────────────────────────────────────

    /**
     * 连接上的 Schema 清单。
     *
     * <p>GET /api/ui/schema/schemas?connection=xxx → {@code {connection, schemas: [...]}}
     *
     * <p>{@code SchemaTools.listSchemas} 返回的是裸 {@code List<String>}。包一层是为了给
     * {@code connection} 的回显留位置——页面上同时开着多个连接时，一份没有归属的名字数组
     * 无法判断是哪个库的。包装键名就是内容名（{@code schemas}），工具里的字段一个都没改。
     */
    @GetMapping("/schema/schemas")
    public Map<String, Object> schemas(
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", connection);
        result.put("schemas", schemaTools.listSchemas(connection));
        return result;
    }

    /**
     * 指定 Schema 下的表清单（含行数估算，由工具决定）。
     *
     * <p>GET /api/ui/schema/tables?schema=&amp;connection= → {@code {connection, schema, tables: [...]}}
     *
     * <p>{@code schema} 不设必填：省略时由方言解析当前 Schema（Oracle 登录用户、
     * MySQL 当前 database、PostgreSQL {@code current_schema()}），这是工具既有的语义，
     * 在这里加一条必填校验等于把那个语义关掉。
     */
    @GetMapping("/schema/tables")
    public Map<String, Object> tables(
            @RequestParam(name = "schema", required = false) @Nullable String schema,
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", connection);
        result.put("schema", schema);
        result.put("tables", schemaTools.listTables(schema, connection));
        return result;
    }

    /**
     * 指定 Schema 下的视图及其 SQL 定义。
     *
     * <p>GET /api/ui/schema/views?schema=&amp;connection= → {@code {connection, schema, views: [...]}}
     */
    @GetMapping("/schema/views")
    public Map<String, Object> views(
            @RequestParam(name = "schema", required = false) @Nullable String schema,
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", connection);
        result.put("schema", schema);
        result.put("views", schemaTools.listViews(schema, connection));
        return result;
    }

    /**
     * 一张表的索引明细。
     *
     * <p>GET /api/ui/schema/indexes?table=T&amp;schema=&amp;connection=
     * → {@code {connection, schema, table, indexes: [...]}}
     *
     * <p>{@code table} 必填（缺失 400）：不传的话工具会去查「表名为 null 的索引」，
     * 结果是一次没有意义的库往返加一条 500。
     */
    @GetMapping("/schema/indexes")
    public Map<String, Object> indexes(
            @RequestParam(name = "table", required = false) @Nullable String table,
            @RequestParam(name = "schema", required = false) @Nullable String schema,
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        String effectiveTable = requireParam(table, "table");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", connection);
        result.put("schema", schema);
        result.put("table", effectiveTable);
        result.put("indexes", schemaTools.listIndexes(effectiveTable, schema, connection));
        return result;
    }

    /**
     * 指定 Schema 下的序列。
     *
     * <p>GET /api/ui/schema/sequences?schema=&amp;connection= → {@code {connection, schema, sequences: [...]}}
     */
    @GetMapping("/schema/sequences")
    public Map<String, Object> sequences(
            @RequestParam(name = "schema", required = false) @Nullable String schema,
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", connection);
        result.put("schema", schema);
        result.put("sequences", schemaTools.listSequences(schema, connection));
        return result;
    }

    /**
     * 表结构（列名、类型、可空性）。
     *
     * <p>GET /api/ui/schema/table?table=T&amp;schema=&amp;connection=
     * → 原样透传 {@code SchemaTools.describeTable}：命中时 {@code {table, schema, columnCount, columns}}，
     * 未命中时 {@code {error, table, schema, schemaSource, hint}}。
     *
     * <p><b>未命中不是错误</b>：工具返回 200 + {@code error} 与 {@code hint}，其中
     * {@code schemaSource} 说明这次实际搜的 Schema 是调用方给的还是方言默认的——页面要把这两个字段
     * 显示出来，否则「表不存在」和「该显式传 schema」这两种情况在界面上一模一样。
     */
    @GetMapping("/schema/table")
    public Map<String, Object> describeTable(
            @RequestParam(name = "table", required = false) @Nullable String table,
            @RequestParam(name = "schema", required = false) @Nullable String schema,
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        return schemaTools.describeTable(requireParam(table, "table"), schema, connection);
    }

    /**
     * 按关键词跨 Schema 模糊搜表。
     *
     * <p>GET /api/ui/schema/search?keyword=k&amp;connection= → {@code {connection, keyword, tables: [...]}}
     *
     * <p>{@code keyword} 必填（缺失 400）：工具允许空关键词并把它当成「返回全部表」，
     * 那在页面上是一次误触就拉全库表名的行为，这里不给这个默认。
     */
    @GetMapping("/schema/search")
    public Map<String, Object> searchTables(
            @RequestParam(name = "keyword", required = false) @Nullable String keyword,
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        String effectiveKeyword = requireParam(keyword, "keyword");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", connection);
        result.put("keyword", effectiveKeyword);
        result.put("tables", schemaTools.searchTables(effectiveKeyword, connection));
        return result;
    }

    /**
     * 连接对端的产品与驱动信息。
     *
     * <p>GET /api/ui/schema/database-info?connection= → 原样透传
     * {@code QueryTools.getDatabaseInfo}（{@code databaseProductName}、
     * {@code databaseProductVersion}、{@code driverName} 等）。
     *
     * <p>页面用它回答「我连的到底是什么」：{@code /api/ui/connections} 报的是注册时声明的方言，
     * 这里报的是<b>对端真实自述</b>的产品与版本，两者不一致就是「方言配错了」，
     * 而方言配错的表现往往是别的端点莫名 500。
     */
    @GetMapping("/schema/database-info")
    public Map<String, Object> databaseInfo(
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        return queryTools.getDatabaseInfo(connection);
    }

    // ─── 数据资产与敏感列 ──────────────────────────────────────────────────

    /**
     * 整个 Schema 的目录扫描汇总。
     *
     * <p>GET /api/ui/catalog/scan?connection=&amp;schema= → 原样透传
     * {@code CatalogTools.scanSchema}（{@code connection}、{@code schema}、{@code totalTables}、
     * {@code sensitiveTableCount}、{@code entries}）。
     *
     * <p><b>这是本批里最慢的端点</b>：每张表要三次元数据往返（并发上限 4，工具内部定的），
     * 大 Schema 上是分钟级。页面不要把它放进自动刷新，也不要在 tab 切换时预取。
     * 单表失败不会中断扫描，那张表在 {@code entries} 里是「生成失败」占位。
     */
    @GetMapping("/catalog/scan")
    public Map<String, Object> catalogScan(
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "schema", required = false) @Nullable String schema) {
        return catalogTools.scanSchema(connection, schema);
    }

    /**
     * Schema 内被判定为敏感的字段清单。
     *
     * <p>GET /api/ui/catalog/sensitive?connection=&amp;schema= → 原样透传
     * {@code CatalogTools.listSensitiveColumns}（{@code sensitiveColumnCount}、{@code columns} 等）。
     *
     * <p>敏感级别是<b>按字段名与注释的命名规则推断</b>的，不看数据内容：命名不规范的库会漏判，
     * 名字像敏感字段而实际不是的会误判。页面上这份清单是线索，不是合规结论。
     */
    @GetMapping("/catalog/sensitive")
    public Map<String, Object> catalogSensitive(
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "schema", required = false) @Nullable String schema) {
        return catalogTools.listSensitiveColumns(connection, schema);
    }

    /**
     * 单表目录：字段级的敏感级别与业务分类。
     *
     * <p>GET /api/ui/catalog/table?tableName=T&amp;connection= → 原样透传
     * {@code CatalogTools.generateCatalog}（{@code columns}、{@code columnCount}、
     * {@code maxSensitivity}、{@code hasSensitiveColumns} 等）。
     *
     * <p>参数名跟着工具叫 {@code tableName} 而不是 {@code table}：这一批里
     * {@code /schema/**} 用的是 {@code table}、{@code /catalog/**} 与 {@code /quality/**}
     * 用的是 {@code tableName}，两边都和各自工具的形参一致。刻意不统一成一个名字——
     * 统一之后前端某个地方传错了参数名，表现是「必填参数缺失」的 400，
     * 而与工具签名对齐至少让「照着工具文档拼 URL」这件事是对的。
     */
    @GetMapping("/catalog/table")
    public Map<String, Object> catalogTable(
            @RequestParam(name = "tableName", required = false) @Nullable String tableName,
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        return catalogTools.generateCatalog(connection, requireParam(tableName, "tableName"));
    }

    /**
     * 按关键词检索数据资产（匹配表名与表注释）。
     *
     * <p>GET /api/ui/catalog/search?keyword=k&amp;connection=&amp;limit=
     * → 原样透传 {@code CatalogTools.searchAssets}（{@code resultCount}、{@code maxResults}、{@code assets}）。
     *
     * <p>{@code limit} 传了才夹到 {@value WebUiController#MAX_LIMIT}，没传就保持 {@code null}
     * 让工具用配置里的 {@code entropy.mcp.database.catalog.max-search-results}。
     * 不给它一个本地默认值是刻意的：本地默认会盖掉那个配置，于是「改了配置没生效」。
     * 生效的上限由工具在 {@code maxResults} 里回显，页面显示那个值即可。
     */
    @GetMapping("/catalog/search")
    public Map<String, Object> catalogSearch(
            @RequestParam(name = "keyword", required = false) @Nullable String keyword,
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "limit", required = false) @Nullable Integer limit) {
        return catalogTools.searchAssets(connection, requireParam(keyword, "keyword"),
                limit != null ? clampLimit(limit) : null);
    }

    /**
     * 目录模块的生效配置。
     *
     * <p>GET /api/ui/catalog/config → 原样透传 {@code CatalogTools.getCatalogConfig}
     * （{@code enabled}、{@code autoGenerateComments}、{@code enableSensitiveDetection}、
     * {@code maxSearchResults}）。
     *
     * <p>不连库，因此它是这一组里唯一在没有任何注册连接时也能成功的端点——页面可以用它
     * 判断「目录功能有没有开」，而不必先失败一次 {@code /catalog/scan}。
     */
    @GetMapping("/catalog/config")
    public Map<String, Object> catalogConfig() {
        return catalogTools.getCatalogConfig();
    }

    // ─── 血缘 ──────────────────────────────────────────────────────────────

    /**
     * 一张表的上下游血缘。
     *
     * <p>GET /api/ui/lineage?table=T&amp;connection=&amp;maxDepth=&amp;format=
     * → 原样透传 {@code LineageTools.analyzeLineage}。
     *
     * <p>返回形状<b>由 {@code format} 决定</b>，这是页面必须知道的一件事：
     * {@code format=mermaid} 或 {@code dot} 时只有 {@code {format, graph}} 两个键（图文本在
     * {@code graph} 下，不是裸字符串）；省略或其它取值时是结构化 JSON——{@code directUpstream}、
     * {@code directDownstream}、{@code allUpstream}、{@code allDownstream}、{@code edgeCount}，
     * 检测到异常（如环）时才多一个 {@code anomalies}。按 {@code anomalies} 一定存在来写前端会踩空。
     *
     * <p>{@code maxDepth} 不在这里夹取：工具自己夹到 1..10，再受
     * {@code entropy.mcp.database.lineage.max-traversal-depth} 约束，
     * 这里再夹一层只会多出一个和那两者都不一致的第三个上限。
     */
    @GetMapping("/lineage")
    public Map<String, Object> lineage(
            @RequestParam(name = "table", required = false) @Nullable String table,
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "maxDepth", required = false) @Nullable Integer maxDepth,
            @RequestParam(name = "format", required = false) @Nullable String format) {
        return lineageTools.analyzeLineage(requireParam(table, "table"), connection, maxDepth, format);
    }

    /**
     * 变更影响面：沿外键向下游递归。
     *
     * <p>GET /api/ui/lineage/impact?table=T&amp;connection=&amp;maxDepth=
     * → 原样透传 {@code LineageTools.getImpactAnalysis}（{@code totalImpacted}、
     * {@code impactedTables}、{@code byDepth}，有异常时附 {@code anomalies}）。
     */
    @GetMapping("/lineage/impact")
    public Map<String, Object> lineageImpact(
            @RequestParam(name = "table", required = false) @Nullable String table,
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "maxDepth", required = false) @Nullable Integer maxDepth) {
        return lineageTools.getImpactAnalysis(requireParam(table, "table"), connection, maxDepth);
    }

    /**
     * 整个连接上的全部血缘边。
     *
     * <p>GET /api/ui/lineage/edges?connection= → 原样透传 {@code LineageTools.listAllEdges}
     * （{@code totalEdges}、{@code edges}）。
     *
     * <p>{@code totalEdges=0} 的解释见类注释：多数情况下是库里没有外键或
     * {@code foreignKeyEnabled=false}，不是「表之间真的没关系」。
     */
    @GetMapping("/lineage/edges")
    public Map<String, Object> lineageEdges(
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        return lineageTools.listAllEdges(connection);
    }

    /**
     * 血缘模块的生效配置。
     *
     * <p>GET /api/ui/lineage/config → 原样透传 {@code LineageTools.getLineageConfig}
     * （{@code enabled}、{@code foreignKeyEnabled}、{@code viewDependencyEnabled}、
     * {@code maxTraversalDepth}、{@code autoAnalyze}、{@code maxTablesPerGraph}）。
     *
     * <p><b>页面必须把这份配置和血缘图一起展示</b>，理由见类注释里 {@code foreignKeyEnabled} 那一段：
     * 空图不能自解释。
     */
    @GetMapping("/lineage/config")
    public Map<String, Object> lineageConfig() {
        return lineageTools.getLineageConfig();
    }

    // ─── ETL 作业 ──────────────────────────────────────────────────────────

    /**
     * ETL 作业列表。
     *
     * <p>GET /api/ui/jobs → gateway 开启时原样透传 {@code EtlTools.listJobs}
     * （{@code totalJobs}、{@code jobs}）；关闭时 200 + {@code {enabled, reason, property}}。
     *
     * <p>降级成 200 而不是 404/503：gateway 关掉是<b>部署选择</b>，页面该显示「本部署未启用 ETL」
     * 这句话，而不是一个让人去查日志的错误。判据是 {@link #etlTools} 这个 bean 在不在，
     * 与 {@code /api/ui/info} 里 {@code switches.gatewayEnabled} 读的是同一个键，两处不可能互相矛盾。
     */
    @GetMapping("/jobs")
    public Map<String, Object> jobs() {
        if (etlTools == null) {
            return gatewayDisabled();
        }
        return etlTools.listJobs();
    }

    /**
     * 单个 ETL 作业的执行状态。
     *
     * <p>GET /api/ui/jobs/status?jobId=xxx → gateway 开启时原样透传
     * {@code EtlTools.getJobStatus}（{@code job} 及步骤状态）；关闭时同 {@link #jobs()} 的降级体。
     *
     * <p>{@code jobId} 走<b>查询参数而不是路径变量</b>：jobId 由调用方自己起名，可能含 {@code /}、
     * {@code %} 或空格，落在路径段上会被 Spring 的路径匹配或容器的 URL 规范化吃掉
     * （表现是 404 或一个被截断的 id），而查询参数只做一次百分号解码。
     */
    @GetMapping("/jobs/status")
    public Map<String, Object> jobStatus(
            @RequestParam(name = "jobId", required = false) @Nullable String jobId) {
        String effectiveJobId = requireParam(jobId, "jobId");
        if (etlTools == null) {
            return gatewayDisabled();
        }
        return etlTools.getJobStatus(effectiveJobId);
    }

    /**
     * {@link EtlTools} 缺席时的统一返回体：{@code {enabled, reason, property}}。
     *
     * <p>{@code enabled} 报的是 {@code entropy.mcp.gateway.enabled} 的<b>生效值</b>而不是写死的
     * {@code false}：如果哪天 bean 缺席的原因变了（比如条件注解改了判据），这里会显示
     * 「开关是 true 但 bean 没有」这种自相矛盾的组合，而写死 false 会把矛盾藏起来。
     * 键名 {@code property} 把那个键本身报出来，让人不必翻源码就知道该配哪一项。
     */
    private Map<String, Object> gatewayDisabled() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled",
                Boolean.parseBoolean(environment.getProperty("entropy.mcp.gateway.enabled", "false")));
        result.put("reason", "ETL tools are not available: the gateway is disabled in this deployment");
        result.put("property", "entropy.mcp.gateway.enabled");
        return result;
    }

    // ─── 备份 ──────────────────────────────────────────────────────────────

    /**
     * 备份记录列表。
     *
     * <p>GET /api/ui/backups?connection=&amp;tableName=&amp;limit=&amp;typeFilter=
     * → 原样透传 {@code BackupTools.listBackups}（{@code total}、{@code records}、{@code storageTotal}）。
     *
     * <p>{@code total} 是本次返回的条数，{@code storageTotal} 是服务端内存里现存的全部条数——
     * 两个都要显示：备份元数据<b>只存在进程内存里</b>，进程重启即全部丢失，也会被条数/天数上限静默淘汰
     * （上限见 {@code /api/ui/backups/config}）。只显示 {@code total} 的话，
     * 「过滤掉了」和「已经被淘汰了」在页面上没有区别。
     *
     * <p>{@code typeFilter} 传了非法取值（不是 FULL / INCREMENTAL / SCHEMA）时工具抛
     * {@code McpToolException} → 500。这里不预校验：合法取值集合是工具那边的
     * {@code BackupType}，在控制器里抄一份就是第二份会漂移的清单。
     */
    @GetMapping("/backups")
    public Map<String, Object> backups(
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "tableName", required = false) @Nullable String tableName,
            @RequestParam(name = "limit", required = false) @Nullable Integer limit,
            @RequestParam(name = "typeFilter", required = false) @Nullable String typeFilter) {
        return backupTools.listBackups(connection, tableName,
                limit != null ? clampLimit(limit) : null, typeFilter);
    }

    /**
     * 单条备份记录详情。
     *
     * <p>GET /api/ui/backups/detail?backupId=xxx → 原样透传 {@code BackupTools.getBackup}。
     *
     * <p>{@code backupId} 同样走查询参数，理由见 {@link #jobStatus}。
     * 记录不存在时工具抛 NOT_FOUND → 500；这在这里是<b>常见情况</b>而不是异常：
     * 进程重启或淘汰之后，页面上一份旧列表里的每个 id 都会这样。
     */
    @GetMapping("/backups/detail")
    public Map<String, Object> backupDetail(
            @RequestParam(name = "backupId", required = false) @Nullable String backupId) {
        return backupTools.getBackup(requireParam(backupId, "backupId"));
    }

    /**
     * 备份模块的生效配置。
     *
     * <p>GET /api/ui/backups/config → 原样透传 {@code BackupTools.getBackupConfig}
     * （{@code enabled}、{@code incrementalEnabled}、{@code maxBackupRows}、
     * {@code storageInMemoryOnly} 等）。
     *
     * <p>{@code storageInMemoryOnly} 必须显示：看到 retention 相关的配置，人会默认备份是持久的。
     */
    @GetMapping("/backups/config")
    public Map<String, Object> backupConfig() {
        return backupTools.getBackupConfig();
    }

    // ─── CDC ───────────────────────────────────────────────────────────────

    /**
     * CDC 状态。
     *
     * <p>GET /api/ui/cdc/status?connection= → 原样透传 {@code CdcTools.getCdcStatus}。
     *
     * <p>这一组里 {@code /cdc/status} 与 {@code /cdc/lsn} 会先要求
     * {@code entropy.mcp.database.cdc.enabled=true}、再要求 {@code connection} 非空，
     * 两条都不满足时是 500。刻意不在这里预判 CDC 开关：那需要在控制器里再读一次配置，
     * 而 {@code /api/ui/cdc/config} 已经把生效值报出来了，页面据此决定要不要发这个请求。
     */
    @GetMapping("/cdc/status")
    public Map<String, Object> cdcStatus(
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        return cdcTools.getCdcStatus(connection);
    }

    /**
     * CDC 订阅登记。
     *
     * <p>GET /api/ui/cdc/subscriptions?connection= → 原样透传 {@code CdcTools.listSubscriptions}
     * （{@code totalCount}、{@code subscriptions}）。
     *
     * <p>订阅只是<b>服务进程内存里的登记</b>，不连库也不在数据库端建任何东西，
     * 所以这个端点在 CDC 关闭时也能成功返回空列表。
     */
    @GetMapping("/cdc/subscriptions")
    public Map<String, Object> cdcSubscriptions(
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        return cdcTools.listSubscriptions(connection);
    }

    /**
     * 当前日志位点。
     *
     * <p>GET /api/ui/cdc/lsn?connection= → 原样透传 {@code CdcTools.getCurrentLsn}。
     */
    @GetMapping("/cdc/lsn")
    public Map<String, Object> cdcLsn(
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        return cdcTools.getCurrentLsn(connection);
    }

    /**
     * 这个连接的方言支不支持 CDC。
     *
     * <p>GET /api/ui/cdc/support?connection= → 原样透传 {@code CdcTools.checkCdcSupport}
     * （{@code cdcSupported} 等）。
     *
     * <p>页面应该先问这里再问 {@code /cdc/status}：{@code cdcSupported=false} 时后者必然失败，
     * 而两条路的错误消息看起来差不多。
     */
    @GetMapping("/cdc/support")
    public Map<String, Object> cdcSupport(
            @RequestParam(name = "connection", required = false) @Nullable String connection) {
        return cdcTools.checkCdcSupport(connection);
    }

    /**
     * CDC 模块的生效配置。
     *
     * <p>GET /api/ui/cdc/config → 原样透传 {@code CdcTools.getCdcConfig}
     * （{@code enabled}、{@code enableRealtimeStreaming}、{@code maxEventsPerPoll} 等）。不连库。
     */
    @GetMapping("/cdc/config")
    public Map<String, Object> cdcConfig() {
        return cdcTools.getCdcConfig();
    }

    // ─── 数据质量 ──────────────────────────────────────────────────────────

    /**
     * 单表数据质量检查。
     *
     * <p>GET /api/ui/quality/table?tableName=T&amp;connection=&amp;schema=&amp;format=
     * → 原样透传 {@code QualityTools.checkTableQuality}（{@code report}、
     * {@code formattedReport}、{@code format}）。
     *
     * <p><b>{@code customRules} 固定传 {@code null}</b>：自定义规则是一个带 {@code CUSTOM_SQL}
     * 类型的结构化列表，用查询参数表达等于让页面往 URL 里塞 SQL，而这个页面只发 GET
     * （见类注释）。所以这里只跑内置检查——逐列空值率 + 全列组合重复行。页面上要说明这一点，
     * 否则「评分 100」会被当成「所有业务规则都通过了」。
     *
     * <p>{@code schema} 在表不属于登录 Schema 时是<b>必须传的</b>（工具那边会因为探查不到表而直接报错，
     * 不再返回评分 100 的空报告）。这里不设必填：登录 Schema 下的表不需要它。
     */
    @GetMapping("/quality/table")
    public Map<String, Object> qualityTable(
            @RequestParam(name = "tableName", required = false) @Nullable String tableName,
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "schema", required = false) @Nullable String schema,
            @RequestParam(name = "format", required = false) @Nullable String format) {
        return qualityTools.checkTableQuality(connection, requireParam(tableName, "tableName"),
                schema, null, format);
    }

    /**
     * 质量告警汇总。
     *
     * <p>GET /api/ui/quality/alerts?limit=20 → 原样透传 {@code QualityTools.getQualityAlertSummary}。
     *
     * <p>两处夹取：这里按 {@link #clampLimit} 夹到 1..{@value WebUiController#MAX_LIMIT}，
     * 工具内部再夹到 1..100，因此实际生效上限是 100。<b>本端点不回显 limit</b>——回显的话
     * 页面会显示一个比实际生效值大的数字，那比不显示更糟；生效条数只能从
     * 返回的列表长度看。工具的当前实现还忽略这个参数并始终返回空列表，
     * 所以页面上这一块暂时永远是空的，这是实现现状而不是「没有告警」。
     */
    @GetMapping("/quality/alerts")
    public Map<String, Object> qualityAlerts(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return qualityTools.getQualityAlertSummary(clampLimit(limit));
    }

    /**
     * 内置质量规则模板。
     *
     * <p>GET /api/ui/quality/templates → 原样透传 {@code QualityTools.listQualityRuleTemplates}
     * （{@code templates}）。不连库、无入参。
     *
     * <p>由于 {@link #qualityTable} 不接自定义规则，这份清单在页面上是<b>说明性</b>的：
     * 它解释了「内置检查覆盖了什么」，以及哪些规则只能通过 MCP 工具调用才跑得到。
     */
    @GetMapping("/quality/templates")
    public Map<String, Object> qualityTemplates() {
        return qualityTools.listQualityRuleTemplates();
    }

    // ─── SQL 体检 ──────────────────────────────────────────────────────────

    /**
     * 查询风险评估（不执行 SQL）。
     *
     * <p>GET /api/ui/sql/risk?connection=&amp;sql=... → 原样透传
     * {@code QueryAnalysisTools.assessQueryRisk}（{@code riskScore}、{@code riskLevel}、
     * {@code tables}、{@code suggestions}、{@code recommendation}）。
     *
     * <p>{@code sql} 走查询参数，代价（URL 长度上限、SQL 进 access log）见类注释。
     * 它<b>会连库</b>：表行数是真查的（按表名缓存 10 分钟），所以 connection 不可用时同样 500。
     */
    @GetMapping("/sql/risk")
    public Map<String, Object> sqlRisk(
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "sql", required = false) @Nullable String sql) {
        return queryAnalysisTools.assessQueryRisk(connection, requireParam(sql, "sql"));
    }

    /**
     * 执行计划。
     *
     * <p>GET /api/ui/sql/plan?connection=&amp;sql=[&amp;withSuggestions=true] → 原样透传
     * {@code QueryAnalysisTools.explainPlan}（{@code plan}、{@code explainSql}、{@code warnings}，
     * 带 {@code withSuggestions=true} 时还有 {@code rewriteSuggestions}）。
     *
     * <p>只接 {@code SELECT} 或以 {@code WITH} 开头的语句，别的会被工具按 SECURITY_VIOLATION 拒掉；
     * 方言不提供 EXPLAIN 时是 EXPLAIN_NOT_SUPPORTED。两者都是 500 + 消息，页面照消息显示即可。
     * 代价见类注释。
     */
    @GetMapping("/sql/plan")
    public Map<String, Object> sqlPlan(
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "sql", required = false) @Nullable String sql,
            @RequestParam(name = "withSuggestions", required = false) @Nullable Boolean withSuggestions) {
        return queryAnalysisTools.explainPlan(connection, requireParam(sql, "sql"), withSuggestions);
    }

    /**
     * 一条 SQL 的完整体检：计划 + 索引建议 + 重写建议 + 行动项。
     *
     * <p>GET /api/ui/sql/analyze?connection=&amp;sql=... → 原样透传
     * {@code OptimizationTools.analyzeQuery}（{@code planRows}、{@code warnings}、
     * {@code indexRecommendations}、{@code rewriteSuggestions}、{@code actionItems}）。
     *
     * <p>它是 {@code /sql/plan} + {@code /sql/rewrites} + {@code /sql/indexes} 的超集，
     * 但底下会真的取执行计划，因此最慢。三个细粒度端点仍然保留：页面上「只想看重写建议」
     * 是个常见诉求，而那一条不连库。代价见类注释。
     */
    @GetMapping("/sql/analyze")
    public Map<String, Object> sqlAnalyze(
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "sql", required = false) @Nullable String sql) {
        return optimizationTools.analyzeQuery(connection, requireParam(sql, "sql"));
    }

    /**
     * SQL 重写建议（纯静态分析）。
     *
     * <p>GET /api/ui/sql/rewrites?connection=&amp;sql=... → 原样透传
     * {@code OptimizationTools.suggestRewrites}（{@code suggestionCount}、{@code suggestions}）。
     *
     * <p><b>这一批 {@code /sql/**} 里唯一不连库的端点</b>：只按文本匹配反模式，
     * {@code connection} 仅作回显。所以它在没有任何可用连接时也能给出结果——
     * 页面可以把它当成「连不上库时至少还能做点什么」的那一项。代价见类注释。
     */
    @GetMapping("/sql/rewrites")
    public Map<String, Object> sqlRewrites(
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "sql", required = false) @Nullable String sql) {
        return optimizationTools.suggestRewrites(connection, requireParam(sql, "sql"));
    }

    /**
     * 一张表的索引推荐。
     *
     * <p>GET /api/ui/sql/indexes?connection=&amp;table=T → 原样透传
     * {@code OptimizationTools.recommendIndexes}（{@code recommendationCount}、{@code recommendations}）。
     *
     * <p>本组唯一以表名（不是 SQL）为输入的端点，所以必填参数是 {@code table}。
     * 返回里的 {@code recommendedSql} 是可执行的 {@code CREATE INDEX} 文本，
     * 但<b>这个页面不提供执行它的地方</b>：页面只发 GET，建索引要另外走 MCP 工具或 DBA 流程。
     */
    @GetMapping("/sql/indexes")
    public Map<String, Object> sqlIndexes(
            @RequestParam(name = "connection", required = false) @Nullable String connection,
            @RequestParam(name = "table", required = false) @Nullable String table) {
        return optimizationTools.recommendIndexes(connection, requireParam(table, "table"));
    }

    /**
     * 优化器模块的生效配置。
     *
     * <p>GET /api/ui/sql/optimizer-config → 原样透传 {@code OptimizationTools.getOptimizerConfig}
     * （{@code enabled}、{@code maxSuggestionsPerQuery}、{@code maxIndexRecommendations}、
     * {@code enableCompositeIndexAnalysis}）。
     *
     * <p>已知事实，页面上别据此解释建议条数：这四项当前只是配置回显，
     * 工具实现<b>没有</b>按它们裁剪结果，所以 {@code maxSuggestionsPerQuery=10} 时
     * {@code /sql/analyze} 也可能给出更多条。
     */
    @GetMapping("/sql/optimizer-config")
    public Map<String, Object> optimizerConfig() {
        return optimizationTools.getOptimizerConfig();
    }

    // ─── 公用 ──────────────────────────────────────────────────────────────

    /**
     * 必填字符串参数的统一校验：缺失或空白一律 400，消息里带上参数名。
     *
     * <p>返回校验通过的值而不是只做断言，是为了让调用点写成
     * {@code tool.call(requireParam(x, "x"))}——分成「先 if 抛错、再用 x」两步的话，
     * 静态分析仍然认为 {@code x} 可能是 {@code null}，于是要么加一个多余的断言，
     * 要么把 {@code @Nullable} 悄悄丢掉。
     */
    private static String requireParam(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
        }
        return value;
    }

    /**
     * 与 {@link WebUiController} 完全一致的夹取语义：小于 1 抬到 1，大于
     * {@value WebUiController#MAX_LIMIT} 压到上限。上限常量直接引用那边的，
     * 不在这里再定义一个——两个类同属一个页面，出现两个不同的 {@code maxLimit}
     * 会让 {@code /api/ui/config} 报的那个值变成半个真话。
     */
    private static int clampLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, WebUiController.MAX_LIMIT);
    }
}
