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
 * - CommandPalette（shell-nav 里的 ⌘K 搜索）：八个视图，1–8 直接切比搜索快。
 * - 图表（dashboard-alert-rail 的 Sparkline / MetricChart）：那些底下是 recharts，没装。
 *   性能页那条折线是手写 SVG（见 components.jsx 的 Sparkline）。
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
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
  SideNav,
  SideNavHeading,
  SideNavItem,
  SideNavSection,
  StatusDot,
  Switch,
  Text,
  Timestamp,
  TopNav,
  VStack,
  useHotkeys,
} from '@astryxdesign/core';
import { FALLBACK_MAX_LIMIT, fetchConfig } from './api.js';
import { focusFirstSearch } from './components.jsx';
import AuditPanel from './panels/AuditPanel.jsx';
import HistoryPanel from './panels/HistoryPanel.jsx';
import ConnectionsPanel from './panels/ConnectionsPanel.jsx';
import PerformancePanel from './panels/PerformancePanel.jsx';
import ReportsPanel from './panels/ReportsPanel.jsx';
import DbaPanel from './panels/DbaPanel.jsx';
import ToolsPanel from './panels/ToolsPanel.jsx';
import InfoPanel from './panels/InfoPanel.jsx';

/*
 * 侧栏顺序 = 使用频率，不是端点的字母序，也不是它们被实现的顺序。
 *
 * 前四项是「值班时反复刷的」：出了事先看流水与性能。中间两项是「查一次就走的」——审计报告
 * 是按窗口出的合规快照，DBA 视图更是要手点才发请求。最后两项（工具清单、服务信息）是
 * 「一套部署上线时确认一次，之后基本不看」的静态事实，放最底下。
 *
 * 这个顺序同时也是数字快捷键 1–8 的顺序（见下面的 useHotkeys）：两者必须是同一份数组，
 * 写成两份的话加一个视图就会让快捷键和侧栏错位一格，而且错位不报错。
 *
 * hint 一句话必须同时说清「数据从哪来」和「有什么约束」：导航项被点开之前，这句话是运维
 * 判断「我要找的东西在不在这一页」的唯一依据。只写标题的话，「审计流水」和「审计历史」
 * 从名字上分不出哪个重启后还在。
 */
const VIEWS = [
  { value: 'audit', label: '审计流水', hint: '进程内环形缓冲，重启即清空' },
  { value: 'history', label: '审计历史', hint: '审计表，需配 spring.datasource.url' },
  { value: 'connections', label: '连接与连接池', hint: '已注册连接与 HikariCP 池状态' },
  { value: 'performance', label: '性能', hint: '慢查询原文与 SQL 模式统计' },
  {
    value: 'reports',
    label: '审计报告',
    hint: '按时间窗出的合规报告；审计未落库时两份报告是 skipped 而不是错误',
  },
  {
    value: 'dba',
    label: 'DBA 视图',
    hint: '唯一会真的连业务库执行查询的一页，默认不自动查，多数视图只有 Oracle 有',
  },
  {
    value: 'tools',
    label: '工具清单',
    hint: '已暴露的 MCP 工具目录，只有名字/分组/摘要，没有入参 schema',
  },
  {
    value: 'info',
    label: '服务信息',
    hint: '版本、profile、四个生效开关；版本来自打包期的 pom，不是 build-info',
  },
];

const AUTO_REFRESH_INTERVAL_MS = 10_000;
const DEFAULT_VIEW = VIEWS[0].value;
const DEFAULT_LIMIT = 50;

// =============================================================================
// location.hash ↔ state
// =============================================================================

/*
 * 现场（view + 条数）记在 location.hash 里，形如 `#view=audit&limit=100`。
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
 * 当前 hash 完全一致，被 writeHash 里的相等判断挡掉。两条边各只走一次。
 *
 * ── 为什么是 replaceState 而不是 pushState ──
 * 条数是个 NumberInput 步进器，点五下 pushState 就是五条历史记录，后退键从此没法用。
 * 代价是浏览器后退不能回到上一个视图——但这个页面的"上一步"概念本来就很弱
 * （八个平级视图，侧栏点一下就到），换掉一个能用的后退键不值得。
 */
