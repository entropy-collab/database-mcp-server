import {
  Banner,
  HStack,
  SegmentedControl,
  SegmentedControlItem,
  Text,
  Timestamp,
  VStack,
} from '@astryxdesign/core';
import { useState } from 'react';
import { fetchAuditReports } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  DataTable,
  ErrorNotice,
  KpiGrid,
  Section,
  booleanColumn,
  numberColumn,
  sqlColumn,
  textColumn,
} from '../components.jsx';

/**
 * 审计报告。来源 GET /api/ui/audit-reports?hours=&limit=（metrics + dataAccess + protection 一次取回）。
 *
 * 这一页有三块数据，它们的可用性不是一回事：
 * - metrics 读的是进程内计数器，永远可用；
 * - dataAccess / protection 要查审计表，审计未落库时后端返回 status="skipped" + reason，
 *   这<b>不是错误</b>（落库是 opt-in），所以按 info 横幅处理而不是走 ErrorNotice。
 *
 * hours 改变会重新拉数据，这不违反「自动刷新默认关闭」：那条约束禁的是页面自己起定时器
 * （页面的读操作会被 PerformanceTimingAspect 记进性能指标），用户手点一个时间档是显式请求。
 */

/**
 * 时间窗档位。
 *
 * 用 SegmentedControl 而不是 NumberInput：这一页的窗口没有「37 小时」这种有意义的取值，
 * 常用的就是「最近一小时 / 半天 / 一天 / 三天 / 一周」这几个，给自由输入等于让人自己去猜
 * 后端的 1..168 夹取范围。上限 168（7 天）与后端 MAX_WINDOW_HOURS 一致，所以每个档位都
 * 是不会被夹的真实值——页面上不会出现「我选了 720 却显示 168」。
 *
 * SegmentedControl 的 value 是 string，所以档位存成字符串，发请求时才转数字。
 */
const WINDOW_OPTIONS = [
  { value: '1', label: '1 小时' },
  { value: '6', label: '6 小时' },
  { value: '24', label: '24 小时' },
  { value: '72', label: '3 天' },
  { value: '168', label: '7 天（后端上限）' },
];

/**
 * protection 报告的两个硬编码，必须写在页面上。
 *
 * ComplianceReportService 内部固定按 5000ms 判慢查询、固定最多取 10000 条流水，两者都与
 * 全局配置无关。所以同一份返回里 metrics.slowQueryCount（按
 * entropy.mcp...slow-query-threshold-ms 算）和 protection.summary.slowQueryCount（按 5000ms 算）
 * 是两个不同标准下的数，对不上是正常的。不写这句的话，第一个发现两个数不一致的人会把它当 bug 报。
 */
const PROTECTION_CAVEAT = '注意：protection 报告内部硬编码慢查询阈值 5000ms、区间最多取 10000 条流水，'
  + '与上方 metrics.slowQueryThresholdMs 不是同一个阈值 —— 两处的慢查询数按不同标准算，'
  + '对不上是预期的，不是 bug。窗口越大越容易撞到 10000 条上限，撞到后这份报告描述的是被截断的样本。';

/**
 * toolBreakdown / connectionBreakdown 这类 {名字: 次数} 的 Map 拍成两列表，按次数降序。
 *
 * 降序是这两张表唯一有用的顺序：它们回答的是「主要是谁在用」，按名字排等于让人自己找最大值。
 * 次数相同时按名字排，图的是同一份数据两次刷新给出同样的行序——否则表格会无意义地跳。
 */
function breakdownRows(map) {
  if (!map || typeof map !== 'object') {
    return [];
  }
  return Object.entries(map)
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => (b.count - a.count) || a.name.localeCompare(b.name));
}

/**
 * 报告没出来时的横幅。
 *
 * 两个报告的 status 有三档：completed / skipped / error。skipped 是配置事实（没有审计表），
 * error 是生成报告时真出了问题（后端把它塞进返回体的 error 字段，HTTP 仍然是 200）——
 * 后者不能跟 skipped 用同一个语气，也不能靠 ErrorNotice 兜（ErrorNotice 只看 HTTP 层的失败，
 * 这个 200 它看不见）。原文一律用后端给的 reason / error，不在前端复述一遍。
 */
