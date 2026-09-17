import { Divider, HStack, StatusDot, Text, Timestamp, VStack } from '@astryxdesign/core';
import { fetchInfo } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import { ErrorNotice, KpiGrid, Section, displayValue } from '../components.jsx';

/**
 * 服务信息。来源 GET /api/ui/info（服务名、版本、profile、启动时间、四个开关、工具数）。
 *
 * 这一页只读进程内状态，不碰任何库，所以它是八个视图里最便宜的一个——但它也是最容易
 * 被误读的一个，因为「版本」和「启动时间」这两个数在这套部署里都不是它们看起来的意思，
 * 页面因此各带一句说明（见下方 VERSION_HINT / STARTED_AT_HINT）。
 */

/**
 * 版本号的来源必须写出来。
 *
 * 后端读的是 spring.ai.mcp.server.version，那个值由 Maven 资源过滤在打包时把 @project.version@
 * 填进 application.yml。所以它是「打包时 pom 里的版本」，不是 build-info（这个仓库没生成
 * build-info，也没写 jar manifest 的 Implementation-Version）。差别在排查时是实的：
 * 有人拿一个旧 jar 配新配置跑，这里显示的仍是旧 jar 的版本，而不是仓库当前的版本。
 * 值是 unknown 就说明资源过滤没生效——那是构建问题，不是运行时问题。
 */
const VERSION_HINT = '来自 spring.ai.mcp.server.version（Maven 构建期填入），不是 build-info';

/**
 * startedAt 是容器 refresh 期间构造本控制器的时刻，比真实 JVM 启动晚、比 ApplicationReadyEvent 早，
 * 误差秒级。uptimeSeconds 由它推出，所以同样是近似值——别拿它算 SLA。
 */
const STARTED_AT_HINT = '控制器构造时刻，秒级近似，不是 JVM 启动时刻';

/**
 * uptimeSeconds 折成「x天 y小时 z分」。
 *
 * 为什么不直接显示秒：运行时长是给人判断「这进程是不是刚重启过」的，86400 秒和 1 天在这个
 * 判断里是同一个信息，但后者不需要心算。刻意丢掉秒位——秒级精度在一个手动刷新的页面上
 * 本来就是假的（数字在你读它的时候已经旧了），显示出来只会让人以为它是实时的。
 *
 * 不到一分钟单独说，而不是显示「0天 0小时 0分」：刚启动是个值得一眼看出来的状态。
 */
function formatUptime(seconds) {
  if (seconds === null || seconds === undefined) {
    return '—';
  }
  const total = Number(seconds);
  if (!Number.isFinite(total) || total < 0) {
    return displayValue(seconds);
  }
  if (total < 60) {
    return '不到 1 分钟';
  }
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const parts = [];
  if (days > 0) { parts.push(`${days}天`); }
  if (hours > 0) { parts.push(`${hours}小时`); }
  if (minutes > 0) { parts.push(`${minutes}分`); }
  return parts.join(' ');
}

/**
 * 四个开关各自的「好坏」口径。
 *
 * 这张表是这一页唯一有判断力的地方，所以口径写在数据里而不是散在 JSX 里：
 * - authEnabled=false 是 error：整个面板与 /api/** 对任何能连上端口的人开放，这是最坏的一格。
 * - allowUnauthenticatedInProduction=true 是 warning：它自己不打开任何东西，但它是
 *   SecurityConfig 的逃生阀——和 authEnabled=false 组合起来才是「production 上真的裸跑」。
 *   单独为 true 时只说明这个部署被允许裸跑，所以是 warning 而不是 error。
 * - gatewayEnabled 刻意中性：网关开着不等于不安全，关着也不等于安全，它就是个功能开关。
 *   给它配色等于把「颜色 = 要去看一眼」这个信号用废。
 * - auditPersistence=false 是 warning：审计只在内存里，重启即丢。它不是故障（落库是 opt-in），
 *   但「以为有审计留存而其实没有」是个会误导事后追查的状态。
 */
const SWITCH_SPECS = [
  {
    key: 'authEnabled',
    label: 'HTTP 鉴权（entropy.mcp.security.enabled）',
    tone: (v) => (v === true ? 'success' : 'error'),
    note: (v) => (v === true
      ? '页面与 /api/** 都需要 admin 凭证'
      : '页面与它调用的所有 API 无凭证可读'),
  },
  {
    key: 'allowUnauthenticatedInProduction',
    label: '允许 production 裸跑（...allow-unauthenticated-in-production）',
    tone: (v) => (v === true ? 'warning' : 'success'),
    note: (v) => (v === true
      ? '逃生阀开着：production profile 下关掉鉴权也不会启动失败'
      : '逃生阀关着：production profile 下关掉鉴权会启动失败'),
  },
  {
    key: 'gatewayEnabled',
    label: '跨库网关（entropy.mcp.gateway.enabled）',
    tone: () => 'neutral',
    note: (v) => (v === true ? '跨库/联邦查询工具可用' : '跨库/联邦查询工具不可用'),
  },
  {
    key: 'auditPersistence',
    label: '审计落库（spring.datasource.url 是否配置）',
    tone: (v) => (v === true ? 'success' : 'warning'),
    note: (v) => (v === true
      ? '审计写入审计表，「审计历史」页可用'
      : '审计只在进程内环形缓冲，重启即清空'),
  },
];

