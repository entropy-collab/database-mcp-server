import { Badge, Banner, HStack, Text, VStack } from '@astryxdesign/core';
import { useMemo } from 'react';
import { fetchTools } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  ErrorNotice,
  InteractiveTable,
  KpiGrid,
  SEARCH_ALL_FIELD,
  Section,
  textColumn,
} from '../components.jsx';

/**
 * 工具清单。来源 GET /api/ui/tools（{total, exposed, groups, tools}）。
 *
 * ── 这一版从「每个分组一张表」改成了「一张带分组列的大表」，理由和上一版正好相反 ──
 * 上一版的注释写得很清楚：分段的代价是"跨组搜某个工具名只能靠浏览器 Ctrl+F"，
 * 而当时的判断是"为一个只读目录加搜索框等于给它加状态"。
 *
 * 现在搜索是这一页明确要有的能力，于是那个代价不再可接受，而分段的收益也随之贬值：
 * - "这一组里都有什么" → 按分组过滤（表头漏斗）一次点击，或者按分组列排序
 * - "某个工具属于哪一组" → 全文搜工具名，分组就在同一行上
 * 128 行一张表配上搜索/排序/过滤，比 10 个各自带搜索框的分段好得多——后者的搜索框
 * 只能搜自己那一组，而运维想搜的时候恰恰是不知道它在哪一组。
 *
 * ── 分段唯一无法替代的那一件事，单独保留 ──
 * ToolCatalog.groups() 刻意不受 ToolExposureFilter 影响，所以可能存在「整组被裁掉、
 * 一个工具都没暴露」的分组。这种分组在大表里会直接消失（没有任何行属于它），
 * 而"这一组被整组裁掉了"是有用的信息。所以它不靠表格表达，而是在表格上方单列出来。
 */

/**
 * 这份清单没有入参 schema，而且这不是个「以后补上」的 TODO。
 *
 * 后端的数据源是 ToolCatalog，它是从 @McpTool 方法反射出来的元数据索引，参数的 JSON Schema
 * 由下游的 SyncToolSpecification 持有，索引里根本没有。想补得注入
 * ObjectProvider<List<SyncToolSpecification>>，而容器里该类型有多个 bean、ToolExposureFilter
 * 又是 BeanPostProcessor——注入时机决定拿到的是裁剪前还是裁剪后的列表。所以这一页是目录，
 * 不是 API 文档：别让人以为能在这里查参数名。
 */
const NO_SCHEMA_TITLE = '这份清单没有工具的入参 schema';
const NO_SCHEMA_DESCRIPTION = '后端的数据源是 ToolCatalog（@McpTool 方法的反射元数据索引），'
  + '它不带 inputSchema。这里只能看到「有哪些工具、各属哪组、一句摘要」，'
  + '查不到参数名与参数类型——那些要问 MCP 客户端的 tools/list。';

/** group 为空的工具归到这一段。后端目前不会给出空 group，但这里不假设它永远不会。 */
const UNGROUPED = '（未分组）';

const COLUMNS = [
  textColumn('name', '工具名', { flex: 2, weight: 'semibold', filter: 'name' }),
  textColumn('group', '分组', { flex: 1, filter: 'group' }),
  textColumn('summary', '摘要', { flex: 5, filter: 'summary' }),
  textColumn('tags', '标签', { flex: 2, filter: 'tags' }),
];

/*
 * group 给成 string（contains）而不是 enum：enum 需要在模块顶层就写死取值，
 * 而分组名来自后端、会随新功能增加。string 的 contains 在这里够用，而且顺带支持
 * 「所有 backup 相关的组」这种前缀式的粗筛。
 *
 * tags 是数组，也按 string 处理：applyFilters 对 string 类型要求 typeof === 'string'，
 * 数组会直接不匹配。所以下面把 tags 拍成了逗号分隔的字符串（见 rows 的 map）。
 */
const SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'name', type: 'string', label: '工具名' },
  { key: 'group', type: 'string', label: '分组' },
  { key: 'summary', type: 'string', label: '摘要' },
  { key: 'tags', type: 'string', label: '标签' },
];

/** 默认按分组、再按工具名升序：大表的默认视图应该和分段版的阅读顺序一致。 */
const DEFAULT_SORT = [
  { sortKey: 'group', direction: 'ascending' },
  { sortKey: 'name', direction: 'ascending' },
];

/** 工具名在 MCP 协议里就是唯一键，直接用。 */
const ROW_KEY = (row) => String(row.name);

