/*
 * 只读运维面板的应用外壳。
 *
 * 布局照搬官方 shell-side-nav / shell-nav 两个模板的结构（`npx @astryxdesign/cli@0.6.2
 * template shell-side-nav --skeleton` 可复现）：AppShell 负责 topNav / sideNav / banner
 * 三个槽位与窄视口折叠，Layout 负责 header + content 两带。
 *
 * 上一版是「一个 h1 + 一排平铺 Tab + 一张表」，看着不像个能用的面板——问题不在配色，
 * 在于没有外壳：没有常驻的导航轴，也没有一眼能看到服务状态的地方。
 *
 * 刻意没用模板里的这些东西：
 * - 图标（模板用 @heroicons/react）：没装，而且为了几个装饰性图标引一个图标库不值得。
 *   SideNavItem 的 icon 是可选的，省掉之后就是纯文字导航。
 * - CommandPalette（shell-nav 里的 ⌘K 搜索）：视图从 8 个涨到 18 个之后这条重新想过两遍。
 *   还是不加：18 项分成五组之后，「我要找的东西在哪一组」是扫一眼就能完成的，
 *   而 CommandPalette 要求先想起视图叫什么再打字。真正的分界线是「组数多到扫不完」，
 *   五组还差得远。前 9 项另有数字键（见 HOTKEY_VIEW_COUNT）。
 * - 图表（dashboard-alert-rail 的 Sparkline / MetricChart）：那些底下是 recharts，没装。
 *   性能页那条折线是手写 SVG（见 components.jsx 的 Sparkline）。
 */
import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AppShell,
  Banner,
  Button,
  HStack,
  Heading,
  Kbd,
  Layout,
  LayoutContent,
  LayoutHeader,
  NumberInput,
  SegmentedControl,
  SegmentedControlItem,
  SideNav,
  SideNavHeading,
  SideNavItem,
  SideNavSection,
  Skeleton,
  StatusDot,
  Switch,
  Text,
  Timestamp,
  TopNav,
  VStack,
  useHotkeys,
} from '@astryxdesign/core';
import { FALLBACK_MAX_LIMIT, fetchConfig, logout, onUnauthorized } from './api.js';
/* 刻意从 ./searchFocus.js 而不是 ./components.jsx 取：后者会把 370 KB 的显示件
   连带一大片 core 拉进入口的静态依赖图（Vite 会给它加 modulepreload），
   视图级懒加载就白做了。理由写在 searchFocus.js 的头注释里。 */
import { focusFirstSearch } from './searchFocus.js';
import { readHashParams, writeHashParams } from './hashState.js';
import { DiagnosticBundleButton } from './diagnostics.jsx';
import {
  COLOR_MODES,
  applyColorMode,
  readStoredColorMode,
  storeColorMode,
} from './colorMode.js';

/*
 * ── 视图级懒加载 ──
 *
 * 16 个视图静态 import 的结果是一个 983,481 B 的主 chunk：打开「审计流水」的人也要
 * 先下载 SQL 体检、血缘、DBA、CDC 那几页的代码。这些页彼此没有共用逻辑（共用的部分
 * 在 components.jsx / api.js，会被提到公共 chunk 里），所以按视图切分是干净的切法。
 *
 * 用 React.lazy 而不是自己写动态 import + state：lazy 的 promise 结果被它自己缓存，
 * 切走再切回不会重新发请求，而手写那版要自己维护一份「已加载过哪些」的登记。
 *
 * ⚠️ 与 WebUiTest 的关系（重要，别误判）：
 * WebUiTest.hashedAssetsReferencedByIndexHtmlAreRetrievable 是从 index.html 的正文里
 * 正则抠 `/assets/...` 再逐个请求。懒加载切出来的 chunk <b>不会</b>被 index.html 引用
 * （它们由主 chunk 在运行期 import()），所以那个测试不会请求它们 —— 测试仍然过，
 * 但也意味着它<b>覆盖不到</b>「懒加载 chunk 是否真的可取回」。
 * SecurityConfig 的白名单是 `/assets/**` 通配，所以取回这件事在鉴权层面是成立的；
 * 产物是否真的落盘则要在构建后自己数一遍 target/classes/static/assets/。
 * 不要为此改测试：那个测试断言的契约（index.html 引用的都取得到）本身是对的。
 */
