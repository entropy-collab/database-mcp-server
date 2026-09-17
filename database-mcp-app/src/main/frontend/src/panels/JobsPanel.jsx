import {
  Banner,
  Button,
  HStack,
  Text,
  TextInput,
  VStack,
} from '@astryxdesign/core';
import { useMemo, useState } from 'react';
import { fetchJobStatus, fetchJobs } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  DataTable,
  ErrorNotice,
  InteractiveTable,
  KpiGrid,
  SEARCH_ALL_FIELD,
  Section,
  contentRowKey,
  displayValue,
  numberColumn,
  textColumn,
} from '../components.jsx';

/**
 * ETL 作业（只读）。来源 GET /api/ui/jobs 与 /api/ui/jobs/status?jobId=。
 *
 * ── 为什么连「列出作业」也要手点，尽管它根本不连库 ──
 * 一开始我以为 /api/ui/jobs 可以跟着挂载就拉：它读的是 JobExecutionEngine 里的内存列表，
 * 不发任何 SQL。查了 PerformanceTimingAspect 之后改了主意——它的
 * DATABASE_ACCESS_CLASSES 里<b>包含</b>
 * `execution(* com.entropy.database.mcp.etl.JobExecutionEngine.*(..))`，
 * 也就是说 listExecutions() 会被当成一次「数据库操作」记进审计流水与性能指标。
 *
 * 那么挂载即拉的代价就不是一次廉价的内存读，而是「运维每次切到这一页，审计流水里就多一条
 * 不是任何人发起的 ETL 记录」。指标和审计被 UI 自己污染，比多点一次按钮糟得多——这也正是
 * 顶栏自动刷新默认关闭的同一条理由。
 *
 * ── 已知后端缺陷，必须写在页面上 ──
 * EtlTools.listJobs() 用 `Map.of(...)` 装每条作业，其中 startedAt / completedAt 在作业
 * 「还没开始」或「还没结束」时是 null。而 `Map.of` <b>不接受 null 值</b>（抛 NPE），
 * safeExecute 把它包成 McpToolException → HTTP 500。
 *
 * 结论：gateway 开着、并且存在未开始 / 未完成的作业时，这个端点必然 500。
 * 这不是页面的 bug，也不是「连不上」——所以 500 时的提示文案要把这句说出来，
 * 否则运维会去查 gateway 配置和数据库连接，而真实原因是一行 Map.of。
 */

/** 500 时的解释。写成常量是因为它同时出现在错误区和空表格的说明里，两处必须逐字一致。 */
const KNOWN_500_HINT = '如果上面的错误是 HTTP 500：这是一个已知的后端缺陷，不是这一页的问题，'
  + '也不一定是连接或配置的问题。EtlTools.listJobs() 用 Map.of 装每条作业，而 Map.of 不接受 '
  + 'null 值；作业「未开始」时 startedAt 是 null、「未完成」时 completedAt 是 null，'
  + '于是只要存在这样的作业，这个端点就会抛 NPE → 500。'
  + '换句话说：gateway 开着而且真有作业在跑时，这个列表反而拿不到。'
  + '单个作业的详情（下方按 jobId 查）走的是另一个方法，不受这个缺陷影响。';

const JOB_COLUMNS = [
  textColumn('jobId', '作业 ID', { flex: 2, weight: 'semibold', filter: 'jobId' }),
  textColumn('jobName', '作业名', { flex: 2, filter: 'jobName' }),
  textColumn('status', '状态', { flex: 1, filter: 'status' }),
  textColumn('progress', '进度', { flex: 1 }),
  numberColumn('totalSteps', '步骤数', { px: 96 }),
  numberColumn('completedSteps', '已完成', { px: 96 }),
  numberColumn('failedSteps', '失败', { px: 88 }),
  textColumn('startedAt', '开始于', { flex: 2 }),
  textColumn('completedAt', '结束于', { flex: 2 }),
];

const JOB_SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'jobId', type: 'string', label: '作业 ID' },
  { key: 'jobName', type: 'string', label: '作业名' },
  { key: 'status', type: 'string', label: '状态' },
];

const JOB_ROW_KEY = contentRowKey(['jobId']);

/**
 * 步骤明细的列。
 *
 * 键名抄的是 EtlTools.getJobStatus 里那段 context(...)：
 * stepId / status / startedAt / completedAt / rowsAffected / error。
 * 不是 message / rowCount —— 第一版按那两个写，页面上会是两列整齐的「—」。
 */
