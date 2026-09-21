/*
 * 后端读取层，外加两组写接口（工具启用/停用、调用者身份增删，都在文件末尾）。
 *
 * 四个后端控制器，四组语义完全不同的端点，这个文件按同样的顺序分成四段：
 * - WebUiController（下一段之前）：进程内状态（审计、连接、池、指标、服务信息）+ DBA 诊断视图。
 *   除 dba 那两个之外都不连业务库，所以面板可以挂载即拉。
 * - WebUiExplorerController（中间那一大段）：schema / catalog / lineage / jobs /
 *   backups / cdc / quality / sql，一共 36 个端点。<b>其中绝大多数会真的对业务库发 SQL</b>，
 *   因此对应的面板一律「先填参数、点按钮才发请求」，不在挂载时打库。
 * - ToolAdminController：`GET /api/tools`、`GET /api/tools/prompt` 与两个 `PUT`。
 * - UserAdminController（本文件末尾）：`GET /api/users` 与一个 `POST` / 一个 `DELETE`。
 *
 * 后两段里的 PUT / POST / DELETE 是整个前端会改服务端状态的<b>全部</b>入口——「本页面只读」
 * 这句话早就不成立了，改动这个文件时不要再按「反正都是 GET」来推理（比如给请求加自动重试：
 * 重试一次 PUT 是幂等的，但重试一次 POST /api/users 会在第一次其实成功时得到一条
 * "用户名已存在"的 400，把一次成功的新建显示成失败）。
 *
 * 错误处理的原则沿用零构建版本：任何非 2xx 都把状态码与响应体原样带回调用方，
 * 由面板显示出来，绝不吞掉后渲染一张空表格。开了鉴权而浏览器没带凭证时，
 * 运维要看到的是 401，而不是「没有数据」。写请求同一口径：400 的消息里带着可选值
 * （后端把「工具名拼错」和「被部署期裁掉」分成两句话写在消息里），原样显示才有用。
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
    /*
     * 401 与 403 的下一步动作完全不同，所以提示也分开写：
     * - 401 = 没登录或会话超时。此时面板已经切到登录视图（见 notifyUnauthorized），这句话通常看不到；
     * - 403 = <b>已经登录了，但这个身份没有 ROLE_ADMIN</b>。跳登录页对它毫无用处——换一次口令还是 403，
     *   要解决只能给那个身份授权（身份管理页）或换一个管理员身份登录。
     */
    const authHint = status === 401
      ? '\n（未登录或会话已超时，面板正在切回登录界面）'
      : status === 403
        ? '\n（已登录，但当前身份没有 ROLE_ADMIN；面板与 /api/** 都要求这个权限）'
        : '';
    super(`HTTP ${status} ${statusText}\n${body}${authHint}`);
    this.name = 'ApiError';
    this.status = status;
  }
}

// =============================================================================
// 会话：401 通知、写请求带 CSRF token、登录与退出
// =============================================================================

/**
 * 读 CSRF token。
 *
 * 后端用的是双提交：token 写在 XSRF-TOKEN cookie 里（Spring Security 7 的 csrf().spa() 预设），
 * 请求按 X-XSRF-TOKEN 头带回去。cookie 在任意一次请求时就会落下，所以面板启动后的第一个
 * GET（/api/ui/config）已经把它拿到了；登录表单发 POST /login 时它也在。
 *
 * 读不到时<b>不</b>伪造一个值也不阻止请求：读不到只有两种情况——鉴权关闭（那条链上 CSRF 是关的），
 * 或者 cookie 真的没下发（那时服务端会回 403，比前端自己造一个假 token 更容易查）。
 */