const PANELS = {
  audit: lazy(() => import('./panels/AuditPanel.jsx')),
  history: lazy(() => import('./panels/HistoryPanel.jsx')),
  reports: lazy(() => import('./panels/ReportsPanel.jsx')),
  connections: lazy(() => import('./panels/ConnectionsPanel.jsx')),
  performance: lazy(() => import('./panels/PerformancePanel.jsx')),
  info: lazy(() => import('./panels/InfoPanel.jsx')),
  tools: lazy(() => import('./panels/ToolsPanel.jsx')),
  schema: lazy(() => import('./panels/SchemaPanel.jsx')),
  catalog: lazy(() => import('./panels/CatalogPanel.jsx')),
  lineage: lazy(() => import('./panels/LineagePanel.jsx')),
  quality: lazy(() => import('./panels/QualityPanel.jsx')),
  sql: lazy(() => import('./panels/SqlDoctorPanel.jsx')),
  dba: lazy(() => import('./panels/DbaPanel.jsx')),
  cdc: lazy(() => import('./panels/CdcPanel.jsx')),
  backups: lazy(() => import('./panels/BackupsPanel.jsx')),
  jobs: lazy(() => import('./panels/JobsPanel.jsx')),
  users: lazy(() => import('./panels/UsersPanel.jsx')),
  authz: lazy(() => import('./panels/AuthzPanel.jsx')),
};

/**
 * 登录界面也懒加载。
 *
 * 它不在 PANELS 里：那张表是"侧栏能点到的视图"，而登录界面不在导航里，它是未认证状态下
 * 整个应用的替代品。懒加载的理由和面板一样——已登录的人不该为一个他看不到的表单付下载成本。
 */
const LoginView = lazy(() => import('./LoginView.jsx'));

/*
 * 导航数据源。
 *
 * ── 为什么从平铺的 8 项改成分组（现在是 5 组 18 项）──
 * 视图数翻倍之后，平铺的侧栏是一列十几个等重的名字，从中找一个要逐行读。分组之后
 * 「我要找的东西大概在哪一组」这一步用扫的就能完成，只在组内才需要读名字。
 * 分组用 SideNavSection（它有 title 与 isHeaderHidden），而不是自己画一行标题：
 * 它会给这一组挂 role="group" + aria-labelledby，屏幕阅读器读得出组名。
 *
 * 分组的判据是「什么时候会打开它」，不是端点的字母序也不是它们被实现的顺序：
 * - 审计：出了事第一时间看的三页；
 * - 运行状态：进程自己的状态，不连业务库，随时可看；
 * - 库与资产：对着某个库问"它长什么样"，每一页都会真的读那个库；
 * - 诊断与运维：拿着一个具体问题（一条慢 SQL、一次备份、一个作业）去查的那些页。
 *
 * ── 结构：分组数组 + 从它推导的平铺数组 ──
 * VIEWS 由 VIEW_GROUPS 摊平得来，而不是两份各自维护。快捷键、hash 校验、当前视图的
 * 标题查找全部走 VIEWS，侧栏走 VIEW_GROUPS —— 只有一份顺序，加一个视图只改一处。
 * 写成两份的话，加一个视图就会让快捷键和侧栏错位一格，而且错位不报错。
 *
 * hint 一句话必须同时说清「数据从哪来」和「有什么约束」：导航项被点开之前，这句话是运维
 * 判断「我要找的东西在不在这一页」的唯一依据。16 项之后这件事比 8 项时更重要 ——
 * 「数据资产」和「数据质量」从名字上分不出哪个会扫全库。
 */