function ReportUnavailable({ title, report }) {
  const isSkipped = report?.status === 'skipped';
  return (
    <Banner
      status={isSkipped ? 'info' : 'error'}
      title={title}
      description={
        isSkipped
          ? `${report?.reason ?? '后端未给出 reason。'}`
            + '（这是配置事实而不是故障：审计落库是 opt-in 的，见 README 的「审计持久化（可选）」一节。）'
          : `status=${report?.status ?? '未知'} · ${report?.error ?? '后端未给出 error 原文。'}`
      }
      container="card"
    />
  );
}

export default function ReportsPanel({ limit, refreshToken }) {
  const [hours, setHours] = useState('24');

  const { data, error } = usePanelData(
    () => fetchAuditReports(Number(hours), limit),
    [hours, limit, refreshToken],
  );

  const metrics = data?.metrics ?? {};
  const dataAccess = data?.dataAccess;
  const protection = data?.protection;
  const summary = protection?.summary ?? {};

  /*
   * 判据是「status 是不是 completed」，而不是「status 是不是 skipped」。
   *
   * 两个报告的 status 有三档，只认 skipped 的话 error 那档会走到表格分支，表现为
   * 「这个窗口内没有审计流水」——把一次生成失败说成了一次成功的空查询。
   * 报告还没回来（undefined）时不出横幅：那是加载中，和「报告出不来」是两件事。
   */
  const dataAccessBlocked = dataAccess != null && dataAccess.status !== 'completed';
  const protectionBlocked = protection != null && protection.status !== 'completed';

  return (
    <VStack gap={6}>
      <ErrorNotice error={error} />

      <Section
        title="时间窗"
        source="GET /api/ui/audit-reports · 窗口由后端按 Instant.now() 往前推，页面自己算不出来"
      >
        <VStack gap={3}>
          <SegmentedControl value={hours} onChange={setHours} label="报告时间窗">
            {WINDOW_OPTIONS.map((opt) => (
              <SegmentedControlItem key={opt.value} value={opt.value} label={opt.label} />
            ))}
          </SegmentedControl>
          {/*
            from/to 必须回显，而且回显的是后端返回的值而不是本地 state：hours 会被后端夹到
            1..168，limit 会被夹到 1..500。不回显的话，刷新一次数字变了就没法解释是窗口
            滑动还是真有新流水。
          */}
          <HStack gap={3} wrap="wrap" vAlign="center">
            <Text type="label" color="secondary">窗口</Text>
            {data?.from
              ? <Timestamp value={data.from} format="system_date_time" />
              : <Text type="supporting" color="secondary">—</Text>}
            <Text type="supporting" color="secondary">→</Text>
            {data?.to
              ? <Timestamp value={data.to} format="system_date_time" />
              : <Text type="supporting" color="secondary">—</Text>}
            <Text type="supporting" color="secondary">
              {`后端生效值：hours=${data?.hours ?? '—'} · limit=${data?.limit ?? '—'}`}
            </Text>
          </HStack>
        </VStack>
      </Section>

      {/*
        metrics 的七个数是进程内计数器，与下面两份报告的时间窗无关——它们描述的是「服务启动
        以来」，不是「最近 N 小时」。放在时间窗控件下面容易被误读成窗口内的数，所以 Section
        的说明里把这件事写出来。
      */}
      <Section
        title="实时审计指标"
        source="同一个端点的 metrics 块 · 进程内计数器，覆盖「服务启动以来」而不是上面选的时间窗"
      >
        <KpiGrid
          items={[
            { label: '查询总数', value: metrics.totalQueries },
            {
              label: '慢查询数',
              value: metrics.slowQueryCount,
              status: metrics.slowQueryCount > 0 ? 'warning' : 'success',
              statusLabel: metrics.slowQueryCount > 0 ? '有超过阈值的查询' : '没有慢查询',
            },
            { label: '慢查询占比', value: metrics.slowQueryRate },
            {
              label: '慢查询阈值',
              value: metrics.slowQueryThresholdMs,
              hint: 'ms · 配置项，与 protection 报告的 5000ms 不是一回事',
            },
            { label: '跟踪的 SQL 模式', value: metrics.trackedPatterns },
            { label: '慢查询保留上限', value: metrics.maxSlowQueries, hint: '环形缓冲容量' },
            { label: 'SQL 模式上限', value: metrics.maxSqlPatterns },
          ]}
        />
      </Section>

      <Section
        title="数据访问报告"
        source="同一个端点的 dataAccess 块 · 审计表在所选窗口内的流水，条数受 limit 限制"
        count={dataAccessBlocked ? undefined : dataAccess?.totalEntries}
      >
        {dataAccessBlocked
          ? <ReportUnavailable title="没有可用的数据访问报告" report={dataAccess} />
          : (
            <DataTable
              columns={[
                textColumn('timestamp', '时间', { flex: 1 }),
                textColumn('tool', '工具', { flex: 1, weight: 'semibold' }),
                textColumn('connectionKey', '连接', { flex: 1 }),
                sqlColumn('sql', 'SQL', { flex: 3 }),
                numberColumn('rows', '行数', { px: 88 }),
                numberColumn('durationMs', '耗时(ms)', { px: 104 }),
                booleanColumn('success', '结果'),
              ]}
              rows={dataAccess?.entries}
              emptyTitle="这个窗口内没有审计流水"
              emptyDescription="换一个更大的时间窗，或确认这段时间确实有查询发生。"
            />
          )}
      </Section>

      <Section
        title="保护级别报告"
        source={`同一个端点的 protection 块 · ${PROTECTION_CAVEAT}`}
      >
        {protectionBlocked
          ? <ReportUnavailable title="没有可用的保护级别报告" report={protection} />
          : (
            <VStack gap={5}>
              <KpiGrid
                items={[
                  { label: '查询总数', value: summary.totalQueries },
                  { label: '成功数', value: summary.successCount },
                  {
                    label: '失败数',
                    value: summary.errorCount,
                    status: summary.errorCount > 0 ? 'error' : 'success',
                    statusLabel: summary.errorCount > 0 ? '窗口内有失败的查询' : '窗口内没有失败',
                  },
                  { label: '失败占比', value: summary.errorRate },
                  {
                    label: '慢查询数',
                    value: summary.slowQueryCount,
                    hint: '按硬编码的 5000ms 判定',
                  },
                  { label: '慢查询占比', value: summary.slowQueryRate, hint: '同上，5000ms 口径' },
                  { label: '导出类查询数', value: summary.exportQueryCount },
                  { label: '导出总行数', value: summary.totalRowsExported },
                ]}
              />

              {/*
                两张归并表放在同一个 Section 里而不是各开一个 Section：它们和上面的 summary
                是同一份报告的三个面，拆成三个 Section 会让「5000ms 那句说明」离它约束的数据
                更远。各自给一行 label 就够区分了，列头本身也已经写着「工具」/「连接」。
              */}
              <VStack gap={2}>
                <Text type="label" weight="semibold">按工具归并</Text>
                <DataTable
                  columns={[
                    textColumn('name', '工具', { flex: 3, weight: 'semibold' }),
                    numberColumn('count', '次数', { px: 96 }),
                  ]}
                  rows={breakdownRows(protection?.toolBreakdown)}
                  emptyTitle="窗口内没有按工具归并的调用"
                  emptyDescription="protection.toolBreakdown 为空。"
                />
              </VStack>

              <VStack gap={2}>
                <Text type="label" weight="semibold">按连接归并</Text>
                <DataTable
                  columns={[
                    textColumn('name', '连接', { flex: 3, weight: 'semibold' }),
                    numberColumn('count', '次数', { px: 96 }),
                  ]}
                  rows={breakdownRows(protection?.connectionBreakdown)}
                  emptyTitle="窗口内没有按连接归并的调用"
                  emptyDescription="protection.connectionBreakdown 为空。"
                />
              </VStack>
            </VStack>
          )}
      </Section>
    </VStack>
  );
}
