import { Stack } from '@astryxdesign/core';
import { fetchPerformance } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  DataTable,
  ErrorNotice,
  PanelNote,
  SubSection,
  SummaryCards,
  numberColumn,
  sqlColumn,
  textColumn,
} from '../components.jsx';

/**
 * 性能。来源 GET /api/ui/performance（慢查询 + SQL 模式 + micrometer 指标一次取回）。
 *
 * metrics 是一张字段名随工具名动态变化的扁平表，没法预先定义列，所以按 key 排序后
 * 拍成 {k, v} 两列。metrics 为 null 是合法状态（部署里没有 micrometer 采集器），
 * 那种情况下给一句解释而不是空表格。
 */
export default function PerformancePanel({ limit, refreshToken }) {
  const { data, error } = usePanelData(() => fetchPerformance(limit), [limit, refreshToken]);

  const summary = data?.summary ?? {};
  const metrics = data?.metrics;
  const metricRows = metrics
    ? Object.keys(metrics).sort().map((k) => ({ k, v: metrics[k] }))
    : [];

  return (
    <Stack direction="column" gap={5}>
      <PanelNote>
        来源 GET /api/ui/performance。全部为进程内统计，重启即清空。
        后端回显的生效条数：{data?.limit ?? '—'}。
      </PanelNote>
      <ErrorNotice error={error} />

      <SummaryCards
        pairs={[
          ['查询总数', summary.totalQueries],
          ['慢查询数', summary.slowQueryCount],
          ['慢查询占比', summary.slowQueryRate],
          ['慢查询阈值(ms)', summary.slowQueryThresholdMs],
          ['跟踪的 SQL 模式', data?.totalTrackedPatterns],
        ]}
      />

      <SubSection title="慢查询">
        <DataTable
          columns={[
            textColumn('timestamp', '时间', { flex: 1 }),
            textColumn('tool', '工具', { flex: 1, weight: 'semibold' }),
            textColumn('connectionKey', '连接', { flex: 1 }),
            sqlColumn('sql', 'SQL', { flex: 3 }),
            numberColumn('rows', '行数', { px: 88 }),
            numberColumn('durationMs', '耗时(ms)', { px: 104 }),
          ]}
          rows={data?.slowQueries}
          emptyTitle="没有超过阈值的查询"
          emptyDescription="阈值见上方「慢查询阈值(ms)」。"
        />
      </SubSection>

      <SubSection title="SQL 模式统计">
        <DataTable
          columns={[
            sqlColumn('pattern', 'SQL 模式', { flex: 3 }),
            numberColumn('count', '次数', { px: 80 }),
            numberColumn('totalDurationMs', '总耗时(ms)', { px: 112 }),
            numberColumn('avgDurationMs', '平均(ms)', { px: 100 }),
            numberColumn('maxDurationMs', '最大(ms)', { px: 100 }),
            numberColumn('totalRows', '总行数', { px: 96 }),
            numberColumn('avgRows', '平均行数', { px: 96 }),
          ]}
          rows={data?.patterns}
          emptyTitle="还没有统计到任何 SQL 模式"
          emptyDescription="服务启动后还没有被计入统计的查询。"
        />
      </SubSection>

      <SubSection title="服务指标">
        <DataTable
          columns={[
            textColumn('k', '指标', { flex: 2 }),
            textColumn('v', '值', { flex: 1, align: 'end' }),
          ]}
          rows={metricRows}
          emptyTitle={metrics ? '暂无指标' : '本部署没有 micrometer 指标采集器'}
          emptyDescription={
            metrics
              ? '采集器在，但还没有累计到任何值。'
              : '/api/ui/performance 的 metrics 字段为 null，这是合法状态而不是故障。'
          }
        />
      </SubSection>
    </Stack>
  );
}
