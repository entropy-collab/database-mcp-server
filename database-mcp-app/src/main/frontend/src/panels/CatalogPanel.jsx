import {
  Banner,
  Button,
  HStack,
  SegmentedControl,
  SegmentedControlItem,
  Text,
  TextInput,
  VStack,
} from '@astryxdesign/core';
import { useMemo, useState } from 'react';
import {
  fetchCatalogConfig,
  fetchCatalogScan,
  fetchCatalogSearch,
  fetchCatalogSensitive,
  fetchCatalogTable,
} from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  DataTable,
  ErrorNotice,
  InteractiveTable,
  KpiGrid,
  SEARCH_ALL_FIELD,
  Section,
  Sparkline,
  booleanColumn,
  contentRowKey,
  displayValue,
  numberColumn,
  textColumn,
} from '../components.jsx';

/**
 * 数据资产与敏感列。来源 GET /api/ui/catalog/*（5 个端点）。
 *
 * ── 这一页有一条别的页面都没有的义务：自己挡住缺 connection 的请求 ──
 * CatalogTools 的 scanSchema / generateCatalog / listSensitiveColumns 三个方法体里第一行就是
 * `validateRequired(connection, "connection")`，它抛的是 McpToolException →
 * <b>HTTP 500</b>，不是 400。控制器那边只对 tableName / keyword 做了 requireParam，
 * connection 是 `required=false` 的可选参数，所以「没填连接名」这件事一路走到工具内部，
 * 换回来一条 500。
 *
 * 500 在这一页的其它语境里是真实故障（连不上、没权限），把「你没填连接名」也显示成 500
 * 会让运维去查库、查网络。所以前端在发请求前自己挡一下，给一句人话。
 * 挡的是<b>四个</b>端点，包括 /catalog/search —— 它的 searchAssets 虽然没有那行
 * validateRequired，但方法签名上的注释写着：null 连接名会在 catalogService 内部的 map 查找处
 * 抛 NPE，而 NPE 不在异常切面认的「漏传 connection」类型里，客户端只收到一条
 * SYS998「未知错误」。也就是说它留空的后果比另外三个还糟，更该挡。
 *
 * ── enabled=false 要说清是「没开」而不是「坏了」──
 * /catalog/config 不连库、挂载即拉，所以在发任何库请求之前就能知道这套功能有没有开。
 * 功能没开时给 info 横幅并把开关键名写出来，而不是让人先失败一次再去猜。
 * 顺带一句已知事实（工具描述里明说的）：enabled / autoGenerateComments /
 * enableSensitiveDetection 三项<b>目前只是配置回显</b>，当前实现没有按它们改变行为，
 * 只有 maxSearchResults 会真实生效。所以这条横幅是"提示去核对"，不是"功能被关掉了"。
 */

/**
 * 敏感级别的排序权重。
 *
 * ── 为什么是子串匹配一组英文显示名，而不是一张枚举名到数字的映射 ──
 * 后端给的<b>不是</b>枚举名。SensitivityLevel 有 zh / en 两个显示名，而两个端点各用了不同的写法：
 * - /catalog/scan 与 /catalog/sensitive 给 `getEn()`，也就是 "Confidential"、"Highly Sensitive"
 *   （注意有空格，不是 HIGHLY_SENSITIVE）；
 * - /catalog/table 给 `zh + " (" + en + ")"`，也就是 "机密 (Confidential)"。
 *
 * 第一版按 'CONFIDENTIAL' 这种枚举名去查表，两种写法一个都命中不了 —— 结果是所有行都不着色，
 * 而「不着色」看起来正好像「没有敏感数据」。所以这里按小写英文名做子串匹配，两种写法都吃。
 * 顺序从高到低，命中即返回：'Highly Sensitive' 必须排在 'Sensitive' 这类更短的模式之前
 * （目前没有更短的重叠模式，但顺序本身是这条规则的一部分）。
 */
