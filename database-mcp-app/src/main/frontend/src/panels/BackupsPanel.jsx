import {
  Banner,
  Button,
  HStack,
  NumberInput,
  SegmentedControl,
  SegmentedControlItem,
  Text,
  TextInput,
  VStack,
} from '@astryxdesign/core';
import { useMemo, useState } from 'react';
import { fetchBackupDetail, fetchBackups, fetchBackupsConfig } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  CopyTextButton,
  ErrorNotice,
  InteractiveTable,
  KpiGrid,
  MonoBlock,
  SEARCH_ALL_FIELD,
  Section,
  contentRowKey,
  displayValue,
  numberColumn,
  textColumn,
  timeColumn,
} from '../components.jsx';

/**
 * 备份清单（只读）。来源 GET /api/ui/backups、/backups/detail、/backups/config。
 *
 * ── 这一页最重要的一句话不是任何数据，而是一句警告 ──
 * `storageInMemoryOnly` 在后端是<b>写死的 true</b>（BackupTools.getBackupConfig 里直接
 * `"storageInMemoryOnly", true`，不是从配置读的）：备份元数据只存在服务进程的内存里，
 * <b>重启即全部丢失</b>，而且超过 maxRecords 或 retentionDays 会被静默淘汰。
 *
 * 为什么必须显眼地写出来：这一页同时显示 retentionDays（留存天数）这种词，而
 * 「留存 7 天」会让任何人默认这些备份是落盘的。真相是反过来的——重启一次，
 * 这张列表里的每一条都会变成一次 NOT_FOUND。要长期留存得在 backupTable 返回时
 * 自己取走 sqlStatements 落盘，那件事这个只读面板做不到。
 *
 * ── 为什么列表也要手点 ──
 * listBackups 读的是内存里的元数据仓库，不连库、也不在 PerformanceTimingAspect 的切点里，
 * 所以「挂载即拉」在技术上是无害的。仍然要手点，是为了守住整页统一的口径：
 * 除了纯配置端点，这一批页面一律「先填参数、点按钮才发请求」。
 * 一页破例的代价是下一个人得逐页去记「哪一页会自己发请求」。
 */

/**
 * typeFilter 的合法取值。
 *
 * 后端刻意<b>不</b>预校验它（合法集合是工具那边的 BackupType 枚举，抄一份就是第二份会漂移的
 * 清单），传了非法值直接 500。前端这里给成一组固定档位而不是自由输入，正是为了不让人
 * 有机会拼错——档位是「不校验」这个决定在 UI 侧的对应物。
 *
 * '' 这个档位表示不传这个参数（后端 required=false）。写成显式档位的理由和血缘页的
 * 「结构化」一样：不给它一个位置，用户就得靠清空输入框来表达「不过滤」。
 */
const TYPE_FILTERS = [
  { value: '', label: '全部' },
  { value: 'FULL', label: 'FULL' },
  { value: 'INCREMENTAL', label: 'INCREMENTAL' },
  { value: 'SCHEMA', label: 'SCHEMA' },
];

/**
 * 备份记录的列。
 *
 * 键名抄的是 BackupTools.toBackupItem 那段 context(...)：backupId / tableName / connection /
 * type / status / createdAt / totalRows / backedUpRows / restoredRows / durationMs，
 * 另外 completedAt 与 errorDetail <b>只在非空时才出现</b>（`if (m.completedAt() != null) put`），
 * 所以它们不是恒定键 —— 表格里给了列也没关系（displayValue 把缺失折成「—」），
 * 但别据此写「所有备份都没完成」这种判断。
 *
 * 注意不是 backupType / connectionKey / rowCount：BackupMetadata 这个 record 里叫
 * connectionKey，而外层这一层改名成了 connection；行数叫 totalRows 而不是 rowCount。
 */
const BACKUP_COLUMNS = [
  textColumn('backupId', '备份 ID', { flex: 2, weight: 'semibold', filter: 'backupId' }),
  textColumn('tableName', '表名', { flex: 2, filter: 'tableName' }),
  textColumn('type', '类型', { flex: 1, filter: 'type' }),
  textColumn('status', '状态', { flex: 1, filter: 'status' }),
  textColumn('connection', '连接', { flex: 1, filter: 'connection' }),
  numberColumn('totalRows', '总行数', { px: 104 }),
  numberColumn('backedUpRows', '已备份行数', { px: 116 }),
  numberColumn('durationMs', '耗时(ms)', { px: 104 }),
  timeColumn('createdAt', '创建于'),
];

const BACKUP_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'backupId', type: 'string', label: '备份 ID' },
  { key: 'tableName', type: 'string', label: '表名' },
  { key: 'type', type: 'string', label: '类型' },
  { key: 'status', type: 'string', label: '状态' },
  { key: 'connection', type: 'string', label: '连接' },
];

const BACKUP_ROW_KEY = contentRowKey(['backupId']);

