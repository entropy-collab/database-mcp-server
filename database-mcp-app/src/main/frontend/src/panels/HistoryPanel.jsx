import { Banner, VStack } from '@astryxdesign/core';
import { useMemo } from 'react';
import { fetchAuditHistory } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  ErrorNotice,
  InteractiveTable,
  RESULT_ENUM_VALUES,
  SEARCH_ALL_FIELD,
  numberColumn,
  resultColumn,
  sqlColumn,
  textColumn,
  timeColumn,
} from '../components.jsx';

/**
 * 审计历史（落库）。来源 GET /api/audit/history。
 *
 * auditPersistence=false 时刻意<b>不发</b>那个注定 503 的请求：发了只会在页面上留下一条
 * 看起来像故障的 HTTP 503，而实际情况是「这套部署没开审计落库」——一个配置事实，不是错误。
 * 判据来自 /api/ui/config，而 config 的 auditPersistence 与 history 返回 503 用的是
 * 同一个 AuditLogRepository bean，所以两者不可能不一致。
 */

/*
 * 列定义与审计流水几乎一样，但刻意<b>不共用</b>一份常量。
 *
 * 两张表的差别是实打实的：这一张有 id（数据库主键，唯一稳定的行标识）、有 error
 * （落库时记下的错误信息，内存缓冲那张的 error 通常已经被环形覆盖掉了），
 * 而且默认排序按 id 倒序而不是按 timestamp——同一毫秒写入的多条记录，
 * 只有 id 能给出确定的先后。共用一份再靠参数开关这些差异，读起来比写两份更绕。
 */
const COLUMNS = [
  numberColumn('id', 'id', { px: 72 }),
  timeColumn('timestamp', '时间'),
  textColumn('tool', '工具', { flex: 1, weight: 'semibold', filter: 'tool' }),
  textColumn('principal', '调用者', { flex: 1, filter: 'principal' }),
  textColumn('connectionKey', '连接', { flex: 1, filter: 'connectionKey' }),
  sqlColumn('sql', 'SQL', { flex: 3, filter: 'sql' }),
  numberColumn('rows', '行数', { px: 88 }),
  numberColumn('durationMs', '耗时(ms)', { px: 104, filter: 'durationMs' }),
  resultColumn('result', '结果', { filter: 'result' }),
  textColumn('error', '错误', { flex: 2, filter: 'error' }),
];

const SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'tool', type: 'string', label: '工具' },
  { key: 'principal', type: 'string', label: '调用者' },
  { key: 'connectionKey', type: 'string', label: '连接' },
  { key: 'sql', type: 'string', label: 'SQL' },
  { key: 'error', type: 'string', label: '错误' },
  { key: 'durationMs', type: 'number', label: '耗时(ms)' },
  { key: 'result', type: 'enum', label: '结果', enumValues: RESULT_ENUM_VALUES },
];

/*
 * 排序不写自定义比较器，理由同 AuditPanel：core 的 defaultCompare 已经处理了
 * number 快路径与空值排末尾，手写的版本反而会把空值当 0。
 */

/** id 倒序 = 写入顺序倒序，比 timestamp 倒序更确定（同毫秒的多条记录也能定序）。 */
const DEFAULT_SORT = [{ sortKey: 'id', direction: 'descending' }];

/*
 * 可分组的字段（第 5 项）。和审计流水页给的是同两个字段，理由也一样：
 * 它们是这张表上唯一「同一个值会重复很多行」的列。
 * 两个 panel 各写一份而不是共享一个常量，和 COLUMNS 不共享是同一个理由
 * （见文件顶部）：两张表的字段集合本来就不完全相同，共享会掩盖差别。
 */
const GROUP_FIELDS = [
  { key: 'tool', label: '工具名' },
  { key: 'principal', label: '调用者' },
  { key: 'connectionKey', label: '连接名' },
];

/** 有主键就用主键。内容拼出来的 key 只是没有主键时的替代品。 */
const ROW_KEY = (row) => String(row.id);

function rowTone(row) {
  return row.success === false ? 'error' : null;
}

export default function HistoryPanel({ limit, refreshToken, auditPersistence }) {
  /*
   * 三态而不是两态：true / false / 读不到 config。
   *
   * 把"读不到 config"和 false 合并会让页面说出一句它并不知道的话（"本服务没有配置
   * spring.datasource.url"）。真实情况可能是 /api/ui/config 返回了 401——那时该显示的是
   * "状态未知"，并且照样把请求发出去，让 history 自己的状态码说话。
   */
  const isUnknown = auditPersistence === undefined || auditPersistence === null;
  const enabled = auditPersistence === true || isUnknown;
  const { data, error } = usePanelData(
    () => fetchAuditHistory(limit),
    [limit, refreshToken],
    { enabled },
  );

  /* result 的派生理由见 AuditPanel：enum 过滤、排序、CSV 三处都要字符串。 */
  const rows = useMemo(
    () => (Array.isArray(data) ? data : []).map((r) => ({
      ...r,
      result: r.success === false ? '失败' : '成功',
    })),
    [data],
  );

  return (
    <VStack gap={6}>
      {auditPersistence === false && (
        <Banner
          status="info"
          title="审计未落库"
          description={
            '本服务没有配置 spring.datasource.url，/api/audit/history 会返回 503，'
            + '因此这一页不发请求。打开方式见 README 的「审计持久化（可选）」一节。'
          }
          container="card"
        />
      )}

      {isUnknown && (
        <Banner
          status="warning"
          title="审计落库状态未知"
          description={
            '读不到 /api/ui/config（很可能是 401）。下面这张表仍然会去请求 '
            + '/api/audit/history，请以它自己的状态码为准。'
          }
          container="card"
        />
      )}

      <ErrorNotice error={error} />

      {enabled && (
        <InteractiveTable
          title="审计表记录"
          source="GET /api/audit/history · 持久化审计表，落库是 opt-in 的 · 点任意一行看完整 SQL"
          rows={rows}
          columns={COLUMNS}
          searchFields={SEARCH_FIELDS}
          searchName="audit-history"
          defaultSort={DEFAULT_SORT}
          getRowKey={ROW_KEY}
          getRowTone={rowTone}
          groupFields={GROUP_FIELDS}
          detailTitle="审计表记录详情"
          detailSqlKey="sql"
          csvBaseName="audit-history"
          emptyTitle="审计表里这个区间没有记录"
          emptyDescription="调大条数，或确认审计写入确实发生过。"
        />
      )}
    </VStack>
  );
}
