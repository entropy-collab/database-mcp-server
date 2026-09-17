import { Banner, Button, HStack, Selector, Text, TextInput, VStack } from '@astryxdesign/core';
import { useState } from 'react';
import { fetchDba, fetchDbaViews } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import { DataTable, ErrorNotice, Section, textColumn } from '../components.jsx';

/**
 * DBA 视图。来源 GET /api/ui/dba?view=...（清单来自 GET /api/ui/dba/views）。
 *
 * 这一页和其它七页有两个本质区别，页面的整个形状都是这两条推出来的：
 *
 * 1. 它会真的往业务库发 SQL（v$session、dba_data_files、v$undostat 这类数据字典与动态性能视图）。
 *    所以它<b>默认什么都不查</b>：进这一页只拉一次 view 清单（那是个常量列表，不连库），
 *    用户选了 view 再点「查询」才发请求。让一个页面在用户只是切了个 tab 的时候就去打业务库，
 *    是这一组端点唯一不可接受的用法——被查的那些库不是这个服务自己的。
 *
 * 2. <b>失败是常态。</b>绝大多数 view 只有 Oracle 实现，别的方言上方言层给不出 SQL，工具抛
 *    McpToolException → HTTP 500 + 消息；连接名不存在、连不上、账号没有数据字典权限同样是 500。
 *    后端刻意不 catch（压成 200 + 空 rows 会让页面永远显示「没有会话」，真实原因藏进服务端日志）。
 *    页面这边对应的义务就是把错误原文显示出来——ErrorNotice 已经是这个行为，不要再包一层
 *    「查询失败，请重试」，那句话把方言不支持和网络抖动说成了同一件事。
 */

/** 需要 schema 的 view。schema 是可选过滤，缺了不是错误（listInvalidObjects/estimateTableSize 都允许 null）。 */
const VIEWS_WITH_SCHEMA = ['invalid', 'tableSize'];

/** 需要 table 的 view。tableSize 缺 table 时后端 400，所以这里按必填处理，不让那次请求发出去。 */
const VIEWS_WITH_TABLE = ['tableSize'];

