/*
 * 面板之间共用的显示件：错误横幅、指标卡片、分区、数据表格、可交互表格、行详情面板、
 * CSV 导出、Markdown 下载、sparkline。
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
  CheckboxList,
  CheckboxListItem,
  Divider,
  EmptyState,
  Grid,
  HStack,
  Heading,
  Popover,
  PowerSearch,
  RadioList,
  RadioListItem,
  StackItem,
  StatusDot,
  Table,
  Text,
  TextInput,
  Timestamp,
  VStack,
  Layout,
  LayoutPanel,
  ResizeHandle,
  paginateData,
  pixel,
  proportional,
  toSearchFilters,
  useClipboard,
  useHotkeys,
  useMediaQuery,
  usePowerSearchConfig,
  useResizable,
  useTableColumnResize,
  useTableColumnSettings,
  useTableColumnSettingsState,
  useTableFiltering,
  useTableGroupedRows,
  useTablePagination,
  useTableRowStatus,
  useTableSortable,
  useTableSortableState,
  useTheme,
} from '@astryxdesign/core';
import { registerSearchHandle } from './searchFocus.js';
import { tableStorageKey, usePersistentState } from './persist.js';
import {
  encodeRowRef,
  readHashParams,
  rowKeyForTable,
  rowRefFromKey,
  writeHashParams,
} from './hashState.js';
import {
  mergeViews,
  newUserViewId,
  readUserViews,
  writeUserViews,
} from './savedViews.js';

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
 * \ufeff（BOM）对 CSV 不是可有可无的：没有它，Excel（Windows 简中默认 GBK）打开这份 UTF-8
 * 文件会把所有中文表头显示成乱码。导出功能的实际用途就是发给别人用 Excel 打开，
 * 所以这三个字节比"文件更干净"重要。
 *
 * 反过来 Markdown 一律<b>不加</b> BOM：那份文件的用途是粘给模型或进 git，而 BOM 会变成正文的
 * 第一个字符（\ufeff 不是空白，trim 不掉），表现为"第一个 # 标题不生效"或者 diff 里一个看不见
 * 的改动。所以 bom 是调用方必须想清楚的参数，没有默认值。
 */
