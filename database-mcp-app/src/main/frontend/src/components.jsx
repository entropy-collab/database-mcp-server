/*
 * 面板之间共用的三个显示件：错误横幅、指标卡片组、数据表格。
 *
 * 抽出来的理由和后端 WebUiController 里"全部走已有工具 bean"一样：四个面板对
 * 「加载失败长什么样」「空表格长什么样」必须给出同一个答案，否则运维会以为
 * 不同 tab 的语义不同。
 */
import {
  Badge,
  Banner,
  Card,
  Code,
  EmptyState,
  HStack,
  Heading,
  Stack,
  Table,
  Text,
  VStack,
  pixel,
  proportional,
} from '@astryxdesign/core';

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
    <Banner
      status="error"
      title="加载失败"
      description={error.message}
      container="card"
    />
  );
}

/** 说明性文案：每个面板顶部都要写清楚数据来自哪个端点、有什么生命周期约束。 */
export function PanelNote({ children }) {
  return (
    <Text size="sm" color="secondary">
      {children}
    </Text>
  );
}

/** 概览指标卡片。pairs 形如 [[label, value], ...]，value 允许 undefined（显示为 —）。 */
export function SummaryCards({ pairs }) {
  return (
    <HStack gap={3} wrap="wrap">
      {pairs.map(([label, value]) => (
        <Card key={label} padding={4} variant="muted" width={190}>
          <VStack gap={1}>
            <Text size="xsm" color="secondary">
              {label}
            </Text>
            <Text size="lg" weight="semibold" hasTabularNumbers>
              {displayValue(value)}
            </Text>
          </VStack>
        </Card>
      ))}
    </HStack>
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
 * LayerProvider），完整内容仍然拿得到。
 */
export function DataTable({ columns, rows, emptyTitle, emptyDescription }) {
  const data = Array.isArray(rows) ? rows : [];
  if (data.length === 0) {
    return <EmptyState title={emptyTitle} description={emptyDescription} isCompact />;
  }
  return (
    <Table
      data={data}
      columns={columns}
      density="compact"
      dividers="rows"
      hasHover
      isStriped
      textOverflow="truncate"
      verticalAlign="top"
    />
  );
}

/** 文本列。weight 用于把「名字」这类主键列稍微加重。 */
export function textColumn(key, header, { flex = 1, align, weight } = {}) {
  return {
    key,
    header,
    align,
    width: proportional(flex),
    renderCell: (item) => (
      <Text size="sm" weight={weight}>
        {displayValue(item[key])}
      </Text>
    ),
  };
}

/** 数值列：固定宽度 + 右对齐 + 等宽数字。 */
export function numberColumn(key, header, { px = 96 } = {}) {
  return {
    key,
    header,
    align: 'end',
    width: pixel(px),
    renderCell: (item) => (
      <Text size="sm" hasTabularNumbers>
        {displayValue(item[key])}
      </Text>
    ),
  };
}

/**
 * SQL / 模式列：用 Code 排版，等宽字体读 SQL 比正文字体好得多。
 *
 * Code 刻意不传 size：0.6.2 的 CodeSize 联合类型只有 'inherit' 一个成员（不是 sm/md/lg），
 * 传别的值 TS 会报错，运行期则是一个不存在的 class。字号由外层 Table 的 density 决定。
 */
export function sqlColumn(key, header, { flex = 3 } = {}) {
  return {
    key,
    header,
    width: proportional(flex),
    renderCell: (item) => <Code>{displayValue(item[key])}</Code>,
  };
}

/** 布尔结果列：success / isAlias / isPoolHealthy 都是这个形状。 */
export function booleanColumn(key, header, { okLabel = 'OK', badLabel = 'FAIL', px = 88, invertTone = false } = {}) {
  return {
    key,
    header,
    align: 'center',
    width: pixel(px),
    renderCell: (item) => {
      const value = item[key];
      if (value === null || value === undefined) {
        return <Text size="sm">—</Text>;
      }
      const good = invertTone ? !value : Boolean(value);
      return <Badge variant={good ? 'success' : 'error'} label={value ? okLabel : badLabel} />;
    },
  };
}

/**
 * 小节标题：连接页和性能页都有多张表，需要标题把它们分开。
 *
 * Heading 只传 level，不传 type：0.6.2 的 HeadingType 联合只有 display-1/2/3，
 * 没有文档示例里出现过的 'heading-2' / 'heading-4' 这类值。字号由 level 决定。
 */
export function SubSection({ title, children }) {
  return (
    <Stack direction="column" gap={3}>
      <Heading level={2}>{title}</Heading>
      {children}
    </Stack>
  );
}
