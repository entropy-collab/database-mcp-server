/*
 * 侧栏导航图标：18 个视图 + 侧栏标题各一个，全部是手写的 inline SVG。
 *
 * ── 为什么现在要有图标（这不是装饰，是折叠态的前提）──
 * App.jsx 的头注释里原本写着「不引图标库，SideNavItem 的 icon 是可选的，省掉之后就是
 * 纯文字导航」。那句话在<b>不折叠</b>的前提下成立，而侧栏是 collapsible 的，于是它漏了一件事：
 *
 *   SideNavItem.tsx 里有一句硬的提前返回 —— `if (isCollapsed && !icon) return null;`
 *
 * 也就是说，没有 icon 的项在折叠态<b>整个不渲染</b>。不是显示一个空方块、不是回落成首字母，
 * 是消失。所以上一版折叠侧栏之后，48px 宽的 rail 里除了那个折叠按钮什么都没有 ——
 * 这不是"折叠后不好看"，是折叠功能实际上是坏的，而且坏得很安静（不报错、不留痕）。
 *
 * ── 为什么手写而不是引一个图标库 ──
 * 1. core 自带的注册表只有 28 个语义图标（chevron / close / success / error / info /
 *    search / funnel / wrench / clock / copy 这类），是给组件内部用的，没有 database、
 *    table、server、key 这些业务概念。18 个视图里能对上的不到三分之一；
 * 2. 混用「内置几个 + 库里几个」会得到两套线宽和两种视觉重量，在一列 18 个图标里非常显眼；
 * 3. 这个仓库有手写 SVG 的先例（性能页那条折线，见 components.jsx 的 Sparkline），
 *    而 18 个单路径图标压缩后是几 KB 的量级，与「不新增 npm 依赖」这条硬约束不冲突。
 *
 * ── 统一的绘制参数（改动时必须一起改，否则会出现一个线更粗的图标）──
 * viewBox 24×24、fill="none"、stroke="currentColor"、strokeWidth 1.75、round 的 cap 与 join。
 * 这是 outline 图标的通用参数，与 theme-neutral 的字重协调；currentColor 让图标自动跟随
 * SideNavItem 的选中态与禁用态配色，不需要为深色模式另写一份。
 *
 * width/height 用 1em 而不是写死 px：core 的 renderIconSlot 会把图标放进一个按字号排版的槽位，
 * 写死 px 会在折叠态（size="sm"）和展开态之间出现两种实际尺寸。
 *
 * ── 传给 SideNavItem 的是组件本身，不走注册表 ──
 * icon 的类型是 `ReactNode | IconType`，而 IconType 就是 `ComponentType<SVGProps>`，
 * 所以直接 `icon={IconAudit}` 即可。刻意不用 registerIcons()：那是全局副作用，而且
 * core 自己在 dev 下会对它发告警、建议改用 theme-scoped 覆盖；为了 18 个只在一处用的图标
 * 去动全局注册表不值得。<b>更要紧的是别传字符串</b>：一个没注册过的字符串（比如 "database"）
 * 在类型上合法（string 是合法的 ReactNode），运行期解析成 undefined、渲染成 null，
 * 但它<b>能通过上面那句 !icon 判断</b> —— 结果是折叠态里一个 32px 的空方块，静默坏掉。
 */

/**
 * 所有图标共用的外壳。
 *
 * 单独抽出来的唯一理由是「绘制参数只有一份」：上面那组 stroke / viewBox 参数散到 18 个
 * 组件里之后，加第 19 个图标时一定会有一项抄漏，而抄漏的表现是那一个图标看起来"怪"，
 * 却很难指出哪里不对。
 *
 * aria-hidden 写死 true：这些图标旁边永远有文字标签（展开态是 label，折叠态由
 * SideNavItem 自动挂的 Tooltip 提供，内容同样是 label），让屏幕阅读器再读一遍图标
 * 只会得到重复的信息。
 */
function Glyph({ children, ...props }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="1em"
      height="1em"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      {children}
    </svg>
  );
}

// ─── 侧栏标题 ────────────────────────────────────────────────────────────────

/**
 * 侧栏标题（SideNavHeading）的图标。
 *
 * 它<b>必须</b>有：SideNavHeading 和 SideNavItem 一样，折叠态下没有 icon 就 return null，
 * 于是 rail 顶部会空掉一块。用数据库的三层柱体，因为这一整个面板讲的就是数据库。
 */
export function IconDatabase() {
  return (
    <Glyph>
      <ellipse cx="12" cy="5" rx="7.5" ry="3" />
      <path d="M4.5 5v7c0 1.66 3.36 3 7.5 3s7.5-1.34 7.5-3V5" />
      <path d="M4.5 12v7c0 1.66 3.36 3 7.5 3s7.5-1.34 7.5-3v-7" />
    </Glyph>
  );
}

// ─── 审计 ────────────────────────────────────────────────────────────────────

/** 审计流水：一叠带条目的清单。 */
export function IconAuditLog() {
  return (
    <Glyph>
      <path d="M5 3.5h14v17H5z" />
      <path d="M8.5 8h7M8.5 12h7M8.5 16h4" />
    </Glyph>
  );
}

/** 审计历史：时钟加一圈回溯箭头。 */
export function IconHistory() {
  return (
    <Glyph>
      <path d="M3.5 12a8.5 8.5 0 1 0 2.9-6.4" />
      <path d="M3.5 4.5V9H8" />
      <path d="M12 8v4.5l3 1.8" />
    </Glyph>
  );
}

/** 审计报告：文档加一组柱状。 */
export function IconReport() {
  return (
    <Glyph>
      <path d="M6 3.5h8l4 4v13H6z" />
      <path d="M14 3.5v4h4" />
      <path d="M9.5 16.5v-3M12 16.5v-5M14.5 16.5v-2" />
    </Glyph>
  );
}

