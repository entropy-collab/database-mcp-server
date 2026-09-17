/*
 * 后端读取层。全部是 GET，页面不提供任何写入口。
 *
 * 两个后端控制器，两组语义完全不同的端点，这个文件按同样的顺序分成两段：
 * - WebUiController（下半段之前）：进程内状态（审计、连接、池、指标、服务信息）+ DBA 诊断视图。
 *   除 dba 那两个之外都不连业务库，所以面板可以挂载即拉。
 * - WebUiExplorerController（本文件末尾那一大段）：schema / catalog / lineage / jobs /
 *   backups / cdc / quality / sql，一共 36 个端点。<b>其中绝大多数会真的对业务库发 SQL</b>，
 *   因此对应的面板一律「先填参数、点按钮才发请求」，不在挂载时打库。
 *
 * 错误处理的原则沿用零构建版本：任何非 2xx 都把状态码与响应体原样带回调用方，
 * 由面板显示出来，绝不吞掉后渲染一张空表格。开了鉴权而浏览器没带凭证时，
 * 运维要看到的是 401，而不是「没有数据」。
 */

/** 后端 WebUiController.MAX_LIMIT 的镜像；真值由 /api/ui/config 的 maxLimit 覆盖。 */
export const FALLBACK_MAX_LIMIT = 500;

/**
 * 相对路径而不是绝对路径：页面既可能挂在 /（欢迎页转发），也可能被 /index.html 直接打开，
 * 两种情况下相对 'api/...' 都解析到同一个 origin 下的 /api/...。
 */
function url(path) {
  return `api/${path}`;
}