function csrfToken() {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

/** 写请求的请求头。CSRF token 只在读得到时才加。 */
function writeHeaders() {
  const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
  const token = csrfToken();
  if (token) {
    headers['X-XSRF-TOKEN'] = token;
  }
  return headers;
}

/**
 * 会话失效的通知。
 *
 * ── 为什么是通知而不是跳转 ──
 * 登录界面是 SPA 自己的一个视图（见 LoginView），所以"未登录"只是应用的一个状态，不是另一个页面。
 * 收到 401 就把这个状态打开、渲染登录表单；登录成功后<b>整页不刷新</b>，location.hash 里的现场
 * （view / limit / 选中行）天然还在。上一版是 location.replace 跳 login.html + redirect 参数把 hash
 * 带回来，那套东西在这个结构下整体不需要了。
 *
 * 只保留一个订阅者（App 挂载时注册）。用数组也行，但这个应用里"谁该响应 401"只有一个答案，
 * 多订阅者只会让"到底谁改了状态"变得难查。
 */
let unauthorizedListener = null;

export function onUnauthorized(listener) {
  unauthorizedListener = listener;
  return () => {
    if (unauthorizedListener === listener) {
      unauthorizedListener = null;
    }
  };
}

function notifyUnauthorized() {
  if (unauthorizedListener) {
    unauthorizedListener();
  }
}

/**
 * POST /login，表单编码（Spring Security 的 formLogin 只认 application/x-www-form-urlencoded）。
 *
 * 成功是 204、口令错是 401、缺 CSRF token 是 403，三者都不带 302——所以这里不能用 getJson/postJson
 * 那两个（它们发 JSON，而且会把 401 当成"会话失效"去通知 App，那在登录界面上是个死循环）。
 *
 * @returns {Promise<void>} 成功即 resolve；失败 reject 一个 ApiError，由登录视图就地显示
 */
export async function login(username, password) {
  const body = new URLSearchParams();
  body.set('username', username);
  body.set('password', password);

  const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
  const token = csrfToken();
  if (token) {
    headers['X-XSRF-TOKEN'] = token;
  }

  const res = await fetch('login', { method: 'POST', headers, body: body.toString() });
  if (!res.ok) {
    throw new ApiError(res.status, res.statusText, await res.text());
  }
}

/**
 * POST /logout → 204，然后回到登录视图。
 *
 * 退出必须是 POST（GET 退出意味着任何一张图片的 src 都能把人踢下线），所以它同样要带 CSRF token。
 * 无论服务端返回什么都切到登录视图：退出失败也不该把人留在一个"以为已经退了"的页面上。
 */
export async function logout() {
  try {
    await fetch('logout', { method: 'POST', headers: writeHeaders() });
  } finally {
    notifyUnauthorized();
  }
}

async function getJson(path) {
  const res = await fetch(url(path), { headers: { Accept: 'application/json' } });
  const body = await res.text();
  if (!res.ok) {
    /* 401 = 没登录或会话超时，通知 App 切到登录视图（403 不通知：那是已登录但权限不够，
       切到登录界面只会让人以为是口令问题）。仍然把 ApiError 抛出去，让调用方的 catch 照常跑。 */
    if (res.status === 401) {
      notifyUnauthorized();
    }
    throw new ApiError(res.status, res.statusText, body);
  }
  return body ? JSON.parse(body) : null;
}

/**
 * 带 JSON 体的 PUT。错误处理与 getJson 逐字相同（同一个 ApiError、同样原样带回响应体）。
 *
 * ── 为什么是新函数而不是给 getJson 加一个可选的 body 参数 ──
 * 那样会得到一个「传了 body 就变成写请求」的两用函数，而这个文件里 40 多个调用点全是读。
 * 一次手滑（给某个 fetchXxx 多传一个参数）就会把一次读操作变成一次真实的状态变更，
 * 而且类型系统在这个项目里帮不上忙（纯 JS，没有 TS 检查）。分成两个函数之后，
 * 「这一行会不会改服务端状态」用函数名就能判断，grep putJson 就是全部写入口。
 *
 * 不设超时也不重试：重试一次 PUT 在后端是幂等的（changed 会变成 false），但那会让
 * 「第一次其实成功了、响应在路上丢了」这种情况显示成"未变更"，和"本来就是这个状态"
 * 混成同一个结果。写操作宁可让调用方看见一次明确的失败。
 */
async function putJson(path, payload) {
  const res = await fetch(url(path), {
    method: 'PUT',
    headers: writeHeaders(),
    body: JSON.stringify(payload),
  });
  const body = await res.text();
  if (!res.ok) {
    if (res.status === 401) {
      notifyUnauthorized();
    }
    throw new ApiError(res.status, res.statusText, body);
  }
  return body ? JSON.parse(body) : null;
}

/**
 * 带 JSON 体的 POST（只有新增调用者身份一个调用点）。错误处理与 getJson / putJson 逐字相同。
 *
 * 与 putJson 分开而不是加一个 method 参数：这两个动词的<b>重试语义相反</b>。PUT 在后端是
 * 幂等的（changed 会变成 false），而 POST /api/users 第二次一定撞上"用户名已存在"的 400——
 * 一个通用的 write() 函数会让"要不要重试"这件事看不出来。这里同样不重试、不设超时。
 */
async function postJson(path, payload) {
  const res = await fetch(url(path), {
    method: 'POST',
    headers: writeHeaders(),
    body: JSON.stringify(payload),
  });
  const body = await res.text();
  if (!res.ok) {
    if (res.status === 401) {
      notifyUnauthorized();
    }
    throw new ApiError(res.status, res.statusText, body);
  }
  return body ? JSON.parse(body) : null;
}

/** 无体的 DELETE（只有停用调用者身份一个调用点）。同样把非 2xx 原样抛成 ApiError。 */
async function deleteJson(path) {
  const res = await fetch(url(path), {
    method: 'DELETE',
    /* 带上写请求的头：DELETE 也要过 CSRF 校验。Content-Type 对没有体的请求无意义但无害，
       为的是让三个写函数共用同一份头，不必再维护第二个版本。 */
    headers: writeHeaders(),
  });
  const body = await res.text();
  if (!res.ok) {
    if (res.status === 401) {
      notifyUnauthorized();
    }
    throw new ApiError(res.status, res.statusText, body);
  }
  return body ? JSON.parse(body) : null;
}

/** GET /api/ui/config → {authEnabled, auditPersistence, maxLimit} */
export function fetchConfig() {
  return getJson('ui/config');
}

/**
 * GET /api/ui/me → {authEnabled, authenticated, username, type, subjectRef, authorities}
 *
 * 顶栏头像与个人信息框的唯一数据源。三件事调用方必须知道：
 *
 * 1. <b>authenticated=false 是 200，不是 401。</b>鉴权关闭的部署里压根没有"当前登录者"，
 *    后端为此刻意返回 200——回 401 会被上面的 notifyUnauthorized 当成会话超时，
 *    把人踢到一个在这种部署下根本登录不了的登录页去；
 * 2. <b>type 与 subjectRef 经常是 null，而且"经常"包括最常见的部署。</b>它们只在
 *    Authentication 的 principal 真的是 McpPrincipal 时才有值，而「没配状态库、管理员口令
 *    来自环境变量」这个默认形态<b>拿不到</b>——SecurityConfig 把 McpPrincipal 交给
 *    InMemoryUserDetailsManager，后者会把它重新包装成 Spring 自己的 User，类型字段在
 *    这一步丢掉（配了 spring.datasource.url 时走 UserStoreUserDetailsService，那时才有）。
 *    所以显示这两个字段的地方必须能"没有就不显示"，而<b>不要</b>在前端拼一个
 *    `${type}:${username}` 补上：那个字符串会被拿去和「权限视图」里的主体标识对照，
 *    而在上面那种情况下它并不是判定用的那个值；
 * 3. authorities 是 authority 全名（ROLE_ADMIN），和 /api/users 的 roles 同一口径。
 *
 * 它<b>不</b>和 /api/ui/config 合并：config 是匿名可读语义下的自举探针（它的 401 正是
 * "要登录"的信号），而这个端点答的是身份。合在一起之后，"鉴权状态未知"和"不知道你是谁"
 * 会共用一次失败，而前者要出红色横幅、后者只该让头像退化成一个占位符。
 */
export function fetchMe() {
  return getJson('ui/me');
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

/**
 * GET /api/ui/tools → {total, exposed, groups: [...], tools: [{name, group, summary, tags}]}（没有 inputSchema）
 *
 * 工具页<b>已经不用它了</b>，改用了下面的 fetchToolAdmin（理由写在 ToolsPanel 的头注释里）。
 * 留在这里的理由同 fetchPool：读取层与后端端点一一对应，删掉会让下一个人以为
 * WebUiController 没有这个端点。<b>新代码不要用它</b>——它缺 exposed/disabled 这两个
 * 逐工具字段，拿它渲染"状态"列只能全填"启用"，那是在说谎。
 */
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

// =============================================================================
// ToolAdminController：运行期启用/停用 MCP 工具 + 按当前清单生成提示词
// =============================================================================

/*
 * 这一组是 /api/tools（注意<b>不在</b> /api/ui/ 之下）。后端把它单独开一个控制器，
 * 理由是「WebUiController 的每个方法都只读进程内状态」这条约束要能一眼看出来，
 * 前端这边同理：这一段之外的全部函数都不会改服务端状态。<b>这一段里只有两个 PUT 会改</b>，
 * fetchToolAdmin 与 fetchToolPrompt 都是 GET。
 *
 * 鉴权和其余端点同一档（SecurityConfig 里 /api/** → ROLE_ADMIN），所以 401/403 的
 * 处理不需要特殊化，ApiError 里那句「需要 admin 凭证」的提示照样适用。
 * 但要知道 entropy.mcp.security.enabled=false 的部署下这两个 PUT 对任何能连上端口的人
 * 开放——那时它比只读面板严重，页面上因此也把"这是写操作"写了出来。
 */

/**
 * GET /api/tools → {total, exposed, disabledCount, persisted,
 *                   tools: [{name, group, summary, tags, exposed, disabled}]}
 *
 * 四个顶层计数的口径（逐字对着 ToolAdminController.list 读出来的，别按直觉推）：
 * - total：tools 数组的长度 = ToolCatalog 全量 + 快照里有而目录里没有的（扩展注册的工具）；
 * - exposed：<b>部署期暴露集的大小</b>，包含当前被停用的那些。它<b>不是</b>"现在客户端能看到几个"；
 * - disabledCount：其中被运行期停用的个数。所以现在 tools/list 里的个数 = exposed - disabledCount，
 *   也就是两个 PUT 返回体里的 remainingExposed；
 * - persisted：false 表示没配 spring.datasource.url，开关只在进程内存里，重启回到配置声明的状态。
 *   内存模式是受支持的部署形态（默认就是它），所以这里是 200 而不是 503。
 *
 * 逐工具的两个布尔：
 * - exposed=false：被部署期 entropy.mcp.tools（plane/groups/include/exclude）裁掉了，
 *   运行期<b>不允许</b>启用（对它发 PUT 是 400）。页面必须提前把按钮禁掉，别让人点了才知道；
 * - disabled=true：已从 tools/list 移除。它一定是 exposed 的子集（后端两个方向都先过 requireExposed）。
 *
 * group 可以是 null（非目录来源的工具），tags 可以是空数组——调用方不要假设非空。
 */
export function fetchToolAdmin() {
  return getJson('tools');
}

/**
 * GET /api/tools/prompt?format=list|system
 * → {format, generatedAt, toolCount, text}
 *
 * 按<b>此刻真正在 tools/list 里的工具</b>生成一份可直接粘给模型的提示词。
 * - format：回显后端解析后的值（不是入参原文）。非法取值是 400，消息里列出可选值；
 *   缺省是 system。空串也走缺省——后端刻意把 `?format=` 当成"没传"；
 * - generatedAt：ISO-8601 字符串。它同时写在 text 里，两处是同一个瞬间；
 * - toolCount：写进 text 的工具数 = fetchToolAdmin 的 exposed − disabledCount。
 *   它<b>不是</b> total：被部署期裁掉和被运行期停用的工具都不在提示词里；
 * - text：纯文本（Markdown），直接粘贴用。<b>几万字是正常量级</b>——128 个工具的完整
 *   描述就是这个体积，所以调用方要先把字符数显示出来再让人复制。
 *
 * 整段文本在后端拼而不是在这里拼，这是刻意的：提示词的价值在于写清"当前有哪些约束"
 * （DDL 开关、连接的 readonly 标记、授权开关、停用了几个工具），而那些判断全在服务端。
 * 在 JS 里复制一份等于保证两边漂移。<b>不要</b>在前端改写或补充 text 的内容。
 *
 * 这是个 GET，不改任何状态；放在这一段只是为了和它的数据源（/api/tools）挨着。
 */
export function fetchToolPrompt(format) {
  return getJson(`tools/prompt${query({ format })}`);
}

/**
 * PUT /api/tools/{name}，体 {"disabled": true|false}
 * → {name, disabled, changed, remainingExposed, persisted}
 *
 * changed=false 是<b>幂等成功</b>（它本来就是这个状态），不是错误。
 * remainingExposed=0 意味着 tools/list 现在是空数组，客户端那边通常表现为"服务没接上"——
 * 后端刻意允许这件事（运行期 kill switch），所以拦不拦由页面决定，页面必须先问一次。
 *
 * 400 的三种来源都在响应体的消息里写清了，原样显示即可：缺 disabled 字段、未知工具名、
 * 以及被部署期配置裁掉的工具名（那类只能改配置重启）。
 *
 * name 走 encodeURIComponent：工具名目前都是 [a-zA-Z0-9_]，但它是从 @McpTool 反射来的，
 * 不是本前端能保证的字符集，拼进路径前一律转义。
 */
export function setToolDisabled(name, disabled) {
  return putJson(`tools/${encodeURIComponent(name)}`, { disabled });
}

/**
 * PUT /api/tools/groups/{group}，体 {"disabled": true|false}
 * → {group, disabled, affected: [...], changed, remainingExposed, persisted}
 *
 * affected 是这条命令<b>作用到</b>的工具（该分组下全部已暴露的工具），changed 是其中状态
 * 真的变了的个数——重复执行同一条命令时 affected 不变而 changed 归零，两者不同是正常的。
 *
 * 分组下一个已暴露的工具都没有时是 400（消息里列出可操作的分组），所以页面的分组下拉
 * 只列"至少有一个已暴露工具"的分组，而不是全量分组名。
 */
export function setGroupDisabled(group, disabled) {
  return putJson(`tools/groups/${encodeURIComponent(group)}`, { disabled });
}

// =============================================================================
// UserAdminController：调用者身份的列出 / 新增 / 停用
// =============================================================================

/*
 * 这一组是 /api/users。它和上一段的区别不只是路径：
 *
 * 1. <b>没配 spring.datasource.url 时三个端点全是 503</b>（UserAdminService 挂在那个键上，
 *    控制器对它是 @Nullable）。而那是受支持的部署形态——身份回落到凭据文件或只有管理员。
 *    所以 503 在这一页要按"功能未启用"渲染，不能按故障渲染；
 * 2. 管理员<b>不在这份清单里</b>。它的口令来自环境变量、不进库，也不能被这组接口创建或停用
 *    （同名创建与停用管理员在服务端都是 400）。列表为空不等于"没有调用者"；
 * 3. <b>这组接口能造出与自己同权限的管理员</b>（UserAdminController 的类注释里写明是有意的）。
 *    页面必须把这件事和"口令以原文提交、必须 HTTPS"一起常驻写出来。
 */

/**
 * GET /api/users → [{username, type, enabled, roles}]（数组，没有包装对象）
 *
 * - username 同时是授权判定里的主体 id；
 * - type 是 user / agent / service 三者之一（Credentials.PRINCIPAL_TYPES）；
 * - enabled=false 是被停用过的身份，<b>行仍然在</b>——停用是 UPDATE 而不是 DELETE；
 * - roles 是服务端排序后的数组，可以是空数组（不授予任何权限，只能过认证）。
 *   注意它是 authority 全名（ROLE_ADMIN），不是 hasRole 里那个去掉前缀的写法。
 *
 * 没配状态库时是 503 + 一条带可操作建议的消息，原样显示即可。
 */
export function fetchUsers() {
  return getJson('users');
}

/**
 * POST /api/users，体 {username, type, password, roles} → 201 + {username, type, enabled, roles}
 *
 * password 是<b>原文</b>，服务端哈希后落库、响应里不回显。这个请求因此只能跑在 HTTPS 上，
 * 而且请求体不要进访问日志（后端 UserAdminService 的类注释同一句话）。
 *
 * roles 省略/空数组表示不授予任何权限。可选值只有 ROLE_ADMIN 与 ROLE_DBA
 * （JdbcUserStore.GRANTABLE_AUTHORITIES），其它值是 400 而不是静默忽略——静默忽略的症状是
 * "配了但不生效"。400 的其余来源（用户名为空 / 与管理员同名 / 类型未知 / 口令为空 / 用户名已存在）
 * 都在响应体的消息里写清了，页面原样显示。
 */
export function createUser({ username, type, password, roles }) {
  return postJson('users', { username, type, password, roles });
}

/**
 * DELETE /api/users/{username} → {username, disabled:true}
 *
 * 语义是<b>停用</b>，不是删除：行还在，enabled 置 0。所以列表里那一行不会消失，只会变成"已停用"。
 * 404（"未知调用者"）只来自<b>用户名不存在</b>：停用语句是无条件的
 * `UPDATE mcp_user SET enabled = 0 WHERE username = ?`，对一个已经停用的身份再发一次照样影响
 * 一行、照样 200。所以返回体里的 disabled:true <b>不代表这次才变</b>——这一页不要照着它说
 * "已生效"之类暗示状态发生了变化的话（后端 UserAdminController.disable 上那句"调用方需要能区分
 * 停用成功与本来就是停用状态"的注释与这段 SQL 不符，以 SQL 为准）。
 * 停用管理员是 400（它是最后一个入口）。
 */
export function disableUser(username) {
  return deleteJson(`users/${encodeURIComponent(username)}`);
}

// =============================================================================
// AuthzViewController：按调用者的连接级/表级授权（只读）
// =============================================================================

/**
 * GET /api/authz →
 * {@code {enabled, grantCount, subjectCount, connectionCount, roles, grants}}
 *
 * - enabled：`entropy.mcp.authz.enabled`。<b>false 时下面那张表与实际行为无关</b>——
 *   总开关关着时判定直接返回，等于全放行，"谁能读写什么"的答案是"所有人都能"。
 *   这一条必须在页面上显眼写出来，否则一张空的 grants 表会被读成"谁都没有权限"，
 *   而真相正好相反；
 * - roles：[{role, canRead, canWrite}]，角色能力的图例。前端<b>不写第二份</b>"reader 能不能写"；
 * - grants：[{subject, subjectType, subjectId, connection, table, scope, role, canRead, canWrite}]，
 *   已按 subject → connection → table 排序（table 为 null 的连接级 grant 排在同连接的表级之前）。
 *   table=null 表示覆盖整条连接；给了表名的 grant <b>只</b>覆盖那张表，不附带连接级权限。
 *
 * 这个端点是<b>纯配置回显 + 角色展开</b>，不问判定引擎（facet 的 schema 没声明 listable，
 * 答不了反向枚举），也不连任何库。授权只能改 yml 并重启，所以这一页没有写操作。
 *
 * 两条这个视图答不了的事，页面要一起说：连接自身的 readonly 标记是另一道闸且在授权之前执行；
 * 只读元数据（listTables / describeTable / listIndexes）根本不过授权判定。
 */
export function fetchAuthz() {
  return getJson('authz');
}