const SENSITIVITY_RANKS = [
  { match: 'highly sensitive', rank: 4 },
  { match: '高度敏感', rank: 4 },
  { match: 'restricted', rank: 3 },
  { match: '受限', rank: 3 },
  { match: 'confidential', rank: 2 },
  { match: '机密', rank: 2 },
  { match: 'internal', rank: 1 },
  { match: 'public', rank: 0 },
];

function sensitivityRank(level) {
  const value = String(level ?? '').toLowerCase();
  if (value === '') {
    return 0;
  }
  for (const entry of SENSITIVITY_RANKS) {
    if (value.includes(entry.match)) {
      return entry.rank;
    }
  }
  /* 未知级别按 0 处理（不着色）：猜一个高危级别会让人误以为发现了敏感数据。 */
  return 0;
}

function sensitivityTone(level) {
  const rank = sensitivityRank(level);
  if (rank >= 3) {
    return 'error';
  }
  if (rank >= 2) {
    return 'warning';
  }
  return null;
}

/*
 * ── 四个模式全都要求非空 connection，包括「检索资产」──
 * 前三个（scan / sensitive / table）在工具方法体第一行就是
 * `validateRequired(connection, "connection")` → McpToolException → HTTP 500。
 *
 * searchAssets 没有那行校验，我一开始据此放开了它。读到方法签名上那段注释之后改回来了：
 * 它明确写着「catalogService 的检索路径拿到 null 连接名会在内部 map 查找处抛 NPE，
 * 而 NPE 不在 McpToolExceptionAspect 认的『漏传 connection』类型里，客户端只会收到一条
 * SYS998『未知错误』」。也就是说留空的后果比另外三个更糟 —— 连"缺了什么"都不告诉你。
 * 所以四个模式一律在前端挡住，给一句人话。
 */
const MODES = [
  {
    value: 'scan',
    label: '扫描 Schema',
    endpoint: 'GET /api/ui/catalog/scan',
    needsTableName: false,
    needsKeyword: false,
  },
  {
    value: 'sensitive',
    label: '敏感列',
    endpoint: 'GET /api/ui/catalog/sensitive',
    needsTableName: false,
    needsKeyword: false,
  },
  {
    value: 'table',
    label: '单表目录',
    endpoint: 'GET /api/ui/catalog/table',
    needsTableName: true,
    needsKeyword: false,
  },
  {
    value: 'search',
    label: '检索资产',
    endpoint: 'GET /api/ui/catalog/search',
    needsTableName: false,
    needsKeyword: true,
  },
];

/**
 * 扫描结果的列。
 *
 * 键名是从 CatalogTools.scanSchema 那段 `m.put(...)` 逐条抄下来的，<b>不是</b>
 * DataCatalogEntry 这个 record 的字段名：那一层刻意只挑了七项出来
 * （tableName、tableComment、rowCount、category、maxSensitivity、hasSensitiveColumns、columnCount）。
 * 按 record 的字段写会多出 schema / tableSizeMb / description 三列全是「—」的列 ——
 * 请求成功、数据也在，但页面上凭空多出三列空白，比少一列更容易让人以为是数据缺失。
 *
 * hasSensitiveColumns 用 booleanColumn 并且 invertTone：这一列 true 不是「OK」而是
 * 「这张表里有敏感字段」。不反色的话满屏绿色 OK，而它们恰恰是要看的那些行。
 */
const SCAN_COLUMNS = [
  textColumn('tableName', '表名', { flex: 2, weight: 'semibold', filter: 'tableName' }),
  textColumn('tableComment', '表注释', { flex: 3, filter: 'tableComment' }),
  textColumn('category', '业务分类', { flex: 1, filter: 'category' }),
  textColumn('maxSensitivity', '最高敏感级别', { flex: 1, filter: 'maxSensitivity' }),
  booleanColumn('hasSensitiveColumns', '含敏感列', {
    okLabel: '有',
    badLabel: '无',
    invertTone: true,
    px: 96,
  }),
  numberColumn('columnCount', '列数', { px: 88 }),
  numberColumn('rowCount', '行数', { px: 112 }),
];