export class ApiError extends Error {
  constructor(status, statusText, body) {
    const authHint = status === 401 || status === 403
      ? '\n（服务已开启 HTTP 鉴权，浏览器需要提供 admin 凭证）'
      : '';
    super(`HTTP ${status} ${statusText}\n${body}${authHint}`);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function getJson(path) {
  const res = await fetch(url(path), { headers: { Accept: 'application/json' } });
  const body = await res.text();
  if (!res.ok) {
    throw new ApiError(res.status, res.statusText, body);
  }
  return body ? JSON.parse(body) : null;
}

/** GET /api/ui/config → {authEnabled, auditPersistence, maxLimit} */
export function fetchConfig() {
  return getJson('ui/config');
}

/** GET /api/audit/logs?limit=n → 进程内环形缓冲，重启即清空 */
export function fetchAuditLogs(limit) {
  return getJson(`audit/logs?limit=${limit}`);
}

/** GET /api/audit/history?limit=n → 审计表；未配 spring.datasource.url 时后端返回 503 */
export function fetchAuditHistory(limit) {
  return getJson(`audit/history?limit=${limit}`);
}

/** GET /api/ui/connections → {registered: {...}, pools: {...}} */
export function fetchConnections() {
  return getJson('ui/connections');
}

/** GET /api/ui/performance?limit=n → {limit, summary, slowQueries, totalTrackedPatterns, patterns, metrics} */
export function fetchPerformance(limit) {
  return getJson(`ui/performance?limit=${limit}`);
}

/**
 * GET /api/ui/info → {serviceName, version, activeProfiles, startedAt, uptimeSeconds, switches, toolCount}
 *
 * version 来自 spring.ai.mcp.server.version（Maven 资源过滤填入），不是 build-info；
 * startedAt / uptimeSeconds 是容器 refresh 期的近似值。两点都由 InfoPanel 写在页面上。
 */
export function fetchInfo() {
  return getJson('ui/info');
}

/** GET /api/ui/tools → {total, exposed, groups: [...], tools: [{name, group, summary, tags}]}（没有 inputSchema） */
export function fetchTools() {
  return getJson('ui/tools');
}

/**
 * GET /api/ui/audit-reports?hours=h&limit=n
 * → {hours, from, to, limit, metrics, dataAccess, protection}
 *
 * hours 被后端夹到 1..168、limit 夹到 1..500，返回体里的 hours/limit/from/to 是夹取后的
 * 真实窗口，所以页面一律回显后端给的值而不是自己手里的入参。
 */
export function fetchAuditReports(hours, limit) {
  return getJson(`ui/audit-reports?hours=${hours}&limit=${limit}`);
}

/**
 * GET /api/ui/pool?connection=xxx → 单个连接的池指标（缺 connection 时后端 400）。
 *
 * 目前没有页面调它：连接页一次就把全部池指标取回来了，这个端点是给「展开某一行看单池」
 * 用的，留在这里是为了让读取层和后端端点一一对应，而不是让下一个人以为它不存在。
 */
export function fetchPool(connection) {
  return getJson(`ui/pool?connection=${encodeURIComponent(connection)}`);
}

/**
 * GET /api/ui/dba?view=&connection=&schema=&table= → {dialect, rows: [...]}
 *
 * 唯一会真的往业务库发 SQL 的端点，绝大多数 view 只有 Oracle 实现，方言不匹配 / 连接不存在/
 * 账号没有数据字典权限都是 500 + 消息。空串参数一律不发：后端把空串按「有值」处理会得到
 * 一个查不到东西的 schema 过滤，而不是「不过滤」。
 */
export function fetchDba({ view, connection, schema, table }) {
  const params = new URLSearchParams({ view });
  if (connection) { params.set('connection', connection); }
  if (schema) { params.set('schema', schema); }
  if (table) { params.set('table', table); }
  return getJson(`ui/dba?${params.toString()}`);
}

/**
 * GET /api/ui/dba/views → {views: [...]}
 *
 * 前端不写第二份 view 清单：写两份之后后端新加的 view 前端点不到，前端多出的 view 点了必然 400。
 * 这个端点只读一个常量列表，不连库，所以它可以跟着面板挂载就发。
 */
export function fetchDbaViews() {
  return getJson('ui/dba/views');
}

// =============================================================================
// WebUiExplorerController：schema / catalog / lineage / jobs / backups / cdc /
// quality / sql，共 36 个端点
// =============================================================================

/**
 * 查询串拼装。空值一律<b>不发</b>。
 *
 * 这不是洁癖，是三个具体的坑：
 * 1. Spring 的 `@RequestParam(required=false)` 把 `?schema=` 解析成空字符串<b>而不是 null</b>，
 *    于是「不过滤」变成「按空 schema 过滤」，得到一张查不到东西的空表；
 * 2. 必填参数走 `requireParam`，它对空白字符串抛 400 —— 发一个 `?table=` 出去，
 *    换回来的是一条「table is required」，而页面上明明有输入框，运维会以为自己填了；
 * 3. SQL 这类值必须 encodeURIComponent，手拼字符串（`?sql=${sql}`）会在 SQL 里出现
 *    `&`（位运算）或 `#`（某些方言的注释/操作符）时把查询串截断，表现是「分析结果不对」
 *    而不是一条报错。URLSearchParams 自己做转义，所以一律走它。
 *
 * 0 与 false 要保留（maxDepth=0 虽然会被后端夹掉，但那是后端的语义），所以判空只挡
 * null / undefined / 空串，不用 falsy 判断。
 */
function query(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') {
      continue;
    }
    search.set(key, String(value));
  }
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}

// ─── Schema 浏览 ─────────────────────────────────────────────────────────────

/** GET /api/ui/schema/schemas → {connection, schemas: [字符串]} */
export function fetchSchemaSchemas({ connection }) {
  return getJson(`ui/schema/schemas${query({ connection })}`);
}

/** GET /api/ui/schema/tables → {connection, schema, tables} · schema 省略时由方言解析当前 Schema */
export function fetchSchemaTables({ schema, connection }) {
  return getJson(`ui/schema/tables${query({ schema, connection })}`);
}

/** GET /api/ui/schema/views → {connection, schema, views}（含视图的 SQL 定义） */
export function fetchSchemaViews({ schema, connection }) {
  return getJson(`ui/schema/views${query({ schema, connection })}`);
}

/** GET /api/ui/schema/sequences → {connection, schema, sequences} */
export function fetchSchemaSequences({ schema, connection }) {
  return getJson(`ui/schema/sequences${query({ schema, connection })}`);
}

/** GET /api/ui/schema/indexes → {connection, schema, table, indexes} · table 必填（缺失 400） */
export function fetchSchemaIndexes({ table, schema, connection }) {
  return getJson(`ui/schema/indexes${query({ table, schema, connection })}`);
}

