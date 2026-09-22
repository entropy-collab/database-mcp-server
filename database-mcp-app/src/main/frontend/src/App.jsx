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
  Avatar,
  Badge,
  Banner,
  Button,
  HStack,
  Heading,
  Kbd,
  Layout,
  LayoutContent,
  LayoutHeader,
  NumberInput,
  Popover,
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
  TextInput,
  Timestamp,
  TopNav,
  VStack,
  useHotkeys,
  useSideNavCollapse,
} from '@astryxdesign/core';
import { FALLBACK_MAX_LIMIT, fetchConfig, fetchMe, logout, onUnauthorized } from './api.js';
/* 刻意从 ./searchFocus.js 而不是 ./components.jsx 取：后者会把 370 KB 的显示件
   连带一大片 core 拉进入口的静态依赖图（Vite 会给它加 modulepreload），
   视图级懒加载就白做了。理由写在 searchFocus.js 的头注释里。 */
import { focusFirstSearch } from './searchFocus.js';
import { readHashParams, writeHashParams } from './hashState.js';
import { DiagnosticBundleButton } from './diagnostics.jsx';
/* 侧栏图标。它们是折叠态能用的<b>前提</b>而不是装饰——SideNavItem 对没有 icon 的项
   在折叠态直接 return null，理由与手写取舍写在 navIcons.jsx 的头注释里。 */
import {
  IconAuditLog,
  IconAuthz,
  IconBackup,
  IconCatalog,
  IconCdc,
  IconConnections,
  IconDatabase,
  IconDba,
  IconHistory,
  IconInfo,
  IconJobs,
  IconLineage,
  IconPerformance,
  IconQuality,
  IconReport,
  IconSchema,
  IconSqlDoctor,
  IconTools,
  IconUsers,
} from './navIcons.jsx';
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
 *
 * ── icon 是必填的，不是可选的装饰 ──
 * SideNavItem 在折叠态有一句 `if (isCollapsed && !icon) return null;`，所以漏掉 icon 的项
 * 在折叠侧栏里<b>整个消失</b>（不报错、不留空位）。加视图时必须一起加图标，
 * 图标在 navIcons.jsx，那个文件的头注释里写了为什么是手写的。
 */
const VIEW_GROUPS = [
  {
    title: '审计',
    views: [
      {
        value: 'audit',
        label: '审计流水',
        icon: IconAuditLog,
        hint: '进程内环形缓冲，重启即清空',
      },
      {
        value: 'history',
        label: '审计历史',
        icon: IconHistory,
        hint: '审计表，需配 spring.datasource.url',
      },
      {
        value: 'reports',
        label: '审计报告',
        icon: IconReport,
        hint: '按时间窗出的合规报告；审计未落库时两份报告是 skipped 而不是错误',
      },
    ],
  },
  {
    title: '运行状态',
    views: [
      {
        value: 'connections',
        label: '连接与连接池',
        icon: IconConnections,
        hint: '已注册连接与 HikariCP 池状态',
      },
      {
        value: 'performance',
        label: '性能',
        icon: IconPerformance,
        hint: '慢查询原文与 SQL 模式统计',
      },
      {
        value: 'info',
        label: '服务信息',
        icon: IconInfo,
        hint: '版本、profile、四个生效开关；版本来自打包期的 pom，不是 build-info',
      },
      {
        value: 'tools',
        label: '工具清单',
        icon: IconTools,
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
        icon: IconSchema,
        hint: '表/视图/序列/索引/表结构，会真的读业务库的数据字典，默认不自动查',
      },
      {
        value: 'catalog',
        label: '数据资产',
        icon: IconCatalog,
        hint: '目录扫描与敏感列推断（按命名规则，不看数据）；扫全 Schema 是全站最慢的操作',
      },
      {
        value: 'lineage',
        label: '血缘',
        icon: IconLineage,
        hint: '只从外键现算，没有外键的库永远是空图；空图的三种原因页面上有列',
      },
      {
        value: 'quality',
        label: '数据质量',
        icon: IconQuality,
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
        icon: IconSqlDoctor,
        hint: '风险/计划/索引/改写；SQL 走查询串，会进 access log 且有长度上限',
      },
      {
        value: 'dba',
        label: 'DBA 视图',
        icon: IconDba,
        hint: '会真的连业务库执行查询，默认不自动查，多数视图只有 Oracle 有',
      },
      {
        value: 'cdc',
        label: 'CDC',
        icon: IconCdc,
        hint: '方言支持/状态/订阅/位点；订阅只是进程内存里的登记，重启即清空',
      },
      {
        value: 'backups',
        label: '备份',
        icon: IconBackup,
        hint: '只读清单；备份元数据只在内存里（硬编码），重启即全部丢失',
      },
      {
        value: 'jobs',
        label: 'ETL 作业',
        icon: IconJobs,
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
        icon: IconUsers,
        hint: '列出/新增/停用调用者身份（写操作）；不含环境变量里的管理员，没配状态库时整页 503',
      },
      {
        value: 'authz',
        label: '权限视图',
        icon: IconAuthz,
        hint: '按调用者的连接级/表级读写；只读（授权改 yml 并重启），开关关闭时全放行而不是全禁止',
      },
    ],
  },
];

