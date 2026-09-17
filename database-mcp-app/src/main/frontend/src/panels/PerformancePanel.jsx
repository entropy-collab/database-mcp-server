import { VStack } from '@astryxdesign/core';
import { useCallback, useMemo } from 'react';
import { fetchPerformance } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  DataTable,
  ErrorNotice,
  ExportCsvButton,
  InteractiveTable,
  KpiGrid,
  SEARCH_ALL_FIELD,
  Section,
  Sparkline,
  contentRowKey,
  numberColumn,
  sqlColumn,
  textColumn,
  timeColumn,
} from '../components.jsx';

/**
 * 性能。来源 GET /api/ui/performance（慢查询 + SQL 模式 + micrometer 指标一次取回）。
 *
 * metrics 是一张字段名随工具名动态变化的扁平表，没法预先定义列，所以按 key 排序后
 * 拍成 {k, v} 两列。metrics 为 null 是合法状态（部署里没有 micrometer 采集器），
 * 那种情况下给一句解释而不是空表格。
 *
 * 「慢查询数」是唯一配色的指标：慢查询阈值是配置项，超过阈值就是明确的坏消息。
 * 「查询总数」这类中性读数不配色。
 *
 * 这一页是唯一同时拿到 summary.slowQueryThresholdMs 和逐条耗时的地方，所以
 * 「耗时超阈值高亮」和 sparkline 都落在这里。审计流水页拿不到阈值——它只能靠
 * /api/ui/performance，而为了一个高亮去多打一个端点，正好会踩 App.jsx 里写的那个坑
 * （页面自己的读操作会被 PerformanceTimingAspect 记进性能指标）。
 */

const SLOW_COLUMNS = [
  timeColumn('timestamp', '时间'),
  textColumn('tool', '工具', { flex: 1, weight: 'semibold', filter: 'tool' }),
  textColumn('connectionKey', '连接', { flex: 1, filter: 'connectionKey' }),
  sqlColumn('sql', 'SQL', { flex: 3, filter: 'sql' }),
  numberColumn('rows', '行数', { px: 88 }),
  numberColumn('durationMs', '耗时(ms)', { px: 104, filter: 'durationMs' }),
];

const SLOW_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'tool', type: 'string', label: '工具' },
  { key: 'connectionKey', type: 'string', label: '连接' },
  { key: 'sql', type: 'string', label: 'SQL' },
  { key: 'durationMs', type: 'number', label: '耗时(ms)' },
];

/*
 * 三张表都不写自定义比较器：core 的 defaultCompare 对 number 走减法快路径、
 * 对 null/NaN 排到末尾、其余走 Intl.Collator({numeric:true})，正好是这些列要的。
 * 手写一遍反而会把空值当成 0 排进数字中间。
 */

/** 慢查询默认按耗时倒序：这张表存在的意义就是"最慢的是哪几条"。 */
const SLOW_DEFAULT_SORT = [{ sortKey: 'durationMs', direction: 'descending' }];

const SLOW_ROW_KEY = contentRowKey(['timestamp', 'tool', 'sql', 'durationMs']);

/*
 * 慢查询表可分组的字段（第 5 项）。
 *
 * 只给慢查询表，<b>不给</b> SQL 模式表：模式表的每一行本身就已经是按 SQL 模式归并过的
 * 汇总行，再分一次组等于每行一个组头；而它也没有 tool / connectionKey 两列
 * （端点返回的 patterns 块里没有这两个字段），分组选项会是两个筛不出东西的死选项。
 */
const SLOW_GROUP_FIELDS = [
  { key: 'tool', label: '工具名' },
  { key: 'connectionKey', label: '连接名' },
];

const PATTERN_COLUMNS = [
  sqlColumn('pattern', 'SQL 模式', { flex: 3, filter: 'pattern' }),
  numberColumn('count', '次数', { px: 80 }),
  numberColumn('totalDurationMs', '总耗时(ms)', { px: 112 }),
  numberColumn('avgDurationMs', '平均(ms)', { px: 100, filter: 'avgDurationMs' }),
  numberColumn('maxDurationMs', '最大(ms)', { px: 100 }),
  numberColumn('totalRows', '总行数', { px: 96 }),
  numberColumn('avgRows', '平均行数', { px: 96 }),
];