const SCAN_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'tableName', type: 'string', label: '表名' },
  { key: 'tableComment', type: 'string', label: '表注释' },
  { key: 'category', type: 'string', label: '业务分类' },
  { key: 'maxSensitivity', type: 'string', label: '最高敏感级别' },
];

/* 扫描结果里没有 schema 这一列（后端没放进去），所以行标识只能靠表名。 */
const SCAN_ROW_KEY = contentRowKey(['tableName']);

/**
 * 敏感列清单的列。键名同样抄的是 CatalogTools.listSensitiveColumns 里那段 m.put：
 * table / column / dataType / comment / sensitivity / category / suggestedClassification。
 * 注意它<b>不是</b> DataElement 的字段名（那边叫 tableName / columnName / columnComment /
 * sensitivityLevel / detectedCategory）—— 两层的命名不一致，页面必须跟着最外层那一层。
 */
const SENSITIVE_COLUMNS = [
  textColumn('table', '表名', { flex: 2, weight: 'semibold', filter: 'table' }),
  textColumn('column', '列名', { flex: 2, filter: 'column' }),
  textColumn('dataType', '类型', { flex: 1, filter: 'dataType' }),
  textColumn('sensitivity', '敏感级别', { flex: 1, filter: 'sensitivity' }),
  textColumn('category', '分类', { flex: 1, filter: 'category' }),
  textColumn('comment', '注释', { flex: 2, filter: 'comment' }),
  textColumn('suggestedClassification', '处置建议', { flex: 2 }),
];

const SENSITIVE_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'table', type: 'string', label: '表名' },
  { key: 'column', type: 'string', label: '列名' },
  { key: 'sensitivity', type: 'string', label: '敏感级别' },
  { key: 'category', type: 'string', label: '分类' },
];

const SENSITIVE_ROW_KEY = contentRowKey(['table', 'column']);

/**
 * 单表目录里每一列的字段。
 *
 * 这一组又是第三套命名：CatalogTools.formatColumns 给的是
 * name / dataType / nullable / comment / sensitivity / category / suggestion
 * （nullable 已经被转成 "YES"/"NO" 字符串，suggestion 而不是 suggestedClassification）。
 * 三个端点三套键名，所以三份列定义，不共用。
 */
const TABLE_COLUMNS = [
  textColumn('name', '列名', { flex: 2, weight: 'semibold' }),
  textColumn('dataType', '类型', { flex: 1 }),
  textColumn('nullable', '可空', { flex: 1 }),
  textColumn('sensitivity', '敏感级别', { flex: 1 }),
  textColumn('category', '分类', { flex: 1 }),
  textColumn('comment', '注释', { flex: 2 }),
  textColumn('suggestion', '处置建议', { flex: 2 }),
];

/** 资产检索结果的列。CatalogTools.searchAssets 只放了这五项，没有 columnCount / schema。 */
const ASSET_COLUMNS = [
  textColumn('tableName', '表名', { flex: 2, weight: 'semibold', filter: 'tableName' }),
  textColumn('tableComment', '表注释', { flex: 4, filter: 'tableComment' }),
  textColumn('category', '业务分类', { flex: 1, filter: 'category' }),
  textColumn('maxSensitivity', '最高敏感级别', { flex: 1, filter: 'maxSensitivity' }),
  numberColumn('rowCount', '行数', { px: 112 }),
];

const ASSET_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'tableName', type: 'string', label: '表名' },
  { key: 'tableComment', type: 'string', label: '表注释' },
  { key: 'category', type: 'string', label: '业务分类' },
  { key: 'maxSensitivity', type: 'string', label: '最高敏感级别' },
];

const ASSET_ROW_KEY = contentRowKey(['tableName']);

const SENSITIVITY_CAVEAT = '敏感级别是按字段名与注释的命名规则推断的，不看数据内容：'
  + '命名不规范的库会漏判，名字像敏感字段而实际不是的会误判。'
  + '这份清单是线索，不是合规结论。';

