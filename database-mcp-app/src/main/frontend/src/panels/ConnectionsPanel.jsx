import { Stack } from '@astryxdesign/core';
import { fetchConnections } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  DataTable,
  ErrorNotice,
  PanelNote,
  SubSection,
  SummaryCards,
  booleanColumn,
  numberColumn,
  textColumn,
} from '../components.jsx';

/**
 * 连接与连接池。来源 GET /api/ui/connections（registered + pools 两块原样透传）。
 *
 * 「总/活跃/空闲」是前端合出来的一列（poolUsage），后端没有这个字段：三个数分三列会让
 * 表格宽到要横滚，而它们在运维视角里就是一个读数。合成放在这里而不是后端，是因为
 * 后端那两块是 MCP 工具的原始口径，不该为了页面排版长出第二套字段。
 */
export default function ConnectionsPanel({ refreshToken }) {
  const { data, error } = usePanelData(() => fetchConnections(), [refreshToken]);

  const registered = data?.registered ?? {};
  const pools = data?.pools ?? {};
  const poolRows = (pools.pools ?? []).map((p) => ({
    ...p,
    poolUsage: `${p.totalConnections} / ${p.activeConnections} / ${p.idleConnections}`,
  }));

  return (
    <Stack direction="column" gap={5}>
      <PanelNote>
        来源 GET /api/ui/connections。JDBC URL 已由后端 JdbcUrlMasker 脱敏。
      </PanelNote>
      <ErrorNotice error={error} />

      <SummaryCards
        pairs={[
          ['已注册连接', registered.totalConnections],
          ['活跃连接', registered.activeConnections],
          ['物理连接池', pools.totalConnections],
          ['连接名（含别名）', pools.totalConnectionNames],
          ['健康池', pools.healthyPools],
          ['降级池', pools.degradedPools],
        ]}
      />

      <SubSection title="已注册连接">
        <DataTable
          columns={[
            textColumn('key', '连接名', { flex: 1, weight: 'semibold' }),
            textColumn('dialect', '方言', { flex: 1 }),
            textColumn('jdbcUrlMasked', 'JDBC URL（已脱敏）', { flex: 3 }),
            textColumn('owner', '归属', { flex: 1 }),
            textColumn('status', '状态', { flex: 1 }),
            textColumn('createdAt', '创建于', { flex: 1 }),
            textColumn('leaseExpiry', '租约到期', { flex: 1 }),
            numberColumn('poolSize', '池大小', { px: 88 }),
          ]}
          rows={registered.connections}
          emptyTitle="没有已注册的连接"
          emptyDescription="这套部署目前是 BYOK-only，或者预声明连接还没有注册成功。"
        />
      </SubSection>

      <SubSection title="连接池">
        <DataTable
          columns={[
            textColumn('connectionName', '连接名', { flex: 1, weight: 'semibold' }),
            textColumn('canonicalName', '物理池', { flex: 1 }),
            booleanColumn('isAlias', '别名', { okLabel: '别名', badLabel: '本体', px: 88 }),
            textColumn('dialect', '方言', { flex: 1 }),
            textColumn('poolUsage', '总/活跃/空闲', { flex: 1, align: 'end' }),
            numberColumn('pendingThreads', '等待线程', { px: 104 }),
            numberColumn('maxPoolSize', '池上限', { px: 88 }),
            booleanColumn('isPoolHealthy', '健康', { okLabel: '健康', badLabel: '降级' }),
            textColumn('healthWarnings', '告警', { flex: 2 }),
          ]}
          rows={poolRows}
          emptyTitle="还没有建立过任何连接池"
          emptyDescription="已注册但从未使用过的连接不会出现在这里。"
        />
      </SubSection>
    </Stack>
  );
}