const IN_MEMORY_WARNING = '备份元数据只存在服务进程的内存里：进程重启即全部丢失，'
  + '超过条数上限或留存天数的记录也会被静默淘汰。淘汰或重启之后，'
  + '这张列表里的 id 每一个都会变成 NOT_FOUND。'
  + '下面那个「留存天数」不代表持久化——它只是内存里的过期时间。'
  + '要长期留存，得在做备份时自己取走 SQL 脚本落盘，这个只读面板做不到那件事。';

export default function BackupsPanel({ limit, refreshToken }) {
  const [connection, setConnection] = useState('');
  const [tableName, setTableName] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [useLimit, setUseLimit] = useState(limit);
  const [submitted, setSubmitted] = useState(null);

  const [backupId, setBackupId] = useState('');
  const [submittedBackupId, setSubmittedBackupId] = useState(null);

  /* config 不连库，跟着挂载就拉：那句「只在内存里」必须在看到任何列表之前就在页面上。 */
  const config = usePanelData(() => fetchBackupsConfig(), [refreshToken]);
  const cfg = config.data;

  const list = usePanelData(
    () => fetchBackups(submitted),
    [JSON.stringify(submitted), refreshToken],
    { enabled: submitted !== null },
  );

  const detail = usePanelData(
    () => fetchBackupDetail({ backupId: submittedBackupId }),
    [submittedBackupId, refreshToken],
    { enabled: submittedBackupId !== null },
  );

  const records = useMemo(
    () => (Array.isArray(list.data?.records) ? list.data.records : []),
    [list.data],
  );

  const missingBackupId = backupId.trim() === '';

  /* sqlScriptPreview 是详情里唯一的长文本，单独抓出来给等宽块 + 复制按钮。 */
  const sqlPreview = typeof detail.data?.sqlScriptPreview === 'string'
    ? detail.data.sqlScriptPreview
    : '';

  /**
   * 详情里除 sqlScriptPreview 之外的字段，铺成 KPI。
   *
   * 动态铺而不是写死一串：这个端点是 BackupTools.getBackup 的原样透传，字段随
   * BackupMetadata 走，写死的话后端加一个字段页面上就看不到。
   * 排除掉 sqlScriptPreview 是因为它是一整段脚本，塞进一张 200px 宽的卡片里没法读。
   */
  const detailItems = useMemo(() => {
    const d = detail.data;
    if (!d || typeof d !== 'object') {
      return [];
    }
    return Object.keys(d)
      .filter((k) => k !== 'sqlScriptPreview')
      .sort()
      .map((k) => ({ label: k, value: d[k] }));
  }, [detail.data]);

  const submittedText = submitted
    ? [
      submitted.connection ? `connection=${submitted.connection}` : 'connection=（不过滤）',
      submitted.tableName ? `tableName=${submitted.tableName}` : null,
      submitted.typeFilter ? `typeFilter=${submitted.typeFilter}` : 'typeFilter=（不过滤）',
      `limit=${submitted.limit}`,
    ].filter(Boolean).join(' · ')
    : null;

  return (
    <VStack gap={6}>
      <ErrorNotice error={config.error} />

      {/*
        这条横幅是这一页存在的主要理由之一，所以：error 级、放最上面、不可关闭。
        用 error 而不是 warning：它说的不是「注意一下」，而是「你正在看的东西会消失」。
      */}
      <Banner
        status="error"
        title="这里列出的备份不是持久的（storageInMemoryOnly 在后端是硬编码的 true）"
        description={IN_MEMORY_WARNING}
        container="card"
      />

      <Section
        title="备份模块的生效配置"
        source="GET /api/ui/backups/config · 不连库、挂载即拉"
      >
        <KpiGrid
          items={[
            {
              label: '功能开关',
              value: cfg?.enabled,
              status: cfg?.enabled === false ? 'warning' : undefined,
              statusLabel: '备份功能未开启',
            },
            { label: '增量备份', value: cfg?.incrementalEnabled },
            {
              label: '单次备份行数上限',
              value: cfg?.maxBackupRows,
              hint: '超过就截断，备份不完整',
            },
            {
              label: '只在内存里',
              value: cfg?.storageInMemoryOnly,
              status: cfg?.storageInMemoryOnly === true ? 'error' : undefined,
              statusLabel: '重启即全部丢失',
              hint: '这一项是写死的 true，不是可配项',
            },
            {
              label: '内存里最多留几条',
              value: cfg?.maxRecords,
              hint: '超出后最旧的被静默淘汰',
            },
            {
              label: '留存天数',
              value: cfg?.retentionDays,
              hint: '内存里的过期时间，和「持久化」无关',
            },
            { label: '当前留存条数', value: cfg?.totalBackups },
          ]}
        />
      </Section>

      <Section
        title="查询条件"
        source="GET /api/ui/backups · 三个过滤条件都可以留空；typeFilter 给的是固定档位，因为后端对非法取值直接 500"
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="connection"
              value={connection}
              onChange={setConnection}
              placeholder="留空不按连接过滤"
              width={220}
            />
            <TextInput
              label="tableName"
              value={tableName}
              onChange={setTableName}
              placeholder="留空不按表名过滤"
              width={220}
            />
            <NumberInput
              label="limit"
              value={useLimit}
              min={1}
              max={500}
              step={10}
              isIntegerOnly
              hasNumberSteppers
              width={140}
              description="后端夹到 1..500"
              onChange={(v) => setUseLimit(v ?? 1)}
            />
            <Button
              label="查询"
              variant="primary"
              isLoading={list.isLoading}
              onClick={() => setSubmitted({
                connection: connection.trim(),
                tableName: tableName.trim(),
                typeFilter,
                limit: useLimit,
              })}
            />
          </HStack>
          <SegmentedControl value={typeFilter} onChange={setTypeFilter} label="备份类型">
            {TYPE_FILTERS.map((t) => (
              <SegmentedControlItem key={t.value || 'all'} value={t.value} label={t.label} />
            ))}
          </SegmentedControl>
          {submitted === null && (
            <Text type="supporting" color="secondary">
              还没点过「查询」。这个端点只读服务进程内存里的元数据，不连任何库。
            </Text>
          )}
        </VStack>
      </Section>

      <ErrorNotice error={list.error} />

      {submitted !== null && (
        <VStack gap={5}>
          <Section
            title="条数对照"
            source={`GET /api/ui/backups · ${submittedText}`}
          >
            <KpiGrid
              items={[
                {
                  label: '本次返回',
                  value: list.data?.total,
                  hint: '受上面的过滤条件与 limit 限制',
                },
                {
                  label: '服务端现存总数',
                  value: list.data?.storageTotal,
                  /* 两个数都必须显示：只显示 total 的话，「被我过滤掉了」和
                     「已经被淘汰 / 重启丢了」在页面上没有区别。 */
                  hint: '内存里现存的全部条数 · 和上一格不等就是被过滤或被 limit 截了',
                },
              ]}
            />
          </Section>

          <InteractiveTable
            title="备份记录"
            source={`GET /api/ui/backups 的 records · ${IN_MEMORY_WARNING}`}
            rows={records}
            columns={BACKUP_COLUMNS}
            searchFields={BACKUP_SEARCH_FIELDS}
            searchName="backups"
            getRowKey={BACKUP_ROW_KEY}
            detailTitle="备份记录（列表里的那一行）"
            csvBaseName="backups"
            emptyTitle="没有备份记录"
            emptyDescription={
              '三种可能，页面区分不了：这个部署上没做过备份、过滤条件太严、'
              + '或者进程重启 / 淘汰把记录清空了（见页面顶部那条警告）。'
            }
          />
        </VStack>
      )}

      <Section
        title="按 backupId 查详情"
        source="GET /api/ui/backups/detail?backupId= · 记录不存在时后端 500（重启或淘汰之后这是常态，不是故障）"
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="backupId"
              value={backupId}
              onChange={setBackupId}
              isRequired
              placeholder="从上面的列表里复制一个"
              status={missingBackupId
                ? { type: 'error', message: 'backupId 必填，缺了后端 400' }
                : undefined}
              width={320}
            />
            <Button
              label="查询"
              variant="primary"
              isDisabled={missingBackupId}
              isLoading={detail.isLoading}
              onClick={() => setSubmittedBackupId(backupId.trim())}
            />
          </HStack>
          {submittedBackupId === null && (
            <Text type="supporting" color="secondary">
              还没查过详情。
            </Text>
          )}
        </VStack>
      </Section>

      <ErrorNotice error={detail.error} />
      {detail.error && (
        <Banner
          status="info"
          title="查不到某个 backupId 通常不是故障"
          description={
            '备份元数据只在内存里：进程重启过、或者这条记录被条数 / 天数上限淘汰了，'
            + '就会是这个结果（工具抛 NOT_FOUND，在 HTTP 上是 500）。'
            + '一份放了几天的旧列表，里面的每个 id 都会这样。'
          }
          container="card"
        />
      )}

      {submittedBackupId !== null && detail.data && (
        <Section
          title={`备份详情 ${displayValue(detail.data.backupId ?? submittedBackupId)}`}
          source="GET /api/ui/backups/detail · 字段随后端的 BackupMetadata 走，这里动态铺开，不写死清单"
          actions={
            <CopyTextButton text={sqlPreview} label="复制 SQL 脚本" what="SQL 脚本" />
          }
        >
          <VStack gap={4}>
            <KpiGrid items={detailItems} />
            <VStack gap={2}>
              <Text type="label" weight="semibold">SQL 脚本预览（sqlScriptPreview）</Text>
              <Text type="supporting" color="secondary">
                这是「预览」而不是完整脚本：后端只给前一段。要完整的 SQL 得走 MCP 工具，
                这个页面只发 GET，也不提供任何执行入口。
              </Text>
              <MonoBlock text={sqlPreview} emptyText="（这条记录没有 sqlScriptPreview）" />
            </VStack>
          </VStack>
        </Section>
      )}
    </VStack>
  );
}
