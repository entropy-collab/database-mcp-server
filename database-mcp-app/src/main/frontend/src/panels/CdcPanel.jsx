import {
  Banner,
  Button,
  HStack,
  Text,
  TextInput,
  VStack,
} from '@astryxdesign/core';
import { useMemo, useState } from 'react';
import {
  fetchCdcConfig,
  fetchCdcLsn,
  fetchCdcStatus,
  fetchCdcSubscriptions,
  fetchCdcSupport,
} from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  ErrorNotice,
  InteractiveTable,
  KpiGrid,
  SEARCH_ALL_FIELD,
  Section,
  booleanColumn,
  contentRowKey,
  displayValue,
  numberColumn,
  textColumn,
} from '../components.jsx';

/**
 * CDC 状态。来源 GET /api/ui/cdc/config、/cdc/support、/cdc/status、/cdc/subscriptions、/cdc/lsn。
 *
 * ── 五个端点的可用性完全不同，这一页的形状就是从这条推出来的 ──
 * - /cdc/config：不连库、无入参，挂载即拉。它是判断「CDC 有没有开」的唯一便宜手段。
 * - /cdc/subscriptions：订阅只是<b>服务进程内存里的登记</b>（不在数据库端建任何东西），
 *   所以 CDC 开关关着它也能成功返回空列表。
 * - /cdc/support：只问方言支不支持，不要求 CDC 开关。
 * - /cdc/status 与 /cdc/lsn：工具里先 requireCdcEnabled 再 validateRequired(connection)，
 *   两条任一不满足就是 500。
 *
 * 所以：连接名留空时不发 status / lsn（自己挡，否则一句「connection cannot be blank」
 * 会以 500 的形式出现，而这一页 500 的其它来源都是真实故障）；CDC 开关关着时也不拦按钮，
 * 但把「关着」这件事写在上面——后端的意思是「页面据 config 决定要不要发这个请求」，
 * 而运维有时就是想看那条 500 的原文。
 *
 * ── 一次点击发四个请求，而不是四个按钮 ──
 * support / status / subscriptions / lsn 回答的是同一个问题的四个面（支不支持、现在什么状态、
 * 有谁在订阅、位点到哪了），单独看任何一个都得不出结论：cdcSupported=false 时 status 必然失败，
 * 而两条路的错误消息看起来差不多。所以它们共用一个「查询」按钮、各自独立显示成功或失败。
 * 四个各配一个按钮的版本试过，代价是每次都要点四下，而且很容易只点了两个就下结论。
 */

/**
 * 订阅清单的列。
 *
 * 键名抄的是 CdcTools.listSubscriptions 那段 m.put：name / connection / schema /
 * tablePattern / changeTypes / pollIntervalMs / active。
 * 是 <b>tablePattern</b>（一个模式，不是具体表名），而且<b>没有 createdAt</b> ——
 * 第一版写了 table 和一列创建时间，两列都会是整列的「—」。
 * active 用 booleanColumn：一条 active=false 的订阅登记着但不在轮询，这件事得一眼看出来。
 */
const SUBSCRIPTION_COLUMNS = [
  textColumn('name', '订阅名', { flex: 2, weight: 'semibold', filter: 'name' }),
  booleanColumn('active', '生效中', { okLabel: '是', badLabel: '否', px: 88 }),
  textColumn('connection', '连接', { flex: 1, filter: 'connection' }),
  textColumn('schema', 'Schema', { flex: 1, filter: 'schema' }),
  textColumn('tablePattern', '表模式', { flex: 2, filter: 'tablePattern' }),
  textColumn('changeTypes', '变更类型', { flex: 2, filter: 'changeTypes' }),
  numberColumn('pollIntervalMs', '轮询(ms)', { px: 112 }),
];

const SUBSCRIPTION_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'name', type: 'string', label: '订阅名' },
  { key: 'tablePattern', type: 'string', label: '表模式' },
  { key: 'changeTypes', type: 'string', label: '变更类型' },
];

const SUBSCRIPTION_ROW_KEY = contentRowKey(['name']);

