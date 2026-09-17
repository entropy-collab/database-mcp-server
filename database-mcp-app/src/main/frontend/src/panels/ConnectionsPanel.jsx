import { VStack } from '@astryxdesign/core';
import { fetchConnections } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  DataTable,
  ErrorNotice,
  KpiGrid,
  Section,
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
 *
 * 指标里只有「降级池」配了颜色：它是唯一一个"不是 0 就要去看一眼"的数。给「已注册连接」
 * 之类的中性读数也上色，等于把颜色这个信号用废。
 */
export default function ConnectionsPanel({ refreshToken }) {
  const { data, error } = usePanelData(() => fetchConnections(), [refreshToken]);

  const registered = data?.registered ?? {};
  const pools = data?.pools ?? {};
  const poolRows = (pools.pools ?? []).map((p) => ({
    ...p,
    poolUsage: `${p.totalConnections} / ${p.activeConnections} / ${p.idleConnections}`,
  }));
  const degraded = pools.degradedPools;

  return (
    <VStack gap={6}>
      <ErrorNotice error={error} />

      <KpiGrid
        items={[
          { label: '已注册连接', value: registered.totalConnections },
          { label: '活跃连接', value: registered.activeConnections },
          { label: '物理连接池', value: pools.totalConnections },
          { label: '连接名（含别名）', value: pools.totalConnectionNames },
          {
            label: '健康池',
            value: pools.healthyPools,
            status: degraded > 0 ? 'warning' : 'success',
            statusLabel: degraded > 0 ? '有降级池' : '全部健康',
          },
          {
            label: '降级池',
            value: degraded,
            status: degraded > 0 ? 'error' : undefined,
            statusLabel: '有池处于降级状态',
          },
        ]}
      />

      <Section
        title="已注册连接"
        source="GET /api/ui/connections · JDBC URL 已由后端 JdbcUrlMasker 脱敏"
        count={registered.connections?.length}
      >
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
      </Section>

      <Section
        title="连接池"
        source="同一个端点的 pools 块 · 已注册但从未使用过的连接不会出现在这里"
        count={poolRows.length}
      >
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
      </Section>
    </VStack>
  );
}