/**
 * GET /api/ui/schema/table → 命中 {table, schema, columnCount, columns}；
 * <b>未命中是 200 + {error, table, schema, schemaSource, hint}</b>，不是 HTTP 错误。
 *
 * 所以调用方不能只看有没有抛异常：拿到结果之后还要判一次有没有 error 键，
 * 并且把 hint 显示出来（它会告诉用户「表在别的 schema 下就要显式传 schema」）。
 */
export function fetchSchemaTable({ table, schema, connection }) {
  return getJson(`ui/schema/table${query({ table, schema, connection })}`);
}

/** GET /api/ui/schema/search → {connection, keyword, tables} · keyword 必填（后端不给「返回全部表」这个默认） */
export function fetchSchemaSearch({ keyword, connection }) {
  return getJson(`ui/schema/search${query({ keyword, connection })}`);
}

/**
 * GET /api/ui/schema/database-info → {databaseProductName, databaseProductVersion, driverName, …}
 *
 * 它报的是对端<b>真实自述</b>的产品与版本，而 /api/ui/connections 报的是注册时声明的方言。
 * 两者不一致就是方言配错了，而方言配错的表现往往是别的端点莫名 500。
 */
export function fetchDatabaseInfo({ connection }) {
  return getJson(`ui/schema/database-info${query({ connection })}`);
}

// ─── 数据资产与敏感列 ─────────────────────────────────────────────────────────

/** GET /api/ui/catalog/config → {enabled, autoGenerateComments, enableSensitiveDetection, maxSearchResults} · 不连库 */
export function fetchCatalogConfig() {
  return getJson('ui/catalog/config');
}

/**
 * GET /api/ui/catalog/scan → {connection, schema, totalTables, sensitiveTableCount, entries}
 *
 * <b>这是全部端点里最慢的一个</b>：每张表三次元数据往返（工具内部并发上限 4），
 * 大 Schema 上是分钟级。绝对不能进自动刷新，也不能在切 tab 时预取。
 */
export function fetchCatalogScan({ connection, schema }) {
  return getJson(`ui/catalog/scan${query({ connection, schema })}`);
}

/** GET /api/ui/catalog/sensitive → {connection, schema, sensitiveColumnCount, columns} */
export function fetchCatalogSensitive({ connection, schema }) {
  return getJson(`ui/catalog/sensitive${query({ connection, schema })}`);
}

/**
 * GET /api/ui/catalog/table → {columns, columnCount, maxSensitivity, hasSensitiveColumns, …}
 *
 * 参数名是 `tableName` 而不是 `table`：catalog / quality 这两组跟着工具的形参叫 tableName，
 * schema 那组叫 table。后端刻意没统一（与工具签名对齐），前端也就不统一，照抄各自的名字。
 */
export function fetchCatalogTable({ tableName, connection }) {
  return getJson(`ui/catalog/table${query({ tableName, connection })}`);
}

/**
 * GET /api/ui/catalog/search → {connection, keyword, resultCount, maxResults, assets}
 *
 * limit 省略时由工具用 entropy.mcp.database.catalog.max-search-results，生效值在 maxResults 里回显；
 * 所以页面显示的上限一律取返回体的 maxResults，不显示自己手里那个入参。
 */
export function fetchCatalogSearch({ keyword, connection, limit }) {
  return getJson(`ui/catalog/search${query({ keyword, connection, limit })}`);
}

// ─── 血缘 ────────────────────────────────────────────────────────────────────

/**
 * GET /api/ui/lineage/config
 * → {enabled, foreignKeyEnabled, viewDependencyEnabled, maxTraversalDepth, autoAnalyze, maxTablesPerGraph}
 *
 * 不连库，而且<b>必须和血缘结果一起显示</b>：血缘边只从外键推导，空图长得和「这张表确实
 * 没有上下游」一模一样，foreignKeyEnabled=false 与 maxTablesPerGraph 截断都会产生空图。
 */
export function fetchLineageConfig() {
  return getJson('ui/lineage/config');
}

