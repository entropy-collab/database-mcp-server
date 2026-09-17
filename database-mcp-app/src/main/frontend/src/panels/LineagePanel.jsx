import {
  Badge,
  Banner,
  Button,
  HStack,
  NumberInput,
  SegmentedControl,
  SegmentedControlItem,
  Text,
  TextInput,
  VStack,
} from '@astryxdesign/core';
import { useMemo, useState } from 'react';
import {
  fetchLineage,
  fetchLineageConfig,
  fetchLineageEdges,
  fetchLineageImpact,
} from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  CopyTextButton,
  DataTable,
  ErrorNotice,
  InteractiveTable,
  KpiGrid,
  MonoBlock,
  SEARCH_ALL_FIELD,
  Section,
  contentRowKey,
  displayValue,
  numberColumn,
  textColumn,
} from '../components.jsx';

/**
 * 血缘。来源 GET /api/ui/lineage、/lineage/impact、/lineage/edges、/lineage/config。
 *
 * ── 这一页的核心设计问题：空图长什么样 ──
 * 血缘边<b>只从外键约束现算</b>，没有任何持久化的血缘表。于是「这张表确实没有上下游」和
 * 下面三种情况在页面上产出完全相同的空结果：
 *   1. 库里真的没有外键（很多业务库靠应用层保证引用完整性，一条外键都不建）；
 *   2. foreignKeyEnabled=false —— 一个配置项就能让所有血缘恒为空；
 *   3. 撞上 maxTablesPerGraph（默认 200）被静默截断 —— 大库上尤其容易，
 *      而截断没有任何显式信号（只有 anomalies 里<b>可能</b>出现 TRUNCATED）。
 *
 * 一张不能自解释的空图会让人去查连接、查权限，而真实原因往往是第 2 或第 3 条。
 * 所以这一页把 /lineage/config 挂载即拉（它不连库），并且在空结果时把这三条可能性
 * 逐条列出来 —— 不是一句「暂无数据」。这也是后端类注释里明确要求页面做的事。
 *
 * ── 为什么 config 值和结果放在一起，而不是收进某个折叠区 ──
 * 它们不是「参考信息」，而是读结果的前提。收起来之后，第一个看到空图的人不会去展开它。
 */

/** 三种视图。value 同时是 SegmentedControl 的取值与下面 switch 的分支键。 */
const MODES = [
  { value: 'lineage', label: '上下游', endpoint: 'GET /api/ui/lineage', needsTable: true },
  { value: 'impact', label: '影响面', endpoint: 'GET /api/ui/lineage/impact', needsTable: true },
  { value: 'edges', label: '全库边', endpoint: 'GET /api/ui/lineage/edges', needsTable: false },
];

/**
 * 图文本的格式。
 *
 * 'json' 不是后端的一个 format 取值，而是「不传 format」——后端据此返回结构化 JSON。
 * 写成一个显式的档位是因为它在页面上确实是三选一：不给档位的话，用户得靠「清空这个输入框」
 * 来表达「我要结构化结果」，而空输入框看起来像还没填。
 */
const FORMATS = [
  { value: 'json', label: '结构化' },
  { value: 'mermaid', label: 'mermaid' },
  { value: 'dot', label: 'dot' },
];

const EDGE_COLUMNS = [
  textColumn('sourceTable', '上游表', { flex: 2, weight: 'semibold', filter: 'sourceTable' }),
  textColumn('sourceColumn', '上游列', { flex: 1, filter: 'sourceColumn' }),
  textColumn('targetTable', '下游表', { flex: 2, weight: 'semibold', filter: 'targetTable' }),
  textColumn('targetColumn', '下游列', { flex: 1, filter: 'targetColumn' }),
  textColumn('type', '类型', { flex: 1, filter: 'type' }),
];

const EDGE_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'sourceTable', type: 'string', label: '上游表' },
  { key: 'targetTable', type: 'string', label: '下游表' },
  { key: 'sourceColumn', type: 'string', label: '上游列' },
  { key: 'targetColumn', type: 'string', label: '下游列' },
];

