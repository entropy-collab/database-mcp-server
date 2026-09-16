import { useCallback, useEffect, useState } from 'react';
import {
  Banner,
  Button,
  Divider,
  HStack,
  Heading,
  NumberInput,
  Stack,
  Switch,
  Tab,
  TabList,
  Text,
} from '@astryxdesign/core';
import { FALLBACK_MAX_LIMIT, fetchConfig } from './api.js';
import AuditPanel from './panels/AuditPanel.jsx';
import HistoryPanel from './panels/HistoryPanel.jsx';
import ConnectionsPanel from './panels/ConnectionsPanel.jsx';
import PerformancePanel from './panels/PerformancePanel.jsx';

const TABS = [
  { value: 'audit', label: '审计流水（内存）' },
  { value: 'history', label: '审计历史（落库）' },
  { value: 'connections', label: '连接与连接池' },
  { value: 'performance', label: '性能' },
];

const AUTO_REFRESH_INTERVAL_MS = 10_000;

/**
 * 无鉴权横幅。
 *
 * 完全由 /api/ui/config 的 authEnabled 驱动，不写死文案：写死的文案会在部署方打开鉴权之后
 * 继续吓人，或者更糟——在关掉鉴权之后继续说"已鉴权"。
 *
 * config 拿不到时也要出横幅（内容改成"状态未知"）：静默假设"已鉴权"是最坏的失败方向。
 */
function AuthBanner({ config, isConfigLoaded }) {
  if (config?.authEnabled === true) {
    return null;
  }
  const unknown = isConfigLoaded && !config;
  if (!isConfigLoaded) {
    return null;
  }
  return (
    <Banner
      status="error"
      title={
        unknown
          ? '无法读取 /api/ui/config：本页面的鉴权状态未知。'
          : '本服务未开启 HTTP 鉴权（entropy.mcp.security.enabled=false）。'
      }
      description={
        unknown
          ? '在确认鉴权状态之前，请当作本页面与其 API 都是无凭证可读的。'
          : '本页面与它调用的所有 API（含审计流水中的 SQL 原文、慢查询原文、连接清单）'
            + '对任何能访问此端口的人开放。关闭这个暴露的唯一开关是 '
            + 'entropy.mcp.security.enabled=true（需配置 MCP_SECURITY_ADMIN_PASSWORD）。'
      }
      container="section"
    />
  );
}

export default function App() {
  const [config, setConfig] = useState(null);
  const [isConfigLoaded, setConfigLoaded] = useState(false);
  const [tab, setTab] = useState('audit');
  const [limit, setLimit] = useState(50);
  const [refreshToken, setRefreshToken] = useState(0);
  const [isAutoRefresh, setAutoRefresh] = useState(false);
  const [updatedAt, setUpdatedAt] = useState(null);

  // 自举：先读 config，再决定横幅与「审计历史」页要不要发请求。
  useEffect(() => {
    let cancelled = false;
    fetchConfig()
      .then((cfg) => { if (!cancelled) { setConfig(cfg); } })
      .catch(() => { if (!cancelled) { setConfig(null); } })
      .finally(() => { if (!cancelled) { setConfigLoaded(true); } });
    return () => { cancelled = true; };
  }, []);

  const refresh = useCallback(() => {
    setRefreshToken((t) => t + 1);
    setUpdatedAt(new Date());
  }, []);

  /*
   * 自动刷新默认关闭，而不是固定轮询。
   *
   * 每刷一次连接页都会经过 DynamicDataSourceManagerImpl 的读方法，而
   * PerformanceTimingAspect.CONNECTION_REGISTRY_BOOKKEEPING 会给这些方法各记一条
   * recordToolExecution——固定轮询等于让页面自己往性能指标里灌流量。
   * 指标被 UI 自己污染，比少看几秒新鲜度糟得多。
   */
  useEffect(() => {
    if (!isAutoRefresh) {
      return undefined;
    }
    const timer = setInterval(refresh, AUTO_REFRESH_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [isAutoRefresh, refresh]);

  const maxLimit = config?.maxLimit ?? FALLBACK_MAX_LIMIT;
  const panelProps = { limit, refreshToken };

  return (
    <Stack direction="column" gap={5} padding={6} maxWidth={1600}>
      <AuthBanner config={config} isConfigLoaded={isConfigLoaded} />

      <Stack direction="column" gap={3}>
        <Heading level={1}>Database MCP Server</Heading>
        <Text size="sm" color="secondary">只读运维面板 · 只发 GET，不提供任何能改动服务或数据库的入口</Text>
      </Stack>

      <HStack gap={4} align="end" wrap="wrap">
        <NumberInput
          label="条数"
          value={limit}
          min={1}
          max={maxLimit}
          step={10}
          isIntegerOnly
          hasNumberSteppers
          width={160}
          onChange={(value) => setLimit(value ?? 1)}
        />
        <Switch
          label={`自动刷新（${AUTO_REFRESH_INTERVAL_MS / 1000}s）`}
          value={isAutoRefresh}
          onChange={setAutoRefresh}
          description="默认关闭：轮询会把页面自己的读操作计入性能指标"
        />
        <Button label="刷新" variant="primary" onClick={refresh} />
        <Text size="sm" color="secondary">
          {updatedAt ? `更新于 ${updatedAt.toLocaleTimeString()}` : '尚未手动刷新'}
        </Text>
      </HStack>

      <Divider />

      <TabList value={tab} onChange={setTab} role="tablist" hasDivider>
        {TABS.map((t) => (
          <Tab key={t.value} value={t.value} label={t.label} panelId={`panel-${t.value}`} />
        ))}
      </TabList>

      {/*
        面板等 config 到位再渲染，和零构建版本一样先自举再刷新。
        原因不是性能，是别说错话：config 还没回来时 auditPersistence 是 undefined，
        「审计历史」页会先闪一条"状态未知"，两百毫秒后又变成正常表格。
        一次请求的等待换掉一次误导性的闪烁，划得来。
      */}
      {isConfigLoaded && (
        <div id={`panel-${tab}`} role="tabpanel">
          {tab === 'audit' && <AuditPanel {...panelProps} />}
          {tab === 'history' && (
            <HistoryPanel {...panelProps} auditPersistence={config?.auditPersistence} />
          )}
          {tab === 'connections' && <ConnectionsPanel {...panelProps} />}
          {tab === 'performance' && <PerformancePanel {...panelProps} />}
        </div>
      )}
    </Stack>
  );
}
