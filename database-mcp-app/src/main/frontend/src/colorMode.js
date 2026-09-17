/*
 * 深色模式：跟随系统 / 亮 / 暗 三档。
 *
 * =============================================================================
 * 先说核实结果：theme-neutral <b>有</b>深色 token，所以这一项是真做，不是写一句
 * 「待支持」了事。
 * =============================================================================
 *
 * 核实过程与依据（全部从 node_modules 的产物里读出来的，不是照抄文档）：
 *
 * 1. `@astryxdesign/theme-neutral@0.6.2` 的 `dist/theme.css` 里有 152 处
 *    `light-dark(<亮>, <暗>)` 声明，其中 96 处是 UI token（其余是
 *    `--color-data-*` 那组数据可视化色板，它们两个值刻意相同）。抽几个看：
 *      --color-text-primary:     light-dark(#000000, #ffffff)
 *      --color-background-card:  light-dark(#ffffff, #1b1b1b)
 *      --color-background-body:  light-dark(#f1f1f1, #1b1b1b)
 *      --color-background-red:   light-dark(#ffc4be, #5b2b28)
 *      --color-error:            light-dark(#76000c, #ffc4be)
 *    两个值是<b>真的不一样</b>的，不是同一个值写了两遍。源头在
 *    `src/neutralTheme.ts`：那里的 token 值本来就是 `[light, dark]` 二元组
 *    （`defineTheme` 的 TokenValue 支持这个形状），`astryx theme build` 把二元组
 *    编译成了 CSS 的 `light-dark()`。
 *
 * 2. theme.css 里<b>没有</b>任何 `@media (prefers-color-scheme: dark)`，也<b>没有</b>
 *    第二个 `@scope`——整份 CSS 只有一个 `@scope ([data-astryx-theme="neutral"])`。
 *    也就是说切换深色<b>不靠</b>换一份 CSS，靠的是让 `light-dark()` 解析到第二个值。
 *
 * 3. `light-dark()` 解析到哪一支，由 CSS 的 `color-scheme` 属性决定。
 *    `@astryxdesign/core/src/reset.css` 第 375 行起有这段映射：
 *      :where(html[data-theme="light"])  { color-scheme: light; }
 *      :where(html[data-theme="dark"])   { color-scheme: dark; }
 *      :where(html:not([data-theme]))    { color-scheme: light dark; }
 *    最后一条正是「跟随系统」：`light dark` 表示两种都支持，浏览器按 OS 偏好选。
 *
 * 结论：三档切换 = 在 `<html>` 上写 `data-theme="light"` / `"dark"` / 把属性删掉。
 * 一行 DOM 操作，不需要重新加载任何 CSS。
 *
 * ── 为什么不用 core 的 <Theme> 组件 ──
 * `Theme` 的 mode prop（ThemeMode，含 'system'）确实做的就是这件事——它在自己是
 * 根 provider 时把 data-theme 同步到 document.documentElement（见 Theme.d.ts 的
 * "Root detection" 一段）。但用它要付两笔代价，而换不回任何东西：
 * - 它要一个 `defineTheme()` 出来的 DefinedTheme 对象作 theme prop，也就是要
 *   `import {neutralTheme} from '@astryxdesign/theme-neutral'`——那是一个 32 KB 的
 *   JS 产物（dist/source.mjs），会进<b>入口</b> chunk。而本项目的 token 是通过
 *   `import '@astryxdesign/theme-neutral/theme.css'` 以纯 CSS 方式加载的（见
 *   main.jsx），那份 JS 里的 token 定义对运行期毫无用处，纯粹是重复。
 * - 它还会同步 `data-astryx-theme`，而 index.html 已经在 `<html>` 上写死了
 *   `data-astryx-theme="neutral"`（那是硬约束，不能动）。让一个组件在运行期去覆写
 *   它，等于给一个已经正确的值加一条随时可能出错的第二来源。
 * 所以这里只做 Theme 会做的那<b>一件</b>有用的事：写 data-theme。
 * 注意 useTheme()（Sparkline 在用）在没有 provider 时会「从根 Theme 的
 * <html data-theme> 解析 mode，没有则回落到系统色」——见 useTheme.d.ts 的注释。
 * 我们写的正是这个属性，所以 Sparkline 取到的 token 会跟着切，不需要额外接线。
 *
 * ── 为什么默认是「跟随系统」而不是「亮」 ──
 * 值班的人白天在办公室、深夜在家，OS 自己已经在切了。默认跟随系统 = 不做决定；
 * 默认写死亮色 = 替深夜那位做了一个错的决定。
 */

/** localStorage 的键。带 dbmcp-ui- 前缀，和行详情面板宽度那个键（见 DetailSplit）同族。 */
const STORAGE_KEY = 'dbmcp-ui-color-mode';

/**
 * 三档。value 就是要写进 `<html data-theme>` 的值，'system' 例外——它表示
 * <b>删掉</b>这个属性（reset.css 的 `html:not([data-theme])` 那条规则接管）。
 *
 * 顺序是「跟随系统 / 亮 / 暗」：跟随系统放第一个，因为它是默认值，
 * 而 SegmentedControl 里默认值排在最左边最不容易被误读成"第三个选项"。
 */
export const COLOR_MODES = [
  { value: 'system', label: '跟随系统' },
  { value: 'light', label: '亮' },
  { value: 'dark', label: '暗' },
];

export const DEFAULT_COLOR_MODE = 'system';

const VALID = new Set(COLOR_MODES.map((m) => m.value));

/**
 * 读 localStorage 里存的选择。
 *
 * try/catch 不是形式主义：localStorage 在 Safari 的隐私模式下、以及某些企业策略
 * 关掉 DOM storage 的浏览器里会<b>抛异常</b>而不是返回 null。一个主题开关不该让
 * 整个面板白屏，所以读不到就当没存过。
 * 存进去的值也要校验：手改过 localStorage 或者版本升级换过档位名的情况下，
 * 一个不认识的值写到 data-theme 上会命中 reset.css 里的哪条规则说不清
 * （`html[data-theme="foo"]` 三条都不匹配，color-scheme 落到 UA 默认）。
 */
export function readStoredColorMode() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    return VALID.has(raw) ? raw : DEFAULT_COLOR_MODE;
  } catch {
    return DEFAULT_COLOR_MODE;
  }
}

export function storeColorMode(mode) {
  try {
    window.localStorage.setItem(STORAGE_KEY, mode);
  } catch {
    /* 存不下就只在本次会话生效。不提示——一个"你的主题偏好存不下来"的横幅
       比这件事本身更烦人，而且用户对此无能为力。 */
  }
}

/**
 * 把选择落到 DOM 上。
 *
 * 'system' 走 removeAttribute 而不是 setAttribute('data-theme', 'light dark')：
 * reset.css 匹配的是 `html:not([data-theme])`，属性只要<b>存在</b>就不匹配，
 * 哪怕值是 'light dark' 也一样——那种写法会落到 UA 默认而不是跟随系统。
 *
 * 只碰 data-theme，绝不碰 data-astryx-theme：后者由 index.html 写死为 "neutral"，
 * 是 theme.css 那个 @scope 生效的前提（改掉 = 全站 token 一个都不赋值）。
 */
export function applyColorMode(mode) {
  const root = document.documentElement;
  if (mode === 'light' || mode === 'dark') {
    root.setAttribute('data-theme', mode);
  } else {
    root.removeAttribute('data-theme');
  }
}
