/*
 * 后端读取层。十个数据端点 + 一个自举端点，全部是 GET，页面不提供任何写入口。
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