function downloadTextFile(filename, text, mimeType, bom) {
  const blob = new Blob(bom ? [`\ufeff${text}`] : [text], { type: mimeType });
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
function stampedFileName(base, extension) {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  return `${base}-${stamp}.${extension}`;
}

/**
 * 「下载 .md」按钮。给「一大段可直接粘贴的 Markdown」用（目前只有工具提示词）。
 *
 * 和 ExportCsvButton 分成两个件而不是加一个 format 参数：那个件的入参是 columns + rows
 * （表格语义），这个件的入参是一整段文本，两者除了"都会触发浏览器下载"之外没有共同点。
 * 共用的只有下载与文件名这两个私有函数。
 */
export function DownloadMarkdownButton({ text, baseName, label = '下载 .md', isDisabled }) {
  const onDownload = useCallback(() => {
    downloadTextFile(stampedFileName(baseName, 'md'), text, 'text/markdown;charset=utf-8;', false);
  }, [text, baseName]);
  return (
    <Button
      label={label}
      variant="secondary"
      isDisabled={isDisabled || !text}
      onClick={onDownload}
    />
  );
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
    downloadTextFile(stampedFileName(baseName, 'csv'), toCsv(columns, rows),
      'text/csv;charset=utf-8;', true);
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
 * 「复制」按钮。SQL 原文、mermaid / dot 图文本都用它。
 *
 * useClipboard 的 copy() 在被拒绝时是<b>静默</b>返回 false 的（见它的 .d.ts：
 * "A clipboard rejection is a silent no-op"）。这一页的部署形态几乎保证会撞上这件事：
 * 内网面板通常跑在 http:// 上，而 navigator.clipboard 只在 secure context 下可用，
 * 所以在 http 页面里这个按钮点了不会有任何反应。
 *
 * 静默失败是最坏的结果——运维会以为复制成功了，粘贴出来是上一次剪贴板的内容。
 * 所以这里必须自己接住 false，并且给出可执行的回退指引（配套显示的文本一律是可选中的纯文本）。
 *
 * 这个件原来叫 CopySqlButton、写死了 SQL 的文案，是详情面板的私有实现。血缘页要复制的是
 * 图文本、SQL 体检页要复制的是 EXPLAIN 语句，三处对「复制失败要说什么」必须给出同一个答案，
 * 所以把它抽出来导出，文案参数化。不抽的下场是三份各自漂移的失败提示。
 */
export function CopyTextButton({ text, label = '复制', copiedLabel = '已复制', what = '内容' }) {
  const { copy, isCopied } = useClipboard({ announce: `已复制${what}` });
  const [hasFailed, setFailed] = useState(false);

  const onCopy = useCallback(() => {
    setFailed(false);
    copy(text).then((ok) => { if (!ok) { setFailed(true); } });
  }, [copy, text]);

  return (
    <VStack gap={1}>
      <Button
        label={isCopied ? copiedLabel : label}
        variant="secondary"
        isDisabled={!text}
        onClick={onCopy}
      />
      {hasFailed && (
        /* Badge + 说明而不是 Text color="error"：0.6.2 的 TextColor 里根本没有 'error'
           这个成员（只有 primary/secondary/disabled/placeholder/accent/inherit），
           传了会得到一个不存在的 class，字反而变成默认色——错误提示看不出是错误。 */
        <HStack gap={2} vAlign="center" wrap="wrap">
          <Badge variant="error" label="复制失败" />
          <Text type="supporting" color="secondary">
            {`浏览器拒绝了剪贴板写入（http:// 页面不是 secure context，clipboard API 不可用）。`
              + `请直接选中下面的${what}复制。`}
          </Text>
        </HStack>
      )}
    </VStack>
  );
}

/**
 * 等宽原文块：SQL 原文、执行计划、mermaid / dot 图文本。
 *
 * 用 <pre> 而不是 core 的 Code / CodeBlock：
 * - Code 是行内件，自带 muted 底色和内边距，长文本套进去会变成一个撑满的灰块；
 *   而且它的 white-space 由组件自己的 class 决定，不能保证保留换行。
 * - CodeBlock 有语法高亮，但它要求一个 language，而这里要显示的东西横跨 SQL、
 *   mermaid、dot 和「方言原样吐出来的执行计划文本」四种，其中后两种它都不认；
 *   猜错 language 的结果是把随机的词染成关键字色，比不染更难读。
 *
 * 这里要的是「原文一个字符都不改」，所以自己控制 white-space: pre-wrap，
 * 只从 token 里借等宽字体（--font-family-code），不写死字体名。
 * overflowX auto 而不是 hidden：dot 的一行可以很长，能横向滚总比被裁掉好。
 */
export function MonoBlock({ text, emptyText = '（没有内容）', maxHeight = 420 }) {
  const value = typeof text === 'string' && text !== '' ? text : null;
  return (
    <Card padding={3}>
      <pre
        style={{
          margin: 0,
          fontFamily: 'var(--font-family-code)',
          fontSize: 'inherit',
          whiteSpace: 'pre-wrap',
          overflowWrap: 'anywhere',
          overflowX: 'auto',
          maxHeight,
          overflowY: 'auto',
        }}
      >
        {value ?? emptyText}
      </pre>
    </Card>
  );
}

/**
 * 详情面板的内容：完整 SQL 原文 + 该行所有字段的键值对。
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
            <CopyTextButton
              text={typeof sql === 'string' ? sql : ''}
              label="复制 SQL"
              what="SQL"
            />
          </HStack>
          <MonoBlock
            text={typeof sql === 'string' ? sql : ''}
            emptyText="（这一行没有 SQL 原文）"
          />
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
 * 注册表本身搬到了 ./searchFocus.js，这里只是把它再导出一遍给 panel 用。
 *
 * 搬走的理由是懒加载：App.jsx 需要 focusFirstSearch（'/' 快捷键在那里注册），
 * 而 App 一旦 import 本文件，Vite 就会把整个 components.jsx 连带它依赖的
 * Table / PowerSearch / Timestamp 那一片 core 拉进入口的静态依赖图，并给
 * index.html 加上 modulepreload —— 首屏又要下载那 370 KB，视图级懒加载白做。
 * 详细说明在 ./searchFocus.js 的头注释里。
 *
 * 这里保留 re-export 是为了不动 16 个 panel 的 import：它们要的是「从 components
 * 拿显示件」这一个来源。
 */
export { focusFirstSearch } from './searchFocus.js';

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

/**
 * 判断一行是不是 useTableGroupedRows 合成出来的<b>组头行</b>。
 *
 * ── 为什么需要这个判断 ──
 * 分组打开后，Table 的 data 里混进了组头行。组头行必须被本文件里两个东西跳过：
 * - 行点击/选中（点组头应该是折叠这一组，不是打开一个不存在的"行详情"）；
 * - rowStatus 的 getStatus（组头没有 success 字段，会被算成"失败"标一个红标记）。
 *
 * ── 判据是怎么定的（读了 core 的实现，不是猜的）──
 * dist/Table/plugins/groupedRows/useTableGroupedRows.js 里，组头行是
 * `new Proxy({[GROUP_HEADER]:true, groupKey, count}, handler)`，其中 GROUP_HEADER 是一个
 * <b>模块私有的 Symbol</b>——外面拿不到，所以不能直接查那个标记。
 * 但那个 Proxy 的 get 陷阱写得很明确：只有 GROUP_HEADER / groupKey / count 三个键返回
 * 真值，<b>其余一切键返回空字符串</b>（注释里说这是为了让排序和过滤插件不读到 undefined）。
 * 于是「同时有字符串 groupKey 和数字 count」这个组合只有组头行满足。
 *
 * 两个条件都要检查，不能只看 count：SQL 模式统计那张表真有一列叫 count（次数），
 * 只判 count 会把它的每一行都当成组头。而 groupKey 这个键名在本项目所有数据里都不存在。
 */
function isGroupHeaderRow(item) {
  return item !== null
    && typeof item === 'object'
    && typeof item.groupKey === 'string'
    && typeof item.count === 'number';
}

function useRowInteractionPlugin({ getRowRef, selectedRef, onSelect, getRowTone }) {
  return useMemo(() => {
    if (!getRowRef) {
      return undefined;
    }
    return {
      transformBodyRow(props, item) {
        /* 组头行原样放过：它的点击行为（折叠该组）由 grouped 插件自己接，
           在这里再挂一个 onClick 会把两件事叠在一起。 */
        if (isGroupHeaderRow(item)) {
          return props;
        }
        const key = getRowRef(item);
        const tone = getRowTone ? getRowTone(item) : null;
        const isSelected = selectedRef != null && selectedRef === key;
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
  }, [getRowRef, selectedRef, onSelect, getRowTone]);
}

/*
 * 下面这几个空值都是<b>模块级常量</b>，不是随手写的字面量。
 *
 * 它们全都进了 useState 的初值或 usePersistentState 的 fallback，而后者又进了
 * useCallback / useMemo 的依赖。写成内联的 {} / [] / new Set() 的话每次渲染都是新引用，
 * 依赖比较永远不相等，下游那一串 memo（过滤、排序、分组、插件身份）全部白设。
 * 这和文件头「columns / searchFields / defaultSort 必须由调用方以模块级常量传入」
 * 是同一条纪律，只是这几个是本文件自己的。
 */
const EMPTY_COLUMN_FILTERS = {};
const EMPTY_COLUMN_WIDTHS = {};
const EMPTY_SORT = [];
const EMPTY_ROWS = [];
const EMPTY_COLLAPSED = new Set();

/**
 * 每页显示多少行的默认值与可选档位。
 *
 * ⚠️ 这和顶栏的「条数」是<b>两件不同的事</b>，而且是这一项最容易被误读的地方：
 * - 顶栏「条数」= limit = <b>后端返回多少条</b>（进 URL 查询串，改它会重新发请求）；
 * - 这里的每页条数 = <b>前端把已经拿到的这些行分几页显示</b>（纯前端切片，不发请求）。
 * 翻到最后一页 ≠ 看到了全部数据 —— 看到的是"后端给的这 limit 条的最后一页"。
 * 所以表格下方必须有一句话把这个关系写出来（见 PAGE_RANGE 那段 JSX），
 * 不写的话一定会有人拿"翻到底了"当成"数据就这么多"。
 *
 * 默认 25 而不是 core 的默认 10：这些表是用来"扫"的，一屏 10 行会让翻页变成主要动作。
 * 25 行大约是一屏能看完又不用滚太多的量。
 */
const DEFAULT_PAGE_SIZE = 25;
const PAGE_SIZE_OPTIONS = [10, 25, 50, 100];

/**
 * 一行在某个分组字段上的组值。
 *
 * 空值折成「（空）」而不是空字符串：空字符串会让组头变成一条只有数字的行，
 * 而"这一组是连接名为空的记录"本身是个有意义的信息（比如工具调用没带连接名）。
 */
function groupValueOf(row, key) {
  const raw = row?.[key];
  if (raw === null || raw === undefined || raw === '') {
    return '（空）';
  }
  return String(raw);
}

/**
 * 组头的内容。
 *
 * core 的默认渲染是 `<groupKey> (<count>)`，够用但是英文括号风格，
 * 而且条数没有单位。这里给成「<组值> · N 条」，和页面其他地方的中文口径一致。
 * 条数是这个组头存在的主要理由之一（第 5 项明确要求显示），所以它不是装饰，
 * 用 Badge 让它和组名在视觉上分开——组名是标签，条数是随筛选变化的读数。
 */
function renderGroupHeaderContent(groupKey, count) {
  return (
    <HStack gap={2} vAlign="center" wrap="wrap">
      <Text type="label" weight="semibold">{groupKey}</Text>
      <Badge variant="neutral" label={`${count} 条`} />
    </HStack>
  );
}

/*
 * =============================================================================
 * 表头带上的三个控件：保存的视图 / 分组 / 列显隐
 * =============================================================================
 *
 * 三个都做成 Popover 里的一小块表单，而不是常驻在标题带上：
 * 标题带已经有「清空筛选」和「导出 CSV」两个按钮，再平铺三组控件会让标题带比表格还高。
 * Popover 的触发器是一个按钮，点开才占空间——这三件事都是"偶尔调一次"的。
 *
 * 都不传 size：那是控件高度不是字号（口径见 ExportCsvButton 的说明）。
 */

/**
 * 保存的视图（第 6 项）。
 *
 * 列表里预设在前、用户的在后（顺序由 savedViews.mergeViews 决定）。
 * 删除按钮<b>只</b>出现在用户视图上：预设是模块级常量，压根不在 localStorage 里，
 * 所以"不可删"不是靠这里的 isBuiltIn 判断挡住的——那个判断只是别画一个点了没用的按钮。
 */
function SavedViewsControl({ views, onApply, onSave, onDelete, title }) {
  const [isOpen, setOpen] = useState(false);
  const [draftName, setDraftName] = useState('');

  const save = () => {
    onSave(draftName);
    setDraftName('');
    setOpen(false);
  };

  return (
    <Popover
      isOpen={isOpen}
      onOpenChange={setOpen}
      label={`${title} 的保存视图`}
      placement="below"
      width={340}
      content={
        <VStack gap={4} padding={4}>
          <VStack gap={2}>
            <Text type="label" weight="semibold">切换到</Text>
            {views.length === 0
              ? <Text type="supporting" color="secondary">还没有可用的视图。</Text>
              : views.map((view) => (
                <HStack key={view.id} gap={2} vAlign="center" wrap="wrap">
                  <Button
                    label={view.name}
                    variant="ghost"
                    onClick={() => { onApply(view.snapshot); setOpen(false); }}
                  />
                  {view.isBuiltIn
                    ? <Badge variant="info" label="内置" />
                    : (
                      <Button
                        label="删除"
                        variant="ghost"
                        onClick={() => onDelete(view.id)}
                      />
                    )}
                </HStack>
              ))}
          </VStack>
          <Divider />
          <VStack gap={2}>
            <Text type="label" weight="semibold">把当前现场存成新视图</Text>
            <Text type="supporting" color="secondary">
              会记下当前的筛选条件、排序、分组和列显隐；存在这台浏览器的 localStorage 里，
              换机器不会带过去。
            </Text>
            <TextInput
              label="视图名"
              value={draftName}
              placeholder="例如：昨晚那批失败"
              onChange={setDraftName}
            />
            <HStack gap={2} wrap="wrap">
              <Button
                label="保存"
                variant="primary"
                isDisabled={draftName.trim() === ''}
                onClick={save}
              />
            </HStack>
          </VStack>
        </VStack>
      }
    >
      <Button label="视图" variant="secondary" />
    </Popover>
  );
}

/**
 * 分组字段选择（第 5 项）。默认「不分组」，它是列表里的第一项而不是一个"关闭"按钮——
 * 三选一（不分组 / 按工具 / 按连接）用一组单选比"一个开关加一个下拉"少一层状态。
 *
 * RadioList 要求 name：一页可能有多张表各带一个分组选择器，name 撞了之后
 * 原生 radio 的分组语义会把两张表的选项串成一组（点这张表的选项会取消另一张表的）。
 * 所以 name 里带表名。
 */
function GroupByControl({ fields, value, onChange, title }) {
  const [isOpen, setOpen] = useState(false);
  const current = value ?? '';
  const currentLabel = fields.find((f) => f.key === value)?.label;

  return (
    <Popover
      isOpen={isOpen}
      onOpenChange={setOpen}
      label={`${title} 的分组方式`}
      placement="below"
      width={300}
      content={
        <VStack gap={3} padding={4}>
          <RadioList
            name={`group-by-${title}`}
            label="按什么分组"
            value={current}
            onChange={(next) => { onChange(next === '' ? null : next); setOpen(false); }}
          >
            <RadioListItem value="" label="不分组" description="默认。按当前排序平铺。" />
            {fields.map((f) => (
              <RadioListItem
                key={f.key}
                value={f.key}
                label={`按${f.label}`}
                description="组头显示该组条数，点组头折叠。"
              />
            ))}
          </RadioList>
          <Text type="supporting" color="secondary">
            分组作用在<b>当前这一页</b>的行上（顺序是：筛选 → 排序 → 按组值重排 → 切页 → 分组），
            所以每一页各自成组，组头里的条数是本页该组的条数。
          </Text>
        </VStack>
      }
    >
      <Button
        label={currentLabel ? `分组：${currentLabel}` : '分组'}
        variant="secondary"
      />
    </Popover>
  );
}

/**
 * 列显隐（第 4 项）。
 *
 * 用 CheckboxList 的 collection 模式（value/onChange 收一个 string[]），
 * 直接对上 useTableColumnSettingsState.setActiveColumnKeys —— 它的 .d.ts 里明确写了
 * 这个方法「Useful as an onChange handler for any list-based column picker」，
 * 而且会强制保留 isAlwaysVisible 的列（所以不需要在这里再挡一次）。
 *
 * 「恢复默认」同时清列显隐和列宽：这两件事在用户眼里是一件事（"把这张表恢复原样"），
 * 分成两个按钮的话，只点一个的人会以为没生效。
 */
function ColumnSettingsControl({
  options,
  activeKeys,
  onChange,
  onShowAll,
  onReset,
  hasCustomWidths,
  title,
}) {
  const [isOpen, setOpen] = useState(false);
  const hiddenCount = options.length - activeKeys.length;

  return (
    <Popover
      isOpen={isOpen}
      onOpenChange={setOpen}
      label={`${title} 的列设置`}
      placement="below"
      width={300}
      content={
        <VStack gap={3} padding={4}>
          <CheckboxList
            label="显示哪些列"
            description="第一列锁定，不能隐藏。拖列边可以调宽度。"
            value={[...activeKeys]}
            onChange={onChange}
          >
            {options.map((opt) => (
              <CheckboxListItem
                key={opt.key}
                value={opt.key}
                label={opt.label}
                isDisabled={opt.isAlwaysVisible}
                description={opt.isAlwaysVisible ? '始终显示' : undefined}
              />
            ))}
          </CheckboxList>
          <HStack gap={2} wrap="wrap">
            <Button label="全选" variant="ghost" onClick={onShowAll} />
            <Button label="恢复默认" variant="ghost" onClick={onReset} />
          </HStack>
          <Text type="supporting" color="secondary">
            {'列显隐与列宽按表分别记在这台浏览器的 localStorage 里'}
            {hasCustomWidths ? '（当前有自定义列宽）' : ''}
            {'；「恢复默认」会同时清掉这张表的列显隐与列宽。'}
          </Text>
        </VStack>
      }
    >
      <Button
        label={hiddenCount > 0 ? `列（隐藏 ${hiddenCount}）` : '列'}
        variant="secondary"
      />
    </Popover>
  );
}

/**
 * 带搜索 / 排序 / 过滤 / 分组 / 分页 / 列显隐 / 行详情 / CSV 导出的表格分区。
 *
 * ── 三条过滤链路合并成一条 ──
 * 页面上有两个入口（顶部 PowerSearch 的 token、表头漏斗），但底下只有一个引擎：
 * usePowerSearchConfig 给的 applyFilters。表头的过滤状态用 core 的 toSearchFilters
 * 翻成同一种 PowerSearchFilter，两边拼起来一次过。
 * 官方文档管这个叫 "define filters once, apply everywhere"——照做的好处很实际：
 * 两个入口对"contains 是不是区分大小写"这类问题不可能给出不同答案。
 *
 * ── 数据流的顺序（这一版新增了两步，顺序是有讲究的）──
 *   全量 → 合成全文字段 → 过滤 → 排序 → 按组值稳定重排 → 切页 → 分组
 * 「先过滤再排序」：反过来结果一样但白排了一遍被过滤掉的行（limit 可以调到 500）。
 * 「排序之后才按组值重排」：见 groupOrdered 那段，Array.sort 的稳定性让组内保持用户的排序。
 * 「切页在分组之前」：useTableGroupedRows 的文档要求的顺序（filter, sort, slice, then group），
 * 反过来会让组头被切到别的页去。
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
 * columns / searchFields / defaultSort / groupFields：它们全都进了 memo 的依赖，
 * 在渲染里现造对象等于每次渲染都重算一遍全部过滤和排序。
 *
 * ── searchName 兼作表标识 ──
 * 它本来就每张表唯一（'audit-logs' / 'slow-queries' / 'sql-patterns' …），
 * 于是列显隐、列宽、保存的视图三处的 localStorage key，以及 hash 里 row 的表前缀，
 * 全部用它。<b>改一个表的 searchName 等于让那张表的用户设置全部失效</b>（不报错，
 * 只是回到默认），所以别为了"名字更好看"去改它。
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
  /** 可分组的字段，形如 [{key:'tool', label:'工具名'}]。不传就没有分组 UI。 */
  groupFields,
}) {
  const tableId = searchName;
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

  /*
   * ── 表头过滤 ──
   *
   * 刻意<b>不</b>用 core 的 useTableFilterState，改成自己拿 useState。
   * 理由只有一个而且很硬：useTableFilterState 只暴露 {filters, onFilterChange, clearAll}，
   * 没有整体 setter（见它的 .d.ts）。而第 6 项「保存的视图」要做的事就是把一整份
   * filters 恢复回去——用 onFilterChange 逐个键调用能凑出来，但那是 N 次 setState，
   * 中间态会各触发一次全量过滤，而且删键（value=null）和加键的顺序还得自己排。
   * useTableFilterState 自己的文档说它是「useState 加一个类型正确的 onFilterChange 的
   * 便利封装」，所以这里不是绕过什么机制，就是把那层便利自己写一遍、多给一个 setter。
   */
  const [columnFilters, setColumnFilters] = useState(EMPTY_COLUMN_FILTERS);
  const onFilterChange = useCallback((key, value) => {
    setColumnFilters((prev) => {
      const next = { ...prev };
      if (value === null || value === undefined) {
        /* 删键而不是留一个 null：hasColumnFilter 和快照序列化都按"键存在即有过滤"读，
           留 null 会让「清掉了这一列」显示成「还有筛选」。 */
        delete next[key];
      } else {
        next[key] = value;
      }
      return next;
    });
  }, []);
  const clearAllColumnFilters = useCallback(() => setColumnFilters(EMPTY_COLUMN_FILTERS), []);

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
  /*
   * 排序也改成<b>受控</b>（sort + onSortChange），而不是原来的 defaultSort 非受控模式。
   * 同样是第 6 项要求的：快照要能把排序存下来再恢复，非受控模式下外面拿不到也改不了它。
   * defaultSort 现在当受控 state 的初值用——各 panel 传进来的仍是模块级常量，
   * 所以 useState 的惰性初值只在挂载时取一次，语义没变。
   */
  const [sortState, setSortState] = useState(() => defaultSort ?? EMPTY_SORT);
  const { sortedData, sortConfig } = useTableSortableState({
    data: filtered,
    sort: sortState,
    onSortChange: setSortState,
  });
  const sortPlugin = useTableSortable(sortConfig);

  // ── 分组（第 5 项）──
  /*
   * 默认不分组（groupKey = null）。分组是一个"我现在想按工具看"的临时视角，
   * 默认打开会让第一眼看到的不是最新的记录而是一堆组头。
   * groupFields 没传（大多数表）时下面这些全是空转，UI 也不出现。
   */
  const [groupKey, setGroupKey] = useState(null);
  const [collapsedGroups, setCollapsedGroups] = useState(EMPTY_COLLAPSED);
  const onToggleGroup = useCallback((key) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }, []);

  const groupBy = useCallback(
    (row) => (groupKey ? groupValueOf(row, groupKey) : ''),
    [groupKey],
  );

  /*
   * 分组打开时，先按组值做一次<b>稳定</b>重排。
   *
   * useTableGroupedRows 的文档明确写了这个要求：「grouping runs on the rows it is handed,
   * so the order is filter, sort, slice, then group」，并且要求先按组键排、再按用户的键排
   * （相当于后端的 ORDER BY group, sort）。不这么做的话，同一个组的行不连续，
   * 每一页都会撒着好几个组头，翻页时组头还会重复出现。
   *
   * 这里没有"先按组再按用户键"地重写比较器，而是在<b>已经排好序</b>的 sortedData 上
   * 只按组值再排一次 —— Array.prototype.sort 在 ES2019 起保证稳定，
   * 所以组内顺序就是用户选的排序。少写一个双键比较器，结果一样。
   */
  const groupOrdered = useMemo(() => {
    if (!groupKey) {
      return sortedData;
    }
    const collator = new Intl.Collator(undefined, { numeric: true });
    return [...sortedData].sort(
      (a, b) => collator.compare(groupValueOf(a, groupKey), groupValueOf(b, groupKey)),
    );
  }, [sortedData, groupKey]);

  // ── 分页（第 3 项）──
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  const totalItems = groupOrdered.length;
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
  /*
   * 当前页夹到 [1, totalPages]。
   *
   * 必须有：在第 8 页上加一个筛选条件把结果砍到 12 行，page 还是 8，
   * paginateData 会切出一个空数组——页面变成"没有命中任何行"，而实际上有 12 行。
   * 这是分页最常见的一个 bug，而且它长得和"筛过头了"一模一样。
   *
   * 夹取在渲染期算（safePage），回写 state 放在 effect 里：渲染期 setState 会多一次
   * 渲染，而且 React 会警告。
   */
  const safePage = Math.min(Math.max(page, 1), totalPages);
  useEffect(() => {
    if (page !== safePage) {
      setPage(safePage);
    }
  }, [page, safePage]);

  const pageRows = useMemo(
    () => paginateData(groupOrdered, safePage, pageSize),
    [groupOrdered, safePage, pageSize],
  );

  const paginationPlugin = useTablePagination({
    page: safePage,
    onPageChange: setPage,
    totalItems,
    pageSize,
    onPageSizeChange: (next) => {
      setPageSize(next);
      /* 换每页条数就回到第一页：不回的话「第 8 页」在新的条数下指向的是完全不同的数据，
         用户以为自己只改了密度。 */
      setPage(1);
    },
    pageSizeOptions: PAGE_SIZE_OPTIONS,
    variant: 'pages',
    position: 'below',
    align: 'start',
    /* label 带上表名：性能页有两张 InteractiveTable，两个 nav landmark 同名的话
       屏幕阅读器读出来分不清是哪张表的分页。 */
    label: `${title} 分页`,
    /* 刻意不传 size：那是控件高度（sm/md）不是字号，口径同 ExportCsvButton 的说明。 */
  });

  /* 分组之后再交给 Table。不分组时不喂数据给这个 hook（hook 不能有条件地调）。 */
  const grouped = useTableGroupedRows({
    data: groupKey ? pageRows : EMPTY_ROWS,
    groupBy,
    collapsedGroups,
    onToggleGroup,
    getRowKey,
    renderGroupHeader: renderGroupHeaderContent,
  });

  // ── 列显隐 + 列宽持久化（第 4 项）──
  const columnOptions = useMemo(
    () => columns.map((col, index) => ({
      key: col.key,
      label: col.header ?? col.key,
      /* 第一列锁死不可隐藏：全部列都能关掉的话，用户可以把表变成一张空白，
         而且下一次打开还是空白（状态是持久化的）——那种状态看不出是自己造成的。
         第一列在各表都是主键或时间，恰好也是最该留着的那一列。 */
      isAlwaysVisible: index === 0,
    })),
    [columns],
  );
  const defaultColumnKeys = useMemo(() => columns.map((col) => col.key), [columns]);

  /*
   * 列显隐按<b>表</b>持久化，key 里带表标识（dbmcp-ui:cols:<searchName>）。
   * 全局一份的后果：在审计流水里藏掉 SQL 列，慢查询的 SQL 列也跟着消失——
   * 两张表的列集合本来就不同，这种串味没有任何合理解释。
   *
   * 校验函数把存下来的键和当前列定义对一遍：列被改名/删掉之后，
   * 一个不存在的 key 塞给 Table 会渲染出一列空白，而且不报错。
   */
  const validateColumnKeys = useCallback(
    (stored) => {
      if (!Array.isArray(stored)) {
        return null;
      }
      const known = new Set(defaultColumnKeys);
      const kept = stored.filter((k) => typeof k === 'string' && known.has(k));
      const always = columnOptions.filter((o) => o.isAlwaysVisible).map((o) => o.key);
      for (const key of always) {
        if (!kept.includes(key)) {
          kept.unshift(key);
        }
      }
      return kept.length > 0 ? kept : null;
    },
    [defaultColumnKeys, columnOptions],
  );
  const [activeColumnKeys, setActiveColumnKeys, resetColumnKeys] = usePersistentState(
    tableStorageKey('cols', tableId),
    defaultColumnKeys,
    validateColumnKeys,
  );

  const columnSettingsState = useTableColumnSettingsState({
    columns: columnOptions,
    activeColumnKeys,
    onChangeActiveColumnKeys: setActiveColumnKeys,
    defaultColumnKeys,
  });
  const columnSettingsPlugin = useTableColumnSettings(
    columnSettingsState.columnSettingsConfig,
  );

  /** 当前可见的列，按 activeColumnKeys 的顺序。CSV 导出和列宽插件都要它。 */
  const visibleColumns = useMemo(
    () => activeColumnKeys
      .map((key) => columns.find((col) => col.key === key))
      .filter(Boolean),
    [activeColumnKeys, columns],
  );

  /*
   * 列宽同样按表持久化。
   *
   * useTableColumnResize 是受控的：columnWidths 由我们拿着，它只在拖完
   * （pointerup / Enter）时通过 onColumnResizeEnd 回吐一批更新。那一批里除了被拖的列，
   * 还包含"为了避免布局跳动被一起固化成像素宽"的邻居列——所以是 merge 而不是覆盖。
   *
   * columns 传的是 visibleColumns 而不是全量：这个 prop 是用来推每列的最小宽度、
   * 并判断哪些列是 proportional 的（最后一个 proportional 列没有拖拽手柄）。
   * 喂全量的话，被隐藏的列会参与"最后一个 proportional 列是谁"的判断，算出来的是错的。
   */
  const validateColumnWidths = useCallback(
    (stored) => {
      if (!stored || typeof stored !== 'object' || Array.isArray(stored)) {
        return null;
      }
      const known = new Set(defaultColumnKeys);
      const out = {};
      for (const [key, value] of Object.entries(stored)) {
        if (known.has(key) && Number.isFinite(value) && value > 0) {
          out[key] = value;
        }
      }
      return out;
    },
    [defaultColumnKeys],
  );
  const [columnWidths, setColumnWidths, resetColumnWidths] = usePersistentState(
    tableStorageKey('colw', tableId),
    EMPTY_COLUMN_WIDTHS,
    validateColumnWidths,
  );
  const onColumnResizeEnd = useCallback(
    (updates) => setColumnWidths((prev) => ({ ...prev, ...updates })),
    [setColumnWidths],
  );
  const columnResizePlugin = useTableColumnResize({
    columnWidths,
    onColumnResizeEnd,
    columns: visibleColumns,
  });

  // ── 行详情 + 深链（第 8 项）──
  /*
   * 选中的行用一个<b>短引用</b>（rowRef）标识，而不是 getRowKey 的原始返回值。
   * 原因和取舍写在 hashState.js 的 rowRefFromKey 上方：审计历史的 key 是 "4213"
   * （原样进 URL），审计流水/慢查询的 key 里含完整 SQL（折成 h:<摘要>）。
   *
   * 初值直接从 hash 里读，这就是"刷新后自动展开对应详情面板"的全部实现——
   * 不需要等数据回来，因为 selectedRef 是一个纯字符串比较的目标，
   * 数据到位之后下面的 selectedRow 自然就能查到那一行。
   */
  const getRowRef = useCallback(
    (row) => (getRowKey ? rowRefFromKey(getRowKey(row)) : null),
    [getRowKey],
  );
  const [selectedRef, setSelectedRef] = useState(
    () => rowKeyForTable(readHashParams().row, tableId),
  );

  /*
   * selectedRef → hash。
   *
   * ⚠️ 那个 else if 分支不是多余的：一页可能有多张表（性能页两张），
   * 每张表都会跑这个 effect。如果没选中就无条件写 row:null，
   * 那么「慢查询表没选中」会把「SQL 模式表刚选中的那一行」从 hash 里抹掉，
   * 而且两张表会来回抹——刷新后深链永远失效。
   * 所以只在 hash 里那个 row <b>属于本表</b>时才清它。
   *
   * 写入走 hashState.writeHashParams：合并式（不会碰 view / limit）、replaceState
   * （不触发 hashchange，所以不会反过来触发下面那个监听器）、带相等判断。
   */
  useEffect(() => {
    if (selectedRef) {
      writeHashParams({ row: encodeRowRef(tableId, selectedRef) });
    } else if (rowKeyForTable(readHashParams().row, tableId) !== null) {
      writeHashParams({ row: null });
    }
  }, [selectedRef, tableId]);

  /*
   * hash → selectedRef。手改地址栏、点别人发来的深链（同页内换 row）、前进后退走这条边。
   * 反方向不会触发它（replaceState 不发 hashchange），所以不会打环。
   */
  useEffect(() => {
    const onHashChange = () => {
      setSelectedRef(rowKeyForTable(readHashParams().row, tableId));
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, [tableId]);

  const selectedRow = useMemo(() => {
    if (selectedRef == null || !getRowKey) {
      return null;
    }
    /* 在未过滤的全量里回查，而不是在 sortedData 里：面板开着的时候改搜索条件，
       详情不该跟着消失——运维正在照着它读的那一行，被列表过滤掉不代表他看完了。
       分页同理：翻到第 3 页不该把第 1 页打开的那条详情关掉。 */
    return searchable.find((row) => getRowRef(row) === selectedRef) ?? null;
  }, [searchable, selectedRef, getRowKey, getRowRef]);

  /* Esc 关面板注册在这里而不是 App：一页多张表时，每张表各自关掉自己的面板，
     语义正好是"关掉打开着的详情面板"。放到 App 反而要维护一份"谁开着"的登记。
     allowInInputs 保持默认 false —— 在搜索框里按 Esc 应该由输入框自己处理
     （清空 / 关下拉），而不是顺带把旁边的面板也关了。 */
  useHotkeys([{ keys: 'escape', onPress: () => setSelectedRef(null) }]);

  const rowPlugin = useRowInteractionPlugin({
    getRowRef: getRowKey ? getRowRef : null,
    selectedRef,
    onSelect: setSelectedRef,
    getRowTone,
  });

  /* useTableRowStatus 的 getStatus 要求 useCallback 包一下才有稳定的 plugin 身份。
     没有 getRowTone 时也得调这个 hook（hook 不能有条件地调），返回 null 让它不画标记。
     组头行一律返回 null：它没有 success 字段，Proxy 会把它读成 ''，
     那样每个组头都会被标一个"失败"标记（见 isGroupHeaderRow 上方的说明）。 */
  const getStatus = useCallback(
    (item) => {
      if (isGroupHeaderRow(item)) {
        return null;
      }
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
     * 键名不只是标识，core 会按它<b>重排</b>执行顺序。这一段每次加插件都要重新核一遍。
     *
     * useBaseTablePlugins 里有一份写死的 canonical order（dist/Table/useBaseTablePlugins.js
     * 的 PLUGIN_ORDER，逐字核对过）：
     *     ['columnSettings', 'sort', 'tree', 'selection', 'pagination']
     * 不在这份名单里的键名被<b>追加到已知插件之后</b>，彼此之间保持这里的插入顺序
     * （它用的是 Array.prototype.sort，稳定，所以未知插件之间的相对顺序 = 插入顺序）。
     *
     * 于是这一版新加的四个插件分两类：
     * - columnSettings / pagination 在名单里 → 无论写在哪，它们的位置由 core 决定；
     * - columnResize / grouped 不在名单里 → 排在全部已知插件之后，按下面的插入顺序。
     *
     * 「我写在第一个所以先跑」是<b>无效推理</b>：本文件里 rowStatus 写在最前面，
     * 实际执行时 columnSettings 和 sort 都在它前面。
     *
     * 实际生效的顺序（照 PLUGIN_ORDER + 插入顺序推出来）：
     *     columnSettings → sort → pagination → rowStatus → filter → row → columnResize → grouped
     * 这个顺序对本文件是<b>正确的</b>，三处依赖它：
     * 1. columnSettings.transformColumns 先跑，把隐藏的列滤掉；rowStatus 之后才插它那一列，
     *    所以状态标记列不会被"不在 activeColumnKeys 里"这条规则误杀。反过来就会被杀掉。
     * 2. columnResize.transformColumns 在 columnSettings 之后跑，它看到的是已经过滤过的列，
     *    和我们传给它的 visibleColumns 一致——两边不一致会让"最后一个 proportional 列
     *    没有拖拽手柄"这条规则作用在错的列上。
     * 3. grouped.transformBodyRow 最后跑，它要把组头行的单元格整体换成一个通栏单元格；
     *    排在 row 插件之后，才能盖掉 row 插件给行加的东西（我们另外还在 row 插件里
     *    显式跳过了组头，两道保险）。
     *
     * 键名<b>逐字</b>不能错：写成 'sorting' 而不是 'sort'、'colSettings' 而不是
     * 'columnSettings'，都会静默掉出 canonical order —— 不报错，只是顺序变了。
     */
    const map = {};
    map.columnSettings = columnSettingsPlugin;
    map.sort = sortPlugin;
    map.pagination = paginationPlugin;
    if (getRowTone) {
      map.rowStatus = rowStatusPlugin;
    }
    map.filter = filterPlugin;
    if (rowPlugin) {
      map.row = rowPlugin;
    }
    map.columnResize = columnResizePlugin;
    if (groupKey) {
      map.grouped = grouped.plugin;
    }
    return map;
  }, [
    columnSettingsPlugin,
    sortPlugin,
    paginationPlugin,
    getRowTone,
    rowStatusPlugin,
    filterPlugin,
    rowPlugin,
    columnResizePlugin,
    groupKey,
    grouped.plugin,
  ]);

  const hasColumnFilter = Object.keys(columnFilters).length > 0;
  const hasAnyFilter = searchFilters.length > 0 || hasColumnFilter;

  const clearFilters = useCallback(() => {
    setSearchFilters([]);
    clearAllColumnFilters();
    setPage(1);
  }, [clearAllColumnFilters]);

  // ── 保存的视图（第 6 项）──
  const [userViews, setUserViews] = useState(() => readUserViews(tableId));
  const availableFieldKeys = useMemo(
    () => (searchFields ?? []).map((f) => f.key),
    [searchFields],
  );
  const allViews = useMemo(
    () => mergeViews(userViews, availableFieldKeys),
    [userViews, availableFieldKeys],
  );

  /**
   * 应用一个快照。四个维度里值为 null 的表示「保持当前不变」——
   * 内置预设只关心过滤条件，点它不该顺带推翻你刚调好的列显隐（见 savedViews.js）。
   */
  const applyView = useCallback((snapshot) => {
    if (Array.isArray(snapshot.searchFilters)) {
      setSearchFilters(snapshot.searchFilters);
    }
    if (snapshot.columnFilters && typeof snapshot.columnFilters === 'object') {
      setColumnFilters(snapshot.columnFilters);
    }
    if (Array.isArray(snapshot.sort)) {
      setSortState(snapshot.sort);
    }
    if (snapshot.groupBy !== null && snapshot.groupBy !== undefined) {
      setGroupKey(snapshot.groupBy === '' ? null : snapshot.groupBy);
    }
    if (Array.isArray(snapshot.activeColumnKeys)) {
      setActiveColumnKeys(snapshot.activeColumnKeys);
    }
    /* 换视图必回第一页：留在第 5 页上换一套筛选条件，看到的是新结果集的第 5 页，
       而人的预期是"从头看"。 */
    setPage(1);
  }, [setActiveColumnKeys]);

  const saveCurrentAsView = useCallback((name) => {
    const trimmed = name.trim();
    if (trimmed === '') {
      return;
    }
    const view = {
      id: newUserViewId(),
      name: trimmed,
      /* 用户存的快照四项都有值（照现状全量记下来），和预设的"部分为 null"不同。
         这样它恢复出来的是一个确定的现场，而不是"我的几条 AND 你当时的状态"。 */
      snapshot: {
        searchFilters,
        columnFilters,
        sort: sortState,
        groupBy: groupKey ?? '',
        activeColumnKeys: [...activeColumnKeys],
      },
    };
    setUserViews((prev) => {
      const next = [...prev, view];
      writeUserViews(tableId, next);
      return next;
    });
  }, [searchFilters, columnFilters, sortState, groupKey, activeColumnKeys, tableId]);

  const deleteUserView = useCallback((id) => {
    setUserViews((prev) => {
      const next = prev.filter((v) => v.id !== id);
      writeUserViews(tableId, next);
      return next;
    });
  }, [tableId]);

  /* 「共 N 条由顶栏条数决定」这句话的数据。见 DEFAULT_PAGE_SIZE 上方那段
     关于「limit 和分页是两件事」的说明——这句话是那段说明在页面上的落点。 */
  const rangeStart = totalItems === 0 ? 0 : (safePage - 1) * pageSize + 1;
  const rangeEnd = Math.min(safePage * pageSize, totalItems);

  const table = (
    <Section
      title={title}
      source={source}
      count={totalItems}
      totalCount={all.length}
      actions={
        <>
          {extraActions}
          <SavedViewsControl
            views={allViews}
            onApply={applyView}
            onSave={saveCurrentAsView}
            onDelete={deleteUserView}
            title={title}
          />
          {groupFields && groupFields.length > 0 && (
            <GroupByControl
              fields={groupFields}
              value={groupKey}
              onChange={(next) => {
                setGroupKey(next);
                /* 换分组字段就把折叠状态清掉：折叠的键是上一个字段的值，
                   留着的话新分组里会有几组莫名是折叠的。 */
                setCollapsedGroups(EMPTY_COLLAPSED);
                setPage(1);
              }}
              title={title}
            />
          )}
          <ColumnSettingsControl
            options={columnOptions}
            activeKeys={activeColumnKeys}
            onChange={columnSettingsState.setActiveColumnKeys}
            onShowAll={columnSettingsState.showAllColumns}
            onReset={() => { resetColumnKeys(); resetColumnWidths(); }}
            hasCustomWidths={Object.keys(columnWidths).length > 0}
            title={title}
          />
          {hasAnyFilter && (
            <Button label="清空筛选" variant="ghost" onClick={clearFilters} />
          )}
          {csvBaseName && (
            /* 导出<b>可见列</b> × <b>筛选后的全部行</b>（不是当前这一页）。
               两个选择各有理由：列跟着页面走，因为导出的语境是"我看到的这些发给你"；
               行不跟着页面走，因为翻页只是显示密度，没人会认为"导出"只导这 25 行。 */
            <ExportCsvButton
              columns={visibleColumns}
              rows={groupOrdered}
              baseName={csvBaseName}
            />
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
          onChange={(next) => { setSearchFilters([...next]); setPage(1); }}
          label={`搜索${title}`}
          placeholder={searchPlaceholder}
          resultCount={`${totalItems} 行`}
          handleRef={searchHandleRef}
        />
        {beforeTable}
        <DataTable
          columns={columns}
          rows={groupKey ? grouped.data : pageRows}
          /* 分组打开时用 grouped.idKey：它给合成的组头行发 `__group_<键>` 这样的 key，
             而 getRowKey 对组头行会算出一串空值拼出来的假 key（Proxy 把未知字段读成 ''），
             多个组头会撞成同一个 React key。 */
          idKey={groupKey ? grouped.idKey : getRowKey}
          plugins={plugins}
          emptyTitle={
            totalItems === 0 && all.length > 0 ? '当前筛选条件没有命中任何行' : emptyTitle
          }
          emptyDescription={
            totalItems === 0 && all.length > 0
              ? '清空筛选可以看到全部 ' + all.length + ' 行。'
              : emptyDescription
          }
        />
        {/*
          分页与「条数」的关系说明。位置在表格之后、紧贴分页控件
          （分页控件由 pagination 插件渲染在 Table 内部的下方）。

          这句话不是可选的补充说明：不写的话，翻到最后一页的人会认为自己看完了
          全部数据，而实际上他看完的是「后端按顶栏条数返回的这一批」的最后一页。
          这个误读会直接导致错误结论（"审计里没有这条记录" ≠ "这条记录不存在"）。
        */}
        {totalItems > 0 && (
          <Text type="supporting" color="secondary">
            {`本页显示第 ${rangeStart}–${rangeEnd} 行，共 ${totalItems} 行`}
            {hasAnyFilter ? `（已按筛选条件从 ${all.length} 行里筛出）` : ''}
            {` · 这 ${all.length} 行是后端按顶栏「条数」返回的一批，`}
            {'翻到最后一页不等于看完了全部数据；要看更多请调大顶栏的条数。'}
          </Text>
        )}
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
                onClick={() => setSelectedRef(null)}
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
