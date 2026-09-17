/*
 * 一键导出诊断包。
 *
 * 值班时最常见的动作是「把现在的状态发给另一个人」。在这之前那个动作要分四次：
 * 服务信息页截一张、连接页截一张、性能页导一份 CSV、审计流水再导一份 CSV，
 * 而且四份的时间点还不一样——对方拿到之后分不清"连接数少"和"审计里没记录"是不是
 * 同一时刻的事。这个按钮把四个只读端点在同一时刻并发拉一遍，打成一个 JSON 文件。
 *
 * ── ⚠️ 为什么必须是「手点」而不是自动执行 ──
 * 这四个端点里 /api/ui/connections 会走 DynamicDataSourceManagerImpl 的读方法，
 * 而 PerformanceTimingAspect.CONNECTION_REGISTRY_BOOKKEEPING 会给这些方法各记一条
 * recordToolExecution。也就是说：<b>拉一次诊断包，就会往性能指标里写几条记录</b>。
 * 这和 App.jsx 里「自动刷新默认关闭」是同一个坑——页面自己污染它正在观测的指标。
 * 一次手动导出留下几条记录是可接受的代价（而且导出的人知道自己点了）；
 * 挂到定时器或者挂到「切到某个视图就预取」上就不可接受了。
 * 所以这里没有任何自动触发路径，也刻意不缓存结果：每次点都是一次显式的、
 * 用户知情的读操作。
 *
 * ── 为什么用 allSettled 而不是 all ──
 * 这四块的失败是<b>正常且有信息量的</b>：没开鉴权的浏览器拿到 401、审计没落库、
 * micrometer 没装、连接一个都没注册……Promise.all 会让第一个失败吞掉其余三块，
 * 导出一个空文件；而值班要的恰恰是「哪一块拿不到、错误原文是什么」。
 * 所以每块单独记 ok + error 原文，一块失败不影响其余。
 *
 * ── 为什么不发任何新请求形态 ──
 * 全部走 api.js 里已有的四个 GET 函数，没有新端点、没有新参数、没有 POST。
 * limit 用顶栏当前的条数，和用户此刻在页面上看到的数据口径一致。
 */
import { useCallback, useState } from 'react';
import { Badge, Button, HStack, Text, VStack } from '@astryxdesign/core';
import { fetchAuditLogs, fetchConnections, fetchInfo, fetchPerformance } from './api.js';

/**
 * 诊断包里的四块。顺序就是 JSON 里的顺序，也是这四件事的阅读顺序：
 * 先"这是什么服务"，再"它连着谁"，再"它跑得怎么样"，最后"它刚刚做了什么"。
 *
 * endpoint 字段是给<b>读这份 JSON 的人</b>看的：拿到文件的人往往不是导出的人，
 * 让他能照着这一行自己再打一次同样的请求，比让他猜数据从哪来有用。
 */
function bundleParts(limit) {
  return [
    { key: 'info', endpoint: 'GET /api/ui/info', load: () => fetchInfo() },
    { key: 'connections', endpoint: 'GET /api/ui/connections', load: () => fetchConnections() },
    {
      key: 'performance',
      endpoint: `GET /api/ui/performance?limit=${limit}`,
      load: () => fetchPerformance(limit),
    },
    {
      key: 'auditLogs',
      endpoint: `GET /api/audit/logs?limit=${limit}`,
      load: () => fetchAuditLogs(limit),
    },
  ];
}

/**
 * 文件名的时间戳用<b>本机</b>时间。
 *
 * 和表格里时间列走相对时间是同一个理由的另一面（见 components.jsx 的 timeColumn）：
 * 服务器时钟可能偏几分钟，而文件名是给导出的人自己排序用的——他要的是自己表上的时间。
 * JSON 内容里的 exportedAt 则是 ISO + 时区偏移，跨机器对齐用那一个。
 */
function bundleFileName() {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `dbmcp-diagnostics-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}.json`;
}