const PATTERN_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'pattern', type: 'string', label: 'SQL 模式' },
  { key: 'avgDurationMs', type: 'number', label: '平均(ms)' },
  { key: 'count', type: 'number', label: '次数' },
];

/** 模式表默认按总耗时倒序：优化的收益 = 单次耗时 × 次数，总耗时正好是这个乘积。 */
const PATTERN_DEFAULT_SORT = [{ sortKey: 'totalDurationMs', direction: 'descending' }];

const PATTERN_ROW_KEY = (row) => String(row.pattern);

/** 指标表的两列。指标名是动态的，这张表不做搜索/详情，只按 key 排了序。 */
const METRIC_COLUMNS = [
  textColumn('k', '指标', { flex: 2 }),
  textColumn('v', '值', { flex: 1, align: 'end' }),
];

export default function PerformancePanel({ limit, refreshToken }) {
  const { data, error } = usePanelData(() => fetchPerformance(limit), [limit, refreshToken]);

  const summary = data?.summary ?? {};
  const metrics = data?.metrics;
  const metricRows = useMemo(
    () => (metrics ? Object.keys(metrics).sort().map((k) => ({ k, v: metrics[k] })) : []),
    [metrics],
  );
  const slowCount = summary.slowQueryCount;
  const threshold = Number(summary.slowQueryThresholdMs);
  const hasThreshold = Number.isFinite(threshold) && threshold > 0;

  const slowQueries = useMemo(
    () => (Array.isArray(data?.slowQueries) ? data.slowQueries : []),
    [data],
  );
  const patterns = useMemo(
    () => (Array.isArray(data?.patterns) ? data.patterns : []),
    [data],
  );

  /*
   * 慢查询表的高亮标准是「阈值的两倍」，不是阈值本身。
   *
   * 这张表里的每一行按定义都已经超过阈值了——把全部行都标黄，等于没有标：
   * 颜色要指出的是"这几条明显不同"，不是重复一遍表格标题。两倍是个判断，不是标准，
   * 所以写在这里而不是藏在组件里：改成 5 倍或换成 p95 都只需要动这一行。
   */
  const slowTone = useCallback(
    (row) => {
      if (!hasThreshold) {
        return null;
      }
      return Number(row.durationMs) >= threshold * 2 ? 'error' : null;
    },
    [hasThreshold, threshold],
  );

  /*
   * 模式表相反：这里的数据是混的（大部分模式的平均耗时远低于阈值），
   * 所以"平均耗时超过阈值"是一个真信号——这个模式不是偶尔慢，是一直慢。
   */
  const patternTone = useCallback(
    (row) => {
      if (!hasThreshold) {
        return null;
      }
      return Number(row.avgDurationMs) > threshold ? 'warning' : null;
    },
    [hasThreshold, threshold],
  );

  /*
   * sparkline 的数据：慢查询的耗时序列，按时间正序。
   *
   * 用的是 slowQueries 里已经有的 durationMs / timestamp，没有为了画图多发一个请求——
   * 那正是 App.jsx 里那条「页面别往性能指标里灌流量」约束想避免的事。
   *
   * 为什么按时间正序而不是照表格的耗时倒序：折线的横轴必须是时间才有意义，
   * 按耗时排过序的折线只是一条单调递减的线，除了"确实是排过序的"什么都说明不了。
   * 这也是它不做成表格里的一列的原因——它回答的是"慢查询是均匀分布还是集中在某几分钟"，
   * 一个只有把所有行放在一起看才存在的问题。
   */
  const slowSeries = useMemo(() => {
    const withTime = slowQueries
      .map((q) => ({ t: new Date(q.timestamp).getTime(), ms: Number(q.durationMs) }))
      .filter((p) => Number.isFinite(p.ms));
    // 时间解析不出来的行排在最后而不是丢掉：它的耗时读数本身仍然是真的。
    withTime.sort((a, b) => (Number.isNaN(a.t) ? 1 : Number.isNaN(b.t) ? -1 : a.t - b.t));
    return withTime.map((p) => p.ms);
  }, [slowQueries]);

  return (
    <VStack gap={6}>
      <ErrorNotice error={error} />

      <KpiGrid
        items={[
          { label: '查询总数', value: summary.totalQueries },
          {
            label: '慢查询数',
            value: slowCount,
            status: slowCount > 0 ? 'warning' : 'success',
            statusLabel: slowCount > 0 ? '有超过阈值的查询' : '没有慢查询',
          },
          { label: '慢查询占比', value: summary.slowQueryRate },
          { label: '慢查询阈值', value: summary.slowQueryThresholdMs, hint: 'ms' },
          { label: '跟踪的 SQL 模式', value: data?.totalTrackedPatterns },
        ]}
      />

      <InteractiveTable
        title="慢查询"
        source={
          'GET /api/ui/performance · 进程内统计，重启即清空 · 后端生效条数 '
          + `${data?.limit ?? '—'}`
          + (hasThreshold ? ` · 标红的是超过阈值两倍（≥ ${threshold * 2}ms）的` : '')
        }
        rows={slowQueries}
        columns={SLOW_COLUMNS}
        searchFields={SLOW_SEARCH_FIELDS}
        searchName="slow-queries"
        defaultSort={SLOW_DEFAULT_SORT}
        getRowKey={SLOW_ROW_KEY}
        getRowTone={slowTone}
        groupFields={SLOW_GROUP_FIELDS}
        detailTitle="慢查询详情"
        detailSqlKey="sql"
        csvBaseName="slow-queries"
        emptyTitle="没有超过阈值的查询"
        emptyDescription="阈值见上方「慢查询阈值」。"
        beforeTable={
          <Sparkline
            values={slowSeries}
            label="慢查询耗时序列（按时间正序，红点是最慢那次）"
            unit="ms"
          />
        }
      />

      <InteractiveTable
        title="SQL 模式统计"
        source={
          '同一个端点的 patterns 块 · 按模式归并后的累计耗时与行数'
          + (hasThreshold ? ` · 标黄的是平均耗时超过阈值（> ${threshold}ms）的模式` : '')
        }
        rows={patterns}
        columns={PATTERN_COLUMNS}
        searchFields={PATTERN_SEARCH_FIELDS}
        searchName="sql-patterns"
        defaultSort={PATTERN_DEFAULT_SORT}
        getRowKey={PATTERN_ROW_KEY}
        getRowTone={patternTone}
        detailTitle="SQL 模式详情"
        detailSqlKey="pattern"
        csvBaseName="sql-patterns"
        emptyTitle="还没有统计到任何 SQL 模式"
        emptyDescription="服务启动后还没有被计入统计的查询。"
      />

      {/*
        指标表刻意<b>不</b>上 InteractiveTable：它的字段名随已注册工具动态变化，
        搜索字段定义（PowerSearchField）没法预先写出来；而且它本来就只有两列、
        已经按 key 排好序，浏览器 Ctrl+F 就够用。导出按钮还是给——
        这张表是最常被要求"发一份过来"的。
      */}
      <Section
        title="服务指标"
        source="同一个端点的 metrics 块 · 字段名随已注册工具动态变化"
        count={metricRows.length}
        actions={
          <ExportCsvButton columns={METRIC_COLUMNS} rows={metricRows} baseName="service-metrics" />
        }
      >
        <DataTable
          columns={METRIC_COLUMNS}
          rows={metricRows}
          emptyTitle={metrics ? '暂无指标' : '本部署没有 micrometer 指标采集器'}
          emptyDescription={
            metrics
              ? '采集器在，但还没有累计到任何值。'
              : '/api/ui/performance 的 metrics 字段为 null，这是合法状态而不是故障。'
          }
        />
      </Section>
    </VStack>
  );
}