/** 一条边的身份 = 四个端点全一致。用内容做 key，不能用下标（排序后下标会变）。 */
const EDGE_ROW_KEY = contentRowKey(['sourceTable', 'sourceColumn', 'targetTable', 'targetColumn']);

/** 异常清单的列。type 只有 CYCLE / ORPHAN / TRUNCATED 三种。 */
const ANOMALY_COLUMNS = [
  textColumn('type', '类型', { flex: 1, weight: 'semibold' }),
  textColumn('description', '说明', { flex: 4 }),
  textColumn('affectedTables', '涉及的表', { flex: 3 }),
];

/** 影响面按深度分层的列。 */
const DEPTH_COLUMNS = [
  numberColumn('depth', '深度', { px: 88 }),
  numberColumn('count', '表数', { px: 88 }),
  textColumn('tables', '这一层的表', { flex: 6 }),
];

/**
 * 空结果的三条可能性，和 config 的实际取值一起显示。
 *
 * 每一条都带上「怎么确认」——只说「可能是没有外键」帮不上忙，得说清去哪儿看。
 * config 拿不到时（拉 config 也失败了）就不能断言 foreignKeyEnabled 是什么，
 * 那一条改成「读不到配置，无法排除」：静默假设它是开的，会把最可能的原因藏起来。
 */
function EmptyGraphExplainer({ config, edgeCount }) {
  const fkOff = config?.foreignKeyEnabled === false;
  const configUnknown = config == null;
  return (
    <Banner
      status={fkOff ? 'warning' : 'info'}
      title={`血缘图是空的（edgeCount=${displayValue(edgeCount)}），这有三种原因，页面区分不了`}
      description={
        '1) 库里真的没有外键：血缘边只从外键约束推导，靠应用层保证引用完整性的库会一条边都没有。'
        + '确认方式是去「Schema 浏览」页看目标表的索引与约束。\n'
        + `2) foreignKeyEnabled 开关：当前值 ${configUnknown ? '读不到（/lineage/config 也失败了，无法排除这一条）' : displayValue(config?.foreignKeyEnabled)}`
        + `${fkOff ? ' —— 它是关的，那么所有血缘恒为空，这就是原因。' : '。配置项是 entropy.mcp.database.lineage.foreign-key-enabled。'}\n`
        + `3) 撞上 maxTablesPerGraph 被截断：当前值 ${displayValue(config?.maxTablesPerGraph)}。`
        + '超限时只取前 N 张表，结果不完整而且没有显式信号（异常清单里可能、但不保证出现 TRUNCATED）。'
        + '库里表数超过这个值时，空结果与不完整结果都要按「不可信」处理。'
      }
      container="card"
    />
  );
}

