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
import { fetchQualityAlerts, fetchQualityTable, fetchQualityTemplates } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  CopyTextButton,
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
 * 数据质量。来源 GET /api/ui/quality/templates、/quality/table、/quality/alerts。
 *
 * ── 这一页有两句必须写在界面上的话，否则页面会主动骗人 ──
 *
 * 1. <b>/quality/alerts 恒返回空列表。</b>QualityTools.getQualityAlertSummary 的当前实现
 *    忽略 limit 参数，并且始终返回空的告警列表（工具描述里自己写着这一点）。
 *    所以那一块永远是空的——这是<b>实现现状</b>，不是「没有告警」。
 *    不写出来的话，一个空列表会被读成「数据健康」，而它其实什么都没说。
 *
 * 2. <b>评分 100 不等于业务规则都通过了。</b>控制器给 customRules 固定传 null
 *    （自定义规则是带 CUSTOM_SQL 类型的结构化列表，用查询参数表达等于往 URL 里塞 SQL，
 *    而这个页面只发 GET），所以这里跑的只有内置检查：逐列空值率 + 全列组合重复行。
 *    枚举值校验、区间校验、自定义 SQL 一条都没跑。
 *
 * 模板清单（/quality/templates）因此在这一页是<b>说明性</b>的：它解释了「内置检查覆盖了什么」
 * 以及「哪些规则只能通过 MCP 工具调用才跑得到」，而不是一份可以在这里勾选的菜单。
 */

/** formattedReport 的渲染格式。取值来自工具里那个 switch（json / csv / text），大小写不敏感。 */
const FORMATS = [
  { value: 'json', label: 'json' },
  { value: 'text', label: 'text' },
  { value: 'csv', label: 'csv' },
];

/**
 * 内置检查真正会执行的两种规则类型。
 *
 * 用来在模板清单里把「这条能在这一页跑到」和「这条只能走 MCP 工具」区分开。
 * 判据抄的是工具描述里那句话：null_rate 与 duplicates 由检查引擎自动执行，
 * 而 ENUM_VALUES / RANGE / CUSTOM_SQL 只能通过 customRules 传入——本页面传不了。
 */
const BUILTIN_RULE_IDS = new Set(['null_rate', 'duplicates']);

const TEMPLATE_COLUMNS = [
  textColumn('id', '规则 ID', { flex: 2, weight: 'semibold', filter: 'id' }),
  textColumn('name', '名称', { flex: 2, filter: 'name' }),
  textColumn('runsHere', '这一页跑得到', { flex: 1, filter: 'runsHere' }),
  textColumn('description', '说明', { flex: 4, filter: 'description' }),
  textColumn('parameters', '参数', { flex: 3 }),
];

const TEMPLATE_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'id', type: 'string', label: '规则 ID' },
  { key: 'name', type: 'string', label: '名称' },
  { key: 'runsHere', type: 'string', label: '这一页跑得到' },
  { key: 'description', type: 'string', label: '说明' },
];

const TEMPLATE_ROW_KEY = contentRowKey(['id']);

const ISSUE_COLUMNS = [
  textColumn('severity', '严重度', { flex: 1, filter: 'severity' }),
  textColumn('ruleName', '规则', { flex: 2, weight: 'semibold', filter: 'ruleName' }),
  textColumn('ruleType', '类型', { flex: 1, filter: 'ruleType' }),
  textColumn('column', '列', { flex: 2, filter: 'column' }),
  textColumn('actualValue', '实际值', { flex: 1 }),
  textColumn('threshold', '阈值', { flex: 1 }),
  numberColumn('issueCount', '问题行数', { px: 104 }),
  numberColumn('totalRows', '总行数', { px: 104 }),
  textColumn('detail', '明细', { flex: 3, filter: 'detail' }),
];

const ISSUE_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'severity', type: 'string', label: '严重度' },
  { key: 'ruleName', type: 'string', label: '规则' },
  { key: 'ruleType', type: 'string', label: '类型' },
  { key: 'column', type: 'string', label: '列' },
  { key: 'detail', type: 'string', label: '明细' },
];