/**
 * GET /api/ui/lineage → <b>返回形状由 format 决定</b>：
 * - 省略 format：{table, connection, maxDepth, directUpstream, directDownstream,
 *   allUpstream, allDownstream, edgeCount, hasUpstream, hasDownstream}，
 *   检测到异常时<b>才</b>多一个 anomalies —— 它不是恒定键，按「一定存在」写会读到 undefined；
 * - format=mermaid / dot：只有 {format, graph} 两个键，图文本在 graph 下。
 *
 * maxDepth 不在前端夹取：工具自己夹到 1..10，再受 max-traversal-depth 约束，
 * 前端再夹一层就是第三个互相不一致的上限。生效值看返回体的 maxDepth。
 */
export function fetchLineage({ table, connection, maxDepth, format }) {
  return getJson(`ui/lineage${query({ table, connection, maxDepth, format })}`);
}

/**
 * GET /api/ui/lineage/impact
 * → {sourceTable, connection, maxDepth, totalImpacted, impactedTables, byDepth}
 *
 * 根表那个键叫 <b>sourceTable</b>，不是 table —— 和 /api/ui/lineage 不一样。
 * 写成 data.table 不会报错，只会显示一个空的表名。
 */
export function fetchLineageImpact({ table, connection, maxDepth }) {
  return getJson(`ui/lineage/impact${query({ table, connection, maxDepth })}`);
}

/** GET /api/ui/lineage/edges → {connection, totalEdges, edges} · 每张表一次外键查询，大库开销高 */
export function fetchLineageEdges({ connection }) {
  return getJson(`ui/lineage/edges${query({ connection })}`);
}

// ─── ETL 作业 ────────────────────────────────────────────────────────────────

/**
 * GET /api/ui/jobs → gateway 开启时 {totalJobs, jobs}；关闭时 200 + {enabled:false, reason, property}。
 *
 * 关闭是<b>部署选择</b>而不是故障，所以它是 200 —— 页面要按 info 处理，不能走 ErrorNotice。
 */
export function fetchJobs() {
  return getJson('ui/jobs');
}

/** GET /api/ui/jobs/status → {jobId, jobName, status, …, steps}；gateway 关闭时同 fetchJobs 的降级体 */
export function fetchJobStatus({ jobId }) {
  return getJson(`ui/jobs/status${query({ jobId })}`);
}

// ─── 备份 ────────────────────────────────────────────────────────────────────

/**
 * GET /api/ui/backups/config
 * → {enabled, incrementalEnabled, maxBackupRows, storageInMemoryOnly, maxRecords, retentionDays, totalBackups}
 *
 * storageInMemoryOnly 在后端是<b>硬编码的 true</b>（BackupTools 里直接写死），
 * 不是一个可配项。页面必须把它显眼地写出来，否则看到 retentionDays 的人会以为备份是持久的。
 */
export function fetchBackupsConfig() {
  return getJson('ui/backups/config');
}

/**
 * GET /api/ui/backups → {total, records, storageTotal}
 *
 * total 是本次返回的条数，storageTotal 是服务端内存里现存的<b>全部</b>条数。两个都要显示：
 * 只显示 total 的话，「被过滤掉了」和「已经被淘汰了」在页面上没有区别。
 */
export function fetchBackups({ connection, tableName, limit, typeFilter }) {
  return getJson(`ui/backups${query({ connection, tableName, limit, typeFilter })}`);
}

/** GET /api/ui/backups/detail → 单条详情（含 sqlScriptPreview）· 记录不存在时 500，重启后是常态 */
export function fetchBackupDetail({ backupId }) {
  return getJson(`ui/backups/detail${query({ backupId })}`);
}

// ─── CDC ─────────────────────────────────────────────────────────────────────

/** GET /api/ui/cdc/config → {enabled, enableRealtimeStreaming, maxEventsPerPoll, …} · 不连库 */
export function fetchCdcConfig() {
  return getJson('ui/cdc/config');
}

/** GET /api/ui/cdc/support → {connection, cdcSupported, note} · 不要求 CDC 开关，先问这里再问 status */
export function fetchCdcSupport({ connection }) {
  return getJson(`ui/cdc/support${query({ connection })}`);
}

/**
 * GET /api/ui/cdc/status → {connection, cdcSupported, currentLsn, activeSubscriptions, totalEventsCaptured, lastEventTime}
 *
 * 工具里先 requireCdcEnabled 再 validateRequired(connection)，两条都不满足是 500。
 * 所以调用它之前页面得先看过 /cdc/config 的 enabled，并且连接名非空。
 */
