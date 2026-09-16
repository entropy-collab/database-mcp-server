import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/*
 * 只读运维面板的构建配置。
 *
 * outDir 为什么是这个相对路径：产物必须落在 Maven 的 ${project.build.outputDirectory}/static
 * （也就是 target/classes/static），才会被 spring-boot-maven-plugin 的 repackage 收进
 * BOOT-INF/classes/static，进而被 Boot 的静态资源解析器在 fat jar 里找到。
 * frontend-maven-plugin 的 workingDirectory 是 src/main/frontend，所以从这里往上三级
 * （frontend → main → src）到 database-mcp-app，再进 target/classes/static。
 * 刻意不写绝对路径也不读环境变量：绝对路径在别人的机器和 CI 上都是错的。
 *
 * emptyOutDir 必须显式给出：outDir 在 Vite 的 root 之外时 Vite 默认拒绝清空并打印警告，
 * 不清空的后果是上一次构建的 assets/index-<旧 hash>.js 留在 target/classes/static 里，
 * 跟着新产物一起进 jar——一个 index.html 引用不到的死文件，白占体积还会让人误判版本。
 */
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../../../target/classes/static',
    emptyOutDir: true,
    // 面板是内网运维工具，出问题要能在浏览器里看栈；sourcemap 不进 jar（见下方 rollup 配置说明）。
    sourcemap: false,
    // 单文件产物更好排查：hash 变了就是构建变了，不用去比对一堆 chunk。
    // 但 Astryx 内部对 Tooltip 之类做了动态 import，Vite 仍会切出小 chunk，这是预期的。
    chunkSizeWarningLimit: 700,
  },
  /*
   * base 保持默认的 '/'：产物引用会写成 /assets/index-<hash>.js。
   * 这一点和 SecurityConfig 里的 "/assets/**" 白名单是同一个约定，改这里就必须改那里。
   */
  base: '/',
  server: {
    // 仅本地 `npm run dev` 用：把 API 打到本机跑着的服务上，避免开发时的跨域。
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