export default function InfoPanel({ refreshToken }) {
  const { data, error } = usePanelData(() => fetchInfo(), [refreshToken]);

  const switches = data?.switches ?? {};
  const toolCount = data?.toolCount ?? {};
  const profiles = data?.activeProfiles;

  /*
   * activeProfiles 是空数组时显示 default，而不是「—」。
   *
   * 空数组在 Spring 里有确切含义：没有显式激活任何 profile，生效的是 default。写成「—」
   * 会让人以为这个值读不到，进而去翻配置找一个并不存在的问题。
   */
  const profileText = Array.isArray(profiles)
    ? (profiles.length > 0 ? profiles.join(', ') : 'default（未显式激活任何 profile）')
    : displayValue(profiles);

  return (
    <VStack gap={6}>
      <ErrorNotice error={error} />

      <KpiGrid
        items={[
          { label: '版本', value: data?.version, hint: VERSION_HINT },
          {
            label: '工具数（已暴露 / 全部）',
            value: toolCount.exposed === undefined && toolCount.total === undefined
              ? undefined
              : `${displayValue(toolCount.exposed)} / ${displayValue(toolCount.total)}`,
            hint: '两个数不等说明 ToolExposureFilter 裁剪生效了，不是工具丢了',
          },
          {
            label: '运行时长',
            value: formatUptime(data?.uptimeSeconds),
            hint: '近似值，不到分钟的部分不显示',
          },
          { label: 'active profiles', value: profileText },
        ]}
      />

      <Section
        title="服务标识"
        source="GET /api/ui/info · 全部读进程内状态，这一页不连任何库"
      >
        <VStack gap={3}>
          <HStack gap={3} wrap="wrap" vAlign="center">
            <Text type="label" color="secondary">服务名</Text>
            <Text type="body">{displayValue(data?.serviceName)}</Text>
            <Text type="supporting" color="secondary">spring.ai.mcp.server.name</Text>
          </HStack>
          <Divider />
          <HStack gap={3} wrap="wrap" vAlign="center">
            <Text type="label" color="secondary">版本</Text>
            <Text type="body">{displayValue(data?.version)}</Text>
            <Text type="supporting" color="secondary">{VERSION_HINT}</Text>
          </HStack>
          <Divider />
          <HStack gap={3} wrap="wrap" vAlign="center">
            <Text type="label" color="secondary">启动于</Text>
            {data?.startedAt
              ? <Timestamp value={data.startedAt} format="system_date_time" type="body" color="primary" />
              : <Text type="body">—</Text>}
            <Text type="supporting" color="secondary">{STARTED_AT_HINT}</Text>
          </HStack>
        </VStack>
      </Section>

      <Section
        title="生效开关"
        source="同一个端点的 switches 块 · 四项都是生效值，不是代码里的默认值"
        count={SWITCH_SPECS.length}
      >
        <VStack gap={3}>
          {SWITCH_SPECS.map((spec, index) => {
            const value = switches[spec.key];
            /*
             * 读不到时用 neutral，而不是套用 tone(undefined)。
             * tone 函数对 undefined 会给出「坏」的那一档（比如 authEnabled 会算成 error），
             * 那等于把「不知道」说成「已确认是坏的」——两者在排查时该做的事不一样。
             */
            const known = value === true || value === false;
            return (
              <VStack gap={2} key={spec.key}>
                {index > 0 && <Divider />}
                <HStack gap={3} wrap="wrap" vAlign="center">
                  <StatusDot
                    variant={known ? spec.tone(value) : 'neutral'}
                    label={`${spec.label}：${known ? displayValue(value) : '状态未知'}`}
                  />
                  <Text type="body">{spec.label}</Text>
                  <Text type="label" weight="semibold">
                    {known ? displayValue(value) : '未知'}
                  </Text>
                </HStack>
                <Text type="supporting" color="secondary">
                  {known ? spec.note(value) : '读不到 /api/ui/info 的 switches，按「状态未知」处理。'}
                </Text>
              </VStack>
            );
          })}
        </VStack>
      </Section>
    </VStack>
  );
}
