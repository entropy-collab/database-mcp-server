/*
 * location.hash ↔ 页面现场。
 *
 * hash 里现在有三个键：`#view=audit&limit=100&row=audit-logs:<行标识>`
 * - view / limit 由 App 拥有（哪个视图、后端返回多少条）
 * - row 由 InteractiveTable 拥有（哪张表的哪一行开着详情面板，见第 8 项）
 *
 * ── 为什么要把读写抽到一个模块里 ──
 * 两个拥有者各写一半，而 hash 是<b>一个</b>字符串。原来 App 里的 writeHash 是
 * `#view=${view}&limit=${limit}`——整串重写。InteractiveTable 再往里塞 row 的话，
 * App 下一次写就会把 row 抹掉（改一下条数，详情面板的深链就没了）。
 * 所以写入必须是「合并」而不是「重写」，而合并逻辑只能有一份。
 *
 * ── 为什么坚持 replaceState（别改成 location.hash = x）──
 * 这是原来那套双向同步不打环的<b>唯一</b>原因，改掉就会环：
 * - `history.replaceState` 按规范<b>不</b>触发 hashchange；
 * - `location.hash = x` 会触发。
 * 于是「state 变 → 写 hash」这条边不会反过来触发「hashchange → 改 state」。
 * 反方向（手改地址栏 / 前进后退 / 点站内 hash 链接）触发 hashchange → 改 state →
 * 写 hash，此时写入的值和当前 hash 完全一致，被下面的相等判断挡掉。两条边各只走一次。
 *
 * 也仍然是 replaceState 而不是 pushState：条数是个步进器，点五下就是五条历史记录；
 * 点五行看详情也是五条。后退键会彻底不可用。
 *
 * ── 键的顺序固定 ──
 * 顺序写死成 view → limit → row，而不是照 URLSearchParams 的插入顺序输出。
 * 不固定的话，同一个现场可能写出两种不同的字符串，相等判断失效，
 * 每次渲染都会 replaceState 一次（不报错，但白做，而且在 devtools 里看着像有 bug）。
 */

const KEY_ORDER = ['view', 'limit', 'row'];

/** 把当前 hash 解析成一个普通对象。缺的键是 undefined，不是空串。 */
export function readHashParams() {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  const out = {};
  for (const key of KEY_ORDER) {
    const value = params.get(key);
    if (value !== null && value !== '') {
      out[key] = value;
    }
  }
  return out;
}

/**
 * 合并式写入。patch 里值为 null / undefined / '' 的键表示<b>删掉</b>这个键。
 *
 * 删掉而不是写成空串：`#view=audit&limit=50&row=` 里那个尾巴既难读，
 * 又会让 readHashParams 之外的解析（比如人眼、或者别人写的脚本）多一次判断。
 */