/** 平铺顺序 = 侧栏从上到下的顺序 = 数字快捷键的顺序。只有这一份。 */
const VIEWS = VIEW_GROUPS.flatMap((group) => group.views);

/**
 * 侧栏过滤：按关键字筛出仍要显示的分组与视图。
 *
 * ── 为什么加过滤而不是加 CommandPalette ──
 * 文件头注释里否决 CommandPalette 的判据是「组数多到扫不完」，五组还差得远 —— 那条判断仍然成立，
 * 这里加的<b>不是</b>它的替代品。差别在于：CommandPalette 要求先想起视图叫什么再打字（盲打），
 * 而就地过滤是「一边缩一边看」——18 项里找一个不确定叫什么的页面，后者才有用。
 * VS Code 的侧栏筛选、Grafana 的导航搜索都是这个形态，而不是一个浮层。
 *
 * ── 同时匹配 label 和 hint ──
 * 只匹配 label 的话，输入"外键"找不到「血缘」（外键这个词在 hint 里）。而 hint 恰好是这一页
 * 唯一说清"数据从哪来、有什么约束"的地方，让它可搜索等于让「我要找的东西在哪一页」这个问题
 * 能用自己的词提问，而不必先知道我们给那一页起了什么名字。
 *
 * ── 过滤<b>只影响显示</b>，不动快捷键 ──
 * 数字键仍然绑在 VIEWS 的全局下标上（见 HOTKEY_VIEW_COUNT），被过滤掉的项照样能按。
 * 反过来做（过滤后重排快捷键）会让同一个键在不同过滤状态下指向不同视图——比没有快捷键糟。
 */