const VIEW_GROUPS = [
  {
    title: '审计',
    views: [
      { value: 'audit', label: '审计流水', hint: '进程内环形缓冲，重启即清空' },
      { value: 'history', label: '审计历史', hint: '审计表，需配 spring.datasource.url' },
      {
        value: 'reports',
        label: '审计报告',
        hint: '按时间窗出的合规报告；审计未落库时两份报告是 skipped 而不是错误',
      },
    ],
  },
  {
    title: '运行状态',
    views: [
      { value: 'connections', label: '连接与连接池', hint: '已注册连接与 HikariCP 池状态' },
      { value: 'performance', label: '性能', hint: '慢查询原文与 SQL 模式统计' },
      {
        value: 'info',
        label: '服务信息',
        hint: '版本、profile、四个生效开关；版本来自打包期的 pom，不是 build-info',
      },
      {
        value: 'tools',
        label: '工具清单',
        hint: 'MCP 工具目录 + 运行期启用/停用（本面板唯一的写操作）；没有入参 schema',
      },
    ],
  },
  {
    title: '库与资产',
    views: [
      {
        value: 'schema',
        label: 'Schema 浏览',
        hint: '表/视图/序列/索引/表结构，会真的读业务库的数据字典，默认不自动查',
      },
      {
        value: 'catalog',
        label: '数据资产',
        hint: '目录扫描与敏感列推断（按命名规则，不看数据）；扫全 Schema 是全站最慢的操作',
      },
      {
        value: 'lineage',
        label: '血缘',
        hint: '只从外键现算，没有外键的库永远是空图；空图的三种原因页面上有列',
      },
      {
        value: 'quality',
        label: '数据质量',
        hint: '只跑内置检查（空值率+重复行），评分 100 不代表业务规则通过；告警汇总恒为空',
      },
    ],
  },
  {
    title: '诊断与运维',
    views: [
      {
        value: 'sql',
        label: 'SQL 体检',
        hint: '风险/计划/索引/改写；SQL 走查询串，会进 access log 且有长度上限',
      },
      {
        value: 'dba',
        label: 'DBA 视图',
        hint: '会真的连业务库执行查询，默认不自动查，多数视图只有 Oracle 有',
      },
      {
        value: 'cdc',
        label: 'CDC',
        hint: '方言支持/状态/订阅/位点；订阅只是进程内存里的登记，重启即清空',
      },
      {
        value: 'backups',
        label: '备份',
        hint: '只读清单；备份元数据只在内存里（硬编码），重启即全部丢失',
      },
      {
        value: 'jobs',
        label: 'ETL 作业',
        hint: '只读；网关关闭时是 200+enabled=false，有未完成作业时列表端点会 500',
      },
    ],
  },
  /*
   * 访问控制单独成组，而不是把「身份管理」塞进「运行状态」跟在工具清单后面。
   *
   * 两个理由，第二个是硬的：
   * - 分组判据是「什么时候会打开它」。工具开关回答"客户端能看到哪些工具"，身份管理回答
   *   "谁能连上来"——后者是在加人/裁人的时候打开的，和排查运行状态不是同一件事；
   * - <b>插在中间会挪动已有的数字快捷键</b>。序号按 VIEWS 的全局下标算，把它放进第二组
   *   会让 Schema 浏览从 8 变成 9、数据资产从 9 掉出快捷键范围——一个已经被记住的键位
   *   悄悄指向了另一个视图，比没有快捷键糟。放在末尾则不影响前 9 项。
   * 这一组后续还会加「权限视图」（按身份看连接级/表级读写），所以它不是为了一项而建的组。
   */
  {
    title: '访问控制',
    views: [
      {
        value: 'users',
        label: '身份管理',
        hint: '列出/新增/停用调用者身份（写操作）；不含环境变量里的管理员，没配状态库时整页 503',
      },
      {
        value: 'authz',
        label: '权限视图',
        hint: '按调用者的连接级/表级读写；只读（授权改 yml 并重启），开关关闭时全放行而不是全禁止',
      },
    ],
  },
];

/** 平铺顺序 = 侧栏从上到下的顺序 = 数字快捷键的顺序。只有这一份。 */
const VIEWS = VIEW_GROUPS.flatMap((group) => group.views);

/**
 * 只有前 9 项绑数字键。
 *
 * 键盘上就 1–9 能用（0 留着不用：它在"第 10 个"这个位置上没有直觉，而且 Cmd+0 在多数
 * 浏览器里是重置缩放）。想过的两个替代方案都更差：
 * - 双位数（按 1 再按 2 到第 12 项）：需要一个组合键状态机和超时判定，为一个内网面板
 *   的导航加这个不值得；
 * - g+字母那套（g 然后 s 去 Schema）：得给 16 个视图各起一个不冲突的助记字母，
 *   而且那套约定本身要学。
 *
 * 关键是<b>不能静默失效</b>：第 10 项之后没有快捷键这件事，必须在快捷键说明条里写出来
 * （见 ShortcutHints），否则按了 1 到 9 都好、按到第 10 个视图时会以为是自己记错了键。
 */
const HOTKEY_VIEW_COUNT = 9;

const AUTO_REFRESH_INTERVAL_MS = 10_000;
const DEFAULT_VIEW = VIEWS[0].value;
const DEFAULT_LIMIT = 50;

// =============================================================================
// location.hash ↔ state
// =============================================================================

/*
 * 现场（view + 条数 + 选中行）记在 location.hash 里，
 * 形如 `#view=audit&limit=100&row=audit-logs:<行标识>`。
 *
 * ── 为什么是 hash 而不是 path，也不引路由库 ──
 * 这个页面是 Spring Boot 静态资源，没有服务端路由；path 形式的 /audit 刷新会 404
 * （得在后端加转发——而这一轮只改前端）。hash 不进请求行，刷新一定回到 index.html。
 * 路由库（react-router 之类）解决的是嵌套路由和数据加载，这里两样都没有，
 * 而且「不新增 npm 依赖」是硬约束。URLSearchParams 是标准 API，够了。
 *
 * ── 双向同步为什么不会打环 ──
 * 写入用 history.replaceState，它按规范<b>不会</b>触发 hashchange（location.hash = x 会）。
 * 于是「state 变 → 写 hash」这条边不会反过来触发「hashchange → 改 state」。
 * 反方向（手改地址栏 / 前进后退）触发 hashchange → 改 state → 写 hash，此时写入的值和
 * 当前 hash 完全一致，被 writeHashParams 里的相等判断挡掉。两条边各只走一次。
 * <b>这套机制不能改坏</b>：第 8 项（行深链）加了第三个键 row，它由 InteractiveTable
 * 写，仍然走同一个 replaceState + 相等判断，所以两个拥有者不会互相触发。
 *
 * ── 为什么是 replaceState 而不是 pushState ──
 * 条数是个 NumberInput 步进器，点五下 pushState 就是五条历史记录，后退键从此没法用。
 * 代价是浏览器后退不能回到上一个视图——但这个页面的"上一步"概念本来就很弱
 * （16 个平级视图，侧栏点一下就到），换掉一个能用的后退键不值得。
 *
 * ── 为什么读写下沉到 ./hashState.js ──
 * row 那个键由 InteractiveTable 拥有，而 view / limit 由这里拥有。原来的 writeHash 是
 * 整串重写（`#view=X&limit=Y`），那样这里每写一次就会把 row 抹掉。合并式写入只能有
 * 一份实现，所以搬到了共享模块，详见那个文件的头注释。
 */
