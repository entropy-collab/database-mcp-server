/*
 * 后端读取层。四个数据端点 + 一个自举端点，全部是 GET，页面不提供任何写入口。
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