function filterGroups(keyword) {
  const needle = keyword.trim().toLowerCase();
  if (needle === '') {
    return VIEW_GROUPS;
  }
  return VIEW_GROUPS
    .map((group) => ({
      ...group,
      views: group.views.filter((v) => `${v.label} ${v.hint}`.toLowerCase().includes(needle)),
    }))
    /* 空组整组不渲染，而不是渲染一个只有组名的空壳：SideNavSection 的组名在没有子项时
       会变成一行没有任何用处的标题，而"这一组里没有匹配"这件事用"组名消失了"表达就够了。 */
    .filter((group) => group.views.length > 0);
}

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
 *
 * ── 这一版从"标题带上方独占一行"改成"与标题同一行、右对齐" ──
 * 它报的是<b>全局</b>状态（整个服务的鉴权与审计），不随视图变化，而独占一行会让它在
 * 每次切视图时都重新抢一次注意力，并且把真正变化的标题往下压。放到标题右侧之后：
 * 标题拿回左上角的主导位置（Grafana / Stripe 的 page header 就是这个结构），
 * 而状态仍然常驻可见——只是不再是"第一眼"。gap 给 5 保证两组不会读成一块。
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
 * 显示设置：条数 / 自动刷新 / 主题，收进一个浮层。
 *
 * ── 为什么从顶栏平铺改成浮层 ──
 * 上一版顶栏 endContent 里平铺了六组控件：条数 NumberInput（132px）、自动刷新 Switch、
 * 「主题」Text + 三档 SegmentedControl、诊断包、刷新、头像。加起来 900px 以上，
 * 在 1280 宽的屏上就开始 wrap 成两行，而 wrap 之后顶栏高度翻倍、主标题被挤扁。
 *
 * 判据是<b>操作频率</b>，不是"能不能放下"：刷新与身份是每次都要的（留在外面），
 * 而条数、自动刷新、主题都是"调一次用很久"的设置。Vercel / Linear / Stripe 的顶栏
 * 一律只留 1-2 个高频动作加身份，设置进齿轮——这里照这个分法。
 *
 * ── 三项都刻意保留"当前值可见" ──
 * 收进浮层的代价是当前值看不见了，所以触发器的 label 带上条数（`50 条`）：
 * 条数直接决定每张表取回多少行，它变了而人不知道，会把"数据只有这么多"读成事实。
 * 自动刷新开着时另挂一个 Badge——一个正在周期性打接口的开关，藏起来是危险的。
 */
function DisplaySettingsMenu({
  limit, maxLimit, onLimitChange,
  isAutoRefresh, onAutoRefreshChange,
  colorMode, onColorModeChange,
}) {
  return (
    <Popover
      label="显示设置"
      placement="below"
      alignment="end"
      width={300}
      content={
        <VStack gap={5} padding={4}>
          <VStack gap={2}>
            <Text type="label" weight="semibold">每张表取回条数</Text>
            <NumberInput
              label="条数"
              isLabelHidden
              value={limit}
              min={1}
              max={maxLimit}
              step={10}
              isIntegerOnly
              hasNumberSteppers
              onChange={(value) => onLimitChange(value ?? 1)}
            />
            <Text type="supporting" color="secondary">
              {`上限 ${maxLimit}，由后端 /api/ui/config 的 maxLimit 决定。`}
            </Text>
          </VStack>

          <VStack gap={2}>
            <Switch
              label={`自动刷新（每 ${AUTO_REFRESH_INTERVAL_MS / 1000} 秒）`}
              value={isAutoRefresh}
              onChange={onAutoRefreshChange}
            />
            {/* 这句必须写出来：打开它会让页面自己往性能指标里灌流量，理由见 refresh 那个 effect */}
            <Text type="supporting" color="secondary">
              默认关闭。打开后每次轮询都会被记进「性能」页的工具调用计数，
              也就是说指标里会混进本页面自己的流量。
            </Text>
          </VStack>

          <VStack gap={2}>
            {/*
              主题三档。用 SegmentedControl 而不是一个「深色」Switch：
              「跟随系统」是默认值也是第三种状态，Switch 只有两态，
              硬塞会变成"开=暗、关=亮"，而那样就没有跟随系统了。
              label 是 aria-label（不渲染），所以上方另给一句可见的 Text。
              刻意不传 size：那是控件高度不是字号，口径同 ExportCsvButton。
            */}
            <Text type="label" weight="semibold">主题</Text>
            <SegmentedControl
              value={colorMode}
              onChange={onColorModeChange}
              label="配色模式"
            >
              {COLOR_MODES.map((m) => (
                <SegmentedControlItem key={m.value} value={m.value} label={m.label} />
              ))}
            </SegmentedControl>
          </VStack>
        </VStack>
      }
    >
      <Button
        /* label 带上生效条数：这个值决定每张表取回多少行，藏起来会让人把
           "只有这么多数据"当成事实。自动刷新开着时另挂一个 Badge。 */
        label={`${limit} 条`}
        variant="secondary"
        endContent={isAutoRefresh
          ? <Badge variant="info" label="自动刷新" />
          : undefined}
      />
    </Popover>
  );
}