function readHash() {
  const params = readHashParams();
  const view = params.view;
  const limit = Number.parseInt(params.limit ?? '', 10);
  return {
    // 未知的 view 直接忽略而不是报错：分享出去的链接可能来自一个还有 / 已经没有
    // 这个视图的版本，那种情况下落到默认视图比显示一个空白页有用。
    view: VIEWS.some((v) => v.value === view) ? view : null,
    /*
     * 夹到 [1, FALLBACK_MAX_LIMIT]：FALLBACK_MAX_LIMIT 是后端 WebUiController.MAX_LIMIT
     * 的镜像，超出的值后端本来也会夹。在前端先夹是为了让顶栏输入框显示的数字和
     * 实际生效的条数一致——回显一个 99999 而实际只取回 500 条，是在骗人。
     */
    limit: Number.isInteger(limit)
      ? Math.min(Math.max(limit, 1), FALLBACK_MAX_LIMIT)
      : null,
  };
}

/**
 * 无鉴权告警。
 *
 * 完全由 /api/ui/config 的 authEnabled 驱动，不写死文案：写死的文案会在部署方打开鉴权之后
 * 继续吓人，或者更糟——在关掉鉴权之后继续说"已鉴权"。
 * config 拿不到时也要出告警（内容改成"状态未知"）：静默假设"已鉴权"是最坏的失败方向。
 *
 * 位置从正文顶部挪到 AppShell 的 banner 槽——上一版它占掉近半屏，成了页面的主角。
 * 挪进 banner 后仍然常驻、仍然是 error 色、仍然不可关闭，只是不再抢戏。
 */
function AuthBanner({ config, isConfigLoaded }) {
  if (!isConfigLoaded || config?.authEnabled === true) {
    return null;
  }
  const unknown = !config;
  return (
    <Banner
      status="error"
      title={
        unknown
          ? '无法读取 /api/ui/config：本页面的鉴权状态未知'
          : '本服务未开启 HTTP 鉴权（entropy.mcp.security.enabled=false）'
      }
      description={
        unknown
          ? '在确认鉴权状态之前，请当作本页面与其 API 都是无凭证可读的。'
          : '本页面与它调用的所有 API（含审计流水中的 SQL 原文、慢查询原文、连接清单）'
            + '对任何能访问此端口的人开放。关闭这个暴露的唯一开关是 '
            + 'entropy.mcp.security.enabled=true（需配置 MCP_SECURITY_ADMIN_PASSWORD）。'
      }
      container="section"
    />
  );
}

/**
 * 顶栏状态条：鉴权、审计落库、数据新鲜度三件事常驻可见。
 *
 * 这三个都是「看错了会得出错误结论」的前提条件：不知道审计没落库，就会以为重启后
 * 少掉的流水是数据丢了；不知道鉴权关着，就不会意识到这一页在对外开放。
 * 所以它们不该埋在某个 tab 里，而要一直挂在顶上。
 *
 * 「数据更新」这一格刻意用 system_time（绝对的 HH:mm:ss）而不是表格里那种相对时间：
 * 它回答的是"我手上这份数据有多旧"，而运维是拿自己的表去对的——这里要的正是墙上时钟。
 * 表格里的时间戳换成相对时间的理由（服务器时钟快 11 分钟）在 components.jsx 的
 * timeColumn 上，两处不是同一个问题。
 */
