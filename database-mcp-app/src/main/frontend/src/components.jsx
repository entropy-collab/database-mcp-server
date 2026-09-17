/*
 * 面板之间共用的显示件：错误横幅、指标卡片、分区、数据表格、可交互表格、行详情面板、
 * CSV 导出、sparkline。
 *
 * 抽出来的理由和后端 WebUiController 里"全部走已有工具 bean"一样：四个面板对
 * 「加载失败长什么样」「空表格长什么样」必须给出同一个答案，否则运维会以为
 * 不同视图的语义不同。
 *
 * 排版口径照官方 dashboard-alert-rail 模板（`npx @astryxdesign/cli@0.6.2 template
 * dashboard-alert-rail --skeleton` 可复现）：
 * - 字号一律走语义 type（label / supporting / body / display-*），不用 size="sm" 这种
 *   绝对值。主题换 scale 时语义 type 会跟着走，写死的 size 不会。
 * - 指标用 Grid + Card 而不是一排定宽 Card：定宽在窄视口下会挤成两行半，
 *   `columns={{minWidth, repeat:'fit'}}` 才是按可用宽度自适应列数。
 * - 每个分区自带标题带（标题 + 数据来源 + 行数），表格本身不再解释自己是什么。
 *
 * ── 这一版新增的四件事，以及它们为什么都落在这个文件里 ──
 * 搜索/排序/过滤、行详情面板、CSV 导出、相对时间列，四个面板都要。写四份的下场是
 * 「审计流水的搜索框和慢查询的搜索框行为不一样」——运维会以为是数据的差别。
 * 所以它们做成 InteractiveTable 一个件，panel 只负责给列定义与字段定义。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Badge,
  Banner,
  Button,
  Card,
  Divider,
  EmptyState,
  Grid,
  HStack,
  Heading,
  PowerSearch,
  StackItem,
  StatusDot,
  Table,
  Text,
  Timestamp,
  VStack,
  Layout,
  LayoutPanel,
  ResizeHandle,
  pixel,
  proportional,
  toSearchFilters,
  useClipboard,
  useHotkeys,
  useMediaQuery,
  usePowerSearchConfig,
  useResizable,
  useTableFilterState,
  useTableFiltering,
  useTableRowStatus,
  useTableSortable,
  useTableSortableState,
  useTheme,
} from '@astryxdesign/core';

/*
 * 注意：core 自己也导出一个叫 Section 的组件，本文件<b>没有</b>导入它。
 * 下面导出的 Section 是这个项目自己的标题带实现（标题 + 数据来源 + 行数 + 右侧按钮），
 * 和 core 的 Section 不是一回事。在 panel 里搜到 Section 时，看 import 来源。
 */

/**
 * 单元格取值的统一口径。
 *
 * Astryx Table 的默认渲染是 String(item[key])，对 null / undefined 会渲染出字面量
 * "null" / "undefined"——审计记录里 error、leaseExpiry 这些字段经常是空的，
 * 满屏 "null" 比空白更难读，所以这里统一折成短横线。
 * 对象走 JSON.stringify：healthWarnings 是个数组，直接 String() 会得到 "[object Object]"。
 */