function readHash() {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  const view = params.get('view');
  const limit = Number.parseInt(params.get('limit') ?? '', 10);
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

function writeHash(view, limit) {
  const next = `#view=${view}&limit=${limit}`;
  if (window.location.hash !== next) {
    window.history.replaceState(null, '', next);
  }
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
        <Kbd keys="8" />
        <Text type="supporting" color="secondary">切视图</Text>
        <Kbd keys="/" />
        <Text type="supporting" color="secondary">聚焦搜索</Text>
        <Kbd keys="escape" />
        <Text type="supporting" color="secondary">关详情面板</Text>
      </HStack>
    </HStack>
  );
}

export default function App() {
  const [config, setConfig] = useState(null);
  const [isConfigLoaded, setConfigLoaded] = useState(false);
  /* 初值从 hash 读，而且是惰性初值（useState(fn)）：写成 useState(readHash().view) 的话
     每次渲染都会解析一遍 hash，而它只在挂载时有用。 */
  const [view, setView] = useState(() => readHash().view ?? DEFAULT_VIEW);
  const [limit, setLimit] = useState(() => readHash().limit ?? DEFAULT_LIMIT);
  const [refreshToken, setRefreshToken] = useState(0);
  const [isAutoRefresh, setAutoRefresh] = useState(false);
  const [isHotkeyEnabled, setHotkeyEnabled] = useState(true);
  const [updatedAt, setUpdatedAt] = useState(null);

  // 自举：先读 config，再决定告警条与「审计历史」页要不要发请求。
  useEffect(() => {
    let cancelled = false;
    fetchConfig()
      .then((cfg) => { if (!cancelled) { setConfig(cfg); } })
      .catch(() => { if (!cancelled) { setConfig(null); } })
      .finally(() => { if (!cancelled) { setConfigLoaded(true); } });
    return () => { cancelled = true; };
  }, []);

  // state → hash。见 readHash 上方关于"为什么不打环"的说明。
  useEffect(() => { writeHash(view, limit); }, [view, limit]);

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
   * 数字键用 String(i+1) 从 VIEWS 生成，而不是手写八条：手写的那一版在加第九个视图时
   * 一定会忘记加快捷键，而且忘了不报错。
   */
  const hotkeys = useMemo(() => [
    { keys: 'r', onPress: refresh, isDisabled: !isHotkeyEnabled },
    { keys: '/', onPress: focusFirstSearch, isDisabled: !isHotkeyEnabled },
    ...VIEWS.map((v, i) => ({
      keys: String(i + 1),
      onPress: () => setView(v.value),
      isDisabled: !isHotkeyEnabled,
    })),
  ], [refresh, isHotkeyEnabled]);
  useHotkeys(hotkeys);

  const maxLimit = config?.maxLimit ?? FALLBACK_MAX_LIMIT;
  const panelProps = { limit, refreshToken };
  const current = VIEWS.find((v) => v.value === view) ?? VIEWS[0];

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
              <Button label="刷新" variant="primary" onClick={refresh} />
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
          <SideNavSection title="视图" isHeaderHidden>
            {VIEWS.map((v, i) => (
              <SideNavItem
                key={v.value}
                label={`${i + 1} · ${v.label}`}
                href={`#view=${v.value}&limit=${limit}`}
                isSelected={v.value === view}
                /*
                  href 写成真的 hash 而不是 "#"：这样中键 / Cmd+点击能在新标签里打开
                  同一个视图，右键也能复制链接——分享现场的实际用法。
                  onClick 仍然 preventDefault 走内部 state，避免多走一次 hashchange。
                */
                onClick={(e) => { e.preventDefault(); setView(v.value); }}
              />
            ))}
          </SideNavSection>
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
                {view === 'audit' && <AuditPanel {...panelProps} />}
                {view === 'history' && (
                  <HistoryPanel {...panelProps} auditPersistence={config?.auditPersistence} />
                )}
                {view === 'connections' && <ConnectionsPanel {...panelProps} />}
                {view === 'performance' && <PerformancePanel {...panelProps} />}
                {view === 'reports' && <ReportsPanel {...panelProps} />}
                {view === 'dba' && <DbaPanel {...panelProps} />}
                {view === 'tools' && <ToolsPanel {...panelProps} />}
                {view === 'info' && <InfoPanel {...panelProps} />}
              </div>
            )}
          </LayoutContent>
        }
      />
    </AppShell>
  );
}