/**
 * 快捷键说明 + 总开关，收进一个浮层。
 *
 * ── 为什么要有这个开关（不是我多加的功能，是 useHotkeys 自己的要求）──
 * core 的 useHotkeys 文档里有一段硬性要求：注册「单个无修饰键」的快捷键（'r'、'/'、'1'）
 * 触及 WCAG 2.1.4 Character Key Shortcuts，语音输入用户和运动障碍用户很容易误触发，
 * 因此<b>必须</b>提供三者之一——关掉的方式、改键的方式、或者限定在某个组件聚焦时才生效。
 * 这一页的快捷键是全局的（切视图必须在任何位置都能按），改键需要一套设置界面，
 * 所以选第一个：一个开关，默认开。
 *
 * ── 为什么从常驻一行改成浮层，以及为什么这不违反上面那条要求 ──
 * 上一版是标题带底部常驻一整行（一个 Switch + 六个 Kbd + 四段说明文字）。那一行是
 * <b>文档</b>：内容永不变化，却在每个视图上都占一行，而它旁边就是真正会变的标题与 hint。
 * GitHub / Linear / Notion 都是把快捷键表收进一个 `?` 浮层，不常驻。
 *
 * 关键是<b>开关仍然一次点击可达</b>（浮层第一项），而不是埋进某个设置页的第三级——
 * WCAG 那条要求的是"提供关闭的方式"，浮层满足；它没有要求这个开关常驻在屏幕上。
 *
 * 触发器刻意做成一个带 `?` 的 Kbd 视觉而不是纯文字按钮：上一版注释里那条顾虑是真的
 * ——第一次误触发（在非输入框区域按了 r，页面突然刷新）时，人需要一个"这里有快捷键"的
 * 线索才能理解刚发生了什么。一个键帽形状的按钮就是这个线索，而一整行说明不是必需的。
 */
function KeyboardShortcutsMenu({ isEnabled, onToggle }) {
  return (
    <Popover
      label="键盘快捷键"
      placement="below"
      alignment="end"
      width={340}
      content={
        <VStack gap={4} padding={4}>
          {/* 开关放第一项：WCAG 2.1.4 要求的"关闭方式"必须容易到达，不能排在说明后面 */}
          <Switch label="启用键盘快捷键" value={isEnabled} onChange={onToggle} />
          <Text type="supporting" color="secondary">
            这些都是单个无修饰键，语音输入或误触时容易触发，所以给了这个开关（WCAG 2.1.4）。
          </Text>

          <VStack gap={3}>
            <HStack gap={3} align="center" wrap="wrap">
              <Kbd keys="r" />
              <Text type="supporting">刷新当前视图</Text>
            </HStack>
            <HStack gap={3} align="center" wrap="wrap">
              <Kbd keys="1" />
              <Text type="supporting" color="secondary">–</Text>
              <Kbd keys="9" />
              <Text type="supporting">切视图</Text>
            </HStack>
            {/*
              「前 9 项」这句话必须写出来，不能只写 1–9 就完事。
              侧栏有 18 项而键盘只有 9 个数字键，不说清的话，按到第 10 个视图时用户会以为
              自己记错了键位或者快捷键坏了 —— 一个静默失效的功能比没有这个功能更糟。
              带数字键帽的导航项只有前 9 个（见 SideNavItem 的 endContent），两处口径必须一致。
            */}
            <Text type="supporting" color="secondary">
              {`只有侧栏前 ${HOTKEY_VIEW_COUNT} 项有数字键，其余 ${VIEWS.length - HOTKEY_VIEW_COUNT} 项点侧栏。`}
              {'侧栏每一项右侧的键帽就是它的键位。'}
            </Text>
            <HStack gap={3} align="center" wrap="wrap">
              <Kbd keys="/" />
              <Text type="supporting">聚焦当前页第一个搜索框</Text>
            </HStack>
            <HStack gap={3} align="center" wrap="wrap">
              <Kbd keys="escape" />
              <Text type="supporting">关闭表格的行详情面板</Text>
            </HStack>
          </VStack>

          <Text type="supporting" color="secondary">
            输入框聚焦时一律不拦截按键（含 contenteditable）。
          </Text>
        </VStack>
      }
    >
      {/*
        触发器用 isIconOnly + 一个键帽形状的图标：它同时是"这里有快捷键"的视觉线索
        （误触发之后有个地方可点）和一个不占宽度的入口。label 变成 aria-label，
        并把当前是开还是关一起说出来——一个看不出状态的开关入口比没有入口糟。
      */}
      <Button
        label={isEnabled ? '键盘快捷键（已启用）' : '键盘快捷键（已关闭）'}
        variant="ghost"
        isIconOnly
        icon={<Kbd keys="?" />}
      />
    </Popover>
  );
}