export function writeHashParams(patch) {
  const merged = { ...readHashParams(), ...patch };
  const parts = [];
  for (const key of KEY_ORDER) {
    const value = merged[key];
    if (value === null || value === undefined || value === '') {
      continue;
    }
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  const next = parts.length > 0 ? `#${parts.join('&')}` : '';
  /* 相等判断是不打环的第二道保险（第一道是 replaceState 本身不触发 hashchange）。
     顺带避免了在 devtools 的 history 面板里刷出一串一模一样的条目。 */
  if (window.location.hash !== next) {
    window.history.replaceState(null, '', next || window.location.pathname);
  }
}

/**
 * 行深链的键：`<表标识>:<行标识>`。
 *
 * ── 为什么要带表标识 ──
 * 一页可能有多张表（性能页有慢查询 + SQL 模式两张 InteractiveTable）。
 * 只写行标识的话，两张表都会去匹配同一个值：要么两张同时开面板，
 * 要么开在错的那张上。表标识用 InteractiveTable 已有的 searchName
 * （'audit-logs' / 'slow-queries' / 'sql-patterns' …），它本来就是每张表唯一的。
 *
 * ── 分隔符为什么是 ':' ──
 * 行标识本身可能含各种字符（contentRowKey 用 \u0000 拼接多个字段），
 * 所以整体走 encodeURIComponent（在 writeHashParams 里做），
 * 解析时只按<b>第一个</b>冒号切一次——表标识里不会有冒号，行标识里有也不影响。
 */
export function encodeRowRef(tableId, rowKey) {
  return `${tableId}:${rowKey}`;
}

/** 从 hash 的 row 值里取出属于 tableId 的那个行标识；不属于它就返回 null。 */
export function rowKeyForTable(rowRef, tableId) {
  if (typeof rowRef !== 'string') {
    return null;
  }
  const sep = rowRef.indexOf(':');
  if (sep < 0 || rowRef.slice(0, sep) !== tableId) {
    return null;
  }
  const key = rowRef.slice(sep + 1);
  return key === '' ? null : key;
}

/*
 * 行标识 → 能放进 URL 的短引用。
 *
 * ── 为什么不能直接把 getRowKey 的结果塞进 hash ──
 * 各表的行标识来源不一样（见各 panel 的 ROW_KEY）：
 * - 审计历史有数据库主键 id，getRowKey 返回 "4213" —— 又短又可读，直接用最好；
 * - 审计流水和慢查询没有 id，用的是 contentRowKey(['timestamp','tool','sql','durationMs'])，
 *   它把四个字段用 \u0000 拼起来，<b>其中 sql 是完整的 SQL 原文</b>。
 *   那种 key 动辄上千字符，而且含控制字符，encodeURIComponent 之后是一坨 %00 和 %20，
 *   地址栏里根本没法看，复制给别人更是灾难。
 *
 * 所以分两种：短且干净的原样用（可读性优先），长的或含控制字符的折成一个短摘要。
 *
 * ── 摘要用 FNV-1a 而不是 crypto.subtle.digest ──
 * 后者是<b>异步</b>的（返回 Promise），而这个函数要在渲染期同步比较每一行；
 * 而且它只在 secure context 下可用，而这个面板通常跑在 http:// 上
 * （同一个原因让剪贴板按钮需要兜底，见 CopyTextButton）。
 * FNV-1a 是三行代码的非加密散列，这里不需要抗碰撞——需要的只是"同一行每次算出同一个值"。
 *
 * ── 碰撞的后果，以及为什么可以接受 ──
 * 32 位摘要在一页最多 500 行的规模下碰撞概率极低；真撞了的后果是详情面板展开了
 * 另一行。这和 contentRowKey 本身已经承认的风险是同一量级（见 AuditPanel 的注释：
 * 同一毫秒同一条 SQL 跑了两次会撞），没有引入新的失败模式。
 * <b>注意</b>：这类组合键（timestamp+tool+sql+durationMs）本来就不是真正唯一的，
 * 深链指向的是「内容长这样的那一行」，不是「那一条记录」——内存环形缓冲里没有记录 id，
 * 这是数据本身的限制，前端无法绕过。
 */
const MAX_INLINE_ROW_KEY = 48;

function fnv1a36(text) {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    /* Math.imul 保证 32 位乘法不溢出成浮点：`hash * 16777619` 在 JS 里会丢精度，
       丢精度不会报错，只会让不同输入更容易算出同一个值。 */
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(36);
}

export function rowRefFromKey(rawKey) {
  const text = String(rawKey ?? '');
  // eslint-disable-next-line no-control-regex
  if (text.length <= MAX_INLINE_ROW_KEY && !/[\u0000-\u001f:]/.test(text)) {
    return text;
  }
  /* h: 前缀让「这是摘要不是原值」在 URL 里就看得出来，
     也保证它不会和一个恰好长这样的真 id 相撞（真 id 里不会有冒号）。 */
  return `h:${fnv1a36(text)}`;
}
