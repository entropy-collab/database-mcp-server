import {
  Badge,
  Banner,
  Button,
  HStack,
  Text,
  TextArea,
  TextInput,
  VStack,
} from '@astryxdesign/core';
import { useMemo, useState } from 'react';
import {
  fetchOptimizerConfig,
  fetchSqlAnalyze,
  fetchSqlIndexes,
  fetchSqlPlan,
  fetchSqlRewrites,
  fetchSqlRisk,
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
  sqlColumn,
  textColumn,
} from '../components.jsx';

/**
 * SQL 体检。来源 GET /api/ui/sql/risk、/sql/plan、/sql/analyze、/sql/rewrites、/sql/indexes、
 * /sql/optimizer-config。
 *
 * ── SQL 走查询串，这是一个有代价的选择，代价必须写在界面上 ──
 * 这个面板的安全边界是「只发 GET」：全站没有一个写路径，前端也不需要 CSRF token。
 * 把 SQL 改成 POST body 会更干净，但那会打破这条边界，所以后端刻意保持 GET。两个代价：
 *
 * 1. <b>URL 长度上限</b>。Tomcat 的 maxHttpRequestHeaderSize（常见默认约 8KB）约束的是
 *    整个请求行 + 头部。粘一段几千行的报表 SQL 进去会以 400 或截断的形式失败，
 *    而不是「分析结果不对」——所以页面上实时显示当前 SQL 的字节数，并在接近上限时变色。
 *    不显示的话，第一个撞上它的人会以为是分析功能坏了。
 * 2. <b>SQL 会进 access log</b>。查询参数会被网关、反向代理、容器的访问日志原样记下来，
 *    连带 SQL 里的字面量（可能含手机号、证件号这类值）。POST body 通常不记，query string 一定记。
 *    也就是说：在这里贴一条带真实身份证号的 WHERE，那个号码就落到了访问日志里。
 *
 * 这两条不是这批端点新引入的能力（同样的分析作为 MCP 工具一直可调），但传输方式是这里选的。
 *
 * ── 四个按钮而不是一个 ──
 * 四条链路的开销差得很远：/sql/rewrites 是纯静态文本分析（本组唯一不连库的），
 * /sql/risk 要查表行数，/sql/plan 要真的取执行计划，/sql/analyze 是三者的超集因而最慢。
 * 合成一个「体检」按钮的话，只想看重写建议的人（一个常见诉求，而且连不上库时也能用）
 * 得付掉整份分析的代价。所以按开销分成四个入口，每个自己说清连不连库。
 */

/**
 * URL 长度的警戒线。
 *
 * 8KB 是 Tomcat maxHttpRequestHeaderSize 的常见默认值，而它管的是<b>整个</b>请求行加头部，
 * 不只是这段 SQL：Cookie、User-Agent、Accept 都要从这个预算里扣。所以警戒线取一半（4KB）
 * 而不是贴着 8KB —— 贴着上限提示等于在已经会失败的时候才提示。
 *
 * 用字节数而不是字符数：URL 编码后一个中文字符是 9 个字节（%E4%B8%AD 这种，每字节 3 个字符），
 * 按 length 算会严重低估。TextEncoder 是标准 API，不引依赖。
 */
const URL_BUDGET_BYTES = 8 * 1024;
const URL_WARN_BYTES = 4 * 1024;

function encodedByteLength(sql) {
  if (!sql) {
    return 0;
  }
  /* 量的是 encodeURIComponent 之后的长度：真正进 URL 的是它，不是原文。 */
  return new TextEncoder().encode(encodeURIComponent(sql)).length;
}

/**
 * 四个动作。needsConnection 那一列是从工具源码读出来的，不是猜的：
 * - assessQueryRisk / explainPlan（QueryAnalysisTools）与 analyzeQuery / recommendIndexes
 *   （OptimizationTools）方法体第一行就是 validateRequired(connection, "connection")；
 * - suggestRewrites 只校验 sql，connection 仅作回显。
 * 所以只有 rewrites 那一项能在连接名留空时成功，其余三项留空会拿到一条 500。
 */