export default function CatalogPanel({ limit, refreshToken }) {
  const [connection, setConnection] = useState('');
  const [schema, setSchema] = useState('');
  const [tableName, setTableName] = useState('');
  const [keyword, setKeyword] = useState('');
  const [mode, setMode] = useState('scan');
  const [submitted, setSubmitted] = useState(null);

  /* config 不连库，跟着挂载就拉。它是这一页唯一在「一个连接都没注册」时也能成功的端点。 */
  const config = usePanelData(() => fetchCatalogConfig(), [refreshToken]);
  const isFeatureOff = config.data?.enabled === false;

  const result = usePanelData(
    () => {
      const params = submitted.params;
      switch (submitted.mode) {
        case 'scan':
          return fetchCatalogScan(params);
        case 'sensitive':
          return fetchCatalogSensitive(params);
        case 'table':
          return fetchCatalogTable(params);
        case 'search':
          return fetchCatalogSearch(params);
        default:
          throw new Error(`未知的模式：${submitted.mode}`);
      }
    },
    [JSON.stringify(submitted), refreshToken],
    { enabled: submitted !== null },
  );

  const currentMode = MODES.find((m) => m.value === mode) ?? MODES[0];
  const trimmedConnection = connection.trim();

  /*
   * 三个「不发这次请求」的判据。
   *
   * 缺 connection 那一条是这一页特有的（见文件头）：它不是为了省一次请求，是为了不让
   * 一句「connection cannot be blank」以 HTTP 500 的形式出现在页面上 —— 在这一页，
   * 500 的其它来源全都是真实故障。检索模式留空更糟：它会在服务内部 NPE，
   * 换回来一条 SYS998「未知错误」，连缺了什么都不告诉你。
   */
  const missingConnection = trimmedConnection === '';
  const missingTableName = currentMode.needsTableName && tableName.trim() === '';
  const missingKeyword = currentMode.needsKeyword && keyword.trim() === '';
  const canSubmit = !missingConnection && !missingTableName && !missingKeyword;

  const submittedMode = submitted?.mode ?? null;
  const data = result.data;

  const entries = useMemo(
    () => (Array.isArray(data?.entries) ? data.entries : []),
    [data],
  );
  const sensitiveCols = useMemo(
    () => (Array.isArray(data?.columns) ? data.columns : []),
    [data],
  );
  const assets = useMemo(
    () => (Array.isArray(data?.assets) ? data.assets : []),
    [data],
  );

  /*
   * 扫描结果里每张表的列数，画成一条 sparkline。
   *
   * 它回答的是「这个 Schema 的表宽度分布」——扫描本身很慢（每张表三次元数据往返），
   * 既然已经付了这个代价，就把结果里能一眼看出异常的那一维显示出来：
   * 一张 600 列的宽表通常是设计问题，而它在一张按表名排序的表格里不会自己冒出来。
   * 只在有两个以上数据点时才画（Sparkline 自己会对 <2 个点返回 null）。
   */
  const columnCounts = useMemo(
    () => entries.map((e) => Number(e.columnCount)).filter(Number.isFinite),
    [entries],
  );

  const submittedText = submitted
    ? [
      `mode=${submitted.mode}`,
      submitted.params.connection ? `connection=${submitted.params.connection}` : 'connection=（未填）',
      submitted.params.schema ? `schema=${submitted.params.schema}` : null,
      submitted.params.tableName ? `tableName=${submitted.params.tableName}` : null,
      submitted.params.keyword ? `keyword=${submitted.params.keyword}` : null,
    ].filter(Boolean).join(' · ')
    : null;

  return (
    <VStack gap={6}>
      {/* config 拉失败也要说：拉不到就没法判断功能开没开，那不等于「功能是开的」。 */}
      <ErrorNotice error={config.error} />

      {isFeatureOff && (
        <Banner
          status="info"
          title="本部署没有开启数据目录功能（entropy.mcp.database.catalog.enabled=false）"
          description={
            '下面的按钮仍然可以点，请求也仍然会发出去：CatalogTools 里这个开关除了在 '
            + '/catalog/config 被回显之外没有别的读处（features 模块里也搜不到对它的判断），'
            + '所以它当前并不短路任何调用。写出来是为了让「结果为空 / 行为不符预期」时'
            + '第一时间能想到这一条，而不是去查连接与权限。'
          }
          container="card"
        />
      )}

      <Banner
        status="warning"
        title="扫描整个 Schema 是这一页最贵的操作"
        description={
          '/catalog/scan 对每张表要三次元数据往返（工具内部并发上限 4），大 Schema 上是分钟级。'
          + '所以这一页默认不查任何东西，也不会被顶栏的自动刷新带着跑。'
          + '单表失败不中断整次扫描，那张表在结果里是「生成失败」占位。'
        }
        container="card"
      />

      <Section
        title="目录模块的生效配置"
        source="GET /api/ui/catalog/config · 不连库，挂载即拉；这一页唯一在没有任何注册连接时也能成功的端点"
      >
        <KpiGrid
          items={[
            {
              label: '功能开关',
              value: config.data?.enabled,
              status: config.data?.enabled === false ? 'warning' : undefined,
              statusLabel: '目录功能未开启',
              hint: 'entropy.mcp.database.catalog.enabled',
            },
            {
              label: '自动生成注释',
              value: config.data?.autoGenerateComments,
              hint: '表/列没有注释时是否按名字推断一句描述',
            },
            {
              label: '敏感检测',
              value: config.data?.enableSensitiveDetection,
              hint: '关掉之后 maxSensitivity 与敏感列清单都会是空的',
            },
            {
              label: '检索条数上限',
              value: config.data?.maxSearchResults,
              hint: 'catalog/search 不传 limit 时的生效值',
            },
          ]}
        />
      </Section>

      <Section
        title="查询条件"
        source={`${currentMode.endpoint} · 模式决定要填哪些参数`}
      >
        <VStack gap={4}>
          <SegmentedControl value={mode} onChange={setMode} label="查什么">
            {MODES.map((m) => (
              <SegmentedControlItem key={m.value} value={m.value} label={m.label} />
            ))}
          </SegmentedControl>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="connection"
              value={connection}
              onChange={setConnection}
              isRequired
              placeholder="必填"
              description="这一组四个端点都需要连接名；留空时后端返回的是 500 / SYS998，而不是一条参数错误"
              status={missingConnection
                ? {
                  type: 'error',
                  message: mode === 'search'
                    ? '必须先填连接名：检索路径留空会在服务内部 NPE，换回来一条「未知错误」'
                    : '必须先填连接名：留空的话后端返回的是 500，而不是一条参数错误',
                }
                : undefined}
              width={260}
            />
            {(mode === 'scan' || mode === 'sensitive') && (
              <TextInput
                label="schema"
                value={schema}
                onChange={setSchema}
                placeholder="留空用连接的默认 Schema"
                width={220}
              />
            )}
            {currentMode.needsTableName && (
              <TextInput
                label="tableName"
                value={tableName}
                onChange={setTableName}
                isRequired
                placeholder="表名"
                description="参数名跟着工具叫 tableName，不是 table"
                status={missingTableName
                  ? { type: 'error', message: 'tableName 必填，缺了后端 400' }
                  : undefined}
                width={240}
              />
            )}
            {currentMode.needsKeyword && (
              <TextInput
                label="keyword"
                value={keyword}
                onChange={setKeyword}
                isRequired
                placeholder="匹配表名与表注释"
                status={missingKeyword
                  ? { type: 'error', message: 'keyword 必填，缺了后端 400' }
                  : undefined}
                width={260}
              />
            )}
            <Button
              label="查询"
              variant="primary"
              isDisabled={!canSubmit}
              isLoading={result.isLoading}
              onClick={() => setSubmitted({
                mode,
                params: {
                  connection: trimmedConnection,
                  schema: schema.trim(),
                  tableName: tableName.trim(),
                  keyword: keyword.trim(),
                  /* limit 只有检索模式用得上，而且只有传了才会盖掉配置里的
                     max-search-results（后端刻意不给本地默认值），所以别的模式一律不传。 */
                  limit: mode === 'search' ? limit : undefined,
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

      {submittedMode === 'scan' && (
        <VStack gap={5}>
          <Section
            title="扫描汇总"
            source={`GET /api/ui/catalog/scan · ${submittedText}`}
          >
            <VStack gap={4}>
              <KpiGrid
                items={[
                  { label: '表总数', value: data?.totalTables },
                  {
                    label: '含敏感列的表',
                    value: data?.sensitiveTableCount,
                    status: data?.sensitiveTableCount > 0 ? 'warning' : 'success',
                    statusLabel: data?.sensitiveTableCount > 0
                      ? '有表被判定含敏感字段'
                      : '没有表被判定含敏感字段',
                    hint: '按命名规则推断，不看数据内容',
                  },
                  { label: 'Schema', value: data?.schema ?? '（连接默认）' },
                  { label: '连接', value: data?.connection },
                ]}
              />
              <Sparkline values={columnCounts} label="每张表的列数（按返回顺序）" unit=" 列" />
            </VStack>
          </Section>

          <InteractiveTable
            title="扫描结果"
            source={`GET /api/ui/catalog/scan 的 entries · ${SENSITIVITY_CAVEAT}`}
            rows={entries}
            columns={SCAN_COLUMNS}
            searchFields={SCAN_SEARCH_FIELDS}
            searchName="catalog-scan"
            getRowKey={SCAN_ROW_KEY}
            getRowTone={(row) => sensitivityTone(row.maxSensitivity)}
            detailTitle="表目录详情"
            csvBaseName="catalog-scan"
            emptyTitle="这次扫描没有返回任何表"
            emptyDescription="Schema 里确实没有表，或者账号看不到它们的元数据。"
          />
        </VStack>
      )}

      {submittedMode === 'sensitive' && (
        <VStack gap={5}>
          <Section
            title="敏感列汇总"
            source={`GET /api/ui/catalog/sensitive · ${submittedText}`}
          >
            <KpiGrid
              items={[
                {
                  label: '敏感列数',
                  value: data?.sensitiveColumnCount,
                  status: data?.sensitiveColumnCount > 0 ? 'warning' : 'success',
                  statusLabel: data?.sensitiveColumnCount > 0 ? '有字段被判定敏感' : '没有字段被判定敏感',
                },
                { label: 'Schema', value: data?.schema ?? '（连接默认）' },
                { label: '连接', value: data?.connection },
              ]}
            />
          </Section>

          <InteractiveTable
            title="敏感列清单"
            source={`GET /api/ui/catalog/sensitive 的 columns · ${SENSITIVITY_CAVEAT}`}
            rows={sensitiveCols}
            columns={SENSITIVE_COLUMNS}
            searchFields={SENSITIVE_SEARCH_FIELDS}
            searchName="catalog-sensitive"
            getRowKey={SENSITIVE_ROW_KEY}
            getRowTone={(row) => sensitivityTone(row.sensitivity)}
            detailTitle="敏感列详情"
            csvBaseName="catalog-sensitive"
            emptyTitle="没有字段被判定为敏感"
            emptyDescription={
              '两种可能，页面区分不了：这个 Schema 里确实没有命名像敏感字段的列，'
              + '或者上方配置里的「敏感检测」是关的。'
            }
          />
        </VStack>
      )}

      {submittedMode === 'table' && (
        <Section
          title={`单表目录 ${displayValue(data?.tableName)}`}
          source={`GET /api/ui/catalog/table · ${submittedText} · ${SENSITIVITY_CAVEAT}`}
          count={data ? sensitiveCols.length : undefined}
        >
          <VStack gap={4}>
            <KpiGrid
              items={[
                {
                  label: '最高敏感级别',
                  value: data?.maxSensitivity,
                  status: sensitivityTone(data?.maxSensitivity) ?? undefined,
                  statusLabel: '这张表里有高敏感字段',
                },
                {
                  label: '含敏感列',
                  value: data?.hasSensitiveColumns,
                  status: data?.hasSensitiveColumns === true ? 'warning' : 'success',
                  statusLabel: data?.hasSensitiveColumns === true ? '有' : '无',
                },
                { label: '列数', value: data?.columnCount },
                { label: '业务分类', value: data?.category },
                { label: '行数', value: data?.rowCount, hint: '来自元数据估算，不是 count(*)' },
                { label: '大小(MB)', value: data?.tableSizeMb },
                { label: '关键词', value: data?.keywords },
                { label: '描述', value: data?.description },
              ]}
            />
            <DataTable
              columns={TABLE_COLUMNS}
              rows={sensitiveCols}
              emptyTitle={result.error ? '这次查询失败了' : '这张表返回了 0 列'}
              emptyDescription={
                result.error
                  ? '错误原文见上方。'
                  : '请求成功但 columns 是空的——表名对得上、账号也能读元数据的话，这值得去看一眼后端。'
              }
            />
          </VStack>
        </Section>
      )}

      {submittedMode === 'search' && (
        <VStack gap={5}>
          <Section
            title="检索汇总"
            source={`GET /api/ui/catalog/search · ${submittedText}`}
          >
            <KpiGrid
              items={[
                { label: '命中条数', value: data?.resultCount },
                {
                  label: '生效上限',
                  value: data?.maxResults,
                  /* 显示后端回显的 maxResults 而不是前端手里的 limit：不传 limit 时生效值来自
                     配置里的 max-search-results，回显自己的入参会说错话。 */
                  hint: '后端回显的生效值 · 命中条数等于它时结果被截断了',
                },
                { label: '关键词', value: data?.keyword },
                { label: '连接', value: data?.connection },
              ]}
            />
          </Section>

          <InteractiveTable
            title="资产检索结果"
            source="GET /api/ui/catalog/search 的 assets · 匹配表名与表注释，两侧自动加通配符"
            rows={assets}
            columns={ASSET_COLUMNS}
            searchFields={ASSET_SEARCH_FIELDS}
            searchName="catalog-assets"
            getRowKey={ASSET_ROW_KEY}
            detailTitle="资产详情"
            csvBaseName="catalog-assets"
            emptyTitle="没有命中任何资产"
            emptyDescription="关键词只匹配表名与表注释，不匹配列名——找字段请用「敏感列」或「单表目录」。"
          />
        </VStack>
      )}
    </VStack>
  );
}

/*
 * ── 与需求说明不一致的三处，以源码为准 ──
 *
 * 1. 说明里写「catalog 这组缺 connection 时后端返回 500」。四个端点里只有三个是那样：
 *    CatalogTools.searchAssets 没有 validateRequired(connection)。但它<b>更该挡</b> ——
 *    方法签名上的注释写明 null 连接名会在服务内部 NPE，客户端拿到的是 SYS998「未知错误」。
 *    所以页面对四个模式一律要求连接名。
 *
 * 2. 说明里的 /catalog/table 返回字段有 category、rowCount、tableSizeMb 等，这些都对；
 *    但它的 columns 每一项的键名是 name / dataType / nullable / comment / sensitivity /
 *    category / suggestion（CatalogTools.formatColumns），而 /catalog/sensitive 的 columns
 *    是 table / column / dataType / comment / sensitivity / category / suggestedClassification，
 *    /catalog/scan 的 entries 又是第三套。三套键名，所以页面里是三份列定义。
 *
 * 3. 说明里的 /catalog/scan entries 没提具体字段，实际只有七项
 *    （tableName、tableComment、rowCount、category、maxSensitivity、hasSensitiveColumns、
 *    columnCount）—— 没有 schema、没有 tableSizeMb、没有 description，尽管
 *    DataCatalogEntry 这个 record 里有。按 record 写会多出几列永远是「—」的空列。
 */
