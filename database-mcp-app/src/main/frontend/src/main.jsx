/*
 * 前端入口。除了挂载 React，这里唯一的职责是把 Astryx 的三张 CSS 按正确顺序引进来。
 *
 * ── 关于这三个 import 路径的坑（务必不要按官方文档改回去）──
 * 0.6.2 的公开文档写的是 `import '@astryxdesign/theme-neutral/css'`，这条路径在 0.6.2 里
 * 根本不存在：包的 exports 字段只声明了 "./theme.css"，用 /css 会在 vite build 阶段直接失败，
 * 报 `"./css" is not exported from package`。下面这三条是从两个包各自的 package.json
 * exports 字段里读出来的真实入口：
 *   @astryxdesign/core        → "./reset.css"（src/reset.css）、"./astryx.css"（dist/astryx.css）
 *   @astryxdesign/theme-neutral → "./theme.css"（dist/theme.css）
 * Astryx 还在 Beta，文档与包漂移不止这一处；下次遇到「路径解析不了」时，
 * 先去 node_modules/<pkg>/package.json 读 exports，不要照抄网上的示例。
 *
 * 顺序有意义：reset 先落地，再是组件样式，最后才是主题 token。
 * 主题必须最后，因为它要覆盖 astryx.css 里的默认变量值。
 */
import '@astryxdesign/core/reset.css';
import '@astryxdesign/core/astryx.css';
import '@astryxdesign/theme-neutral/theme.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { LayerProvider } from '@astryxdesign/core';
import App from './App.jsx';

/*
 * LayerProvider 包在最外层：Table 的 textOverflow="truncate" 会在被截断的单元格上挂 Tooltip，
 * Tooltip 走 Layer 体系。没有这个 Provider 时不是样式变丑，而是 hover 到截断单元格就抛异常。
 */
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <LayerProvider>
      <App />
    </LayerProvider>
  </StrictMode>,
);