const ACTIONS = [
  {
    value: 'risk',
    label: '风险评估',
    endpoint: 'GET /api/ui/sql/risk',
    needsConnection: true,
    note: '不执行 SQL，但会真的查表行数（按表名缓存 10 分钟）',
  },
  {
    value: 'plan',
    label: '执行计划',
    endpoint: 'GET /api/ui/sql/plan',
    needsConnection: true,
    note: '只接 SELECT 或以 WITH 开头的语句；方言不提供 EXPLAIN 时是 500',
  },
  {
    value: 'analyze',
    label: '全面分析',
    endpoint: 'GET /api/ui/sql/analyze',
    needsConnection: true,
    note: '计划 + 索引建议 + 重写建议 + 行动项，是另外三个的超集，也最慢',
  },
  {
    value: 'rewrites',
    label: '改写建议',
    endpoint: 'GET /api/ui/sql/rewrites',
    needsConnection: false,
    note: '纯静态文本分析，本组唯一不连库的一项 —— 连不上库时它仍然可用',
  },
];

/**
 * 索引推荐的列。
 *
 * 键名照 IndexRecommendation 这个 record 的字段：table / column / indexType /
 * recommendedSql / reason / priority。<b>是 column 而不是 columns</b>（复合索引时它的值是
 * "列1, 列2" 这样一个字符串），priority 是数字且<b>越小越优先</b>——所以那一列的表头写清方向，
 * 否则按它排序的人会以为数字大的更急。
 */
const INDEX_COLUMNS = [
  textColumn('table', '表', { flex: 2, weight: 'semibold', filter: 'table' }),
  textColumn('column', '列', { flex: 2, filter: 'column' }),
  textColumn('indexType', '索引类型', { flex: 1, filter: 'indexType' }),
  numberColumn('priority', '优先级(小=急)', { px: 124 }),
  textColumn('reason', '理由', { flex: 3, filter: 'reason' }),
  sqlColumn('recommendedSql', '建议的 DDL', { flex: 4 }),
];

const INDEX_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'table', type: 'string', label: '表' },
  { key: 'column', type: 'string', label: '列' },
  { key: 'indexType', type: 'string', label: '索引类型' },
  { key: 'reason', type: 'string', label: '理由' },
];

const INDEX_ROW_KEY = contentRowKey(['table', 'column', 'indexType']);

/**
 * 重写建议的列。键名照 RewriteSuggestion 这个 record：
 * type / originalPattern / suggestedPattern / reason / transformedSql。
 * 它<b>没有</b> severity —— 第一版按 pattern / description / severity 写的，
 * 那三个键在返回体里一个都不存在，页面上会是四列整齐的「—」。
 */
const REWRITE_COLUMNS = [
  textColumn('type', '类型', { flex: 1, weight: 'semibold', filter: 'type' }),
  textColumn('originalPattern', '原写法', { flex: 2, filter: 'originalPattern' }),
  textColumn('suggestedPattern', '建议写法', { flex: 2, filter: 'suggestedPattern' }),
  textColumn('reason', '理由', { flex: 3, filter: 'reason' }),
  sqlColumn('transformedSql', '改写后的 SQL', { flex: 4 }),
];

const REWRITE_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'type', type: 'string', label: '类型' },
  { key: 'originalPattern', type: 'string', label: '原写法' },
  { key: 'suggestedPattern', type: 'string', label: '建议写法' },
  { key: 'reason', type: 'string', label: '理由' },
];

const REWRITE_ROW_KEY = contentRowKey(['type', 'originalPattern', 'reason']);

/** 风险等级着色。后端给的是小写的 low / medium / high（不是枚举名），所以按小写比。 */
function riskStatus(level) {
  const value = String(level ?? '').toLowerCase();
  if (value === 'high') {
    return 'error';
  }
  if (value === 'medium') {
    return 'warning';
  }
  if (value === 'low') {
    return 'success';
  }
  return undefined;
}