export function fetchCdcStatus({ connection }) {
  return getJson(`ui/cdc/status${query({ connection })}`);
}

/** GET /api/ui/cdc/subscriptions → {connection, totalCount, subscriptions} · 订阅只是进程内存里的登记，CDC 关着也能成功 */
export function fetchCdcSubscriptions({ connection }) {
  return getJson(`ui/cdc/subscriptions${query({ connection })}`);
}

/** GET /api/ui/cdc/lsn → {connection, currentLsn} · 同 status，要求 CDC 开关 + connection */
export function fetchCdcLsn({ connection }) {
  return getJson(`ui/cdc/lsn${query({ connection })}`);
}

// ─── 数据质量 ────────────────────────────────────────────────────────────────

/** GET /api/ui/quality/templates → {templates} · 不连库、无入参 */
export function fetchQualityTemplates() {
  return getJson('ui/quality/templates');
}

/**
 * GET /api/ui/quality/table → {report, formattedReport, format}
 *
 * customRules 固定是 null（页面只发 GET，自定义规则是结构化列表，塞不进查询串），
 * 所以跑的只有内置检查：逐列空值率 + 全列组合重复行。评分 100 ≠ 业务规则都通过了。
 */
export function fetchQualityTable({ tableName, connection, schema, format }) {
  return getJson(`ui/quality/table${query({ tableName, connection, schema, format })}`);
}

/**
 * GET /api/ui/quality/alerts → 透传 QualityTools.getQualityAlertSummary。
 *
 * <b>工具的当前实现忽略 limit 并且恒返回空列表。</b>这是实现现状，不是「没有告警」——
 * 页面上必须把这句写出来，否则一个空列表会被读成「数据健康」。
 * 端点也刻意不回显 limit（回显会显示一个比实际生效值大的数），所以这里也不假装它有意义。
 */
export function fetchQualityAlerts({ limit }) {
  return getJson(`ui/quality/alerts${query({ limit })}`);
}

// ─── SQL 体检 ────────────────────────────────────────────────────────────────

/**
 * GET /api/ui/sql/optimizer-config
 * → {enabled, maxSuggestionsPerQuery, maxIndexRecommendations, enableCompositeIndexAnalysis}
 *
 * 已知事实：这四项当前<b>只是配置回显</b>，工具实现没有按它们裁剪结果。
 * 所以页面上不能用 maxSuggestionsPerQuery 去解释「为什么只有这几条建议」。
 */
export function fetchOptimizerConfig() {
  return getJson('ui/sql/optimizer-config');
}

/** GET /api/ui/sql/risk → {…, tables, riskScore, riskLevel, suggestions, recommendation} · 会连库（表行数是真查的） */
export function fetchSqlRisk({ sql, connection }) {
  return getJson(`ui/sql/risk${query({ sql, connection })}`);
}

/** GET /api/ui/sql/plan → {…, originalSql, explainSql, plan, warnings} · 只接 SELECT / WITH，方言没有 EXPLAIN 时 500 */
export function fetchSqlPlan({ sql, connection }) {
  return getJson(`ui/sql/plan${query({ sql, connection })}`);
}

/** GET /api/ui/sql/analyze → {…, planRows, warnings, indexRecommendations, rewriteSuggestions, actionItems} · 三者的超集，最慢 */
export function fetchSqlAnalyze({ sql, connection }) {
  return getJson(`ui/sql/analyze${query({ sql, connection })}`);
}

/**
 * GET /api/ui/sql/rewrites → {…, suggestionCount, suggestions}
 *
 * <b>/sql/** 里唯一不连库的一个</b>：纯文本反模式匹配，connection 只做回显、而且不必填。
 * 连不上库的时候它是这一页仅存的可用功能。
 */
export function fetchSqlRewrites({ sql, connection }) {
  return getJson(`ui/sql/rewrites${query({ sql, connection })}`);
}

/** GET /api/ui/sql/indexes → {…, recommendationCount, recommendations} · 本组唯一以表名（不是 SQL）为输入 */
export function fetchSqlIndexes({ table, connection }) {
  return getJson(`ui/sql/indexes${query({ table, connection })}`);
}