const STEP_COLUMNS = [
  textColumn('stepId', '步骤 ID', { flex: 2, weight: 'semibold' }),
  textColumn('status', '状态', { flex: 1 }),
  numberColumn('rowsAffected', '影响行数', { px: 104 }),
  textColumn('startedAt', '开始于', { flex: 2 }),
  textColumn('completedAt', '结束于', { flex: 2 }),
  textColumn('error', '错误', { flex: 3 }),
];

/**
 * 失败 / 有失败步骤的行标红。
 *
 * 判据用 failedSteps > 0 而不是只看 status：status 是整个作业的状态，
 * 一个「已完成」的作业里也可能有失败的步骤（引擎不一定因为一步失败就把整个作业标失败），
 * 而那种行恰恰是要先看的。
 */
function jobTone(row) {
  if (Number(row.failedSteps) > 0) {
    return 'error';
  }
  const status = String(row.status ?? '').toUpperCase();
  if (status.includes('FAIL') || status.includes('ERROR')) {
    return 'error';
  }
  if (status.includes('RUNNING') || status.includes('PENDING')) {
    return 'warning';
  }
  return null;
}

/**
 * gateway 关闭时的降级体：{enabled:false, reason, property}。
 *
 * 它是 <b>200</b> 而不是错误，所以走 info 横幅而不是 ErrorNotice：
 * 「这个部署没开 ETL」是一个配置事实，把它显示成故障会让人去查日志。
 * property 一定要显示出来——它就是那个开关的键名，看到它就不用去翻源码。
 */
function GatewayDisabledNotice({ data }) {
  return (
    <Banner
      status="info"
      title="本部署没有启用 ETL（网关关闭）"
      description={
        `后端返回的是 200 + enabled=${displayValue(data.enabled)}，这是配置事实而不是故障。\n`
        + `reason：${displayValue(data.reason)}\n`
        + `要打开的配置项：${displayValue(data.property)}=true\n`
        + '关掉时容器里根本没有 EtlTools 这个 bean（它的 @ConditionalOnProperty 没有 '
        + 'matchIfMissing），所以这一页不是"暂时没数据"，而是"这个部署没有这个功能"。'
      }
      container="card"
    />
  );
}