export default function ToolsPanel({ refreshToken }) {
  const { data, error } = usePanelData(() => fetchTools(), [refreshToken]);

  const declaredGroups = useMemo(
    () => (Array.isArray(data?.groups) ? data.groups : []),
    [data],
  );

  const rows = useMemo(() => {
    const tools = Array.isArray(data?.tools) ? data.tools : [];
    return tools.map((tool) => ({
      ...tool,
      group: tool.group || UNGROUPED,
      /* 数组拍成字符串：过滤引擎的 string 分支要求 typeof === 'string'，
         数组会静默不匹配（返回 false，不报错）——那种"搜不到但也不说为什么"最难查。
         CSV 导出也顺带变成可读的 "a, b" 而不是 ["a","b"]。 */
      tags: Array.isArray(tool.tags) ? tool.tags.join(', ') : tool.tags,
    }));
  }, [data]);

  /*
   * 一个工具都没暴露的分组。
   *
   * 两个来源都要看：declaredGroups 里没出现在 rows 里的（整组被 ToolExposureFilter 裁掉），
   * 以及反过来 rows 里出现但 declaredGroups 里没有的（groups() 和 exposedDescriptors()
   * 是 ToolCatalog 的两个独立读法，理论上会不一致）。后者是防御性的，但它防的不是
   * "不可能发生"——不接这一段就等于某个工具在页面上凭空消失。现在改成大表之后，
   * 它已经不会消失了（大表列的是 rows），所以只把这件事标出来供核对。
   */
  const presentGroups = useMemo(() => new Set(rows.map((r) => r.group)), [rows]);
  const emptyGroups = declaredGroups.filter((g) => !presentGroups.has(g));
  const undeclaredGroups = [...presentGroups].filter((g) => !declaredGroups.includes(g));

  return (
    <VStack gap={6}>
      <ErrorNotice error={error} />

      <Banner
        status="info"
        title={NO_SCHEMA_TITLE}
        description={NO_SCHEMA_DESCRIPTION}
        container="card"
      />

      <KpiGrid
        items={[
          {
            label: '工具总数',
            value: data?.total,
            hint: '容器里反射出来的全部工具，含被裁剪掉的',
          },
          {
            label: '已暴露',
            value: data?.exposed,
            hint: 'ToolExposureFilter 裁剪后真正交给客户端的，也就是下面列出的这些',
          },
          {
            label: '分组数',
            value: declaredGroups.length || undefined,
            hint: '全量分组名，可能有分组当前一个工具都没暴露',
          },
        ]}
      />

      {(emptyGroups.length > 0 || undeclaredGroups.length > 0) && (
        <Section
          title="下面这张表里不会出现的分组"
          source="GET /api/ui/tools 的 groups 块与 tools 块对不上的部分"
        >
          <VStack gap={3}>
            {emptyGroups.length > 0 && (
              <VStack gap={2}>
                <Text type="supporting" color="secondary">
                  这些分组在全量目录里存在，但当前一个工具都没暴露（被 ToolExposureFilter
                  整组裁掉了）。表格里因此没有它们的行。
                </Text>
                <HStack gap={2} wrap="wrap">
                  {emptyGroups.map((g) => <Badge key={g} variant="neutral" label={g} />)}
                </HStack>
              </VStack>
            )}
            {undeclaredGroups.length > 0 && (
              <VStack gap={2}>
                <Text type="supporting" color="secondary">
                  这些分组名出现在 tools 里、却不在 groups 里。两者是 ToolCatalog 的两个
                  独立读法，对不上说明其中一个的口径变了——值得去看一眼后端。
                </Text>
                <HStack gap={2} wrap="wrap">
                  {undeclaredGroups.map((g) => <Badge key={g} variant="warning" label={g} />)}
                </HStack>
              </VStack>
            )}
          </VStack>
        </Section>
      )}

      <InteractiveTable
        title="已暴露的工具"
        source="GET /api/ui/tools · 只列已暴露的工具 · 按分组过滤用表头的漏斗"
        rows={rows}
        columns={COLUMNS}
        searchFields={SEARCH_FIELDS}
        searchName="tools"
        defaultSort={DEFAULT_SORT}
        getRowKey={ROW_KEY}
        detailTitle="工具详情"
        csvBaseName="mcp-tools"
        emptyTitle="没有任何已暴露的工具"
        emptyDescription="ToolExposureFilter 把全部工具都裁掉了，或者 /api/ui/tools 返回了空清单。"
      />
    </VStack>
  );
}
