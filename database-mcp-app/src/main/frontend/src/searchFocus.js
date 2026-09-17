/*
 * `/` 聚焦搜索框的注册表。
 *
 * ── 为什么单独一个文件（这不是洁癖，是懒加载的前提）──
 * 这段逻辑原来住在 components.jsx 里，App.jsx 从那里 import focusFirstSearch。
 * 上了视图级懒加载之后这一条 import 是致命的：它把整个 components.jsx（连带它依赖的
 * Table / PowerSearch / Timestamp / useResizable 那一大片 core）拉进了<b>入口</b>的
 * 静态依赖图，于是 Vite 给 index.html 加了 `<link rel="modulepreload">`，
 * 首屏又要下载那 370 KB —— 懒加载等于白做（实测：不拆这一步，components chunk 与
 * IconButton chunk 都会被 index.html preload）。
 *
 * 这个模块<b>不能</b>再 import 任何 core 组件，否则同样的问题会原样回来。
 * 它只有一个 Set 和三个纯函数，没有依赖。
 *
 * ── 为什么是模块级有序 Set，而不是 context / querySelector ──
 * 一页可能有多张表（性能页三张），快捷键注册点在 App（一个地方能看全所有快捷键），
 * 而输入框在 InteractiveTable 里。想过的两个替代都更差：
 * - 每个 InteractiveTable 自己注册 '/'：useHotkeys 是每实例一个 window 监听器，
 *   三张表都会响应，最后哪个抢到焦点取决于挂载顺序；
 * - document.querySelector 找 input：顶部搜索框和表头漏斗里的输入框长得一样，选不准。
 *
 * Set 的迭代顺序 = 插入顺序 = 挂载顺序 = JSX 里的出现顺序 = 页面从上到下的顺序，
 * 所以「第一个」就是运维视觉上的第一个搜索框。
 */
const searchHandles = new Set();

/** InteractiveTable 在 effect 里调它，返回值直接当 cleanup 用。 */
export function registerSearchHandle(handle) {
  searchHandles.add(handle);
  return () => { searchHandles.delete(handle); };
}

/** 聚焦当前页面上第一个搜索框。没有搜索框（连接页/服务信息页）时什么都不做。 */
export function focusFirstSearch() {
  const first = searchHandles.values().next().value;
  first?.current?.focusTypeahead?.();
}