export default function JobsPanel({ refreshToken }) {
  /*
   * listNonce 而不是一个布尔值：点第二次「重新读取」时，如果 deps 里只有一个已经是 true 的
   * 布尔，usePanelData 的 effect 不会再跑，按钮就成了哑的（点了没反应，也不报错）。
   * 用一个每次都变的数当 deps，语义正好是「用户又要了一次」。
   * null = 还没点过，也就是 enabled=false。
   */
  const [listNonce, setListNonce] = useState(null);
  const [jobId, setJobId] = useState('');
  const [submittedJobId, setSubmittedJobId] = useState(null);
  const listRequested = listNonce !== null;

  const list = usePanelData(
    () => fetchJobs(),
    [listNonce, refreshToken],
    { enabled: listRequested },
  );

  const detail = usePanelData(
    () => fetchJobStatus({ jobId: submittedJobId }),
    [submittedJobId, refreshToken],
    { enabled: submittedJobId !== null },
  );

  /* enabled 明确是 false 才算「网关关闭」：undefined 是还没加载，两者不能混。 */
  const listDisabled = list.data?.enabled === false;
  const detailDisabled = detail.data?.enabled === false;

  const jobs = useMemo(
    () => (Array.isArray(list.data?.jobs) ? list.data.jobs : []),
    [list.data],
  );

  /*
   * 单个作业的字段<b>嵌在 job 下面</b>：getJobStatus 返回的是 {job: {jobId, jobName, status,
   * startedAt, completedAt, progress, steps}}，不是平铺的。第一版直接读 detail.data.jobId，
   * 结果整块都是「—」——请求成功、数据也在，只是读错了一层。
   *
   * gateway 关闭时的降级体（{enabled, reason, property}）是平铺的、没有 job 这一层，
   * 所以下面的 detailDisabled 判断走的是 detail.data.enabled，不能走 job。
   */
  const job = detail.data?.job;

  const steps = useMemo(() => {
    const raw = job?.steps;
    return Array.isArray(raw) ? raw : [];
  }, [job]);

  const missingJobId = jobId.trim() === '';

  return (
    <VStack gap={6}>
      <Banner
        status="warning"
        title="这一页只读，而且连列表也要手点"
        description={
          '两件事：1) 页面不提供提交 / 停止作业的入口——那些是写操作，这个面板只发 GET；'
          + '2) 列出作业虽然不连库，但 JobExecutionEngine 的方法在 PerformanceTimingAspect 的'
          + '审计切点里，挂载即拉等于每次切到这一页就往审计流水和性能指标里各塞一条'
          + '不是任何人发起的记录。所以它也要手点。'
        }
        container="card"
      />

      <Section
        title="作业列表"
        source="GET /api/ui/jobs · 网关关闭时返回 200 + enabled=false（那是配置事实，不是故障）"
        count={list.data && !listDisabled ? jobs.length : undefined}
        actions={
          <Button
            label={listRequested ? '重新读取' : '读取作业列表'}
            variant="primary"
            isLoading={list.isLoading}
            onClick={() => setListNonce(Date.now())}
          />
        }
      >
        {!listRequested && (
          <Text type="supporting" color="secondary">
            还没读取过，这一页目前没有向后端发过请求。
          </Text>
        )}
      </Section>

      <ErrorNotice error={list.error} />
      {list.error && (
        <Banner
          status="warning"
          title="这个 500 大概率是一个已知的后端缺陷"
          description={KNOWN_500_HINT}
          container="card"
        />
      )}

      {listDisabled && <GatewayDisabledNotice data={list.data} />}

      {listRequested && !listDisabled && !list.error && (
        <VStack gap={5}>
          <KpiGrid
            items={[
              { label: '作业总数', value: list.data?.totalJobs },
              {
                label: '有失败步骤的作业',
                value: jobs.filter((j) => Number(j.failedSteps) > 0).length,
                status: jobs.some((j) => Number(j.failedSteps) > 0) ? 'error' : 'success',
                statusLabel: '有作业存在失败的步骤',
                hint: '按 failedSteps>0 统计，与作业整体 status 不一定一致',
              },
            ]}
          />
          <InteractiveTable
            title="作业"
            source="GET /api/ui/jobs 的 jobs · 只读，页面不提供提交 / 停止入口"
            rows={jobs}
            columns={JOB_COLUMNS}
            searchFields={JOB_SEARCH_FIELDS}
            searchName="etl-jobs"
            getRowKey={JOB_ROW_KEY}
            getRowTone={jobTone}
            detailTitle="作业详情（列表里的那一行）"
            csvBaseName="etl-jobs"
            emptyTitle="没有任何作业"
            emptyDescription={
              '引擎里的执行记录是空的：这个部署上还没提交过作业，或者进程重启过'
              + '（作业记录在内存里）。'
            }
          />
        </VStack>
      )}

      <Section
        title="按 jobId 查单个作业"
        source={
          'GET /api/ui/jobs/status?jobId= · jobId 走查询参数而不是路径段，'
          + '因为 jobId 由调用方起名，可能含 / 或空格'
        }
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="jobId"
              value={jobId}
              onChange={setJobId}
              isRequired
              placeholder="submitEtlJob 返回的那个 id"
              status={missingJobId
                ? { type: 'error', message: 'jobId 必填，缺了后端 400' }
                : undefined}
              width={320}
            />
            <Button
              label="查询"
              variant="primary"
              isDisabled={missingJobId}
              isLoading={detail.isLoading}
              onClick={() => setSubmittedJobId(jobId.trim())}
            />
          </HStack>
          {submittedJobId === null && (
            <Text type="supporting" color="secondary">
              还没查过单个作业。这一条不受上面那个 Map.of 缺陷影响。
            </Text>
          )}
        </VStack>
      </Section>

      <ErrorNotice error={detail.error} />

      {detailDisabled && <GatewayDisabledNotice data={detail.data} />}

      {submittedJobId !== null && !detailDisabled && job && (
        <Section
          title={`作业 ${displayValue(job.jobId)}`}
          source={`GET /api/ui/jobs/status · jobId=${submittedJobId} · 字段嵌在返回体的 job 下`}
          count={steps.length}
        >
          <VStack gap={4}>
            <KpiGrid
              items={[
                { label: '作业名', value: job.jobName },
                { label: '状态', value: job.status },
                { label: '进度', value: job.progress },
                { label: '开始于', value: job.startedAt },
                { label: '结束于', value: job.completedAt },
              ]}
            />
            <DataTable
              columns={STEP_COLUMNS}
              rows={steps}
              emptyTitle="这个作业没有步骤信息"
              emptyDescription="steps 是空的：作业还没开始执行，或者它本来就没有分步。"
            />
          </VStack>
        </Section>
      )}
    </VStack>
  );
}