export default function LineagePanel({ refreshToken }) {
  const [table, setTable] = useState('');
  const [connection, setConnection] = useState('');
  const [maxDepth, setMaxDepth] = useState(3);
  const [format, setFormat] = useState('json');
  const [mode, setMode] = useState('lineage');
  const [submitted, setSubmitted] = useState(null);

  /* config 不连库，跟着挂载就拉。它必须先到位，否则空图没法解释（见文件头）。 */
  const config = usePanelData(() => fetchLineageConfig(), [refreshToken]);
  const cfg = config.data;

  const result = usePanelData(
    () => {
      const p = submitted.params;
      switch (submitted.mode) {
        case 'lineage':
          return fetchLineage(p);
        case 'impact':
          return fetchLineageImpact(p);
        case 'edges':
          return fetchLineageEdges(p);
        default:
          throw new Error(`未知的模式：${submitted.mode}`);
      }
    },
    [JSON.stringify(submitted), refreshToken],
    { enabled: submitted !== null },
  );

  const currentMode = MODES.find((m) => m.value === mode) ?? MODES[0];
  const missingTable = currentMode.needsTable && table.trim() === '';
  const canSubmit = !missingTable;

  const submittedMode = submitted?.mode ?? null;
  const data = result.data;

  /* format=mermaid / dot 时返回体只有 {format, graph} 两个键——别的字段一个都没有。 */
  const graphText = typeof data?.graph === 'string' ? data.graph : null;
  const isGraphText = submittedMode === 'lineage' && graphText !== null;

  const directUpstream = useMemo(
    () => (Array.isArray(data?.directUpstream) ? data.directUpstream : []),
    [data],
  );
  const directDownstream = useMemo(
    () => (Array.isArray(data?.directDownstream) ? data.directDownstream : []),
    [data],
  );
  const edges = useMemo(
    () => (Array.isArray(data?.edges) ? data.edges : []),
    [data],
  );

  /*
   * anomalies 只在检测到异常时才出现，<b>不是恒定键</b>（后端 javadoc 明说了这一点）。
   * 所以这里必须走 Array.isArray 而不是 `data.anomalies.length`——后者在正常情况下
   * 会抛 "Cannot read properties of undefined"，而那是最常见的情况。
   */
  const anomalies = useMemo(
    () => (Array.isArray(data?.anomalies) ? data.anomalies : []),
    [data],
  );

  /** byDepth 是 {深度: [表名]} 的 Map，拍成三列表。深度按数值升序，不是字符串序。 */
  const depthRows = useMemo(() => {
    const byDepth = data?.byDepth;
    if (!byDepth || typeof byDepth !== 'object') {
      return [];
    }
    return Object.entries(byDepth)
      .map(([depth, tables]) => ({
        depth: Number(depth),
        count: Array.isArray(tables) ? tables.length : 0,
        tables: Array.isArray(tables) ? tables.join(', ') : displayValue(tables),
      }))
      .sort((a, b) => a.depth - b.depth);
  }, [data]);

  /*
   * 「这次结果算不算空图」。
   *
   * 三种模式的判据不一样，所以在这里算一次而不是在三个分支里各判一次：
   * - lineage 结构化：edgeCount 为 0（或者上下游两个数组都空）
   * - impact：totalImpacted 为 0
   * - edges：totalEdges 为 0
   * 图文本模式不判：graph 里有没有边，页面读不出来（那是一段 mermaid 文本），
   * 硬猜不如不说。
   */
  const isEmptyGraph = data != null && !isGraphText && (
    (submittedMode === 'lineage' && (data.edgeCount === 0
      || (directUpstream.length === 0 && directDownstream.length === 0)))
    || (submittedMode === 'impact' && data.totalImpacted === 0)
    || (submittedMode === 'edges' && data.totalEdges === 0)
  );

  const submittedText = submitted
    ? [
      `mode=${submitted.mode}`,
      submitted.params.table ? `table=${submitted.params.table}` : null,
      submitted.params.connection ? `connection=${submitted.params.connection}` : 'connection=（默认）',
      submitted.params.maxDepth !== undefined ? `maxDepth=${submitted.params.maxDepth}` : null,
      submitted.params.format ? `format=${submitted.params.format}` : null,
    ].filter(Boolean).join(' · ')
    : null;

  return (
    <VStack gap={6}>
      <ErrorNotice error={config.error} />

      {cfg?.enabled === false && (
        <Banner
          status="info"
          title="本部署没有开启血缘功能（entropy.mcp.database.lineage.enabled=false）"
          description={
            '这是配置事实而不是故障。下面的查询仍然会发出去，但在这个开关关闭的部署上'
            + '结果通常没有意义——先确认这一项，再去怀疑连接与权限。'
          }
          container="card"
        />
      )}

      <Banner
        status="warning"
        title="血缘是靠外键现算的，不是一份维护好的血缘表"
        description={
          '每次查询都会读目标库的外键元数据（全库边模式是每张表一次外键查询，大库开销很高），'
          + '所以这一页默认不查任何东西。同样因为是现算的：一条外键都不建的库上，'
          + '这一页永远是空的，而那不是故障。'
        }
        container="card"
      />

      <Section
        title="血缘模块的生效配置"
        source="GET /api/ui/lineage/config · 不连库、挂载即拉 · 这四个值是读下面任何结果的前提，尤其是空结果"
      >
        <KpiGrid
          items={[
            {
              label: '功能开关',
              value: cfg?.enabled,
              status: cfg?.enabled === false ? 'warning' : undefined,
              statusLabel: '血缘功能未开启',
              hint: 'entropy.mcp.database.lineage.enabled',
            },
            {
              label: '外键血缘',
              value: cfg?.foreignKeyEnabled,
              status: cfg?.foreignKeyEnabled === false ? 'error' : 'success',
              statusLabel: cfg?.foreignKeyEnabled === false
                ? '关闭 —— 所有血缘恒为空'
                : '开启',
              hint: '关掉之后所有血缘图都是空的，且空图和「真的没有上下游」长得一样',
            },
            {
              label: '视图依赖血缘',
              value: cfg?.viewDependencyEnabled,
              hint: '视图 → 基表的依赖是否也算进血缘',
            },
            {
              label: '最大遍历深度',
              value: cfg?.maxTraversalDepth,
              hint: '下面的 maxDepth 再大也会被它压住',
            },
            {
              label: '单图最大表数',
              value: cfg?.maxTablesPerGraph,
              hint: '超限时静默截断，只取前 N 张表',
            },
            {
              label: '自动分析',
              value: cfg?.autoAnalyze,
              hint: '与本页无关：页面从不自动发请求',
            },
          ]}
        />
      </Section>

      <Section
        title="查询条件"
        source={`${currentMode.endpoint} · ${submittedText ?? '还没提交过查询'}`}
      >
        <VStack gap={4}>
          <SegmentedControl value={mode} onChange={setMode} label="看什么">
            {MODES.map((m) => (
              <SegmentedControlItem key={m.value} value={m.value} label={m.label} />
            ))}
          </SegmentedControl>
          <HStack gap={3} wrap="wrap" vAlign="end">
            {currentMode.needsTable && (
              <TextInput
                label="table"
                value={table}
                onChange={setTable}
                isRequired
                placeholder="根表名"
                status={missingTable
                  ? { type: 'error', message: 'table 必填，缺了后端 400' }
                  : undefined}
                width={240}
              />
            )}
            <TextInput
              label="connection"
              value={connection}
              onChange={setConnection}
              placeholder="留空用默认连接"
              width={220}
            />
            {mode !== 'edges' && (
              <NumberInput
                label="maxDepth"
                value={maxDepth}
                min={1}
                max={10}
                step={1}
                isIntegerOnly
                hasNumberSteppers
                width={132}
                /* 上限写 10 是照工具自己的夹取范围（1..10），不是我定的；真实生效值还会被
                   上面那个 maxTraversalDepth 压住，所以结果区回显后端返回的 maxDepth。 */
                description="工具夹到 1..10，再受 maxTraversalDepth 约束"
                onChange={(v) => setMaxDepth(v ?? 1)}
              />
            )}
            {mode === 'lineage' && (
              <SegmentedControl value={format} onChange={setFormat} label="返回格式">
                {FORMATS.map((f) => (
                  <SegmentedControlItem key={f.value} value={f.value} label={f.label} />
                ))}
              </SegmentedControl>
            )}
            <Button
              label="查询"
              variant="primary"
              isDisabled={!canSubmit}
              isLoading={result.isLoading}
              onClick={() => setSubmitted({
                mode,
                params: {
                  table: table.trim(),
                  connection: connection.trim(),
                  maxDepth: mode === 'edges' ? undefined : maxDepth,
                  /* 'json' 是前端自己造的档位名，对应「不传 format」。传上去后端会当成
                     未知取值走结构化分支——能工作，但那是碰巧；显式传 undefined 更准确。 */
                  format: mode === 'lineage' && format !== 'json' ? format : undefined,
                },
              })}
            />
          </HStack>
          {submitted === null && (
            <Text type="supporting" color="secondary">
              还没点过「查询」，这一页目前没有向任何库发过请求。
            </Text>
          )}
        </VStack>
      </Section>

      <ErrorNotice error={result.error} />

      {isEmptyGraph && (
        <EmptyGraphExplainer
          config={cfg}
          edgeCount={data?.edgeCount ?? data?.totalEdges ?? data?.totalImpacted}
        />
      )}

      {/*
        anomalies 单独一块，放在结果之前。
        它只在有异常时才存在，而它说的事（环、悬空引用、被截断）会让下面的结果不可信——
        放在结果下方的话，看完一张「正常」的表之后才发现它是截断的样本，为时已晚。
      */}
      {anomalies.length > 0 && (
        <Section
          title="血缘异常"
          source="返回体里的 anomalies · 只在检测到异常时才有这个键 · TRUNCATED 意味着下面的结果是不完整的样本"
          count={anomalies.length}
        >
          <DataTable
            columns={ANOMALY_COLUMNS}
            rows={anomalies.map((a) => ({
              ...a,
              affectedTables: Array.isArray(a.affectedTables)
                ? a.affectedTables.join(', ')
                : displayValue(a.affectedTables),
            }))}
            emptyTitle="没有异常"
            emptyDescription="走不到这里：这一块只在 anomalies 非空时渲染。"
          />
        </Section>
      )}

      {isGraphText && (
        <Section
          title={`图文本（format=${displayValue(data?.format)}）`}
          source={
            'GET /api/ui/lineage · 这个格式下返回体只有 format 与 graph 两个键，'
            + '没有 edgeCount / 上下游数组，所以这一块不显示统计'
          }
          actions={<CopyTextButton text={graphText} label="复制图文本" what="图文本" />}
        >
          <VStack gap={3}>
            <Text type="supporting" color="secondary">
              {data?.format === 'mermaid'
                ? '粘到支持 mermaid 的地方（多数 Markdown 渲染器、mermaid.live）就能看到图。'
                  + '这一页不内嵌渲染器：那要引一个图库，而「不新增 npm 依赖」是硬约束。'
                : '粘给 Graphviz（dot -Tsvg）渲染。同上，页面不内嵌渲染器。'}
            </Text>
            <MonoBlock text={graphText} emptyText="（后端返回了空的 graph）" />
          </VStack>
        </Section>
      )}

      {submittedMode === 'lineage' && !isGraphText && data != null && (
        <VStack gap={5}>
          <Section
            title={`${displayValue(data.table)} 的上下游`}
            source={`GET /api/ui/lineage · ${submittedText} · 后端生效的 maxDepth=${displayValue(data.maxDepth)}`}
          >
            <KpiGrid
              items={[
                { label: '直接边总数', value: data.edgeCount },
                { label: '有上游', value: data.hasUpstream },
                { label: '有下游', value: data.hasDownstream },
                {
                  label: '多层上游表数',
                  value: Array.isArray(data.allUpstream) ? data.allUpstream.length : undefined,
                  hint: '按广度顺序，不含根表',
                },
                {
                  label: '多层下游表数',
                  value: Array.isArray(data.allDownstream) ? data.allDownstream.length : undefined,
                  hint: '按广度顺序，不含根表',
                },
                {
                  label: '生效深度',
                  value: data.maxDepth,
                  hint: '后端返回值 · 和输入不一致就是被夹过了',
                },
              ]}
            />
          </Section>

          <InteractiveTable
            title="直接上游（本表引用的父表）"
            source="返回体的 directUpstream · sourceTable 是父表、targetTable 是本表"
            rows={directUpstream}
            columns={EDGE_COLUMNS}
            searchFields={EDGE_SEARCH_FIELDS}
            searchName="lineage-upstream"
            getRowKey={EDGE_ROW_KEY}
            csvBaseName="lineage-upstream"
            emptyTitle="没有直接上游"
            emptyDescription="这张表没有指向别的表的外键 —— 或者见上方关于空图的三条可能性。"
          />

          <InteractiveTable
            title="直接下游（引用本表的子表）"
            source="返回体的 directDownstream · sourceTable 是本表、targetTable 是子表"
            rows={directDownstream}
            columns={EDGE_COLUMNS}
            searchFields={EDGE_SEARCH_FIELDS}
            searchName="lineage-downstream"
            getRowKey={EDGE_ROW_KEY}
            csvBaseName="lineage-downstream"
            emptyTitle="没有直接下游"
            emptyDescription="没有别的表通过外键引用它 —— 或者见上方关于空图的三条可能性。"
          />

          {/*
            多层上下游只是表名数组（不是边），所以用 Badge 铺开而不是塞进表格：
            一列只有表名的表格比一排标签更占地方，而且这里没有可排序 / 可过滤的第二个维度。
          */}
          <Section
            title="多层上下游（表名，按广度顺序）"
            source="返回体的 allUpstream / allDownstream · 不含根表 · 只有表名，没有边的列信息"
          >
            <VStack gap={4}>
              <VStack gap={2}>
                <Text type="label" weight="semibold">
                  {`上游 ${Array.isArray(data.allUpstream) ? data.allUpstream.length : 0} 张`}
                </Text>
                <HStack gap={2} wrap="wrap">
                  {(Array.isArray(data.allUpstream) ? data.allUpstream : []).map((t) => (
                    <Badge key={`up-${t}`} variant="neutral" label={String(t)} />
                  ))}
                </HStack>
              </VStack>
              <VStack gap={2}>
                <Text type="label" weight="semibold">
                  {`下游 ${Array.isArray(data.allDownstream) ? data.allDownstream.length : 0} 张`}
                </Text>
                <HStack gap={2} wrap="wrap">
                  {(Array.isArray(data.allDownstream) ? data.allDownstream : []).map((t) => (
                    <Badge key={`down-${t}`} variant="neutral" label={String(t)} />
                  ))}
                </HStack>
              </VStack>
            </VStack>
          </Section>
        </VStack>
      )}

      {submittedMode === 'impact' && data != null && (
        <Section
          title={`修改 ${displayValue(data.sourceTable)} 会波及的表`}
          source={
            `GET /api/ui/lineage/impact · ${submittedText} · 根表那个键叫 sourceTable 而不是 table`
            + ` · 后端生效的 maxDepth=${displayValue(data.maxDepth)}`
          }
          count={data ? depthRows.length : undefined}
        >
          <VStack gap={4}>
            <KpiGrid
              items={[
                {
                  label: '受影响表数',
                  value: data.totalImpacted,
                  status: data.totalImpacted > 0 ? 'warning' : 'success',
                  statusLabel: data.totalImpacted > 0 ? '有下游会被牵连' : '没有下游被牵连',
                },
                { label: '分了几层', value: depthRows.length },
                { label: '生效深度', value: data.maxDepth },
                { label: '连接', value: data.connection ?? '（默认）' },
              ]}
            />
            <DataTable
              columns={DEPTH_COLUMNS}
              rows={depthRows}
              emptyTitle="没有受影响的表"
              emptyDescription="沿外键往下游一层都走不到 —— 或者见上方关于空图的三条可能性。"
            />
          </VStack>
        </Section>
      )}

      {submittedMode === 'edges' && (
        <VStack gap={5}>
          <Section
            title="全库边汇总"
            source={`GET /api/ui/lineage/edges · ${submittedText} · 每张表一次外键查询，大库开销很高`}
          >
            <KpiGrid
              items={[
                { label: '边总数', value: data?.totalEdges },
                { label: '连接', value: data?.connection ?? '（默认）' },
                {
                  label: '单图最大表数',
                  value: cfg?.maxTablesPerGraph,
                  hint: '库里表数超过它时，这份边集是被截断的',
                },
              ]}
            />
          </Section>

          <InteractiveTable
            title="全库血缘边"
            source="GET /api/ui/lineage/edges 的 edges · 已去重 · type 恒为 FOREIGN_KEY"
            rows={edges}
            columns={EDGE_COLUMNS}
            searchFields={EDGE_SEARCH_FIELDS}
            searchName="lineage-edges"
            getRowKey={EDGE_ROW_KEY}
            detailTitle="边详情"
            csvBaseName="lineage-edges"
            emptyTitle="这个库里没有任何血缘边"
            emptyDescription="见上方关于空图的三条可能性 —— 这个结果最常见的原因是库里一条外键都没建。"
          />
        </VStack>
      )}
    </VStack>
  );
}
