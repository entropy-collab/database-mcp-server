import { Banner, Stack } from '@astryxdesign/core';
import { fetchAuditHistory } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  DataTable,
  ErrorNotice,
  PanelNote,
  booleanColumn,
  numberColumn,
  sqlColumn,
  textColumn,
} from '../components.jsx';

/**
 * 审计历史（落库）。来源 GET /api/audit/history。
 *
 * auditPersistence=false 时刻意<b>不发</b>那个注定 503 的请求：发了只会在页面上留下一条
 * 看起来像故障的 HTTP 503，而实际情况是「这套部署没开审计落库」——一个配置事实，不是错误。
 * 判据来自 /api/ui/config，而 config 的 auditPersistence 与 history 返回 503 用的是
 * 同一个 AuditLogRepository bean，所以两者不可能不一致。
 */
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

  return (
    <Stack direction="column" gap={4}>
      <PanelNote>
        持久化审计表。审计落库是 opt-in 的：没有配置 spring.datasource.url 时这一页永远是空的，
        接口会返回 503。来源 GET /api/audit/history。
      </PanelNote>

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
        <DataTable
          columns={[
            numberColumn('id', 'id', { px: 72 }),
            textColumn('timestamp', '时间', { flex: 1 }),
            textColumn('tool', '工具', { flex: 1, weight: 'semibold' }),
            textColumn('connectionKey', '连接', { flex: 1 }),
            sqlColumn('sql', 'SQL', { flex: 3 }),
            numberColumn('rows', '行数', { px: 88 }),
            numberColumn('durationMs', '耗时(ms)', { px: 104 }),
            booleanColumn('success', '结果'),
            textColumn('error', '错误', { flex: 2 }),
          ]}
          rows={data}
          emptyTitle="审计表里这个区间没有记录"
          emptyDescription="调大条数，或确认审计写入确实发生过。"
        />
      )}
    </Stack>
  );
}