function StatusStrip({ config, isConfigLoaded, updatedAt }) {
  const authOn = config?.authEnabled === true;
  const persisted = config?.auditPersistence === true;
  return (
    <HStack gap={5} align="center" wrap="wrap">
      <StatusDot
        variant={!isConfigLoaded ? 'neutral' : authOn ? 'success' : 'error'}
        label={!isConfigLoaded ? '鉴权：读取中' : authOn ? 'HTTP 鉴权：开' : 'HTTP 鉴权：关'}
      />
      <StatusDot
        variant={!isConfigLoaded ? 'neutral' : persisted ? 'success' : 'warning'}
        label={
          !isConfigLoaded
            ? '审计：读取中'
            : persisted ? '审计：已落库' : '审计：仅内存（重启即丢）'
        }
      />
      <HStack gap={2} align="center">
        <Text type="label" color="secondary">数据更新</Text>
        {updatedAt
          ? <Timestamp value={updatedAt.toISOString()} format="system_time" />
          : <Text type="supporting" color="secondary">尚未手动刷新</Text>}
      </HStack>
    </HStack>
  );
}

/**
 * 快捷键说明条 + 总开关。
 *
 * ── 为什么要有这个开关（不是我多加的功能，是 useHotkeys 自己的要求）──
 * core 的 useHotkeys 文档里有一段硬性要求：注册「单个无修饰键」的快捷键（'r'、'/'、'1'）
 * 触及 WCAG 2.1.4 Character Key Shortcuts，语音输入用户和运动障碍用户很容易误触发，
 * 因此<b>必须</b>提供三者之一——关掉的方式、改键的方式、或者限定在某个组件聚焦时才生效。
 * 这一页的快捷键是全局的（切视图必须在任何位置都能按），改键需要一套设置界面，
 * 所以选第一个：一个开关，默认开。
 *
 * 说明条本身也是必要的：没写出来的快捷键等于不存在，而且第一次误触发时（比如在
 * 某个非输入框区域按了 r 页面突然刷新）看得见这行字才能理解发生了什么。
 */
function ShortcutHints({ isEnabled, onToggle }) {
  return (
    <HStack gap={3} align="center" wrap="wrap">
      <Switch label="快捷键" value={isEnabled} onChange={onToggle} />
      <HStack gap={2} align="center" wrap="wrap">
        <Kbd keys="r" />
        <Text type="supporting" color="secondary">刷新</Text>
        <Kbd keys="1" />
        <Text type="supporting" color="secondary">–</Text>
        <Kbd keys="9" />
        {/*
          「前 9 项」这句话必须写出来，不能只写 1–9 就完事。
          侧栏有 16 项而键盘只有 9 个数字键，不说清的话，按到第 10 个视图时用户会以为
          自己记错了键位或者快捷键坏了 —— 一个静默失效的功能比没有这个功能更糟。
          带数字前缀的导航项只有前 9 个（见 SideNavItem 的 label），两处口径必须一致。
        */}
        <Text type="supporting" color="secondary">
          {`切视图（只有侧栏前 ${HOTKEY_VIEW_COUNT} 项有数字键，其余 ${VIEWS.length - HOTKEY_VIEW_COUNT} 项点侧栏）`}
        </Text>
        <Kbd keys="/" />
        <Text type="supporting" color="secondary">聚焦搜索</Text>
        <Kbd keys="escape" />
        <Text type="supporting" color="secondary">关详情面板</Text>
      </HStack>
    </HStack>
  );
}

/**
 * 懒加载期间的占位。
 *
 * 用 core 的 Skeleton（props 以 dist/Skeleton/Skeleton.d.ts 为准：width / height /
 * radius / index，radius 走 token 档位 'none'|0..4|'rounded'，index 只影响脉冲动画的
 * 错峰起始时间）。刻意不用 Spinner 或一句「加载中…」：
 * - 面板的内容形状是已知的（一排指标卡 + 一张表），骨架屏能把这个形状先占住，
 *   chunk 到位后不会整页跳一下；
 * - 一个居中的转圈在这个位置反而像"整页在重载"。
 *
 * 三块的尺寸对着面板的真实结构给：指标行 ≈ 96px 高、标题带 ≈ 56px、表体给 320px。
 * index 递增让三块的脉冲错开，看起来是一整片在呼吸而不是三块同时闪。
 */
function PanelSkeleton() {
  return (
    <VStack gap={6} aria-busy="true" aria-live="polite">
      <Skeleton height={96} radius={2} index={0} />
      <Skeleton height={56} radius={2} index={1} />
      <Skeleton height={320} radius={2} index={2} />
    </VStack>
  );
}