/**
 * 顶栏右侧的个人信息菜单。
 *
 * 用 Popover 而不是 DropdownMenu：菜单里要显示一段复合信息（用户名 + 类型 + 权限），
 * DropdownMenu 的 DropdownMenuItem 是行级动作，放不下这种结构化卡片。
 * Popover 触发器必须是 button 或 role="button"：Avatar 传了 onClick 后会渲染成
 * <button type="button">，所以自动满足。
 *
 * 鉴权关闭时不渲染：没有"当前登录者"可展示，摆一个灰色头像只会让人去点，
 * 而点开之后能看到的信息只有"鉴权关闭"——这在顶部横幅里已经用 error 级别写过了。
 */
function UserProfileMenu({ userInfo, onLogout }) {
  if (!userInfo?.authenticated) {
    return null;
  }

  const username = userInfo.username ?? '—';
  const typeLabel = userInfo.type
    ? ({ user: '用户', agent: 'AI Agent', service: '服务账号' })[userInfo.type] ?? userInfo.type
    : null;
  const authorities = userInfo.authorities ?? [];

  return (
    <Popover
      label="个人信息"
      placement="below"
      alignment="end"
      width={300}
      content={
        <VStack gap={4} padding={4}>
          <VStack gap={1}>
            <HStack gap={3} align="center">
              <Avatar name={username} size="lg" tooltip={false} />
              <VStack gap={0}>
                <Text type="body" weight="semibold">{username}</Text>
                {typeLabel && (
                  <Text type="supporting" color="secondary">{typeLabel}</Text>
                )}
              </VStack>
            </HStack>
          </VStack>
          {userInfo.subjectRef && (
            <VStack gap={1}>
              <Text type="label" color="secondary">主体标识</Text>
              <Text type="supporting">{userInfo.subjectRef}</Text>
            </VStack>
          )}
          {authorities.length > 0 && (
            <VStack gap={1}>
              <Text type="label" color="secondary">权限</Text>
              <Text type="supporting">{authorities.join(', ')}</Text>
            </VStack>
          )}
          <Button label="退出登录" variant="secondary" onClick={onLogout} />
        </VStack>
      }
    >
      <Button
        label={username}
        variant="ghost"
        icon={<Avatar name={username} size="xsm" tooltip={false} />}
      />
    </Popover>
  );
}

/**
 * 侧栏的过滤框，折叠态自动让位。
 *
 * ── 为什么必须包成一个组件，而不是把 TextInput 直接塞进 topContent ──
 * 折叠后的侧栏是一条 48px 宽、`overflow: hidden` 的 rail。而 SideNav <b>不会</b>隐藏或改造
 * topContent（它只给那一格加了 `alignItems: center`，见 SideNav.tsx 的 stickyTop 分支），
 * 所以一个输入框直接放那儿会被裁成 30 来像素的残片——既打不出字，又占掉 rail 顶部一格，
 * 看起来像渲染坏了。这是上一版加过滤框时漏掉的。
 *
 * 包成组件之后它在 SideNavCollapseContext 的 <b>provider 内部</b>渲染（provider 包住整个
 * nav，含 stickyTop），因此可以直接读折叠态。写成一个函数调用或裸 JSX 都读不到。
 *
 * 折叠时返回 null 而不是换成一个搜索图标按钮：rail 上的每个图标在那个语境里都代表"一个视图"，
 * 混进一个"展开侧栏并聚焦过滤框"的按钮会让图标列表的含义不再统一。想过滤就先展开，
 * 而展开按钮就在 rail 底部。
 */