/**
 * 一串字符串（warnings / actionItems / tables / suggestions）铺成标签或列表。
 *
 * 这些字段后端给的是字符串数组，长度从 0 到十几条不等。塞进单元格会被 displayValue
 * 变成一行 JSON，而它们各自是要逐条读的句子。所以短的走 Badge、长的走带序号的文本行——
 * 判据是「像标签还是像句子」，取 24 个字符作分界（表名、列名都远短于它，
 * 而告警和行动项都是完整句子）。
 */
function StringList({ items, emptyText }) {
  const list = Array.isArray(items) ? items.filter((x) => x !== null && x !== undefined) : [];
  if (list.length === 0) {
    return <Text type="supporting" color="secondary">{emptyText}</Text>;
  }
  const isShort = list.every((x) => String(x).length <= 24);
  if (isShort) {
    return (
      <HStack gap={2} wrap="wrap">
        {list.map((x, i) => (
          <Badge key={`${x}-${i}`} variant="neutral" label={String(x)} />
        ))}
      </HStack>
    );
  }
  return (
    <VStack gap={1}>
      {list.map((x, i) => (
        <Text key={`${x}-${i}`} type="supporting" wordBreak="break-all">
          {`${i + 1}. ${String(x)}`}
        </Text>
      ))}
    </VStack>
  );
}

