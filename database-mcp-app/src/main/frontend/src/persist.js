/*
 * localStorage 的统一入口。
 *
 * ── 为什么每处都要 try/catch ──
 * localStorage 在两种真实环境里会<b>抛异常</b>而不是返回 null：
 * - Safari 的无痕模式（读写都可能抛 SecurityError / QuotaExceededError）；
 * - 企业策略或浏览器设置关掉了 DOM storage。
 * 这个面板是内网运维工具，被各种奇怪配置的浏览器打开是常态。一个"记住列宽"的功能
 * 不该让整页白屏，所以读不到就当没存过、写不进就只在本次会话生效，都不弹提示——
 * 用户对此无能为力，提示只是噪声。
 *
 * ── 为什么 key 一律带表标识 ──
 * 列显隐、列宽、保存的视图都是<b>每张表一份</b>的。用一个全局 key 的后果是：
 * 在审计流水里藏掉「SQL」列，慢查询表的 SQL 列也跟着不见了——而两张表的列集合
 * 本来就不一样，这种串味没有任何合理解释。表标识用 InteractiveTable 已有的
 * searchName（'audit-logs' / 'slow-queries' / 'sql-patterns' …），它本来就每张表唯一。
 *
 * ── 前缀 ──
 * 全部以 dbmcp-ui- 开头，和行详情面板宽度那个 autoSaveId（'dbmcp-ui-row-detail-width'）
 * 同族。同一个 origin 下可能还跑着别的东西，不加前缀会撞。
 */
import { useCallback, useState } from 'react';

const PREFIX = 'dbmcp-ui';

/** `dbmcp-ui:<用途>:<表标识>`。用途和表标识都不含冒号，所以不会有歧义。 */
export function tableStorageKey(purpose, tableId) {
  return `${PREFIX}:${purpose}:${tableId}`;
}

export function readJson(key, fallback) {
  try {
    const raw = window.localStorage.getItem(key);
    if (raw === null) {
      return fallback;
    }
    const parsed = JSON.parse(raw);
    return parsed === null || parsed === undefined ? fallback : parsed;
  } catch {
    /* JSON.parse 失败也走这里：手改过 localStorage、或者上一个版本存的是别的形状。
       抛出去会让整个面板挂掉，而正确的行为是"当作没存过"。 */
    return fallback;
  }
}

export function writeJson(key, value) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* 见文件头注释：写不进就只在本次会话生效。 */
  }
}

export function removeKey(key) {
  try {
    window.localStorage.removeItem(key);
  } catch {
    /* 同上。 */
  }
}

/**
 * 「state + 落 localStorage」的组合。
 *
 * ── 为什么写入放在 setter 里而不是 useEffect ──
 * useEffect 版本在挂载时会先写一次（把刚读出来的值原样写回去），本身无害，
 * 但它让「localStorage 里有没有这一项」不再等于「用户有没有改过设置」——
 * 而第 4 项要的正是这个区别：没改过的表应该跟着列定义的变化走，
 * 改过的表才该被存下来的值覆盖。所以只在真的 set 时写。
 *
 * ── validate ──
 * 存下来的值可能来自上一个版本（比如列被删掉了、档位名改了）。校验函数把不合法的
 * 值折回 fallback，而不是把一个不存在的列 key 塞给 Table（那会渲染出一列空白，
 * 而且没有任何报错指向 localStorage）。
 */
export function usePersistentState(key, fallback, validate) {
  const [value, setValue] = useState(() => {
    const stored = readJson(key, undefined);
    if (stored === undefined) {
      return fallback;
    }
    return validate ? validate(stored) ?? fallback : stored;
  });

  const set = useCallback((next) => {
    setValue((prev) => {
      const resolved = typeof next === 'function' ? next(prev) : next;
      writeJson(key, resolved);
      return resolved;
    });
  }, [key]);

  /** 恢复默认 = 删掉存的那一份，而不是存一份"等于默认值"的副本。 */
  const reset = useCallback(() => {
    removeKey(key);
    setValue(fallback);
    // fallback 是模块级常量或 useMemo 出来的，依赖它是安全的。
  }, [key, fallback]);

  return [value, set, reset];
}