export default function App() {
  const [config, setConfig] = useState(null);
  const [isConfigLoaded, setConfigLoaded] = useState(false);
  /**
   * 未认证状态。
   *
   * 它由两处置起：自举时 /api/ui/config 回 401，以及运行期任何一个请求回 401
   * （会话超时走这条，见 api.js 的 notifyUnauthorized）。置起后整个应用被登录界面替换，
   * <b>但不卸载 hash</b>——登录完回到同一个视图、同一条选中行。
   */
  const [needsLogin, setNeedsLogin] = useState(false);
  /** 登录成功后重跑一次自举：拿到 config 才知道鉴权状态与 maxLimit。 */
  const [bootstrapToken, setBootstrapToken] = useState(0);
  /* 初值从 hash 读，而且是惰性初值（useState(fn)）：写成 useState(readHash().view) 的话
     每次渲染都会解析一遍 hash，而它只在挂载时有用。 */
  const [view, setView] = useState(() => readHash().view ?? DEFAULT_VIEW);
  const [limit, setLimit] = useState(() => readHash().limit ?? DEFAULT_LIMIT);
  const [refreshToken, setRefreshToken] = useState(0);
  const [isAutoRefresh, setAutoRefresh] = useState(false);
  const [isHotkeyEnabled, setHotkeyEnabled] = useState(true);
  const [updatedAt, setUpdatedAt] = useState(null);
  /* 惰性初值：读 localStorage 是同步 IO，只在挂载时需要一次。 */
  const [colorMode, setColorMode] = useState(readStoredColorMode);

  // 自举：先读 config，再决定告警条与「审计历史」页要不要发请求。
  useEffect(() => {
    let cancelled = false;
    setConfigLoaded(false);
    fetchConfig()
      .then((cfg) => {
        if (!cancelled) {
          setConfig(cfg);
          setNeedsLogin(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setConfig(null);
          /* 401 = 没登录或会话超时 → 切到登录视图。
             其他错误（503、网络不通）照旧走 isConfigLoaded=true + config=null → AuthBanner 说"状态未知"。 */
          if (err?.status === 401) {
            setNeedsLogin(true);
          }
        }
      })
      .finally(() => { if (!cancelled) { setConfigLoaded(true); } });
    return () => { cancelled = true; };
  }, [bootstrapToken]);

  // 运行期 401 通知（会话超时走这条）
  useEffect(() => onUnauthorized(() => {
    setNeedsLogin(true);
    setConfigLoaded(false);
    setConfig(null);
  }), []);

  // state → hash。见 readHash 上方关于"为什么不打环"的说明。
  useEffect(() => { writeHashParams({ view, limit }); }, [view, limit]);

  /*
   * 换视图时把 hash 里的 row 清掉。
   *
   * row 的值是 `<表标识>:<行标识>`（见 hashState.js），表标识只在某一个视图里存在。
   * 不清的话，从审计流水（row=audit-logs:…）切到血缘，hash 里会一直挂着一个
   * 谁都匹配不上的 row —— 不报错，但复制出去的链接带着一段无意义的垃圾。
   *
   * ⚠️ isFirstRun 这个 ref 不是防抖，是<b>深链能用的前提</b>：
   * 挂载时 view 就已经是从 hash 里读出来的了，如果这个 effect 在首次运行时也清 row，
   * 那么 `#view=audit&limit=100&row=audit-logs:xxx` 这种分享出来的链接，
   * 打开的瞬间 row 就被自己抹掉了 —— 深链永远打不开，而且看不出是谁抹的。
   */
  const isFirstViewRun = useRef(true);
  useEffect(() => {
    if (isFirstViewRun.current) {
      isFirstViewRun.current = false;
      return;
    }
    writeHashParams({ row: null });
  }, [view]);

  /*
   * 深色模式：state → DOM + localStorage。
   *
   * 写 DOM 放在 effect 里而不是渲染期：渲染期改 document.documentElement 是副作用，
   * StrictMode 下会跑两遍（这里幂等，但原则上不该这么写）。
   * 首帧会有一瞬间的"未应用"——对本页无所谓：这是内网面板，不是首屏渲染敏感的落地页，
   * 而唯一能彻底消掉这一帧的办法是在 index.html 里插一段内联脚本，
   * 那要动 index.html（硬约束里那条 data-astryx-theme 就在那个文件里，不碰为好）。
   */
  useEffect(() => {
    applyColorMode(colorMode);
    storeColorMode(colorMode);
  }, [colorMode]);

  // hash → state。手改地址栏、点站内 hash 链接、前进后退都走这条边。
  useEffect(() => {
    const onHashChange = () => {
      const next = readHash();
      if (next.view && next.view !== view) {
        setView(next.view);
      }
      if (next.limit && next.limit !== limit) {
        setLimit(next.limit);
      }
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, [view, limit]);

  const refresh = useCallback(() => {
    setRefreshToken((t) => t + 1);
    setUpdatedAt(new Date());
  }, []);

  /*
   * 自动刷新默认关闭，而不是固定轮询。
   *
   * 每刷一次连接页都会经过 DynamicDataSourceManagerImpl 的读方法，而
   * PerformanceTimingAspect.CONNECTION_REGISTRY_BOOKKEEPING 会给这些方法各记一条
   * recordToolExecution——固定轮询等于让页面自己往性能指标里灌流量。
   * 指标被 UI 自己污染，比少看几秒新鲜度糟得多。
   *
   * 同理：顶栏刻意不显示「已注册连接数」。那个数字只有 /api/ui/connections 能给，
   * 为了一个常驻角标去周期性打这个端点，正好会踩上面这个坑。
   *
   * 这是整个前端唯一的 setInterval，而且只在用户显式打开开关后才存在。
   */
  useEffect(() => {
    if (!isAutoRefresh) {
      return undefined;
    }
    const timer = setInterval(refresh, AUTO_REFRESH_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [isAutoRefresh, refresh]);

  /*
   * 全局快捷键。
   *
   * allowInInputs 全部保持默认的 false，这是「输入框聚焦时不要拦截按键」那条要求的
   * 全部实现——core 的 useHotkeys 自己会检查 input / textarea / select / contenteditable。
   * 自己再写一遍 event.target.tagName 判断反而更容易漏（漏的通常是 contenteditable）。
   *
   * Esc 不在这里注册：它要关的是某张表的详情面板，而"哪张表开着面板"这件事只有那张表
   * 自己知道。放在 InteractiveTable 里，每个实例各关自己的（见 components.jsx）。
   *
   * 数字键用 String(i+1) 从 VIEWS 生成，而不是手写九条：手写的那一版在加视图时一定会
   * 忘记加快捷键，而且忘了不报错。
   *
   * slice(0, HOTKEY_VIEW_COUNT)：只有前 9 项有键，理由见 HOTKEY_VIEW_COUNT 上方。
   * 从 16 项里生成 16 条快捷键的写法试过，第 10 条起 keys 是 '10'、'11' —— useHotkeys
   * 把它们当成"按 1 再按 0"的序列还是一个不存在的键名，取决于它的解析实现，
   * 而无论哪种都不是"按一下就切"。所以宁可只给前 9 项，并把这件事写在说明条里。
   */
  const hotkeys = useMemo(() => [
    { keys: 'r', onPress: refresh, isDisabled: !isHotkeyEnabled },
    { keys: '/', onPress: focusFirstSearch, isDisabled: !isHotkeyEnabled },
    ...VIEWS.slice(0, HOTKEY_VIEW_COUNT).map((v, i) => ({
      keys: String(i + 1),
      onPress: () => setView(v.value),
      isDisabled: !isHotkeyEnabled,
    })),
  ], [refresh, isHotkeyEnabled]);
  useHotkeys(hotkeys);

  const maxLimit = config?.maxLimit ?? FALLBACK_MAX_LIMIT;
  const panelProps = { limit, refreshToken };
  const current = VIEWS.find((v) => v.value === view) ?? VIEWS[0];
  /* PANELS 的键和 VIEWS 的 value 是同一套（readHash 已经挡掉了未知 view），
     取不到时兜到第一个视图，而不是渲染 undefined —— lazy 组件是 undefined 时
     React 抛的错读不出哪个视图坏了。 */
  const CurrentPanel = PANELS[current.value] ?? PANELS[VIEWS[0].value];

  /*
   * 未认证时整个外壳都不渲染，只给登录界面。
   *
   * 这个提前返回必须放在<b>所有 hook 之后</b>（上面那些 useState / useEffect / useHotkeys 都要照常跑），
   * 否则两次渲染之间的 hook 数量会变，React 会直接抛错。
   *
   * 刻意不渲染 AppShell 的骨架：侧栏与顶栏在未登录时全是不可用的，摆出来只会让人去点。
   */
  if (needsLogin) {
    return (
      <Suspense fallback={<PanelSkeleton />}>
        <LoginView onSuccess={() => {
          setNeedsLogin(false);
          // 重跑自举：config 决定告警条、maxLimit 与「审计历史」页要不要发请求
          setBootstrapToken((t) => t + 1);
        }}
        />
      </Suspense>
    );
  }

  return (
    <AppShell
      contentPadding={0}
      height="fill"
      banner={<AuthBanner config={config} isConfigLoaded={isConfigLoaded} />}
      topNav={
        <TopNav
          heading={<Text weight="semibold">Database MCP Server · 只读运维面板</Text>}
          endContent={
            <HStack gap={3} align="center" wrap="wrap">
              <NumberInput
                label="条数"
                isLabelHidden
                value={limit}
                min={1}
                max={maxLimit}
                step={10}
                isIntegerOnly
                hasNumberSteppers
                width={132}
                onChange={(value) => setLimit(value ?? 1)}
              />
              <Switch
                label={`自动刷新 ${AUTO_REFRESH_INTERVAL_MS / 1000}s`}
                value={isAutoRefresh}
                onChange={setAutoRefresh}
              />
              {/*
                主题三档。用 SegmentedControl 而不是一个「深色」Switch：
                「跟随系统」是默认值也是第三种状态，Switch 只有两态，
                硬塞会变成"开=暗、关=亮"，而那样就没有跟随系统了。
                label 是 aria-label（不渲染），所以旁边另给一句可见的 Text。
                刻意不传 size：那是控件高度不是字号，口径同 ExportCsvButton。
              */}
              <HStack gap={2} align="center">
                <Text type="label" color="secondary">主题</Text>
                <SegmentedControl
                  value={colorMode}
                  onChange={setColorMode}
                  label="配色模式"
                >
                  {COLOR_MODES.map((m) => (
                    <SegmentedControlItem key={m.value} value={m.value} label={m.label} />
                  ))}
                </SegmentedControl>
              </HStack>
              {/* 诊断包按钮是手点的，理由（会被 CONNECTION_REGISTRY_BOOKKEEPING 记一条）
                  写在 diagnostics.jsx 的头注释里。 */}
              <DiagnosticBundleButton limit={limit} />
              <Button label="刷新" variant="primary" onClick={refresh} />
              {/*
                退出登录。只在鉴权开着时才有意义——关着的时候没有会话可退，
                摆一个点了什么都不发生的按钮比没有按钮更糟。
                它是 POST /logout（GET 退出意味着任何一张图片的 src 都能把人踢下线），
                CSRF token 由 api.js 带上；退完跳登录页。
              */}
              {config?.authEnabled === true && (
                <Button label="退出" variant="secondary" onClick={() => { logout(); }} />
              )}
            </HStack>
          }
        />
      }
      sideNav={
        <SideNav
          collapsible
          resizable={{ defaultWidth: 240, minWidth: 200, maxWidth: 340 }}
          header={<SideNavHeading heading="运维视图" />}
        >
          {/*
            分组渲染。上一版是一个 isHeaderHidden 的单 Section 包住八项；16 项之后组名
            必须<b>显示</b>出来（isHeaderHidden 去掉了），否则分组只在代码里存在。

            数字前缀只给前 9 项：给不带快捷键的项也编号会造出「第 12 项按 12」的错觉。
            序号从 VIEWS 里的全局下标算，而不是组内下标 —— 组内下标会让四组各自从 1 开始，
            和快捷键完全对不上。
          */}
          {VIEW_GROUPS.map((group) => (
            <SideNavSection key={group.title} title={group.title}>
              {group.views.map((v) => {
                const index = VIEWS.findIndex((x) => x.value === v.value);
                const hasHotkey = index < HOTKEY_VIEW_COUNT;
                return (
                  <SideNavItem
                    key={v.value}
                    label={hasHotkey ? `${index + 1} · ${v.label}` : v.label}
                    href={`#view=${v.value}&limit=${limit}`}
                    isSelected={v.value === view}
                    /*
                      href 写成真的 hash 而不是 "#"：这样中键 / Cmd+点击能在新标签里打开
                      同一个视图，右键也能复制链接——分享现场的实际用法。
                      onClick 仍然 preventDefault 走内部 state，避免多走一次 hashchange。
                    */
                    onClick={(e) => { e.preventDefault(); setView(v.value); }}
                  />
                );
              })}
            </SideNavSection>
          ))}
        </SideNav>
      }
    >
      <Layout
        height="fill"
        header={
          <LayoutHeader padding={5} hasDivider>
            <VStack gap={3}>
              <StatusStrip
                config={config}
                isConfigLoaded={isConfigLoaded}
                updatedAt={updatedAt}
              />
              <VStack gap={1}>
                <Heading level={2}>{current.label}</Heading>
                <Text type="supporting" color="secondary">{current.hint}</Text>
              </VStack>
              <ShortcutHints isEnabled={isHotkeyEnabled} onToggle={setHotkeyEnabled} />
            </VStack>
          </LayoutHeader>
        }
        content={
          /*
            面板等 config 到位再渲染，和上一版一样先自举再刷新。
            原因不是性能，是别说错话：config 还没回来时 auditPersistence 是 undefined，
            「审计历史」页会先闪一条"状态未知"，两百毫秒后又变成正常表格。
            一次请求的等待换掉一次误导性的闪烁，划得来。
          */
          <LayoutContent padding={5} isScrollable>
            {isConfigLoaded && (
              <div role="region" aria-label={current.label}>
                {/*
                  Suspense 的 key 绑当前视图：切视图时让 Suspense 边界重置，
                  于是新视图的 chunk 在下载期间显示骨架屏，而不是把上一个视图的
                  内容留在屏幕上（React 18+ 对已挂载子树的 transition 默认保留旧 UI，
                  在这里会造成「点了侧栏但内容没变」的错觉）。
                */}
                <Suspense key={view} fallback={<PanelSkeleton />}>
                  <CurrentPanel
                    {...panelProps}
                    {...(view === 'history'
                      ? { auditPersistence: config?.auditPersistence }
                      : null)}
                  />
                </Suspense>
              </div>
            )}
          </LayoutContent>
        }
      />
    </AppShell>
  );
}