const ISSUE_ROW_KEY = contentRowKey(['ruleId', 'column']);

/** 严重度着色。CRITICAL / ERROR 是 error，WARNING 是 warning，INFO 不着色。 */
function issueTone(row) {
  const severity = String(row.severity ?? '').toUpperCase();
  if (severity === 'CRITICAL' || severity === 'ERROR') {
    return 'error';
  }
  if (severity === 'WARNING') {
    return 'warning';
  }
  return null;
}

const ALERTS_CAVEAT = '这一块永远是空的：QualityTools.getQualityAlertSummary 的当前实现'
  + '忽略 limit 参数并且始终返回空的告警列表。所以空列表是实现现状，不是'
  + '「没有告警」，更不是「数据健康」。要判断数据质量，用上面的单表检查。';

export default function QualityPanel({ limit, refreshToken }) {
  const [connection, setConnection] = useState('');
  const [schema, setSchema] = useState('');
  const [tableName, setTableName] = useState('');
  const [format, setFormat] = useState('json');
  const [submitted, setSubmitted] = useState(null);
  const [alertsNonce, setAlertsNonce] = useState(null);

  /* templates 不连库、无入参，跟着挂载就拉。 */
  const templates = usePanelData(() => fetchQualityTemplates(), [refreshToken]);

  const check = usePanelData(
    () => fetchQualityTable(submitted),
    [JSON.stringify(submitted), refreshToken],
    { enabled: submitted !== null },
  );

  /*
   * 告警汇总也要手点，尽管它是个几乎什么都不做的实现。
   *
   * 理由不是开销，是别在页面上凭空造出一块「看起来在监控什么」的区域：
   * 一块自动出现的空告警列表，比一块要手点才出现的空列表更容易被当成「一切正常」。
   * 手点之后紧跟一条说明它为什么是空的，语义才是准的。
   */
  const alerts = usePanelData(
    () => fetchQualityAlerts({ limit }),
    [alertsNonce, limit, refreshToken],
    { enabled: alertsNonce !== null },
  );

  const templateRows = useMemo(() => {
    const raw = templates.data?.templates;
    if (!Array.isArray(raw)) {
      return [];
    }
    return raw.map((t) => ({
      ...t,
      /* 派生成中文字符串而不是布尔：PowerSearch 的 enum/string 分支都只认 string，
         而排序的默认比较器拿 "true"/"false" 排出来毫无意义。和 resultColumn 的取舍一致。 */
      runsHere: BUILTIN_RULE_IDS.has(String(t.id)) ? '是（内置检查）' : '否（只能走 MCP 工具）',
      /* parameters 是 {参数名: 类型说明} 的 Map，拍成一行可读文本：
         对象直接进单元格会被 displayValue 变成 JSON，那也能读，但列宽有限时不如 "a=b, c=d"。 */
      parameters: t.parameters && typeof t.parameters === 'object'
        ? Object.entries(t.parameters).map(([k, v]) => `${k}=${v}`).join(', ')
        : displayValue(t.parameters),
    }));
  }, [templates.data]);

  const report = check.data?.report;
  const issues = useMemo(
    () => (Array.isArray(report?.issues) ? report.issues : []),
    [report],
  );
  const formattedReport = typeof check.data?.formattedReport === 'string'
    ? check.data.formattedReport
    : '';

  const missingTableName = tableName.trim() === '';

  /*
   * 「评分高但其实什么都没查」的两种情况，需要在页面上区分出来：
   * - totalRows === 0：工具对空表直接返回评分 100 且不执行任何规则。那不是「质量好」。
   * - rulesChecked === 0：一条规则都没跑。
   * 这两种下的 overallScore 都是没有信息量的，所以不给它配「成功」色。
   */
  const emptyTable = report != null && Number(report.totalRows) === 0;
  const noRules = report != null && Number(report.rulesChecked) === 0;
  const scoreIsMeaningful = report != null && !emptyTable && !noRules;

  return (
    <VStack gap={6}>
      <ErrorNotice error={templates.error} />

      <Banner
        status="warning"
        title="这一页跑的只有内置检查：逐列空值率 + 全列组合重复行"
        description={
          '后端给自定义规则固定传 null——那是一个带 CUSTOM_SQL 类型的结构化列表，'
          + '用查询参数表达等于往 URL 里塞 SQL，而这个页面的安全边界是「只发 GET」。'
          + '所以枚举值校验、数值区间校验、自定义 SQL 一条都不会执行。'
          + '换句话说：这里的「评分 100」只意味着没有空值和重复行，'
          + '不意味着任何业务规则通过了。要跑那些规则，得走 MCP 工具的 checkTableQuality。'
        }
        container="card"
      />

      <Section
        title="单表质量检查"
        source="GET /api/ui/quality/table · 会真的扫目标表（逐列统计 + 全列组合去重），大表上很慢"
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
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
            <TextInput
              label="connection"
              value={connection}
              onChange={setConnection}
              placeholder="留空用默认连接"
              width={220}
            />
            <TextInput
              label="schema"
              value={schema}
              onChange={setSchema}
              placeholder="表不在登录 Schema 下时必须填"
              description="填错 / 该填而没填时工具直接报错，不再返回评分 100 的空报告"
              width={260}
            />
            <Button
              label="检查"
              variant="primary"
              isDisabled={missingTableName}
              isLoading={check.isLoading}
              onClick={() => setSubmitted({
                tableName: tableName.trim(),
                connection: connection.trim(),
                schema: schema.trim(),
                format,
              })}
            />
          </HStack>
          <SegmentedControl value={format} onChange={setFormat} label="formattedReport 的格式">
            {FORMATS.map((f) => (
              <SegmentedControlItem key={f.value} value={f.value} label={f.label} />
            ))}
          </SegmentedControl>
          <Text type="supporting" color="secondary">
            format 只影响下方那段 formattedReport 文本，不影响结构化的 report
            —— 两者是同一份报告的两种渲染。无法识别的取值按 json 处理。
          </Text>
          {submitted === null && (
            <Text type="supporting" color="secondary">
              还没点过「检查」，这一页目前没有向任何库发过请求。
            </Text>
          )}
        </VStack>
      </Section>

      <ErrorNotice error={check.error} />

      {report != null && (
        <VStack gap={5}>
          {emptyTable && (
            <Banner
              status="info"
              title="这张表是空的（totalRows=0），所以这份报告没有信息量"
              description={
                '工具对空表直接返回评分 100 并且不执行任何规则。'
                + '「100 分」在这里的意思是「没跑任何检查」，不是「质量完美」。'
              }
              container="card"
            />
          )}
          {noRules && !emptyTable && (
            <Banner
              status="warning"
              title="一条规则都没执行（rulesChecked=0）"
              description={
                '内置检查本应始终执行，rulesChecked=0 说明这次检查没有真的跑起来 —— '
                + '这时的 overallScore 不能当结论用。值得去看一眼服务端日志。'
              }
              container="card"
            />
          )}

          <Section
            title={`检查结果 ${displayValue(report.tableName)}`}
            source={
              `GET /api/ui/quality/table · schema=${displayValue(report.schema)}`
              + ` · connection=${displayValue(report.connectionKey)}`
              + ` · 检查时间 ${displayValue(report.checkedAt)}`
            }
          >
            <KpiGrid
              items={[
                {
                  label: '综合评分',
                  value: report.overallScore,
                  status: scoreIsMeaningful
                    ? (Number(report.issuesFound) > 0 ? 'warning' : 'success')
                    : 'neutral',
                  statusLabel: scoreIsMeaningful
                    ? (Number(report.issuesFound) > 0 ? '发现了问题' : '内置检查未发现问题')
                    : '这个分数没有信息量（空表或没跑规则）',
                  hint: '只反映空值率与重复行，不含任何业务规则',
                },
                { label: '总行数', value: report.totalRows },
                { label: '执行的规则数', value: report.rulesChecked },
                {
                  label: '发现的问题数',
                  value: report.issuesFound,
                  status: Number(report.issuesFound) > 0 ? 'warning' : 'success',
                  statusLabel: Number(report.issuesFound) > 0 ? '有问题' : '没有问题',
                },
              ]}
            />
          </Section>

          <InteractiveTable
            title="问题清单"
            source="report.issues · 每条含规则、列、实际值与阈值 · 严重度 CRITICAL/ERROR 标红，WARNING 标黄"
            rows={issues}
            columns={ISSUE_COLUMNS}
            searchFields={ISSUE_SEARCH_FIELDS}
            searchName="quality-issues"
            getRowKey={ISSUE_ROW_KEY}
            getRowTone={issueTone}
            detailTitle="问题详情"
            csvBaseName="quality-issues"
            emptyTitle="内置检查没有发现问题"
            emptyDescription={
              '注意这句话的范围：没有超过阈值的空值率、也没有全列重复的行。'
              + '业务规则（枚举、区间、自定义 SQL）在这一页一条都没跑过。'
            }
          />

          <Section
            title={`渲染后的报告文本（format=${displayValue(check.data?.format)}）`}
            source="返回体的 formattedReport · 和上面的表格是同一份报告，只是换了一种渲染"
            actions={
              <CopyTextButton text={formattedReport} label="复制报告" what="报告文本" />
            }
          >
            <MonoBlock text={formattedReport} emptyText="（后端返回了空的 formattedReport）" />
          </Section>
        </VStack>
      )}

      <Section
        title="质量告警汇总"
        source={`GET /api/ui/quality/alerts · ${ALERTS_CAVEAT}`}
        actions={
          <Button
            label={alertsNonce === null ? '读取告警' : '重新读取'}
            variant="secondary"
            isLoading={alerts.isLoading}
            /* nonce 而不是布尔：布尔已经是 true 时再点不会触发 usePanelData 的 effect，
               按钮就成了哑的（点了没反应，也不报错）。JobsPanel 里是同一个处理。 */
            onClick={() => setAlertsNonce(Date.now())}
          />
        }
      >
        <VStack gap={3}>
          <Banner
            status="info"
            title="这一块的空列表不代表「没有告警」"
            description={ALERTS_CAVEAT}
            container="card"
          />
          <ErrorNotice error={alerts.error} />
          {alertsNonce !== null && alerts.data && (
            /*
              形状是「原样透传」，后端也刻意不回显 limit（回显会显示一个比实际生效值大的数）。
              所以这里把返回体整体铺成键值对，而不是假装它有某个固定结构：
              一旦哪天实现补上了真正的告警，这块不用改也能显示出来。
            */
            <MonoBlock text={JSON.stringify(alerts.data, null, 2)} emptyText="（空响应）" />
          )}
          {alertsNonce === null && (
            <Text type="supporting" color="secondary">
              还没读取过。读了也会是空的，理由见上。
            </Text>
          )}
        </VStack>
      </Section>

      <InteractiveTable
        title="内置质量规则模板"
        source={
          'GET /api/ui/quality/templates · 不连库、挂载即拉 · '
          + '这份清单在这一页是说明性的：只有「这一页跑得到=是」的两条会真的执行'
        }
        rows={templateRows}
        columns={TEMPLATE_COLUMNS}
        searchFields={TEMPLATE_SEARCH_FIELDS}
        searchName="quality-templates"
        getRowKey={TEMPLATE_ROW_KEY}
        detailTitle="规则模板详情"
        csvBaseName="quality-templates"
        emptyTitle="没有规则模板"
        emptyDescription="/api/ui/quality/templates 返回了空清单。"
      />
    </VStack>
  );
}
