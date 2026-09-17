import { VStack } from '@astryxdesign/core';
import { useMemo } from 'react';
import { fetchAuditLogs } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  ErrorNotice,
  InteractiveTable,
  KpiGrid,
  RESULT_ENUM_VALUES,
  SEARCH_ALL_FIELD,
  contentRowKey,
  numberColumn,
  resultColumn,
  sqlColumn,
  textColumn,
  timeColumn,
} from '../components.jsx';

/**
 * 审计流水（内存环形缓冲）。来源 GET /api/audit/logs。
 *
 * 列与字段名与后端 SqlAuditTools 的对外口径逐一对应，没有重命名：前端换个叫法只会让人
 * 在对着接口排查时多绕一圈。唯一的例外是 result——它是前端为了让"成功/失败"能被过滤、
 * 排序、导出而从布尔 success 派生出来的字符串（理由见下面 rows 那段注释）。
 *
 * 顶部四个指标是**对当前这一页数据**算的，不是服务端的全量统计——环形缓冲本身没有
 * 「历史总量」这种东西，条数改一下这四个数就会变。所以标题写「本页」而不是「累计」，
 * 免得被当成服务启动至今的统计读。
 *
 * 指标刻意仍然按**全量本页**算，不跟着搜索框走：它回答的是"这一页数据整体什么样"，
 * 如果跟着过滤变，那就变成了"我筛出来的这些什么样"——后者看表格的行数就够了。
 */

/*
 * 列定义、字段定义、比较器、默认排序都在模块顶层。
 *
 * 不是风格问题：InteractiveTable 把它们全放进了 useMemo / usePowerSearchConfig 的依赖里，
 * 在组件体里现造对象会让每次渲染都重算一遍全部过滤和排序。500 行的时候能感觉到。
 */
const COLUMNS = [
  timeColumn('timestamp', '时间'),
  textColumn('tool', '工具', { flex: 1, weight: 'semibold', filter: 'tool' }),
  textColumn('connectionKey', '连接', { flex: 1, filter: 'connectionKey' }),
  sqlColumn('sql', 'SQL', { flex: 3, filter: 'sql' }),
  numberColumn('rows', '行数', { px: 88 }),
  numberColumn('durationMs', '耗时(ms)', { px: 104, filter: 'durationMs' }),
  resultColumn('result', '结果', { filter: 'result' }),
];

/*
 * 搜索字段。SEARCH_ALL_FIELD 必须在，而且必须来自那个共享常量：它是自由文本的落点，
 * 少了它 PowerSearch 里直接打关键字不会有"全文匹配"这一条候选。
 *
 * durationMs 给成 number 类型，于是能筛「耗时 > 1000」——排查慢的时候这比翻页快。
 * result 给成 enum，于是筛成功/失败是个下拉而不是让人手打"失败"。
 */
const SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'tool', type: 'string', label: '工具' },
  { key: 'connectionKey', type: 'string', label: '连接' },
  { key: 'sql', type: 'string', label: 'SQL' },
  { key: 'durationMs', type: 'number', label: '耗时(ms)' },
  { key: 'result', type: 'enum', label: '结果', enumValues: RESULT_ENUM_VALUES },
];

/*
 * 排序不需要自定义比较器。
 *
 * core 的 useTableSortableState.defaultCompare 已经做了三件正确的事：两边都是 number
 * 时走减法快路径、null/undefined/NaN 一律排到末尾、其余走 Intl.Collator({numeric:true})。
 * 对 timestamp（ISO 字符串，字典序 = 时间序）、tool（中英混排）、durationMs（数字）
 * 都正好是想要的。
 *
 * 最初这里写了 `(a,b) => Number(a.durationMs) - Number(b.durationMs)`，删掉了——
 * 手写那版对空值的处理更差（`Number(null) || 0` 会把空耗时排到数字中间，像是 0ms）。
 */

/** 默认按时间倒序：值班时第一眼要看的是刚刚发生了什么。 */
const DEFAULT_SORT = [{ sortKey: 'timestamp', direction: 'descending' }];

/*
 * 行标识。必须只依赖内容、不能用下标——详情面板是按这个 key 回查行的，
 * 而下标在排序/过滤之后就变了（排一次序，面板会指向另一行）。
 * 环形缓冲没有 id，所以用「时间 + 工具 + SQL + 耗时」这四个的组合；
 * 理论上可能撞（同一毫秒同一条 SQL 跑了两次），撞了的后果只是详情面板显示了
 * 另一条一模一样的记录——可以接受。
 */
const ROW_KEY = contentRowKey(['timestamp', 'tool', 'sql', 'durationMs']);

/** 失败行标红。这是这一页唯一需要"一眼看出来"的东西。 */
function rowTone(row) {
  return row.success === false ? 'error' : null;
}

export default function AuditPanel({ limit, refreshToken }) {
  const { data, error } = usePanelData(() => fetchAuditLogs(limit), [limit, refreshToken]);

  /*
   * result 是把布尔 success 翻成中文字符串的派生字段。
   *
   * 为什么必须派生而不是直接筛 success：PowerSearch 的 applyFilters 对 enum 只认
   * string（boolean 只有 is_true/is_false 两个 value type 为 'empty' 的算子，
   * 而表头过滤对 'empty' 类型渲染不出任何控件——core 的 FilterControl 直接 return null）。
   * 顺带三个好处：排序有意义、CSV 导出是"失败"而不是 false、表格里 Badge 的文案就是它。
   *
   * useMemo 依赖 data 而不是 raw：raw 每次渲染都是新数组，不 memo 的话下游
   * InteractiveTable 里那一串 useMemo（合成搜索字段、过滤、排序）全部白设。
   */
  const rows = useMemo(
    () => (Array.isArray(data) ? data : []).map((r) => ({
      ...r,
      result: r.success === false ? '失败' : '成功',
    })),
    [data],
  );

  const failed = rows.filter((r) => r.success === false).length;
  const durations = rows.map((r) => Number(r.durationMs)).filter((n) => Number.isFinite(n));
  const avgMs = durations.length
    ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length)
    : undefined;
  const maxMs = durations.length ? Math.max(...durations) : undefined;

  return (
    <VStack gap={6}>
      <ErrorNotice error={error} />

      <KpiGrid
        items={[
          { label: '本页记录数', value: rows.length },
          {
            label: '其中失败',
            value: failed,
            status: failed > 0 ? 'error' : 'success',
            statusLabel: failed > 0 ? '有失败记录' : '全部成功',
          },
          { label: '平均耗时', value: avgMs, hint: 'ms' },
          { label: '最慢一条', value: maxMs, hint: 'ms' },
        ]}
      />

      <InteractiveTable
        title="审计流水"
        source="GET /api/audit/logs · 进程内环形缓冲，重启即清空 · 点任意一行看完整 SQL"
        rows={rows}
        columns={COLUMNS}
        searchFields={SEARCH_FIELDS}
        searchName="audit-logs"
        defaultSort={DEFAULT_SORT}
        getRowKey={ROW_KEY}
        getRowTone={rowTone}
        detailTitle="审计记录详情"
        detailSqlKey="sql"
        csvBaseName="audit-logs"
        emptyTitle="内存缓冲区为空"
        emptyDescription="服务启动后还没有被审计的数据库操作。"
      />
    </VStack>
  );
}
