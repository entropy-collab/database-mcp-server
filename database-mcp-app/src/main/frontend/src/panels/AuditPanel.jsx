import { Stack } from '@astryxdesign/core';
import { fetchAuditLogs } from '../api.js';
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
 * 审计流水（内存环形缓冲）。来源 GET /api/audit/logs。
 *
 * 列与字段名与零构建版本逐一对应，没有重命名：字段名是后端 SqlAuditTools 的对外口径，
 * 前端换个叫法只会让人在对着接口排查时多绕一圈。
 */
export default function AuditPanel({ limit, refreshToken }) {
  const { data, error } = usePanelData(() => fetchAuditLogs(limit), [limit, refreshToken]);

  return (
    <Stack direction="column" gap={4}>
      <PanelNote>
        进程内环形缓冲，重启即清空。来源 GET /api/audit/logs。
      </PanelNote>
      <ErrorNotice error={error} />
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
        rows={data}
        emptyTitle="内存缓冲区为空"
        emptyDescription="服务启动后还没有被审计的数据库操作。"
      />
    </Stack>
  );
}