export default function DbaPanel({ refreshToken }) {
  const [view, setView] = useState('');
  const [connection, setConnection] = useState('');
  const [schema, setSchema] = useState('');
  const [table, setTable] = useState('');

  /*
   * 已提交的查询。null = 还没点过「查询」，也就是 usePanelData 的 enabled=false。
   *
   * 为什么不直接把 view/connection/... 当 deps：那样每敲一个字符都会发一次请求，而这一组
   * 请求每次都落到业务库上。把「用户输入」和「已提交的查询」分成两份状态，是这一页唯一
   * 能同时做到「表单可编辑」和「不误触发查询」的办法。
   */
  const [submitted, setSubmitted] = useState(null);

  // view 清单可以跟着挂载就拉：它读的是后端一个 List 常量，不连库，也不会被计入性能指标里的库操作。
  const viewsQuery = usePanelData(() => fetchDbaViews(), [refreshToken]);
  const views = Array.isArray(viewsQuery.data?.views) ? viewsQuery.data.views : [];

  /*
   * refreshToken 进 deps：顶栏「刷新」应该能重跑当前这次查询。
   * 但 enabled 仍然挂在 submitted 上——没提交过查询时，点刷新不会凭空打一次业务库。
   */
  const result = usePanelData(
    () => fetchDba(submitted),
    [JSON.stringify(submitted), refreshToken],
    { enabled: submitted !== null },
  );

  const needsSchema = VIEWS_WITH_SCHEMA.includes(view);
  const needsTable = VIEWS_WITH_TABLE.includes(view);
  const missingTable = needsTable && table.trim() === '';
  const canSubmit = view !== '' && !missingTable;

  const rows = Array.isArray(result.data?.rows) ? result.data.rows : [];

  /*
   * 列从第一行的 key 动态推导，和 PerformancePanel 里 metrics 那段是同一个理由：
   * rows 的字段随 view 变（health 给的是几个健康标志，sessions 给的是 sid/serial#/程序名，
   * datafiles 给的是文件路径与大小），预定义列的话每加一个 view 都要来这里补一份映射，
   * 而漏补的表现是「后端返回了数据但页面上那一列不见了」——最难发现的一种坏法。
   *
   * 只看第一行：Oracle 的这些视图每行字段一致，为「行与行字段不同」做并集会让列序变得不可预测。
   */
  const columns = rows.length > 0
    ? Object.keys(rows[0]).map((key) => textColumn(key, key, { flex: 1 }))
    : [];

  /** 结果区的说明里回显「这份结果是哪次查询产生的」：切了 view 但没重新点查询时，别让人以为看的是新 view 的数据。 */
  const submittedText = submitted
    ? [
      `view=${submitted.view}`,
      submitted.connection ? `connection=${submitted.connection}` : null,
      submitted.schema ? `schema=${submitted.schema}` : null,
      submitted.table ? `table=${submitted.table}` : null,
    ].filter(Boolean).join(' · ')
    : null;

  return (
    <VStack gap={6}>
      {/* 清单本身拉失败也要说：清单没了选择器就是空的，那不是「没有可用的 view」。 */}
      <ErrorNotice error={viewsQuery.error} />

      <Banner
        status="warning"
        title="这一组查询会真的连到业务库执行"
        description={
          '底下的 SQL 是数据字典与动态性能视图（v$session、dba_data_files、v$undostat 之类），'
          + '绝大多数只有 Oracle 实现：别的方言、连接名不存在、账号没有数据字典权限都会返回 '
          + 'HTTP 500 + 消息，这是预期行为而不是故障。所以这一页默认不查任何东西，'
          + '选好 view 再点「查询」。'
        }
        container="card"
      />

      <Section
        title="查询条件"
        source="view 清单来自 GET /api/ui/dba/views · 前端不写第二份清单，后端加的 view 这里自动出现"
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <Selector
              label="view"
              options={views}
              value={view}
              onChange={setView}
              placeholder={viewsQuery.error ? '清单加载失败' : '选择一个 view…'}
              isLoading={viewsQuery.isLoading}
              isDisabled={views.length === 0}
              width={220}
            />
            <TextInput
              label="connection"
              value={connection}
              onChange={setConnection}
              placeholder="留空用默认连接"
              description="连接名见「连接与连接池」页"
              width={220}
            />
            {/*
              schema / table 只在用得上的 view 下出现，而不是常驻置灰。
              常驻会让人以为「所有 view 都能按 schema 过滤，只是这个 view 不让我填」，
              而事实是后端只在 invalid / tableSize 两个分支读这两个参数，其余 view 传了也被丢掉。
            */}
            {needsSchema && (
              <TextInput
                label="schema"
                value={schema}
                onChange={setSchema}
                placeholder="留空不按 schema 过滤"
                width={220}
              />
            )}
            {needsTable && (
              <TextInput
                label="table"
                value={table}
                onChange={setTable}
                isRequired
                placeholder="表名"
                status={missingTable
                  ? { type: 'error', message: 'tableSize 必须带 table，否则后端 400' }
                  : undefined}
                width={220}
              />
            )}
            <Button
              label="查询"
              variant="primary"
              isDisabled={!canSubmit}
              isLoading={result.isLoading}
              onClick={() => setSubmitted({
                view,
                connection: connection.trim(),
                schema: schema.trim(),
                table: table.trim(),
              })}
            />
          </HStack>
          {view === '' && (
            <Text type="supporting" color="secondary">
              还没有选 view，这一页目前没有向任何库发过请求。
            </Text>
          )}
        </VStack>
      </Section>

      {/* 查询本身的错误单独一条：原文（状态码 + 响应体）全留在页面上，不美化不截断。 */}
      <ErrorNotice error={result.error} />

      {submitted !== null && (
        <Section
          title="查询结果"
          source={
            `GET /api/ui/dba · ${submittedText}`
            + ` · 方言 ${result.data?.dialect ?? '—'} · 列由第一行的字段名动态推导`
          }
          count={result.data ? rows.length : undefined}
        >
          <DataTable
            columns={columns}
            rows={rows}
            emptyTitle={result.error ? '这次查询失败了' : '这个视图返回了 0 行'}
            emptyDescription={
              result.error
                ? '错误原文见上方，方言不支持与权限不足都是这个形状。'
                : '请求成功但没有数据——比如当前没有活动会话、没有锁、没有失效对象。'
            }
          />
        </Section>
      )}
    </VStack>
  );
}