export default function CdcPanel({ refreshToken }) {
  const [connection, setConnection] = useState('');
  const [submitted, setSubmitted] = useState(null);

  /* config 不连库，跟着挂载就拉。 */
  const config = usePanelData(() => fetchCdcConfig(), [refreshToken]);
  const cfg = config.data;
  const cdcOff = cfg?.enabled === false;

  const support = usePanelData(
    () => fetchCdcSupport({ connection: submitted }),
    [submitted, refreshToken],
    { enabled: submitted !== null },
  );
  const status = usePanelData(
    () => fetchCdcStatus({ connection: submitted }),
    [submitted, refreshToken],
    { enabled: submitted !== null },
  );
  const subscriptions = usePanelData(
    () => fetchCdcSubscriptions({ connection: submitted }),
    [submitted, refreshToken],
    { enabled: submitted !== null },
  );
  const lsn = usePanelData(
    () => fetchCdcLsn({ connection: submitted }),
    [submitted, refreshToken],
    { enabled: submitted !== null },
  );

  const subs = useMemo(() => {
    const raw = subscriptions.data?.subscriptions;
    if (!Array.isArray(raw)) {
      return [];
    }
    /* changeTypes 是数组，拍成逗号分隔的字符串：PowerSearch 的 string 分支要求
       typeof === 'string'，数组会静默不匹配（返回 false，不报错）——那种"搜不到但也不说
       为什么"最难查。CSV 导出也顺带变成可读的 "a, b" 而不是 ["a","b"]。
       和 ToolsPanel 里对 tags 做的是同一件事。 */
    return raw.map((s) => ({
      ...s,
      changeTypes: Array.isArray(s.changeTypes) ? s.changeTypes.join(', ') : s.changeTypes,
    }));
  }, [subscriptions.data]);

  const missingConnection = connection.trim() === '';

  /**
   * 位点小于 0 的含义。
   *
   * 后端在 getCdcStatus 里对负的 watermark 有一句注释：负数表示<b>取位点的那次查询本身失败了</b>，
   * 而不是「位点是 -1」。不把这件事翻出来的话，页面上会显示一个看起来像正常数字的 -1，
   * 而它其实是一次失败。
   */
  const currentLsn = status.data?.currentLsn ?? lsn.data?.currentLsn;
  const lsnFailed = typeof currentLsn === 'number' && currentLsn < 0;

  return (
    <VStack gap={6}>
      <ErrorNotice error={config.error} />

      {cdcOff && (
        <Banner
          status="info"
          title="本部署没有开启 CDC（entropy.mcp.database.cdc.enabled=false）"
          description={
            '这是配置事实而不是故障。下面「CDC 状态」和「当前位点」两块会失败（工具里先检查这个'
            + '开关，不满足就抛错 → HTTP 500）；「方言支持」和「订阅登记」两块仍然能成功——'
            + '前者只问方言，后者读的是进程内存里的登记表，都不要求这个开关。'
            + '按钮刻意不置灰：有时候你要的就是那条 500 的原文。'
          }
          container="card"
        />
      )}

      <Section
        title="CDC 模块的生效配置"
        source="GET /api/ui/cdc/config · 不连库、挂载即拉"
      >
        <KpiGrid
          items={[
            {
              label: '功能开关',
              value: cfg?.enabled,
              status: cdcOff ? 'warning' : 'success',
              statusLabel: cdcOff ? 'CDC 未开启' : 'CDC 已开启',
              hint: 'entropy.mcp.database.cdc.enabled',
            },
            { label: '实时流', value: cfg?.enableRealtimeStreaming },
            {
              label: '单次轮询最多取',
              value: cfg?.maxEventsPerPoll,
              hint: '条 · 超过就要靠下一次轮询继续',
            },
            {
              label: '默认轮询间隔',
              value: cfg?.defaultPollIntervalMs,
              hint: 'ms · 注册订阅时不传间隔就用它',
            },
            { label: '镜像表', value: cfg?.enableMirrorTables, hint: '建镜像表是 DDL，本页面不提供入口' },
            { label: '镜像任务上限', value: cfg?.maxMirrorTasks },
            { label: '事件监听器', value: cfg?.enableEventListeners },
          ]}
        />
      </Section>

      <Section
        title="查询条件"
        source="一次点击发四个请求：/cdc/support、/cdc/status、/cdc/subscriptions、/cdc/lsn · 各自独立显示成功或失败"
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="connection"
              value={connection}
              onChange={setConnection}
              isRequired
              placeholder="必填"
              description="status 与 lsn 在工具内部对 connection 做必填校验，缺了返回 500 而不是 400"
              status={missingConnection
                ? { type: 'error', message: '必须先填连接名：留空时 status / lsn 返回的是 500' }
                : undefined}
              width={280}
            />
            <Button
              label="查询"
              variant="primary"
              isDisabled={missingConnection}
              isLoading={support.isLoading || status.isLoading
                || subscriptions.isLoading || lsn.isLoading}
              onClick={() => setSubmitted(connection.trim())}
            />
          </HStack>
          {submitted === null && (
            <Text type="supporting" color="secondary">
              还没点过「查询」，这一页目前没有向任何库发过请求。
            </Text>
          )}
        </VStack>
      </Section>

      {submitted !== null && (
        <VStack gap={5}>
          <Section
            title="方言支持"
            source="GET /api/ui/cdc/support · 不要求 CDC 开关，只问这个连接的方言有没有 CDC 实现"
          >
            <VStack gap={3}>
              <ErrorNotice error={support.error} />
              <KpiGrid
                items={[
                  {
                    label: '方言支持 CDC',
                    value: support.data?.cdcSupported,
                    status: support.data?.cdcSupported === false ? 'warning' : 'success',
                    statusLabel: support.data?.cdcSupported === false
                      ? '不支持 —— 下面的状态与位点必然失败'
                      : '支持',
                  },
                  { label: '连接', value: support.data?.connection },
                ]}
              />
              {support.data?.note && (
                <Text type="supporting" color="secondary">
                  {`后端的说明：${displayValue(support.data.note)}`}
                </Text>
              )}
            </VStack>
          </Section>

          <Section
            title="CDC 状态"
            source="GET /api/ui/cdc/status · 要求 CDC 开关 + 非空 connection，任一不满足是 500"
          >
            <VStack gap={3}>
              <ErrorNotice error={status.error} />
              <KpiGrid
                items={[
                  {
                    label: '方言支持 CDC',
                    value: status.data?.cdcSupported,
                  },
                  {
                    label: '当前位点',
                    value: status.data?.currentLsn,
                    status: lsnFailed ? 'error' : undefined,
                    statusLabel: '负数意味着取位点的那次查询失败了',
                    hint: lsnFailed
                      ? '负数不是一个真实位点：后端用它表示取位点失败'
                      : '单位随方言：Oracle 是 SCN、PostgreSQL 是解码后的 WAL LSN、MySQL 是 Unix 秒',
                  },
                  { label: '活跃订阅数', value: status.data?.activeSubscriptions },
                  { label: '累计捕获事件', value: status.data?.totalEventsCaptured },
                  { label: '最后一条事件时间', value: status.data?.lastEventTime },
                ]}
              />
            </VStack>
          </Section>

          <Section
            title="当前位点（单独一次调用）"
            source={
              'GET /api/ui/cdc/lsn · 和上面 status 里的 currentLsn 是同一个来源，'
              + '两处不一致说明这两次调用之间有新变更进来了 —— 那不是矛盾'
            }
          >
            <VStack gap={3}>
              <ErrorNotice error={lsn.error} />
              <KpiGrid
                items={[
                  { label: 'currentLsn', value: lsn.data?.currentLsn },
                  { label: '连接', value: lsn.data?.connection },
                ]}
              />
            </VStack>
          </Section>

          <ErrorNotice error={subscriptions.error} />

          <InteractiveTable
            title="订阅登记"
            source={
              `GET /api/ui/cdc/subscriptions · 后端报的 totalCount=${displayValue(subscriptions.data?.totalCount)}`
              + ' · 订阅只是服务进程内存里的登记，不在数据库端建任何东西，重启即清空'
            }
            rows={subs}
            columns={SUBSCRIPTION_COLUMNS}
            searchFields={SUBSCRIPTION_SEARCH_FIELDS}
            searchName="cdc-subscriptions"
            getRowKey={SUBSCRIPTION_ROW_KEY}
            detailTitle="订阅详情"
            csvBaseName="cdc-subscriptions"
            emptyTitle="这个连接上没有订阅"
            emptyDescription={
              '订阅是通过 MCP 工具 registerSubscription 注册的，本页面不提供注册入口（那是写操作）。'
              + '空列表也可能是进程重启过 —— 登记只在内存里。'
            }
          />
        </VStack>
      )}
    </VStack>
  );
}