export function displayValue(value) {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

/** 加载失败的统一呈现：状态码与响应体原文都留在页面上，不做美化也不截断。 */
export function ErrorNotice({ error }) {
  if (!error) {
    return null;
  }
  return (
    <Banner status="error" title="加载失败" description={error.message} container="card" />
  );
}

/**
 * 单个指标块。
 *
 * status 可选：给了就在标签左边点一个 StatusDot。只有"这个数是好还是坏"能一眼判断的
 * 指标才配色（健康池数、失败数），像"查询总数"这种中性读数不配——到处都是颜色等于没有颜色。
 */
export function KpiTile({ label, value, status, statusLabel, hint }) {
  return (
    <Card padding={4}>
      <VStack gap={2}>
        <HStack gap={2} vAlign="center">
          {status && <StatusDot variant={status} label={statusLabel ?? label} />}
          <Text type="label" color="secondary">{label}</Text>
        </HStack>
        <Heading level={3} hasTabularNumbers>{displayValue(value)}</Heading>
        {hint && <Text type="supporting" color="secondary">{hint}</Text>}
      </VStack>
    </Card>
  );
}

/** 指标行。列数按可用宽度自适应，不写死列数也不写死卡片宽度。 */
export function KpiGrid({ items }) {
  return (
    <Grid gap={3} columns={{ minWidth: 200, repeat: 'fit' }}>
      {items.map((item) => (
        <KpiTile key={item.label} {...item} />
      ))}
    </Grid>
  );
}

// =============================================================================
// CSV 导出
// =============================================================================

/**
 * 一个 CSV 字段的转义。
 *
 * 这个函数不是"顺手写的"：审计流水导出的每一行都带 SQL 原文，而 SQL 原文里同时有
 * 双引号（字符串字面量）和换行（格式化过的多行语句）——RFC 4180 要求的两种必须转义
 * 的字符，在这份数据里是常态而不是边界情况。少转一种，导出的文件在 Excel 里就会从
 * 某一行开始整体错位，而且错位处往前十几行看不出任何异常。
 *
 * 规则就是 RFC 4180 那两条：含 " / , / CR / LF 的字段整体加双引号，内部的 " 翻倍。
 */
function csvField(value) {
  const raw = value === null || value === undefined
    ? ''
    : typeof value === 'object' ? JSON.stringify(value) : String(value);
  return /["\n\r,]/.test(raw) ? `"${raw.replace(/"/g, '""')}"` : raw;
}

/**
 * 把「当前视图里实际显示的行」拍成 CSV 文本。
 *
 * 导出的是过滤 + 排序之后的 rows，不是原始全量：运维点导出的语境几乎总是
 * 「我刚筛出来的这些，发群里」。导全量会让接收方拿到一份和截图对不上的文件。
 *
 * 表头取列的 header（中文），而不是 key：这份文件的读者是人，不是解析器。
 * 想要机器口径的字段名，直接打那个 GET 端点更省事。
 */
export function toCsv(columns, rows) {
  const cols = columns.filter((c) => c.csv !== false);
  const lines = [cols.map((c) => csvField(c.header ?? c.key)).join(',')];
  for (const row of rows) {
    lines.push(cols.map((c) => csvField(row[c.key])).join(','));
  }
  // CRLF 是 RFC 4180 的行分隔符；Excel 对 LF-only 的多行字段处理更容易出岔子。
  return lines.join('\r\n');
}

/**
 * 纯前端下载。不发任何请求——页面的约束是「只发 GET」，而导出根本不需要发。
 *
 * \ufeff（BOM）不是可有可无的：没有它，Excel（Windows 简中默认 GBK）打开这份 UTF-8
 * 文件会把所有中文表头显示成乱码。导出功能的实际用途就是发给别人用 Excel 打开，
 * 所以这三个字节比"文件更干净"重要。
 */
function downloadTextFile(filename, text) {
  const blob = new Blob([`\ufeff${text}`], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // 不 revoke 会让 blob 一直挂在 document 上；导出十几次就是十几份数据留在内存里。
  URL.revokeObjectURL(url);
}

/** 文件名带上时间戳：运维一天里会导出好几次，`audit-logs.csv (3)` 分不清哪个是哪个。 */
function csvFileName(base) {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  return `${base}-${stamp}.csv`;
}

/**
 * 「导出 CSV」按钮。rows 传进来的必须已经是当前视图的行。
 *
 * 刻意不传 size：Button 的 size 是控件高度（28/32/36px）而不是字号，但这一版的口径是
 * 「排版尺寸一律走组件默认与语义 type，不出现 size="sm" 这种字面量」，所以连高度也不写。
 * 标题带里 md 按钮略显敦实，接受这个代价换掉一个以后没人说得清是字号还是高度的字面量。
 */
export function ExportCsvButton({ columns, rows, baseName, isDisabled }) {
  const onExport = useCallback(() => {
    downloadTextFile(csvFileName(baseName), toCsv(columns, rows));
  }, [columns, rows, baseName]);
  return (
    <Button
      label="导出 CSV"
      variant="secondary"
      isDisabled={isDisabled || rows.length === 0}
      onClick={onExport}
    />
  );
}

/**
 * 一个内容分区：标题带 + 内容，整体包在 Card 里。
 *
 * 上一版是「一句灰色说明 + 一张裸表格」，页面读起来没有层级。这里把每张表的三件事
 * 固定成一条标题带：它是什么（title）、数据从哪来（source，写明具体端点）、现在有多少行
 * （count）。行数用 Badge 而不是正文：它是随刷新变化的读数，视觉上应该和标题分开。
 *
 * count 允许 undefined（还没加载完），此时不渲染 Badge 而不是渲染一个 0——
 * 0 行和"不知道几行"在排查时是两件事。
 *
 * totalCount 只在「被过滤掉了一部分」时才有值。过滤后只显示 count 会让人以为数据没了，
 * 所以过滤生效时角标写成 "12 / 50 行" 并额外点一个 info Badge——被过滤这件事必须
 * 在页面上写着，不能只体现在"行数比刚才少"。
 * （用 info 而不是 accent：0.6.2 的 BadgeVariantMap 里没有 accent 这个成员，
 * 只有 neutral/info/success/warning/error 与一串具体色名。）
 *
 * actions 在标题带右侧：导出按钮之类。放在标题带而不是表格上方，是为了让「这个按钮
 * 作用于哪张表」在有多张表的页面（性能页有三张）里没有歧义。
 */
export function Section({ title, source, count, totalCount, actions, children }) {
  const isFiltered = totalCount !== undefined && totalCount !== null && count !== totalCount;
  return (
    <Card padding={0}>
      <VStack gap={0}>
        <HStack gap={3} padding={4} vAlign="start" wrap="wrap">
          <VStack gap={1}>
            <HStack gap={2} vAlign="center" wrap="wrap">
              <Text type="label" weight="semibold">{title}</Text>
              {count !== undefined && count !== null && (
                <Badge
                  variant="neutral"
                  label={isFiltered ? `${count} / ${totalCount} 行` : `${count} 行`}
                />
              )}
              {isFiltered && <Badge variant="info" label="已过滤" />}
            </HStack>
            {source && <Text type="supporting" color="secondary">{source}</Text>}
          </VStack>
          {actions && (
            <>
              {/* 撑开中间，把 actions 顶到右边。用 StackItem size="fill" 而不是
                  HStack 的 justify="between"：标题块 wrap 换行之后 justify 会把按钮
                  甩到下一行的最右侧，看着像孤儿。 */}
              <StackItem size="fill" />
              <HStack gap={2} vAlign="center" wrap="wrap">{actions}</HStack>
            </>
          )}
        </HStack>
        <Divider />
        <VStack padding={4}>{children}</VStack>
      </VStack>
    </Card>
  );
}

/**
 * 数据表格。
 *
 * columns 里的 width 一律显式给出：Astryx 的文档明确说省略 width 会跳过最小宽度下限，
 * 窄视口下列会被压塌。数值列右对齐并开 tabular numbers，耗时/行数这类列才对得齐。
 *
 * textOverflow="truncate" 而不是 wrap：审计流水里的 SQL 原文可能很长，
 * 换行会把一行撑成半屏；截断后 Astryx 会在 hover 时给出 Tooltip（依赖 main.jsx 里的
 * LayerProvider），完整内容仍然拿得到。真要看完整原文，点这一行开右侧详情面板。
 *
 * dividers 从 "rows" 换成 "none" 并关掉斑马纹：外层 Section 已经给了卡片边界，
 * 再叠横线和隔行底色就是三层视觉噪声。行的区分交给 hover。
 */
export function DataTable({ columns, rows, emptyTitle, emptyDescription, plugins, idKey }) {
  const data = Array.isArray(rows) ? rows : [];
  if (data.length === 0) {
    return <EmptyState title={emptyTitle} description={emptyDescription} isCompact />;
  }
  return (
    <Table
      data={data}
      columns={columns}
      idKey={idKey}
      plugins={plugins}
      density="compact"
      dividers="none"
      hasHover
      textOverflow="truncate"
      verticalAlign="top"
    />
  );
}

// =============================================================================
// 列定义
// =============================================================================

/**
 * 列定义里 sortable / filter 两个字段是给 Table 的两个 plugin 看的：
 * - sortable: true → useTableSortable 在表头挂上可点的排序控件
 * - filter: '<PowerSearch 字段 key>' → useTableFiltering 在表头挂上漏斗按钮
 *
 * 两者都是「列声明能力、plugin 提供 UI」的 headless 写法，所以列定义里不出现任何
 * 状态，可以安全地放在模块顶层的常量里（也必须放：见 InteractiveTable 的说明）。
 */

/** 文本列。weight 用于把「名字」这类主键列稍微加重。 */
export function textColumn(key, header, { flex = 1, align, weight, sortable = true, filter } = {}) {
  return {
    key,
    header,
    align,
    sortable,
    filter,
    width: proportional(flex),
    renderCell: (item) => (
      <Text type="supporting" weight={weight}>{displayValue(item[key])}</Text>
    ),
  };
}

/** 数值列：固定宽度 + 右对齐 + 等宽数字。 */
export function numberColumn(key, header, { px = 96, sortable = true, filter } = {}) {
  return {
    key,
    header,
    align: 'end',
    sortable,
    filter,
    width: pixel(px),
    renderCell: (item) => (
      <Text type="supporting" hasTabularNumbers>{displayValue(item[key])}</Text>
    ),
  };
}

/**
 * SQL / 模式列：等宽字体排版。读 SQL 用等宽比正文字体好得多。
 *
 * 用 <code> 原生标签 + --font-family-code token，而不是 core 的 Code 组件（上一版用的是它）：
 * Code 自带 muted 底色和内边距，一整列都套上之后每个单元格都是一个灰块，在 truncate 的
 * 窄列里比纯文本更难读；而且它的 white-space 由自己的 class 决定，SQL 里的换行保不保留
 * 不由调用方说了算。等宽字体是这一列真正需要的那一半，从 token 里单独借来就够。
 *
 * 顺带记一笔上一版踩过的坑，以免有人想把 Code 加回来还顺手传 size：0.6.2 的 CodeSize
 * 联合类型只有 'inherit' 一个成员（不是 sm/md/lg），传别的值 TS 报错、运行期是个
 * 不存在的 class。
 */
export function sqlColumn(key, header, { flex = 3, sortable = true, filter } = {}) {
  return {
    key,
    header,
    sortable,
    filter,
    width: proportional(flex),
    renderCell: (item) => (
      <Text type="supporting">
        <code style={{ fontFamily: 'var(--font-family-code)' }}>{displayValue(item[key])}</code>
      </Text>
    ),
  };
}

/** 布尔结果列：isAlias / isPoolHealthy 都是这个形状。 */
export function booleanColumn(key, header, { okLabel = 'OK', badLabel = 'FAIL', px = 88, invertTone = false, sortable = true, filter } = {}) {
  return {
    key,
    header,
    align: 'center',
    sortable,
    filter,
    width: pixel(px),
    renderCell: (item) => {
      const value = item[key];
      if (value === null || value === undefined) {
        return <Text type="supporting">—</Text>;
      }
      const good = invertTone ? !value : Boolean(value);
      return <Badge variant={good ? 'success' : 'error'} label={value ? okLabel : badLabel} />;
    },
  };
}

/**
 * 成功/失败列。
 *
 * key 刻意是派生出来的字符串字段（'成功' / '失败'）而不是原始的布尔 success：
 * 三个下游都要求字符串——PowerSearch 的 applyFilters 对 enum 只认 string，
 * 排序的默认比较器是 Intl.Collator（拿 "true"/"false" 排序读起来毫无意义），
 * CSV 导出直接吐 true/false 也不如中文可读。派生一次，三处都对。
 * 派生动作放在各 panel 里（见 AuditPanel 的 rows.map），不在这里偷偷加字段。
 */
export function resultColumn(key, header, { px = 92, filter } = {}) {
  return {
    key,
    header,
    align: 'center',
    sortable: true,
    filter,
    width: pixel(px),
    renderCell: (item) => {
      const value = item[key];
      if (value === null || value === undefined || value === '') {
        return <Text type="supporting">—</Text>;
      }
      return <Badge variant={value === '失败' ? 'error' : 'success'} label={value} />;
    },
  };
}

/**
 * 时间列：相对时间 + hover 出绝对时间。
 *
 * ── 为什么默认相对时间而不是绝对时间戳 ──
 * 长春那台服务器的系统时钟比开发机快约 11 分钟，而且 NTP 没同步。满屏绝对时间戳的
 * 后果不是"读起来累"，是会得出错的结论：运维看到 10:42:31 而自己表上是 10:31，
 * 会以为这条记录是未来的、或者以为自己刚才那次操作没被记下来。
 * 相对时间（"3 分钟前"）是浏览器用同一个 Date.now() 算出来的差值——它对两边的
 * 时钟偏差都免疫，因为偏差在减法里被同一个 skew 吃掉了。
 *
 * format="auto" 而不是 "relative"：超过 7 天的记录说"12 天前"没有意义，auto 会自动
 * 切回 date_time。审计表里翻历史时正是这个区间。
 *
 * 绝对时间不是被删掉了，是挪到 hover：tooltipEntries 同时给出本机时区与 UTC 两行，
 * 并且把 ISO 那一行标成可复制——跨时区对齐日志时，要贴给别人的就是这一行。
 */
const TIME_TOOLTIP_ENTRIES = [
  { label: '本机时区' },
  { timezoneID: 'UTC', label: 'UTC' },
  { timezoneID: 'UTC', format: 'system_date_time', label: 'UTC（可复制）', isCopyable: true },
];

export function timeColumn(key, header, { px = 132, sortable = true, filter } = {}) {
  return {
    key,
    header,
    sortable,
    filter,
    width: pixel(px),
    renderCell: (item) => {
      const value = item[key];
      if (value === null || value === undefined || value === '') {
        return <Text type="supporting">—</Text>;
      }
      /* Timestamp 对解析不出来的值返回 null（不抛），页面上会变成一个空单元格。
         后端给的是 Instant（Jackson 序列化成 ISO-8601 或 epoch 秒，两种 Timestamp 都吃），
         但 DBA 视图那种直接来自数据库的时间字符串未必是合法 ISO——所以留一条兜底，
         解析不出来时至少把原文显示出来，而不是显示一个空格。 */
      const parsed = new Date(typeof value === 'number' && value < 1e12 ? value * 1000 : value);
      if (Number.isNaN(parsed.getTime())) {
        return <Text type="supporting">{displayValue(value)}</Text>;
      }
      return (
        <Timestamp value={value} format="auto" type="supporting" tooltipEntries={TIME_TOOLTIP_ENTRIES} />
      );
    },
  };
}

// =============================================================================
// sparkline（手写 SVG，零依赖）
// =============================================================================

/**
 * 一条折线。刻意不引图表库：dashboard 模板里的 Sparkline / MetricChart 底下是 recharts，
 * 为了一条 40px 高的折线往产物里塞一个图表库不值得（也违反"不新增 npm 依赖"）。
 *
 * ── 配色为什么必须用 --color-data-* 而不是普通 UI 色 ──
 * 官方最佳实践把数据可视化的色板单独开了一组 token（--color-data-categorical-* 与
 * --color-data-<hue>-1..5），原因是 UI 色（accent / error / border）的对比度是按
 * 「小面积文字与边框」调的，铺到线条和面积上会偏灰、且在 dark mode 下反转方向不对。
 * 写死 #xxxxxx 更糟：换主题或换 mode 之后它是唯一不跟着变的那一笔。
 *
 * useTheme() 取的是「解析后的原始值」而不是 var(...)：SVG 的 stroke 可以吃 var()，
 * 但 useTheme 同时给了 mode，峰值点要不要换色这类判断需要真实值。
 * 注意本项目没有 <Theme> provider（main.jsx 只引了 theme.css），所以 useTheme 解析的是
 * tokenDefaults——对 --color-data-* 这一组，theme-neutral 并没有覆盖，两边一致。
 */
export function Sparkline({ values, height = 44, label, unit = '' }) {
  const { token } = useTheme();
  const nums = (Array.isArray(values) ? values : []).map(Number).filter(Number.isFinite);

  if (nums.length < 2) {
    return null;
  }

  const width = 600; // viewBox 宽度；实际宽度由 CSS 撑满，见下面的 width="100%"
  const pad = 4;
  const max = Math.max(...nums);
  const min = Math.min(...nums);
  const span = max - min || 1;
  const step = width / (nums.length - 1);
  const y = (n) => pad + (height - 2 * pad) * (1 - (n - min) / span);
  const points = nums.map((n, i) => `${(i * step).toFixed(2)},${y(n).toFixed(2)}`).join(' ');
  const peakIndex = nums.indexOf(max);

  const lineColor = token('--color-data-categorical-blue');
  const peakColor = token('--color-data-categorical-red');
  const baseColor = token('--color-data-gray-2');

  return (
    <VStack gap={1}>
      {label && (
        <HStack gap={2} vAlign="center" wrap="wrap">
          <Text type="label" color="secondary">{label}</Text>
          <Text type="supporting" color="secondary" hasTabularNumbers>
            {`最小 ${min}${unit} · 最大 ${max}${unit} · ${nums.length} 点`}
          </Text>
        </HStack>
      )}
      <svg
        width="100%"
        height={height}
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={label ? `${label}：最小 ${min}${unit}，最大 ${max}${unit}` : '耗时分布'}
      >
        {/* 基线画在最小值上：没有基线时一条起伏的折线看不出"离底有多远"。 */}
        <line
          x1="0"
          y1={y(min)}
          x2={width}
          y2={y(min)}
          stroke={baseColor}
          strokeWidth="1"
          vectorEffect="non-scaling-stroke"
        />
        {/* vectorEffect：viewBox 被横向拉伸（preserveAspectRatio="none"）后，
            没有它线宽会跟着变形，细的地方几乎看不见。 */}
        <polyline
          points={points}
          fill="none"
          stroke={lineColor}
          strokeWidth="1.5"
          strokeLinejoin="round"
          strokeLinecap="round"
          vectorEffect="non-scaling-stroke"
        />
        {/* 峰值单独点一个：这条线上唯一需要"定位到具体某一次"的就是最慢那次。
            r 用 x/y 两个半径抵消横向拉伸，否则圆会被拉成椭圆。 */}
        <ellipse
          cx={peakIndex * step}
          cy={y(max)}
          rx={width / 200}
          ry={height / 18}
          fill={peakColor}
        />
      </svg>
    </VStack>
  );
}

// =============================================================================
// 行详情面板
// =============================================================================

/**
 * 「复制 SQL」按钮。
 *
 * useClipboard 的 copy() 在被拒绝时是<b>静默</b>返回 false 的（见它的 .d.ts：
 * "A clipboard rejection is a silent no-op"）。这一页的部署形态几乎保证会撞上这件事：
 * 内网面板通常跑在 http:// 上，而 navigator.clipboard 只在 secure context 下可用，
 * 所以在 http 页面里这个按钮点了不会有任何反应。
 *
 * 静默失败是最坏的结果——运维会以为复制成功了，粘贴出来是上一次剪贴板的内容。
 * 所以这里必须自己接住 false，并且给出可执行的回退指引（下面的 SQL 是可选中的纯文本）。
 */
function CopySqlButton({ sql }) {
  const { copy, isCopied } = useClipboard({ announce: '已复制 SQL' });
  const [hasFailed, setFailed] = useState(false);

  const onCopy = useCallback(() => {
    setFailed(false);
    copy(sql).then((ok) => { if (!ok) { setFailed(true); } });
  }, [copy, sql]);

  return (
    <VStack gap={1}>
      <Button
        label={isCopied ? '已复制' : '复制 SQL'}
        variant="secondary"
        isDisabled={!sql}
        onClick={onCopy}
      />
      {hasFailed && (
        /* Badge + 说明而不是 Text color="error"：0.6.2 的 TextColor 里根本没有 'error'
           这个成员（只有 primary/secondary/disabled/placeholder/accent/inherit），
           传了会得到一个不存在的 class，字反而变成默认色——错误提示看不出是错误。 */
        <HStack gap={2} vAlign="center" wrap="wrap">
          <Badge variant="error" label="复制失败" />
          <Text type="supporting" color="secondary">
            浏览器拒绝了剪贴板写入（http:// 页面不是 secure context，clipboard API 不可用）。
            请直接选中下面的 SQL 原文复制。
          </Text>
        </HStack>
      )}
    </VStack>
  );
}

/**
 * 详情面板的内容：完整 SQL 原文 + 该行所有字段的键值对。
 *
 * SQL 用 <pre> 而不是 core 的 Code：Code 是行内件，自带底色和内边距，长 SQL 套进去
 * 会变成一个撑满的灰块；而且它的 white-space 由组件自己的 class 决定，不能保证保留
 * 换行。这里要的是「原文一个字符都不改」，所以自己控制 white-space: pre-wrap，
 * 只从 token 里借等宽字体（--font-family-code），不写死字体名。
 *
 * 键值对列出的是这一行的<b>全部</b>字段，包括表格里没有列的（error 的完整堆栈、
 * connectionKey 之类）。表格是"扫"用的，这里是"查"用的——所以刻意不做筛选，
 * 只把内部合成的字段（下划线开头）藏掉。
 */
function RowDetailBody({ row, sqlKey, columns }) {
  const sql = sqlKey ? row[sqlKey] : undefined;
  const headerByKey = useMemo(() => {
    const map = new Map();
    for (const col of columns) {
      map.set(col.key, col.header ?? col.key);
    }
    return map;
  }, [columns]);

  const entries = Object.keys(row)
    .filter((k) => !k.startsWith('_'))
    .sort();

  return (
    <VStack gap={4}>
      {sqlKey && (
        <VStack gap={2}>
          <HStack gap={3} vAlign="center" wrap="wrap">
            <Text type="label" weight="semibold">SQL 原文</Text>
            <CopySqlButton sql={typeof sql === 'string' ? sql : ''} />
          </HStack>
          <Card padding={3}>
            <pre
              style={{
                margin: 0,
                fontFamily: 'var(--font-family-code)',
                fontSize: 'inherit',
                whiteSpace: 'pre-wrap',
                overflowWrap: 'anywhere',
              }}
            >
              {typeof sql === 'string' && sql !== '' ? sql : '（这一行没有 SQL 原文）'}
            </pre>
          </Card>
        </VStack>
      )}

      <VStack gap={2}>
        <Text type="label" weight="semibold">全部字段</Text>
        <VStack gap={0}>
          {entries.map((key) => (
            <VStack key={key} gap={0} paddingBlock={2}>
              <Text type="supporting" color="secondary">
                {headerByKey.has(key) ? `${headerByKey.get(key)}（${key}）` : key}
              </Text>
              <Text
                type="supporting"
                /* wordBreak 是 Text 自己的 prop（WordBreak = 'break-word' | 'break-all'），
                   不用内联 style。break-all 而不是 break-word：这里的值有很多是没有空格的
                   长串（JDBC URL、堆栈里的类名、单行 SQL），break-word 对它们不起作用，
                   面板会被横向撑破。 */
                wordBreak="break-all"
                /* whiteSpace 没有对应的 prop（textWrap 只有 wrap/nowrap/balance/pretty，
                   管不了"保留换行"），错误信息里的多行堆栈要靠这个才不被压成一行。 */
                style={{ whiteSpace: 'pre-wrap' }}
              >
                {displayValue(row[key])}
              </Text>
            </VStack>
          ))}
        </VStack>
      </VStack>
    </VStack>
  );
}

/**
 * 主表 + 右侧可拖宽详情面板的骨架。
 *
 * ── 为什么是 Layout 的 end 槽而不是 Dialog / 抽屉 ──
 * 排查时的动作是「点一行看细节、再点下一行对比」。Dialog 会遮住主表，每次对比都要
 * 关掉再打开；右侧常驻面板可以一直开着连点好几行。这也是 LayoutPanel 文档里
 * "end slot for detail/inspector panels" 的原话用途。
 *
 * ── 窄视口 ──
 * 主表本来就横向紧张（SQL 列吃掉一半宽度），再从旁边切走 420px 会把它挤到不可读。
 * 所以用 useMediaQuery 在 1100px 以下把面板折到内容下方（一个普通 Card），
 * 而不是让 Layout 继续横排。断点取 1100 而不是常用的 768：这张表在 900px 上已经
 * 挤得没法看了，判据是主表还剩多少宽度，不是设备类别。
 *
 * ── Layout 一直渲染，只有 end 槽在变 ──
 * 「没选中行时直接渲染 children」会让选中/取消选中时整棵子树换形状，React 卸载重建，
 * 表格的列宽拖拽状态和滚动位置全丢。多两层 div 换掉这个，划得来。
 */
function DetailSplit({ detail, children }) {
  const isNarrow = useMediaQuery('(max-width: 1100px)');
  /* useResizable 的单区域配置用 defaultSize / minSize / maxSize（不是 SideNav 那套
     defaultWidth / minWidth / maxWidth——那是 ResizableConfig，两个不同的类型）。
     autoSaveId 让宽度落到 localStorage：调好一次的宽度不该在刷新后回到默认值。 */
  const panel = useResizable({
    defaultSize: 440,
    minSize: 300,
    maxSize: 820,
    direction: 'horizontal',
    autoSaveId: 'dbmcp-ui-row-detail-width',
  });

  if (isNarrow) {
    return (
      <VStack gap={4}>
        {children}
        {detail}
      </VStack>
    );
  }

  return (
    <Layout
      height="auto"
      padding={0}
      content={children}
      end={detail ? (
        <>
          {/* ResizeHandle 必须是 LayoutPanel 的兄弟节点：Layout 把 end 槽的内容整体
              放进横向 flex 容器，Fragment 里的两个元素就是相邻的两个 flex 子项。
              isReversed 因为拖动的是右侧面板——不反向的话往左拖会变宽。 */}
          <ResizeHandle resizable={panel.props} direction="horizontal" isReversed hasDivider />
          <LayoutPanel
            resizable={panel.props}
            /* hasDivider=false：ResizeHandle 已经画了一条 1px 分隔线，两个都开会出双线
               （LayoutPanel 的 .d.ts 里明确写了这个组合要注意）。 */
            isScrollable={false}
            padding={0}
            role="complementary"
            label="行详情"
          >
            {detail}
          </LayoutPanel>
        </>
      ) : undefined}
    />
  );
}

// =============================================================================
// `/` 聚焦搜索框
// =============================================================================

/*
 * 快捷键的注册点在 App（一个地方能看全所有快捷键），但输入框在 InteractiveTable 里，
 * 而且一页可能有多张表（性能页三张）。于是需要一条从 App 指到"当前页第一个搜索框"的路。
 *
 * 想过的两个方案，都不如这个：
 * - 每个 InteractiveTable 自己注册 '/'：useHotkeys 是每个实例一个 window 监听器，
 *   三张表就是三个都会响应，最后是最后挂载的那个抢到焦点——顺序还不稳定。
 * - 用 document.querySelector 找 input：搜索框和表头里的过滤输入框长得一样，选不准。
 *
 * 一个模块级的有序注册表，按挂载顺序取第一个。挂载顺序 = JSX 里的出现顺序 =
 * 页面上从上到下的顺序，所以"第一个"就是运维视觉上的第一个搜索框。
 */
const searchHandles = new Set();

function registerSearchHandle(handle) {
  searchHandles.add(handle);
  return () => { searchHandles.delete(handle); };
}

/** 聚焦当前页面上第一个搜索框。没有搜索框（连接页/服务信息页）时什么都不做。 */
export function focusFirstSearch() {
  const first = searchHandles.values().next().value;
  first?.current?.focusTypeahead?.();
}

// =============================================================================
// InteractiveTable
// =============================================================================

/**
 * 全文搜索用的合成字段。
 *
 * PowerSearch 的自由文本（contentSearchFieldKey）只能路由到<b>一个</b>字段上，
 * 而"全文搜索"要求跨列命中。所以给每行合成一个把所有值拼起来的字符串字段，
 * 让自由文本落在它身上——一次 contains 就等于跨全部列的子串匹配。
 *
 * 这个 key 以下划线开头，RowDetailBody 会把它从字段列表里过滤掉：它是实现细节，
 * 不是这条审计记录的一个属性。CSV 导出也不含它（列定义里没有这一列）。
 */
export const SEARCH_ALL_KEY = '_all';

/**
 * 每个 panel 的 searchFields 都必须<b>包含这一项</b>，而且要放在模块顶层的常量数组里。
 *
 * 为什么不由 InteractiveTable 自动拼进去：usePowerSearchConfig 是按 definitions 的
 * <b>引用</b> memo 的。在组件里写 [...searchFields, SEARCH_ALL_FIELD] 每次渲染都是新数组，
 * config 每次都重建，下游的 filterPlugin / 排序 memo 全部跟着失效——不是错，是白算。
 */
export const SEARCH_ALL_FIELD = { key: SEARCH_ALL_KEY, type: 'string', label: '全文' };

/** 成功/失败过滤用的枚举取值。和 resultColumn 派生出的字符串必须逐字一致。 */
export const RESULT_ENUM_VALUES = [
  { value: '成功', label: '成功' },
  { value: '失败', label: '失败' },
];

/** 把一行的所有值拼成一个可搜索的字符串。对象值走 JSON，否则 [object Object] 搜不到。 */
function searchBlob(row) {
  const parts = [];
  for (const key of Object.keys(row)) {
    const value = row[key];
    if (value === null || value === undefined) {
      continue;
    }
    parts.push(typeof value === 'object' ? JSON.stringify(value) : String(value));
  }
  return parts.join(' ');
}

/**
 * 一行的稳定标识。
 *
 * 必须只依赖行内容、<b>不能</b>依赖下标：详情面板是按 key 回查行的，而下标在排序/过滤
 * 之后就变了——用下标当 key 的话，排一次序详情面板就会指向另一行。
 */
export function contentRowKey(keys) {
  return (row) => keys.map((k) => String(row[k] ?? '')).join('\u0000');
}

/**
 * 行的着色 + 状态标记 plugin。
 *
 * ── 为什么两个信号一起给 ──
 * 只给底色：色觉障碍用户拿不到这个信息，而且截图发出去被压缩后淡色底几乎看不见。
 * 只给标记列：一眼扫下来的时候不够醒目，"一眼能看出来"这个要求达不到。
 * 所以 useTableRowStatus 出图形标记（它自己按主题解析成语义色），底色走 --color-background-*
 * token。两个都不写死颜色值。
 *
 * 底色为什么走内联 style 而不是 xstyle：xstyle 吃的是 StyleX 编译产物，而本项目的
 * vite 配置里没有 StyleX 插件（只有 @vitejs/plugin-react），运行期调 stylex.create
 * 得到的是一堆不存在的 class 名。内联 style 里引 var(--color-...) 是这个约束下
 * 唯一既能生效、又不写死颜色的写法。
 */
const ROW_TONE_BACKGROUND = {
  error: 'var(--color-background-red)',
  warning: 'var(--color-background-yellow)',
};

function useRowInteractionPlugin({ getRowKey, selectedKey, onSelect, getRowTone }) {
  return useMemo(() => {
    if (!getRowKey) {
      return undefined;
    }
    return {
      transformBodyRow(props, item) {
        const key = getRowKey(item);
        const tone = getRowTone ? getRowTone(item) : null;
        const isSelected = selectedKey != null && selectedKey === key;
        return {
          ...props,
          htmlProps: {
            ...props.htmlProps,
            'data-selected': isSelected ? 'true' : undefined,
            onClick: () => onSelect(key),
            style: {
              ...props.htmlProps?.style,
              cursor: 'pointer',
              backgroundColor: ROW_TONE_BACKGROUND[tone] ?? props.htmlProps?.style?.backgroundColor,
              /* 选中行用左边一条粗内阴影标出来，而不是换底色：换底色会盖掉上面那条
                 "这行失败了"的信息，而两件事需要同时看见。 */
              boxShadow: isSelected ? 'inset 3px 0 0 0 var(--color-border-blue)' : undefined,
            },
          },
        };
      },
    };
  }, [getRowKey, selectedKey, onSelect, getRowTone]);
}

/**
 * 带搜索 / 排序 / 过滤 / 行详情 / CSV 导出的表格分区。
 *
 * ── 三条过滤链路合并成一条 ──
 * 页面上有两个入口（顶部 PowerSearch 的 token、表头漏斗），但底下只有一个引擎：
 * usePowerSearchConfig 给的 applyFilters。表头的过滤状态用 core 的 toSearchFilters
 * 翻成同一种 PowerSearchFilter，两边拼起来一次过。
 * 官方文档管这个叫 "define filters once, apply everywhere"——照做的好处很实际：
 * 两个入口对"contains 是不是区分大小写"这类问题不可能给出不同答案。
 *
 * ── 顺序：先过滤再排序 ──
 * 反过来（先排序再过滤）结果一样但白排了一遍被过滤掉的行。数据量小无所谓，
 * 写成正确的顺序是因为 limit 可以调到 500。
 *
 * ── 为什么没有 comparators 这个 prop ──
 * 一开始每个 panel 都给数值列写了 `(a,b) => Number(a.x) - Number(b.x)`，读了
 * useTableSortableState 的实现之后全删了：它的 defaultCompare 已经做了三件正确的事——
 * 两边都是 number 时走减法快路径、null/undefined/NaN 一律排到末尾、其余走
 * Intl.Collator({numeric:true})。手写的那版反而更差：`Number(null) || 0` 会把空值
 * 当成 0 排到数字中间，看起来像"耗时 0ms"。需要非常规排序（比如按业务优先级）时
 * 再把这个 prop 加回来，现在没有这种列。
 *
 * ── 必须由调用方以模块级常量传入的 props ──
 * columns / searchFields / defaultSort：它们全都进了 memo 的依赖，
 * 在渲染里现造对象等于每次渲染都重算一遍全部过滤和排序。
 */
export function InteractiveTable({
  title,
  source,
  rows,
  columns,
  searchFields,
  searchName,
  searchPlaceholder = '搜索：直接输入关键字全文匹配，或先选字段再选条件',
  defaultSort,
  getRowKey,
  getRowTone,
  detailTitle,
  detailSqlKey,
  csvBaseName,
  emptyTitle,
  emptyDescription,
  beforeTable,
  extraActions,
}) {
  const all = useMemo(() => (Array.isArray(rows) ? rows : []), [rows]);

  // ── 搜索配置 ──
  const { config: baseConfig, applyFilters } = usePowerSearchConfig(searchFields, searchName);
  /* contentSearchFieldKey 不是 usePowerSearchConfig 的入参（它只按 FieldDefinition 建
     fields），所以在这里补上。补的是 PowerSearchConfig 上一个正式声明过的字段，
     不是私有属性。 */
  const config = useMemo(
    () => ({ ...baseConfig, contentSearchFieldKey: SEARCH_ALL_KEY }),
    [baseConfig],
  );

  const [searchFilters, setSearchFilters] = useState([]);
  const searchHandleRef = useRef(null);
  useEffect(() => registerSearchHandle(searchHandleRef), []);

  // ── 表头过滤 ──
  const { filters: columnFilters, onFilterChange, clearAll } = useTableFilterState();
  const filterPlugin = useTableFiltering({
    filters: columnFilters,
    onFilterChange,
    variant: 'popover',
    searchConfig: config,
  });

  // ── 过滤 → 排序 ──
  const searchable = useMemo(
    () => all.map((row) => ({ ...row, [SEARCH_ALL_KEY]: searchBlob(row) })),
    [all],
  );
  const filtered = useMemo(
    () => applyFilters(
      [...searchFilters, ...toSearchFilters(columnFilters, columns, config)],
      searchable,
    ),
    [applyFilters, searchFilters, columnFilters, columns, config, searchable],
  );
  const { sortedData, sortConfig } = useTableSortableState({
    data: filtered,
    defaultSort,
  });
  const sortPlugin = useTableSortable(sortConfig);

  // ── 行详情 ──
  const [selectedKey, setSelectedKey] = useState(null);
  const selectedRow = useMemo(() => {
    if (selectedKey == null || !getRowKey) {
      return null;
    }
    /* 在未过滤的全量里回查，而不是在 sortedData 里：面板开着的时候改搜索条件，
       详情不该跟着消失——运维正在照着它读的那一行，被列表过滤掉不代表他看完了。 */
    return searchable.find((row) => getRowKey(row) === selectedKey) ?? null;
  }, [searchable, selectedKey, getRowKey]);

  /* Esc 关面板注册在这里而不是 App：一页多张表时，每张表各自关掉自己的面板，
     语义正好是"关掉打开着的详情面板"。放到 App 反而要维护一份"谁开着"的登记。
     allowInInputs 保持默认 false —— 在搜索框里按 Esc 应该由输入框自己处理
     （清空 / 关下拉），而不是顺带把旁边的面板也关了。 */
  useHotkeys([{ keys: 'escape', onPress: () => setSelectedKey(null) }]);

  const rowPlugin = useRowInteractionPlugin({
    getRowKey,
    selectedKey,
    onSelect: setSelectedKey,
    getRowTone,
  });

  /* useTableRowStatus 的 getStatus 要求 useCallback 包一下才有稳定的 plugin 身份。
     没有 getRowTone 时也得调这个 hook（hook 不能有条件地调），返回 null 让它不画标记。 */
  const getStatus = useCallback(
    (item) => {
      const tone = getRowTone ? getRowTone(item) : null;
      if (tone === 'error') {
        return { status: 'error', label: '失败' };
      }
      if (tone === 'warning') {
        return { status: 'warning', label: '超过阈值' };
      }
      return null;
    },
    [getRowTone],
  );
  const rowStatusPlugin = useTableRowStatus({ getStatus });

  const plugins = useMemo(() => {
    /*
     * 键名不只是标识，core 会按它<b>重排</b>执行顺序。
     *
     * useBaseTablePlugins 里有一份写死的 canonical order：
     * ['columnSettings','sort','tree','selection','pagination']。不在这份名单里的
     * 键名（我们的 rowStatus / filter / row 都不在）被追加到已知插件之后，
     * 彼此之间保持这里的插入顺序。
     *
     * 也就是说：想靠"我把 rowStatus 写在第一个"来控制它先跑，是<b>无效</b>的——
     * sort 会被提到它前面。这里不需要那个顺序（只有 rowStatus 会 transformColumns，
     * 而 BaseTable 是先跑完所有插件的 transformColumns 再处理表头单元格的），
     * 所以现状是对的；但如果以后加了第二个会插列的插件，得回来看这一段。
     *
     * 键名也别乱改：写成 'sorting' 而不是 'sort' 就会掉出 canonical order，
     * 而且不报错。
     */
    const map = {};
    if (getRowTone) {
      map.rowStatus = rowStatusPlugin;
    }
    map.filter = filterPlugin;
    map.sort = sortPlugin;
    if (rowPlugin) {
      map.row = rowPlugin;
    }
    return map;
  }, [getRowTone, rowStatusPlugin, filterPlugin, sortPlugin, rowPlugin]);

  const hasColumnFilter = Object.values(columnFilters).some((v) => v != null);

  const table = (
    <Section
      title={title}
      source={source}
      count={sortedData.length}
      totalCount={all.length}
      actions={
        <>
          {extraActions}
          {(searchFilters.length > 0 || hasColumnFilter) && (
            <Button
              label="清空筛选"
              variant="ghost"
              onClick={() => { setSearchFilters([]); clearAll(); }}
            />
          )}
          {csvBaseName && (
            <ExportCsvButton columns={columns} rows={sortedData} baseName={csvBaseName} />
          )}
        </>
      }
    >
      <VStack gap={4}>
        <PowerSearch
          config={config}
          filters={searchFilters}
          /* onChange 的后两个参数（changeType、index）这里用不上：过滤是整体重算的，
             不需要知道是哪一个 token 变了。 */
          onChange={(next) => setSearchFilters([...next])}
          label={`搜索${title}`}
          placeholder={searchPlaceholder}
          resultCount={`${sortedData.length} 行`}
          handleRef={searchHandleRef}
        />
        {beforeTable}
        <DataTable
          columns={columns}
          rows={sortedData}
          idKey={getRowKey}
          plugins={plugins}
          emptyTitle={
            sortedData.length === 0 && all.length > 0 ? '当前筛选条件没有命中任何行' : emptyTitle
          }
          emptyDescription={
            sortedData.length === 0 && all.length > 0
              ? '清空筛选可以看到全部 ' + all.length + ' 行。'
              : emptyDescription
          }
        />
      </VStack>
    </Section>
  );

  if (!detailTitle || !getRowKey) {
    return table;
  }

  return (
    <DetailSplit
      detail={selectedRow ? (
        <Card padding={0}>
          <VStack gap={0}>
            <HStack gap={3} padding={4} vAlign="center" wrap="wrap">
              <Text type="label" weight="semibold">{detailTitle}</Text>
              <StackItem size="fill" />
              <Button
                label="关闭"
                variant="ghost"
                tooltip="Esc"
                onClick={() => setSelectedKey(null)}
              />
            </HStack>
            <Divider />
            <VStack padding={4}>
              <RowDetailBody row={selectedRow} sqlKey={detailSqlKey} columns={columns} />
            </VStack>
          </VStack>
        </Card>
      ) : null}
    >
      {table}
    </DetailSplit>
  );
}