/**
 * 纯前端下载，和 CSV 导出走同一套 Blob + revokeObjectURL 的路子。
 * 不 revoke 会让整份诊断包（可能几百 KB）一直挂在 document 上。
 */
function downloadJson(filename, text) {
  const blob = new Blob([text], { type: 'application/json;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * 顶栏的「导出诊断包」按钮。
 *
 * 按钮在拉取期间 isDisabled 并换文案：四个端点里 /api/ui/performance 在 limit=500 时
 * 不是瞬间返回的，没有这个状态的话连点五下就是二十个请求（顺带二十条 bookkeeping 记录）。
 */
export function DiagnosticBundleButton({ limit }) {
  const [isBusy, setBusy] = useState(false);
  /* 上一次导出的结果摘要：几块成功、几块失败。不是装饰——某块 401 时下载下来的
     JSON 里那一块是 error，如果按钮什么都不说，导出的人会以为拿到了完整快照。 */
  const [lastResult, setLastResult] = useState(null);

  const onExport = useCallback(async () => {
    setBusy(true);
    setLastResult(null);
    const parts = bundleParts(limit);
    /* allSettled：见文件头注释。每块的 reason 原样落进 JSON，不做归纳也不截断——
       ApiError.message 里带着状态码和响应体原文，那正是排查要看的东西。 */
    const settled = await Promise.allSettled(parts.map((p) => p.load()));

    const sections = {};
    let okCount = 0;
    settled.forEach((outcome, i) => {
      const part = parts[i];
      if (outcome.status === 'fulfilled') {
        okCount += 1;
        sections[part.key] = { ok: true, endpoint: part.endpoint, data: outcome.value };
      } else {
        const reason = outcome.reason;
        sections[part.key] = {
          ok: false,
          endpoint: part.endpoint,
          /* 同时留 error（原文，含状态码与响应体）和 httpStatus（ApiError 才有，
             普通网络故障是 undefined）。只留一个的话，"服务返回了 503" 和
             "根本没连上" 在文件里长得一样。 */
          error: reason instanceof Error ? reason.message : String(reason),
          httpStatus: reason?.status,
          data: null,
        };
      }
    });

    const bundle = {
      /* ISO 带毫秒与 Z：这份文件会被跨机器传阅，唯一无歧义的时间格式。 */
      exportedAt: new Date().toISOString(),
      /* 完整 URL 含 hash：hash 里带着 view 与 limit，收到文件的人点一下就回到
         导出者当时看的那个现场。 */
      panelUrl: window.location.href,
      note: '这是只读运维面板在上述时刻抓取的快照，全部来自 GET 端点，'
        + '不含任何写操作，也不是服务端持续记录的日志。'
        + '各块的 ok=false 表示该端点当时取不到（错误原文在 error 里），'
        + '不代表其余块无效。performance / auditLogs 两块的条数由导出时顶栏的「条数」决定，'
        + '不是服务端全量。',
      requestLimit: limit,
      sections,
    };

    downloadJson(bundleFileName(), JSON.stringify(bundle, null, 2));
    setLastResult({ ok: okCount, total: parts.length });
    setBusy(false);
  }, [limit]);

  return (
    <HStack gap={2} vAlign="center" wrap="wrap">
      <Button
        label={isBusy ? '正在抓取…' : '导出诊断包'}
        variant="secondary"
        isDisabled={isBusy}
        tooltip="并发拉取服务信息 / 连接 / 性能 / 审计流水，打成一个 JSON 下载。会在性能指标里留下几条连接注册簿的读记录。"
        onClick={onExport}
      />
      {lastResult && (
        lastResult.ok === lastResult.total
          ? <Badge variant="success" label={`${lastResult.ok}/${lastResult.total} 块已抓取`} />
          : (
            <>
              <Badge variant="warning" label={`${lastResult.ok}/${lastResult.total} 块已抓取`} />
              <Text type="supporting" color="secondary">
                失败的块在 JSON 里是 ok=false，错误原文在 error 字段
              </Text>
            </>
          )
      )}
    </HStack>
  );
}
