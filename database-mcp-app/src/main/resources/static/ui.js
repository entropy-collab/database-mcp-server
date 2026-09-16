/*
 * 只读运维面板脚本。原生 JS，无框架、无构建、无外链。
 *
 * 错误处理的原则：任何非 2xx 都把状态码和响应体原样显示在对应面板里，绝不吞掉后渲染空表格。
 * 打开鉴权（entropy.mcp.security.enabled=true）之后 /api/** 需要 ROLE_ADMIN，
 * 浏览器没带凭证时这里就该明明白白显示 401，而不是让人以为“没有数据”。
 */
'use strict';

var CONFIG = null;

// ─── 基础工具 ──────────────────────────────────────────────────────────

function $(id) { return document.getElementById(id); }

/** 一律用 textContent 落地，不拼 HTML：审计流水里的 SQL 是不可信输入。 */
function cell(row, text, cls) {
  var td = document.createElement('td');
  td.textContent = text === null || text === undefined ? '' : String(text);
  if (cls) { td.className = cls; }
  row.appendChild(td);
  return td;
}

/**
 * @param columns 形如 [{title, key, cls}]，cls 用于 num / sql 这类对齐与换行处理
 */
function renderTable(target, columns, rows, emptyHint) {
  var host = $(target);
  host.textContent = '';
  if (!rows || rows.length === 0) {
    var p = document.createElement('div');
    p.className = 'empty';
    p.textContent = emptyHint || '暂无数据';
    host.appendChild(p);
    return;
  }
  var table = document.createElement('table');
  var thead = document.createElement('thead');
  var htr = document.createElement('tr');
  columns.forEach(function (c) {
    var th = document.createElement('th');
    th.textContent = c.title;
    htr.appendChild(th);
  });
  thead.appendChild(htr);
  table.appendChild(thead);

  var tbody = document.createElement('tbody');
  rows.forEach(function (r) {
    var tr = document.createElement('tr');
    columns.forEach(function (c) {
      var v = r[c.key];
      if (c.key === 'success') {
        var td = cell(tr, v ? 'OK' : 'FAIL');
        td.className = v ? 'ok' : 'bad';
        return;
      }
      if (typeof v === 'object' && v !== null) { v = JSON.stringify(v); }
      cell(tr, v, c.cls);
    });
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  host.appendChild(table);
}

function renderCards(target, pairs) {
  var host = $(target);
  host.textContent = '';
  pairs.forEach(function (p) {
    var card = document.createElement('div');
    card.className = 'card';
    var k = document.createElement('div');
    k.className = 'k';
    k.textContent = p[0];
    var v = document.createElement('div');
    v.className = 'v';
    v.textContent = p[1] === null || p[1] === undefined ? '-' : String(p[1]);
    card.appendChild(k);
    card.appendChild(v);
    host.appendChild(card);
  });
}

function showError(panel, message) {
  var box = $('err-' + panel);
  box.textContent = message;
  box.classList.remove('hidden');
}

function clearError(panel) {
  var box = $('err-' + panel);
  box.textContent = '';
  box.classList.add('hidden');
}

/** 失败时抛出带状态码的 Error，由各面板自己显示；成功返回解析后的 JSON。 */
function getJson(url) {
  return fetch(url, { headers: { 'Accept': 'application/json' } }).then(function (res) {
    return res.text().then(function (body) {
      if (!res.ok) {
        var hint = '';
        if (res.status === 401 || res.status === 403) {
          hint = '\n（服务已开启 HTTP 鉴权，浏览器需要提供 admin 凭证）';
        }
        throw new Error('HTTP ' + res.status + ' ' + res.statusText + '\n' + body + hint);
      }
      return body ? JSON.parse(body) : null;
    });
  });
}

function limit() {
  var v = parseInt($('limit').value, 10);
  if (isNaN(v) || v < 1) { v = 1; }
  if (v > 500) { v = 500; }
  return v;
}
// ─── 各面板加载 ────────────────────────────────────────────────────────

function loadAudit() {
  clearError('audit');
  return getJson('api/audit/logs?limit=' + limit()).then(function (rows) {
    renderTable('table-audit', [
      { title: '时间', key: 'timestamp' },
      { title: '工具', key: 'tool' },
      { title: '连接', key: 'connectionKey' },
      { title: 'SQL', key: 'sql', cls: 'sql' },
      { title: '行数', key: 'rows', cls: 'num' },
      { title: '耗时(ms)', key: 'durationMs', cls: 'num' },
      { title: '结果', key: 'success' }
    ], rows, '内存缓冲区为空：服务启动后还没有被审计的数据库操作。');
  }).catch(function (e) { showError('audit', e.message); });
}

function loadHistory() {
  clearError('history');
  if (CONFIG && CONFIG.auditPersistence === false) {
    // 不发那个注定 503 的请求，直接说清楚为什么是空的以及怎么打开。
    showError('history', '审计未落库：本服务没有配置 spring.datasource.url，'
      + '/api/audit/history 会返回 503。\n'
      + '打开方式见 README 的「审计持久化（可选）」一节；在那之前这一页只能是空的。');
    renderTable('table-history', [], [], '审计持久化未启用。');
    return Promise.resolve();
  }
  return getJson('api/audit/history?limit=' + limit()).then(function (rows) {
    renderTable('table-history', [
      { title: 'id', key: 'id', cls: 'num' },
      { title: '时间', key: 'timestamp' },
      { title: '工具', key: 'tool' },
      { title: '连接', key: 'connectionKey' },
      { title: 'SQL', key: 'sql', cls: 'sql' },
      { title: '行数', key: 'rows', cls: 'num' },
      { title: '耗时(ms)', key: 'durationMs', cls: 'num' },
      { title: '结果', key: 'success' },
      { title: '错误', key: 'error' }
    ], rows, '审计表里这个区间没有记录。');
  }).catch(function (e) { showError('history', e.message); });
}

function loadConnections() {
  clearError('connections');
  return getJson('api/ui/connections').then(function (data) {
    var reg = data.registered || {};
    var pools = data.pools || {};
    renderCards('sum-connections', [
      ['已注册连接', reg.totalConnections],
      ['活跃连接', reg.activeConnections],
      ['物理连接池', pools.totalConnections],
      ['连接名（含别名）', pools.totalConnectionNames],
      ['健康池', pools.healthyPools],
      ['降级池', pools.degradedPools]
    ]);
    renderTable('table-conn', [
      { title: '连接名', key: 'key' },
      { title: '方言', key: 'dialect' },
      { title: 'JDBC URL（已脱敏）', key: 'jdbcUrlMasked' },
      { title: '归属', key: 'owner' },
      { title: '状态', key: 'status' },
      { title: '创建于', key: 'createdAt' },
      { title: '租约到期', key: 'leaseExpiry' },
      { title: '池大小', key: 'poolSize', cls: 'num' }
    ], reg.connections, '没有已注册的连接：这套部署目前是 BYOK-only，'
      + '或者预声明连接还没有注册成功。');
    renderTable('table-pools', [
      { title: '连接名', key: 'connectionName' },
      { title: '物理池', key: 'canonicalName' },
      { title: '别名', key: 'isAlias' },
      { title: '方言', key: 'dialect' },
      { title: '总/活跃/空闲', key: 'poolUsage' },
      { title: '等待线程', key: 'pendingThreads', cls: 'num' },
      { title: '池上限', key: 'maxPoolSize', cls: 'num' },
      { title: '健康', key: 'isPoolHealthy' },
      { title: '告警', key: 'healthWarnings' }
    ], (pools.pools || []).map(function (p) {
      p.poolUsage = p.totalConnections + ' / ' + p.activeConnections + ' / ' + p.idleConnections;
      return p;
    }), '还没有建立过任何连接池：已注册但从未使用过的连接不会出现在这里。');
  }).catch(function (e) { showError('connections', e.message); });
}

function loadPerformance() {
  clearError('performance');
  return getJson('api/ui/performance?limit=' + limit()).then(function (data) {
    var s = data.summary || {};
    renderCards('sum-performance', [
      ['查询总数', s.totalQueries],
      ['慢查询数', s.slowQueryCount],
      ['慢查询占比', s.slowQueryRate],
      ['慢查询阈值(ms)', s.slowQueryThresholdMs],
      ['跟踪的 SQL 模式', data.totalTrackedPatterns]
    ]);
    renderTable('table-slow', [
      { title: '时间', key: 'timestamp' },
      { title: '工具', key: 'tool' },
      { title: '连接', key: 'connectionKey' },
      { title: 'SQL', key: 'sql', cls: 'sql' },
      { title: '行数', key: 'rows', cls: 'num' },
      { title: '耗时(ms)', key: 'durationMs', cls: 'num' }
    ], data.slowQueries, '没有超过阈值的查询。');
    renderTable('table-patterns', [
      { title: 'SQL 模式', key: 'pattern', cls: 'sql' },
      { title: '次数', key: 'count', cls: 'num' },
      { title: '总耗时(ms)', key: 'totalDurationMs', cls: 'num' },
      { title: '平均(ms)', key: 'avgDurationMs', cls: 'num' },
      { title: '最大(ms)', key: 'maxDurationMs', cls: 'num' },
      { title: '总行数', key: 'totalRows', cls: 'num' },
      { title: '平均行数', key: 'avgRows', cls: 'num' }
    ], data.patterns, '还没有统计到任何 SQL 模式。');

    var metrics = data.metrics;
    if (!metrics) {
      renderTable('table-metrics', [], [], '本部署没有 micrometer 指标采集器。');
      return;
    }
    // 指标是一张扁平的 key/value 表，字段名随工具名动态变化，所以按 key 排序后逐行列出。
    renderTable('table-metrics', [
      { title: '指标', key: 'k' },
      { title: '值', key: 'v' }
    ], Object.keys(metrics).sort().map(function (k) {
      return { k: k, v: metrics[k] };
    }), '暂无指标。');
  }).catch(function (e) { showError('performance', e.message); });
}

// ─── 页面骨架 ──────────────────────────────────────────────────────────

var LOADERS = {
  audit: loadAudit,
  history: loadHistory,
  connections: loadConnections,
  performance: loadPerformance
};

var current = 'audit';

function selectTab(name) {
  current = name;
  Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (b) {
    b.classList.toggle('active', b.getAttribute('data-tab') === name);
  });
  ['audit', 'history', 'connections', 'performance'].forEach(function (n) {
    $('panel-' + n).classList.toggle('hidden', n !== name);
  });
  refresh();
}

function refresh() {
  var loader = LOADERS[current];
  if (!loader) { return; }
  loader().then(function () {
    $('updated').textContent = '更新于 ' + new Date().toLocaleTimeString();
  });
}

/**
 * 横幅由 /api/ui/config 的 authEnabled 决定，不写死。
 * 拿不到 config 时也要出横幅（内容改成“状态未知”）——静默假设“已鉴权”是最坏的失败方向。
 */
function renderBanner() {
  var banner = $('auth-banner');
  if (CONFIG && CONFIG.authEnabled === true) {
    banner.classList.add('hidden');
    return;
  }
  var unknown = !CONFIG;
  banner.textContent = unknown
    ? '无法读取 /api/ui/config：本页面的鉴权状态未知。'
    : '本服务未开启 HTTP 鉴权（entropy.mcp.security.enabled=false）。';
  var sub = document.createElement('span');
  sub.className = 'sub';
  sub.textContent = unknown
    ? '在确认鉴权状态之前，请当作本页面与其 API 都是无凭证可读的。'
    : '本页面与它调用的所有 API（含审计流水中的 SQL 原文、慢查询原文、连接清单）'
      + '对任何能访问此端口的人开放。关闭这个暴露的唯一开关是 '
      + 'entropy.mcp.security.enabled=true（需配置 MCP_SECURITY_ADMIN_PASSWORD）。';
  banner.appendChild(sub);
  banner.classList.remove('hidden');
}

var timer = null;

function bootstrap() {
  Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (b) {
    b.addEventListener('click', function () { selectTab(b.getAttribute('data-tab')); });
  });
  $('refresh').addEventListener('click', refresh);
  $('limit').addEventListener('change', refresh);
  // 自动刷新默认关闭：每次刷新都会经过连接注册表的读方法，而那些方法会被
  // PerformanceTimingAspect 记进 recordToolExecution，固定轮询等于让页面自己污染性能指标。
  $('autorefresh').addEventListener('change', function () {
    if (timer) { clearInterval(timer); timer = null; }
    if ($('autorefresh').checked) { timer = setInterval(refresh, 10000); }
  });

  getJson('api/ui/config').then(function (cfg) {
    CONFIG = cfg;
  }).catch(function () {
    CONFIG = null;
  }).then(function () {
    renderBanner();
    refresh();
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', bootstrap);
} else {
  bootstrap();
}