function NavFilter({ value, onChange }) {
  const { isCollapsed } = useSideNavCollapse();
  if (isCollapsed) {
    return null;
  }
  return (
    <TextInput
      label="过滤视图"
      isLabelHidden
      placeholder="过滤视图（名称或说明）"
      value={value}
      onChange={onChange}
      hasClear
      size="sm"
    />
  );
}

/**
 * 过滤没有命中时的说明，折叠态同样让位。
 *
 * 折叠态下过滤框已经不可见，但关键字仍然在 state 里（可能是折叠<b>之前</b>输入的），
 * 于是 rail 里会出现一段被裁掉一半的中文和一个点不到的按钮。理由同 NavFilter。
 */
function NavFilterEmptyState({ keyword, onClear }) {
  const { isCollapsed } = useSideNavCollapse();
  if (isCollapsed) {
    return null;
  }
  return (
    <VStack gap={2} padding={4}>
      <Text type="supporting" color="secondary">
        {`没有名称或说明匹配「${keyword}」的视图。`}
      </Text>
      <Button label="清空过滤" variant="ghost" onClick={onClear} />
    </VStack>
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
  /**
   * 当前登录者信息。
   *
   * 由 /api/ui/me 在自举成功后拉取，用于顶栏右侧的头像与个人信息菜单。
   * 与 config 分开请求：config 是匿名可读的自举探针（401 是"要登录"的信号），
   * 而 /me 的 401 会被 notifyUnauthorized 切到登录页——合在一起之后两者的失败
   * 语义会混在一起。
   *
   * 鉴权关闭时 authenticated=false，头像不渲染——那时没有"当前登录者"可展示。
   */
  const [userInfo, setUserInfo] = useState(null);
  /**
   * 侧栏过滤关键字。
   *
   * <b>刻意不进 hash</b>：hash 里的三个键（view / limit / row）描述的是"看的是什么"，
   * 复制出去给同事是要复现那个现场。过滤关键字是"我正在找东西"这个中间动作，
   * 把它写进 URL 会让分享出去的链接带着一个别人不需要的收窄状态。
   * 同理不进 localStorage：下次打开面板时侧栏少了一半视图、而原因在一个已经忘了的输入框里，
   * 这是个很难自己诊断的状态。
   */
  const [navKeyword, setNavKeyword] = useState('');

  // 自举：先读 config，再决定告警条与「审计历史」页要不要发请求。
  useEffect(() => {
    let cancelled = false;
    setConfigLoaded(false);
    fetchConfig()
      .then((cfg) => {
        if (!cancelled) {
          setConfig(cfg);
          setNeedsLogin(false);
          // config 拿到之后再拉当前登录者——两者分开请求的理由见 userInfo 的注释
          fetchMe()
            .then((me) => { if (!cancelled) { setUserInfo(me); } })
            .catch(() => { if (!cancelled) { setUserInfo(null); } });
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
    setUserInfo(null);
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
  /* 过滤后的分组列表。useMemo 避免每次渲染都跑一遍 filter + map —— VIEW_GROUPS 不变时，
     只有 navKeyword 变化才需要重算。 */
  const visibleGroups = useMemo(() => filterGroups(navKeyword), [navKeyword]);
  const visibleViewCount = useMemo(
    () => visibleGroups.reduce((n, g) => n + g.views.length, 0),
    [visibleGroups],
  );
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
          /*
            endContent 只留<b>高频动作 + 身份</b>，四个控件。
            上一版平铺了六组（条数 132px、自动刷新 Switch、「主题」Text + 三档
            SegmentedControl、诊断包、刷新、头像），加起来 900px 以上，1280 宽的屏上就
            wrap 成两行、顶栏高度翻倍。条数 / 自动刷新 / 主题三项已收进 DisplaySettingsMenu，
            判据是操作频率（详见那个组件的注释）。
          */
          endContent={
            <HStack gap={2} align="center" wrap="wrap">
              <KeyboardShortcutsMenu
                isEnabled={isHotkeyEnabled}
                onToggle={setHotkeyEnabled}
              />
              <DisplaySettingsMenu
                limit={limit}
                maxLimit={maxLimit}
                onLimitChange={setLimit}
                isAutoRefresh={isAutoRefresh}
                onAutoRefreshChange={setAutoRefresh}
                colorMode={colorMode}
                onColorModeChange={setColorMode}
              />
              {/* 诊断包按钮是手点的，理由（会被 CONNECTION_REGISTRY_BOOKKEEPING 记一条）
                  写在 diagnostics.jsx 的头注释里。 */}
              <DiagnosticBundleButton limit={limit} />
              <Button label="刷新" variant="primary" onClick={refresh} />
              {/*
                个人信息菜单。替代了原来的「退出」按钮。
                点击右上角头像弹出个人信息框（用户名、类型、权限）与退出按钮。
                只在鉴权开启且已获取到用户信息时渲染——鉴权关闭时不存在"当前登录者"。
                退出仍然是 POST /logout（理由同原来的退出按钮）。
              */}
              <UserProfileMenu
                userInfo={userInfo}
                onLogout={() => { logout(); }}
              />
            </HStack>
          }
        />
      }
      sideNav={
        <SideNav
          collapsible
          resizable={{ defaultWidth: 240, minWidth: 200, maxWidth: 340 }}
          header={
            <SideNavHeading
              heading="运维视图"
              /* icon 是折叠态的必需品：SideNavHeading 和 SideNavItem 一样，
                 没有 icon 在折叠态直接 return null，rail 顶部会空掉一格。 */
              icon={<IconDatabase />}
              /* 副标题报「当前显示 / 全部」而不是只报总数：过滤生效时这两个数不等，
                 而"少了几项"这件事必须能一眼看出来，否则会被当成视图丢了。
                 折叠态下 SideNavHeading 自己会把它藏掉，不必在这里判断。 */
              subheading={
                navKeyword.trim() === ''
                  ? `${VIEWS.length} 个视图`
                  : `${visibleViewCount} / ${VIEWS.length} 个视图`
              }
            />
          }
          /*
            过滤框钉在标题下方（topContent 是 sticky 的，滚动导航时它不动）。
            这是 VS Code / Grafana 侧栏的形态：就地收窄，而不是浮层。
            理由与"为什么不加 CommandPalette"的区别写在 filterGroups 上方。
            折叠态由 NavFilter 自己让位——SideNav 不会替我们隐藏 topContent。
          */
          topContent={<NavFilter value={navKeyword} onChange={setNavKeyword} />}
        >
          {/*
            分组渲染。上一版是一个 isHeaderHidden 的单 Section 包住八项；16 项之后组名
            必须<b>显示</b>出来（isHeaderHidden 去掉了），否则分组只在代码里存在。

            ── 这一版把三件事从 label 里搬了出去 ──
            1. 数字快捷键：原来拼进 label（`1 · 审计流水`），现在走 endContent 的 Kbd。
               拼进 label 的问题是那个数字会被读成名字的一部分（屏幕阅读器会念"一点审计流水"），
               而且左对齐的序号把真正的名字往右推了两格，扫一列名字时要跳过噪声。
               放到右侧之后它自成一列，这也是 Linear / Slack / VS Code 的做法；
            2. 分组条数：SideNavSection 的 endContent 挂一个 Badge。过滤时它就是"这一组匹配几项"；
            3. hint：原来只在点开之后出现在正文标题带里——那太晚了，它要回答的正是
               "我该不该点这一项"。现在用原生 title 属性挂上去（SideNavItem 把未识别的 props
               透传到根元素），悬停即见。<b>刻意不用 core 的 Tooltip 包一层</b>：那会在
               SideNavSection 与 SideNavItem 之间插进一个 DOM 节点，而这两者之间的
               role="group" / 列表语义是靠直接父子关系成立的。

            序号仍然从 VIEWS 的全局下标算，而不是组内下标或过滤后的下标 —— 后两者都会让
            同一个键在不同状态下指向不同视图。
          */}
          {visibleGroups.map((group) => (
            <SideNavSection
              key={group.title}
              title={group.title}
              endContent={<Badge variant="neutral" label={String(group.views.length)} />}
            >
              {group.views.map((v) => {
                const index = VIEWS.findIndex((x) => x.value === v.value);
                const hasHotkey = index < HOTKEY_VIEW_COUNT;
                return (
                  <SideNavItem
                    key={v.value}
                    label={v.label}
                    /*
                      icon 在折叠态是<b>必需</b>的，不是装饰：SideNavItem 有一句
                      `if (isCollapsed && !icon) return null;`，漏掉 icon 的项在 rail 里
                      整个消失。折叠态只渲染这个图标，label 由 core 自动挂的 Tooltip 提供
                      （hover 200ms），所以 rail 上仍然认得出每一项是什么。
                      传组件本身而不是字符串：字符串会走注册表查找，查不到就静默渲染成空方块
                      （而且能通过上面那句 !icon 判断）。理由详见 navIcons.jsx 的头注释。
                    */
                    icon={v.icon}
                    title={v.hint}
                    /* 快捷键说明条说的是"只有侧栏前 9 项有数字键"，这里只给那 9 项挂键帽，
                       两处口径必须一致：给第 10 项挂一个键帽会造出"按 10"的错觉。
                       折叠态下 endContent 不渲染（core 的折叠分支里没有它），这是对的：
                       48px 的 rail 放不下图标加键帽。 */
                    endContent={hasHotkey ? <Kbd keys={String(index + 1)} /> : undefined}
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
          {/*
            过滤没有命中时给一句话，而不是留一片空白。
            空白侧栏在过滤框下面看起来像"导航坏了"；写出来它才是一个可理解的状态。
            折叠态由 NavFilterEmptyState 自己让位（48px 的 rail 放不下一段中文）。
          */}
          {visibleGroups.length === 0 && (
            <NavFilterEmptyState
              keyword={navKeyword.trim()}
              onClear={() => setNavKeyword('')}
            />
          )}
        </SideNav>
      }
    >
      <Layout
        height="fill"
        header={
          /*
            标题带从三行压到一行半。

            上一版是 VStack 三层：StatusStrip 独占一行 → Heading + hint → ShortcutHints
            独占一行，一共约 150px，而且在<b>每个</b>视图上都长这样。三块里只有中间那块
            随视图变化，另两块一个是全局状态、一个是永不变的快捷键文档。

            这一版按"谁会变"重排（Grafana / Stripe 的 page header 结构）：
            - 第一行：标题（左，视觉主导）+ 全局状态（右，常驻但不抢戏）
            - 第二行：这一页的 hint
            快捷键那一行整块搬进了顶栏的 `?` 浮层（见 KeyboardShortcutsMenu）。

            gap 从 3 收到 2：标题与它自己的 hint 是同一组信息，3 会让它们读成两块。
            hAlign="between" 把两组推到两端（HStack 的 hAlign 是主轴，接受 between）；
            vAlign="start" 而不是 center：标题可能折行，居中会让右侧状态跟着上下跳。
          */
          <LayoutHeader padding={5} hasDivider>
            <VStack gap={2}>
              <HStack gap={5} hAlign="between" vAlign="start" wrap="wrap">
                <Heading level={2}>{current.label}</Heading>
                <StatusStrip
                  config={config}
                  isConfigLoaded={isConfigLoaded}
                  updatedAt={updatedAt}
                />
              </HStack>
              <Text type="supporting" color="secondary">{current.hint}</Text>
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