// ─── 运行状态 ────────────────────────────────────────────────────────────────

/** 连接与连接池：两节相扣的链环。 */
export function IconConnections() {
  return (
    <Glyph>
      <path d="M9.5 14.5 14.5 9.5" />
      <path d="M13 6.5l1.2-1.2a3.6 3.6 0 0 1 5.1 5.1L18.2 11.5" />
      <path d="M11 17.5l-1.2 1.2a3.6 3.6 0 0 1-5.1-5.1L5.8 12.5" />
    </Glyph>
  );
}

/** 性能：仪表盘指针。 */
export function IconPerformance() {
  return (
    <Glyph>
      <path d="M3.5 17a8.5 8.5 0 1 1 17 0" />
      <path d="M12 17l4-5" />
      <circle cx="12" cy="17" r="1.4" />
    </Glyph>
  );
}

/** 服务信息：info 圆圈。 */
export function IconInfo() {
  return (
    <Glyph>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 11v5.5" />
      <path d="M12 7.8v.4" />
    </Glyph>
  );
}

/** 工具清单：扳手。 */
export function IconTools() {
  return (
    <Glyph>
      <path d="M14.8 6.2a3.8 3.8 0 0 0 5 5l-9.6 9.6a2.4 2.4 0 0 1-3.4-3.4z" />
      <path d="M14.8 6.2 17.6 3.4" />
    </Glyph>
  );
}

// ─── 库与资产 ────────────────────────────────────────────────────────────────

/** Schema 浏览：表格网格。 */
export function IconSchema() {
  return (
    <Glyph>
      <path d="M3.5 5.5h17v13h-17z" />
      <path d="M3.5 10h17M3.5 14.5h17M9.5 5.5v13" />
    </Glyph>
  );
}

/** 数据资产：带标签的归档盒。 */
export function IconCatalog() {
  return (
    <Glyph>
      <path d="M3.5 7.5h17v12h-17z" />
      <path d="M3.5 7.5 5.5 4h13l2 3.5" />
      <path d="M10 12h4" />
    </Glyph>
  );
}

/** 血缘：一个父节点分出两个子节点。 */
export function IconLineage() {
  return (
    <Glyph>
      <circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="18" r="2.4" />
      <circle cx="12" cy="5.5" r="2.4" />
      <path d="M12 7.9v3.1M12 11h-6v4.6M12 11h6v4.6" />
    </Glyph>
  );
}

/** 数据质量：盾牌加勾。 */
export function IconQuality() {
  return (
    <Glyph>
      <path d="M12 3.2 19.5 6v5.5c0 4.3-3 7.7-7.5 9.3-4.5-1.6-7.5-5-7.5-9.3V6z" />
      <path d="M9 11.8l2.2 2.2L15 10.2" />
    </Glyph>
  );
}

// ─── 诊断与运维 ──────────────────────────────────────────────────────────────

/** SQL 体检：放大镜里一段代码。 */
export function IconSqlDoctor() {
  return (
    <Glyph>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="M15.4 15.4 20.5 20.5" />
      <path d="M9.2 8.6 7.6 10.5l1.6 1.9M11.8 8.6l1.6 1.9-1.6 1.9" />
    </Glyph>
  );
}

/** DBA 视图：机架式服务器。 */
export function IconDba() {
  return (
    <Glyph>
      <path d="M3.5 4.5h17v6h-17zM3.5 13.5h17v6h-17z" />
      <path d="M7 7.5h.4M7 16.5h.4" />
      <path d="M11 7.5h6M11 16.5h6" />
    </Glyph>
  );
}

/** CDC：两条方向相反的循环箭头。 */
export function IconCdc() {
  return (
    <Glyph>
      <path d="M4 9.5h12.5a3.5 3.5 0 0 1 0 7H8" />
      <path d="M6.5 7 4 9.5 6.5 12" />
      <path d="M10.5 14 8 16.5 10.5 19" />
    </Glyph>
  );
}

/** 备份：归档箱加向下的箭头。 */
export function IconBackup() {
  return (
    <Glyph>
      <path d="M3.5 8.5h17v11h-17z" />
      <path d="M3.5 8.5V4.5h17v4" />
      <path d="M12 11v5l-2.2-2.2M12 16l2.2-2.2" />
    </Glyph>
  );
}

/** ETL 作业：齿轮。 */
export function IconJobs() {
  return (
    <Glyph>
      <circle cx="12" cy="12" r="3" />
      <path d="M12 3.5v2.2M12 18.3v2.2M4.9 7.8l1.9 1.1M17.2 15.1l1.9 1.1M4.9 16.2l1.9-1.1M17.2 8.9l1.9-1.1" />
    </Glyph>
  );
}

// ─── 访问控制 ────────────────────────────────────────────────────────────────

/** 身份管理：两个人形。 */
export function IconUsers() {
  return (
    <Glyph>
      <circle cx="9.5" cy="8" r="3.2" />
      <path d="M3.5 19.5c0-3.3 2.7-5.5 6-5.5s6 2.2 6 5.5" />
      <path d="M16 5.2a3.2 3.2 0 0 1 0 5.6M17.5 14.4c1.9.7 3 2.6 3 5.1" />
    </Glyph>
  );
}

/** 权限视图：钥匙。 */
export function IconAuthz() {
  return (
    <Glyph>
      <circle cx="8" cy="8" r="4.5" />
      <path d="M11.2 11.2 20 20" />
      <path d="M17 17l2-2M14.5 14.5l2-2" />
    </Glyph>
  );
}
