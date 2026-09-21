import { useCallback, useState } from 'react';
import {
  Banner,
  Button,
  Card,
  Heading,
  Text,
  TextInput,
  VStack,
} from '@astryxdesign/core';
import { login } from './api.js';

/**
 * 登录界面。<b>它是 SPA 的一个视图，不是另一个页面。</b>
 *
 * ── 为什么做在这里 ──
 * 上一版是 src/main/resources/static/login.html，一份手写的自包含 HTML。那样做能把匿名可读的
 * 范围压到一个文件，代价是它不能引用 /assets/**（否则要放通整包构建产物），于是样式和面板两套。
 * 现在 /、/index.html、/assets/** 都是 permitAll（判据见 SecurityConfig 的 WEB_UI_RESOURCES
 * 注释：要保护的是数据而不是代码），所以登录界面可以用和面板同一套 Astryx 组件。
 *
 * ── 顺带消掉的两件事 ──
 * 1. redirect 参数：登录成功后不跳转、整页不刷新，location.hash 里的现场（view / limit / 选中行）
 *    原样保留。上一版要把 hash 编进 ?redirect= 再解回来，还要防开放重定向；
 * 2. 会话超时的处理：api.js 收到 401 就通知 App 切到这个视图，用户登录完继续待在原来那张表上。
 *
 * ── 不做记住我 ──
 * 会话时长由 server.servlet.session.timeout 决定（默认 30m）。"记住我"要发一张长期 token，
 * 对一个能执行 DDL 的运维面板来说，那是把"共用电脑上忘记退出"的代价从 30 分钟放大到几周。
 */
export default function LoginView({ onSuccess }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [isPending, setPending] = useState(false);
  const [error, setError] = useState(null);

  const submit = useCallback(async () => {
    setPending(true);
    setError(null);
    try {
      await login(username, password);
      /* 口令不留在内存里：登录成功之后这个组件会被卸载，但 state 在卸载前仍然活着，
         而 React DevTools 能看到它。清掉的成本是一行。 */
      setPassword('');
      onSuccess();
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  }, [username, password, onSuccess]);

  /**
   * 错误文案按状态码分档。
   *
   * 403 在这里几乎只有一个原因（CSRF token 没下发），直接说出来，别让人去反复试口令；
   * 401 是口令不对，其余原样带上状态码——网络不通与服务端故障的下一步动作不同。
   */
  const message = !error
    ? null
    : error.status === 401
      ? '用户名或口令不正确。'
      : error.status === 403
        ? 'CSRF 校验失败（HTTP 403）。刷新本页重试；若仍失败，说明 XSRF-TOKEN cookie 没有下发。'
        : `登录失败：${error.message}`;

  const canSubmit = username.trim() !== '' && password !== '' && !isPending;

  return (
    <VStack height="fill" align="center" vAlign="center" padding={6}>
      <Card padding={6} width={380}>
        <VStack gap={5}>
          <VStack gap={1}>
            <Heading level={2}>Database MCP Server</Heading>
            <Text type="supporting" color="secondary">
              运维面板需要登录。凭据与 /mcp 用的是同一套调用者身份。
            </Text>
          </VStack>

          {message && (
            <Banner status="error" title="登录未通过" description={message} container="card" />
          )}

          <TextInput
            label="用户名"
            value={username}
            onChange={setUsername}
            isRequired
            hasAutoFocus
            autoComplete="username"
            /* Enter 直接提交：登录表单上按回车是肌肉记忆，不给的话会被当成页面卡住 */
            onEnter={() => { if (canSubmit) { submit(); } }}
          />
          <TextInput
            label="口令"
            type="password"
            value={password}
            onChange={setPassword}
            isRequired
            autoComplete="current-password"
            onEnter={() => { if (canSubmit) { submit(); } }}
          />

          <Button
            label="登录"
            variant="primary"
            isLoading={isPending}
            isDisabled={!canSubmit}
            onClick={submit}
          />

          <Text type="supporting" color="secondary">
            口令以原文提交、服务端哈希比对，所以这一页必须跑在 HTTPS 上。
          </Text>
        </VStack>
      </Card>
    </VStack>
  );
}