export default function SqlDoctorPanel({ refreshToken }) {
  const [sql, setSql] = useState('');
  const [connection, setConnection] = useState('');
  const [submitted, setSubmitted] = useState(null);

  const [indexTable, setIndexTable] = useState('');
  const [submittedIndex, setSubmittedIndex] = useState(null);

  /* optimizer-config 不连库、无入参，跟着挂载就拉。 */
  const config = usePanelData(() => fetchOptimizerConfig(), [refreshToken]);
  const cfg = config.data;

  const result = usePanelData(
    () => {
      const p = { sql: submitted.sql, connection: submitted.connection };
      switch (submitted.action) {
        case 'risk':
          return fetchSqlRisk(p);
        case 'plan':
          return fetchSqlPlan(p);
        case 'analyze':
          return fetchSqlAnalyze(p);
        case 'rewrites':
          return fetchSqlRewrites(p);
        default:
          throw new Error(`未知的动作：${submitted.action}`);
      }
    },
    [JSON.stringify(submitted), refreshToken],
    { enabled: submitted !== null },
  );

  const indexes = usePanelData(
    () => fetchSqlIndexes(submittedIndex),
    [JSON.stringify(submittedIndex), refreshToken],
    { enabled: submittedIndex !== null },
  );

  const trimmedSql = sql.trim();
  const trimmedConnection = connection.trim();
  const sqlBytes = useMemo(() => encodedByteLength(trimmedSql), [trimmedSql]);
  const overBudget = sqlBytes >= URL_BUDGET_BYTES;
  const nearBudget = !overBudget && sqlBytes >= URL_WARN_BYTES;
  const missingSql = trimmedSql === '';

  const action = submitted?.action ?? null;
  const data = result.data;

  const planRows = useMemo(() => {
    /* /sql/plan 把计划行放在 plan 下、/sql/analyze 放在 planRows 下 —— 两个键名不一样，都要接。 */
    const raw = data?.planRows ?? data?.plan;
    return Array.isArray(raw) ? raw : [];
  }, [data]);

  /*
   * 计划行的列名是<b>方言的 EXPLAIN 输出列名</b>（executeExplainPlan 返回
   * List<Map<String,String>>，键就是那条 EXPLAIN 查出来的列），所以只能动态推导。
   * 第一版按 operation / cost / cardinality 写死，那是 Oracle 的 PLAN_TABLE 口径，
   * 在 MySQL / PostgreSQL 上会是整表的「—」。
   *
   * 依赖用键名拼成的字符串而不是 planRows：后者每次刷新都是新数组，用它当依赖等于白算。
   */
  const planKeySignature = planRows.length > 0 ? Object.keys(planRows[0]).join('\u0000') : '';
  const planColumns = useMemo(() => {
    const keys = planKeySignature === '' ? [] : planKeySignature.split('\u0000');
    return keys.map((key) => textColumn(key, key, { flex: 1 }));
  }, [planKeySignature]);

  /* plan 也可能是一整段文本（方言原样吐出来的 EXPLAIN 输出），那时上面那个数组是空的。 */
  const planText = typeof data?.plan === 'string' ? data.plan : '';

  const rewriteRows = useMemo(() => {
    const raw = data?.rewriteSuggestions ?? data?.suggestions;
    return Array.isArray(raw) ? raw : [];
  }, [data]);

  const indexRows = useMemo(
    () => (Array.isArray(data?.indexRecommendations) ? data.indexRecommendations : []),
    [data],
  );

  const indexOnlyRows = useMemo(
    () => (Array.isArray(indexes.data?.recommendations) ? indexes.data.recommendations : []),
    [indexes.data],
  );

  /*
   * /sql/risk 的 suggestions 是字符串数组（一条条建议），
   * 而 /sql/rewrites 的 suggestions 是对象数组（结构化的重写建议）。同名不同形，
   * 所以按当前动作分开处理——统一当成一种会在其中一条链路上显示成 [object Object]。
   */
  const riskSuggestions = action === 'risk' && Array.isArray(data?.suggestions)
    ? data.suggestions
    : [];

  /** tables 是 {表名: 估算行数} 的 Map，拍成数组以便逐条显示「表名：行数」。 */
  const riskTables = useMemo(() => {
    if (action !== 'risk' || !data?.tables || typeof data.tables !== 'object') {
      return [];
    }
    return Object.entries(data.tables).map(([name, rows]) => ({ name, rows }));
  }, [action, data]);

  const currentAction = ACTIONS.find((a) => a.value === action);
  const missingIndexTable = indexTable.trim() === '';

  return (
    <VStack gap={6}>
      <ErrorNotice error={config.error} />

      {/*
        这条横幅说的是「你贴进去的 SQL 会去哪儿」，属于使用这一页之前必须知道的事，
        所以用 warning 且放在最上面、不可关闭。
      */}
      <Banner
        status="warning"
        title="SQL 走查询串：会进访问日志，而且有长度上限"
        description={
          '这一页只发 GET（整个面板没有写路径，也就不需要 CSRF），代价是 SQL 只能放在查询参数里：\n'
          + '1) 它会被网关、反向代理、容器的访问日志原样记下来，连带 SQL 里的字面量。'
          + '在这里贴一条带真实手机号 / 证件号的 WHERE，那个值就落到访问日志里了 —— '
          + 'POST body 通常不记，query string 一定记。\n'
          + `2) URL 长度上限：Tomcat 的 maxHttpRequestHeaderSize 常见默认约 ${URL_BUDGET_BYTES / 1024}KB，`
          + '而它管的是整个请求行加头部（Cookie、UA 都要从这个预算里扣）。超长 SQL 会以 400 '
          + '或被截断的形式失败，而不是给出一个"不对的分析结果"。下面的输入框实时显示编码后的字节数。'
        }
        container="card"
      />

      <Section
        title="优化器模块的生效配置"
        source={
          'GET /api/ui/sql/optimizer-config · 不连库、挂载即拉 · '
          + '已知事实：这四项当前只是配置回显，工具实现没有按它们裁剪结果'
        }
      >
        <KpiGrid
          items={[
            {
              label: '功能开关',
              value: cfg?.enabled,
              status: cfg?.enabled === false ? 'warning' : undefined,
              statusLabel: '优化器功能未开启',
            },
            {
              label: '每条查询最多几条建议',
              value: cfg?.maxSuggestionsPerQuery,
              hint: '仅回显 · 实际结果可能比它多，别用它解释建议条数',
            },
            {
              label: '最多几条索引推荐',
              value: cfg?.maxIndexRecommendations,
              hint: '同上，仅回显',
            },
            {
              label: '复合索引分析',
              value: cfg?.enableCompositeIndexAnalysis,
              hint: '同上，仅回显',
            },
          ]}
        />
      </Section>

      <Section
        title="要体检的 SQL"
        source="四个按钮的开销差很远：改写建议不连库，风险评估查表行数，执行计划真的取计划，全面分析是三者的超集"
      >
        <VStack gap={4}>
          <TextArea
            label="SQL"
            value={sql}
            onChange={setSql}
            rows={8}
            isRequired
            placeholder={'SELECT ...\n（执行计划与全面分析只接 SELECT 或以 WITH 开头的语句）'}
            /* hasSpellCheck 关掉：SQL 里几乎每个标识符都会被拼写检查画上红波浪线，
               满屏红线之后真正的问题反而看不见。 */
            hasSpellCheck={false}
            description={
              `编码后 ${sqlBytes} 字节`
              + `（URL 预算约 ${URL_BUDGET_BYTES} 字节，还要和请求头共享）`
            }
            status={
              missingSql
                ? { type: 'error', message: 'sql 必填，缺了后端 400' }
                : overBudget
                  ? {
                    type: 'error',
                    message: '已经超过 URL 预算：这次请求很可能以 400 或被截断的形式失败。'
                      + '把 SQL 精简到能复现问题的最小片段，或者改用 MCP 工具（那条路走 body）。',
                  }
                  : nearBudget
                    ? {
                      type: 'warning',
                      message: '接近 URL 预算的一半。Cookie 与请求头也要从同一个预算里扣，'
                        + '所以再长下去就有失败风险了。',
                    }
                    : undefined
            }
            width="100%"
          />
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="connection"
              value={connection}
              onChange={setConnection}
              placeholder="改写建议之外的三项都需要"
              description="risk / plan / analyze 在工具内部对 connection 做必填校验，缺了是 500"
              width={260}
            />
            {ACTIONS.map((a) => (
              <Button
                key={a.value}
                label={a.label}
                variant={a.value === 'analyze' ? 'primary' : 'secondary'}
                isDisabled={missingSql || (a.needsConnection && trimmedConnection === '')}
                isLoading={result.isLoading && action === a.value}
                /* 按钮自己带 tooltip 说明这一项连不连库：把四条说明堆在下面
                   会变成一段没人读的小字，而这个信息只在决定点哪个按钮时有用。 */
                tooltip={`${a.endpoint} · ${a.note}`}
                onClick={() => setSubmitted({
                  action: a.value,
                  sql: trimmedSql,
                  connection: trimmedConnection,
                })}
              />
            ))}
          </HStack>
          {trimmedConnection === '' && (
            <Text type="supporting" color="secondary">
              连接名留空时只有「改写建议」可用 —— 另外三项在工具内部对 connection
              做必填校验，会返回 500 而不是一条参数错误，所以这里先把按钮置灰。
            </Text>
          )}
          {submitted === null && (
            <Text type="supporting" color="secondary">
              还没点过任何按钮，这一页目前没有向任何库发过请求。
            </Text>
          )}
        </VStack>
      </Section>

      <ErrorNotice error={result.error} />

      {action !== null && data != null && (
        <Section
          title={`${currentAction?.label ?? action} 的结果`}
          source={`${currentAction?.endpoint} · ${currentAction?.note}`}
        >
          <VStack gap={4}>
            {/* 风险评估的三个数 + 建议。riskScore / riskLevel 只有这条链路有。 */}
            {action === 'risk' && (
              <VStack gap={4}>
                <KpiGrid
                  items={[
                    {
                      label: '风险等级',
                      value: data.riskLevel,
                      status: riskStatus(data.riskLevel),
                      statusLabel: `风险等级 ${displayValue(data.riskLevel)}`,
                    },
                    { label: '风险评分', value: data.riskScore },
                    { label: '连接', value: data.connection },
                  ]}
                />
                <VStack gap={2}>
                  <Text type="label" weight="semibold">涉及的表与它们的估算行数</Text>
                  {/*
                    tables 是一个 <b>Map</b>（表名 → 估算行数），不是字符串数组 ——
                    第一版按数组渲染，Object 进 Badge 会变成 [object Object]。
                    行数是评分的主要依据（超 1000 万 +3、超 100 万 +2、超 10 万 +1），
                    所以必须把数字一起显示：只列表名的话，看不出这个分是怎么来的。
                    取不到行数的表根本不会进这个 Map（工具跳过了它们），也就不计分 ——
                    所以「SQL 里明明有五张表，这里只有三张」是正常的，那两张没算进分数。
                  */}
                  {riskTables.length === 0
                    ? (
                      <Text type="supporting" color="secondary">
                        没能从这条 SQL 里解析出任何带行数的表。两种可能：表名解析失败，
                        或者所有表的行数都取不到（那时风险评分只反映语句结构，不反映数据量）。
                      </Text>
                    )
                    : (
                      <HStack gap={2} wrap="wrap">
                        {riskTables.map(({ name, rows }) => (
                          <Badge key={name} variant="neutral" label={`${name}：${rows} 行`} />
                        ))}
                      </HStack>
                    )}
                </VStack>
                <VStack gap={2}>
                  <Text type="label" weight="semibold">建议</Text>
                  <StringList items={riskSuggestions} emptyText="没有给出建议。" />
                </VStack>
                {data.recommendation && (
                  <VStack gap={2}>
                    <Text type="label" weight="semibold">总体结论</Text>
                    <Text type="supporting" wordBreak="break-all">
                      {displayValue(data.recommendation)}
                    </Text>
                  </VStack>
                )}
              </VStack>
            )}

            {/* 执行计划：originalSql / explainSql 要显示出来 —— 后者是真正发给库的那条语句。 */}
            {(action === 'plan' || action === 'analyze') && (
              <VStack gap={4}>
                {data.explainSql && (
                  <VStack gap={2}>
                    <HStack gap={3} vAlign="center" wrap="wrap">
                      <Text type="label" weight="semibold">实际发给数据库的 EXPLAIN 语句</Text>
                      <CopyTextButton
                        text={String(data.explainSql)}
                        label="复制 EXPLAIN"
                        what="EXPLAIN 语句"
                      />
                    </HStack>
                    <MonoBlock text={String(data.explainSql)} />
                  </VStack>
                )}
                <VStack gap={2}>
                  <Text type="label" weight="semibold">告警</Text>
                  <StringList items={data.warnings} emptyText="没有告警。" />
                </VStack>
                {planRows.length > 0 && (
                  <VStack gap={2}>
                    <Text type="label" weight="semibold">计划行</Text>
                    <DataTable
                      columns={planColumns}
                      rows={planRows}
                      emptyTitle="没有计划行"
                      emptyDescription="走不到这里。"
                    />
                  </VStack>
                )}
                {planRows.length === 0 && planText !== '' && (
                  <VStack gap={2}>
                    <Text type="label" weight="semibold">计划原文</Text>
                    <Text type="supporting" color="secondary">
                      这个方言的 EXPLAIN 输出是一整段文本而不是结构化行，所以原样显示。
                    </Text>
                    <MonoBlock text={planText} />
                  </VStack>
                )}
              </VStack>
            )}

            {/* 全面分析额外给索引建议、重写建议与行动项。 */}
            {action === 'analyze' && (
              <VStack gap={4}>
                <VStack gap={2}>
                  <Text type="label" weight="semibold">行动项</Text>
                  <StringList items={data.actionItems} emptyText="没有行动项。" />
                </VStack>
                {indexRows.length > 0 && (
                  <VStack gap={2}>
                    <Text type="label" weight="semibold">索引建议</Text>
                    <Text type="supporting" color="secondary">
                      recommendedSql 是可执行的 CREATE INDEX 文本，但这个页面不提供执行它的地方
                      —— 建索引要另外走 MCP 工具或 DBA 流程。
                    </Text>
                    <DataTable
                      columns={INDEX_COLUMNS}
                      rows={indexRows}
                      emptyTitle="没有索引建议"
                      emptyDescription="走不到这里。"
                    />
                  </VStack>
                )}
              </VStack>
            )}

            {/*
              改写建议刻意<b>不</b>放在这个 Section 里，而是作为页面底部一张独立的
              InteractiveTable（见文件末尾）：它需要搜索、排序和 CSV 导出，
              而 InteractiveTable 自带 Card，嵌进这个 Section 会出现卡片套卡片。
              两处都渲染一遍是我最初的写法，效果是同一份建议在页面上出现两次。
            */}
          </VStack>
        </Section>
      )}

      <Section
        title="按表名要索引推荐"
        source={
          'GET /api/ui/sql/indexes · 本组唯一以表名（不是 SQL）为输入的端点 · '
          + '工具内部对 connection 也做必填校验'
        }
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="table"
              value={indexTable}
              onChange={setIndexTable}
              isRequired
              placeholder="表名"
              status={missingIndexTable
                ? { type: 'error', message: 'table 必填，缺了后端 400' }
                : undefined}
              width={240}
            />
            <Button
              label="查询"
              variant="secondary"
              isDisabled={missingIndexTable || trimmedConnection === ''}
              isLoading={indexes.isLoading}
              onClick={() => setSubmittedIndex({
                table: indexTable.trim(),
                connection: trimmedConnection,
              })}
            />
          </HStack>
          {trimmedConnection === '' && (
            <Text type="supporting" color="secondary">
              这一项也需要上面那个连接名。
            </Text>
          )}
          {submittedIndex === null && (
            <Text type="supporting" color="secondary">
              还没查过索引推荐。
            </Text>
          )}
        </VStack>
      </Section>

      <ErrorNotice error={indexes.error} />

      {submittedIndex !== null && indexes.data && (
        <InteractiveTable
          title={`${displayValue(indexes.data.table ?? submittedIndex.table)} 的索引推荐`}
          source={
            `GET /api/ui/sql/indexes · 后端报的 recommendationCount=${displayValue(indexes.data.recommendationCount)}`
            + ' · recommendedSql 可执行，但本页面不提供执行入口'
          }
          rows={indexOnlyRows}
          columns={INDEX_COLUMNS}
          searchFields={INDEX_SEARCH_FIELDS}
          searchName="sql-indexes"
          getRowKey={INDEX_ROW_KEY}
          detailTitle="索引建议详情"
          detailSqlKey="recommendedSql"
          csvBaseName="sql-index-recommendations"
          emptyTitle="没有索引推荐"
          emptyDescription={
            '优化器认为这张表不需要新索引，或者它拿不到足够的统计信息来判断'
            + '（后者更常见：统计信息过期时推荐会退化成空）。'
          }
        />
      )}

      <InteractiveTable
        title="改写建议"
        source={
          '「改写建议」按钮来自 GET /api/ui/sql/rewrites 的 suggestions，'
          + '「全面分析」的结果里同样一份在 rewriteSuggestions 下 —— 两者共用这张表。'
          + '没命中任何反模式不等于这条 SQL 写得好，只等于它不长得像那几种典型写法。'
        }
        rows={action === 'rewrites' || action === 'analyze' ? rewriteRows : []}
        columns={REWRITE_COLUMNS}
        searchFields={REWRITE_SEARCH_FIELDS}
        searchName="sql-rewrites"
        getRowKey={REWRITE_ROW_KEY}
        detailTitle="改写建议详情"
        detailSqlKey="transformedSql"
        csvBaseName="sql-rewrites"
        emptyTitle="还没有改写建议"
        emptyDescription="点上面的「改写建议」或「全面分析」。前者不连库，连接名留空也能用。"
      />
    </VStack>
  );
}
