/*
 * 保存的视图（第 6 项）。
 *
 * 一个「视图」= 把某张表当前的四件事打成一个命名快照：
 *   1. 过滤条件（PowerSearch 的 token + 表头漏斗）
 *   2. 排序
 *   3. 分组字段
 *   4. 列显隐
 * 存 localStorage，按表分开（key 里带表标识，见 persist.js 的说明）。
 *
 * ── 为什么值得做，而不是让人每次重新点 ──
 * 排查时反复用的组合就那么几个：「只看失败的」「只看超过 1 秒的」「按工具折叠」。
 * 每次重新点四五下不是麻烦，是<b>会点错</b>——筛错一个条件得出的结论是错的，
 * 而且看不出是筛错了。命名快照把这几个组合固定下来。
 *
 * ── 内置预设为什么不可删 ──
 * 「只看失败」「只看慢的」是这张表存在的两个主要理由。允许删掉之后，
 * 一个人删了，下一个人（或者同一个人换了台机器）就以为这个功能不存在。
 * 用户自己存的可以删——那是他自己的东西。
 * 实现上：预设是<b>模块级常量</b>，从不进 localStorage；用户的存在 localStorage。
 * 两者在 UI 上合并显示，删除按钮只出现在后者上。这样"不可删"不是靠一个
 * isBuiltIn 标记去挡（那种标记可以被改过的 localStorage 绕过），而是结构上不可能。
 *
 * ── 快照里 null 的含义 ──
 * 四个维度里值为 null 的表示「保持当前不变」，不是「清空」。
 * 预设只关心过滤条件，所以它的 sort / groupBy / activeColumnKeys 都是 null：
 * 点「只看失败」不该顺带把你刚调好的列显隐推翻。
 * 用户自己存的快照四项都有值（存的时候是照现状全量记下来的）。
 */
import { readJson, tableStorageKey, writeJson } from './persist.js';

/*
 * 内置预设。
 *
 * ── 这两个 filter 对象的形状是从 core 的产物里读出来的，不是猜的 ──
 * PowerSearchFilter = {field, operator, value}（见 dist/PowerSearch/types.d.ts）。
 * operator 的键与 value 的 type 由 usePowerSearchConfig 按字段类型生成
 * （见 dist/PowerSearch/usePowerSearchConfig.js 里的 EnumOps / NumberOps）：
 * - type:'enum'   的字段 → operator 'is' / 'is_not'，value {type:'enum', value:<字符串>}
 * - type:'number' 的字段 → operator 'greater_than' 等，value {type:'float', value:<数字>}
 * 写错 operator 键（比如把 'greater_than' 写成 'gt'）不会报错，只会筛不出东西。
 *
 * ── requires ──
 * 预设引用的字段不一定每张表都有：慢查询表有 durationMs 但没有 result，
 * SQL 模式表两个都没有。requires 列出它依赖的字段 key，InteractiveTable 据此
 * <b>隐藏</b>不适用的预设——而不是显示一个点了没反应的按钮（静默失效比没有更糟）。
 *
 * ── 「只看慢的」的 1000ms 是个判断，不是标准 ──
 * 服务端的慢查询阈值是配置项（entropy 那边的 slow-query-threshold-ms），
 * 这里写死 1000 是「人眼觉得慢」的那条线，两者刻意不挂钩：这个预设要在
 * 审计流水页也能用，而那一页拿不到服务端阈值（拿它要多打一个端点，
 * 正好会踩 App.jsx 里写的那个"页面别污染性能指标"的坑）。
 * 想改成别的数只需要改这一行。
 */
const SLOW_THRESHOLD_MS = 1000;

export const BUILT_IN_VIEWS = [
  {
    id: 'preset:failed',
    name: '只看失败',
    isBuiltIn: true,
    requires: ['result'],
    snapshot: {
      searchFilters: [
        { field: 'result', operator: 'is', value: { type: 'enum', value: '失败' } },
      ],
      /* 表头漏斗清空：预设要给出一个确定的结果，而不是"我的条件 AND 你上次留下的条件"。 */
      columnFilters: {},
      sort: null,
      groupBy: null,
      activeColumnKeys: null,
    },
  },
  {
    id: 'preset:slow',
    name: `只看慢的（> ${SLOW_THRESHOLD_MS}ms）`,
    isBuiltIn: true,
    requires: ['durationMs'],
    snapshot: {
      searchFilters: [
        {
          field: 'durationMs',
          operator: 'greater_than',
          value: { type: 'float', value: SLOW_THRESHOLD_MS },
        },
      ],
      columnFilters: {},
      sort: null,
      groupBy: null,
      activeColumnKeys: null,
    },
  },
];

/** 用户存的视图放在 `dbmcp-ui:views:<表标识>` 下，是一个数组（有序，按保存顺序）。 */
function viewsKey(tableId) {
  return tableStorageKey('views', tableId);
}

/**
 * 读用户存的视图。
 *
 * 逐条校验形状：localStorage 里的东西可能来自上一个版本，也可能被手改过。
 * 一条坏数据不该让整个下拉列表消失，所以是<b>逐条</b>过滤而不是整体判断。
 */
export function readUserViews(tableId) {
  const raw = readJson(viewsKey(tableId), []);
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw.filter(
    (v) => v
      && typeof v.id === 'string'
      && typeof v.name === 'string'
      && v.snapshot
      && typeof v.snapshot === 'object',
  );
}

export function writeUserViews(tableId, views) {
  writeJson(viewsKey(tableId), views);
}

/**
 * 内置预设 + 用户视图，按「预设在前」合并。
 *
 * availableFieldKeys 用来过滤预设（见 BUILT_IN_VIEWS 上方关于 requires 的说明）。
 * 用户视图不过滤：它是用户在这张表上存的，字段一定存在过；万一列定义变了，
 * 应用时多余的条件筛不出东西——那是可见的结果，不是静默失效。
 */
export function mergeViews(userViews, availableFieldKeys) {
  const available = new Set(availableFieldKeys);
  const presets = BUILT_IN_VIEWS.filter(
    (v) => (v.requires ?? []).every((key) => available.has(key)),
  );
  return [...presets, ...userViews];
}

/**
 * 用户视图的 id。
 *
 * 用时间戳而不是 name：允许同名（两个人对"只看今天的"的理解可能不同，
 * 而强制唯一名会让保存动作偶尔失败，那更烦）。前缀 user: 让它和 preset: 永不相撞，
 * 也让「这一条能不能删」在 id 上就看得出来。
 */
export function newUserViewId() {
  return `user:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`;
}
