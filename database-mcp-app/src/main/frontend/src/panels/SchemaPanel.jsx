import {
  Banner,
  Button,
  HStack,
  SegmentedControl,
  SegmentedControlItem,
  Text,
  TextInput,
  VStack,
  pixel,
} from '@astryxdesign/core';
import { useCallback, useMemo, useState } from 'react';
import {
  fetchDatabaseInfo,
  fetchSchemaIndexes,
  fetchSchemaSchemas,
  fetchSchemaSearch,
  fetchSchemaSequences,
  fetchSchemaTable,
  fetchSchemaTables,
  fetchSchemaViews,
} from '../api.js';
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
  textColumn,
} from '../components.jsx';

/**
 * Schema 浏览。来源 GET /api/ui/schema/*（8 个端点）。
 *
 * 这一页和 DbaPanel 是同一类页面，形状也照它抄：<b>默认什么都不查</b>，填好参数点「查询」才发请求。
 * 理由不是性能，是这一组端点全都会真的读业务库的数据字典：连接名不存在、连不上、账号没有
 * 元数据权限、方言不提供对应查询，全部是 HTTP 500 + 消息。让一个页面在用户只是切了个 tab 的
 * 时候就去打业务库，是这一组端点唯一不可接受的用法。
 *
 * ── 为什么 database-info 单独一个按钮而不是跟着「查询」一起发 ──
 * 它答的是「我到底连的是什么」，一次连接只需要问一次；跟着每次查询一起发等于把往返翻倍。
 * 更重要的是它答的问题和别的模式无关：方言配错时它是<b>唯一</b>能指出原因的一项
 * （/api/ui/connections 报的是注册时声明的方言，这里报的是对端自述的产品与版本），
 * 所以它值得一个自己的入口，而不是藏在某次表清单查询的副作用里。
 *
 * ── 结果表的列为什么是动态推导的 ──
 * listTables / listViews / listIndexes / listSequences 底下是 `jdbcTemplate.queryForList(方言 SQL)`，
 * 列名<b>完全由方言的那条 SQL 决定</b>（Oracle 给 TABLE_NAME / NUM_ROWS，PostgreSQL 给别的）。
 * 预定义列的话每支持一种方言都要来这里补一份映射，而漏补的表现是「后端返回了数据但页面上
 * 那一列不见了」——最难发现的一种坏法。和 DbaPanel 里那段是同一个判断。
 */

/**
 * 模式清单。value 同时是 SegmentedControl 的取值与下面 switch 的分支键，只有一份。
 *
 * needs 声明这个模式的必填参数，用来决定「查询」按钮能不能点。写成数据而不是 if 链，
 * 是因为「哪个模式要哪个参数」这件事在后端是 requireParam 的一行，前端多一处 if 就多一处会漂移的抄写。
 */
const MODES = [
  { value: 'schemas', label: 'Schema', needs: [], endpoint: 'GET /api/ui/schema/schemas' },
  { value: 'tables', label: '表', needs: [], endpoint: 'GET /api/ui/schema/tables' },
  { value: 'views', label: '视图', needs: [], endpoint: 'GET /api/ui/schema/views' },
  { value: 'sequences', label: '序列', needs: [], endpoint: 'GET /api/ui/schema/sequences' },
  { value: 'indexes', label: '索引', needs: ['table'], endpoint: 'GET /api/ui/schema/indexes' },
  { value: 'search', label: '搜表', needs: ['keyword'], endpoint: 'GET /api/ui/schema/search' },
  { value: 'table', label: '表结构', needs: ['table'], endpoint: 'GET /api/ui/schema/table' },
];

/** 每个模式在返回体里装数据的键。schemas 是字符串数组，别的都是对象数组。 */
const RESULT_KEY = {
  schemas: 'schemas',
  tables: 'tables',
  views: 'views',
  sequences: 'sequences',
  indexes: 'indexes',
  search: 'tables',
};

/**
 * 「表名」在这一行里叫什么。
 *
 * 用来给表清单加一列「查看结构」按钮——点一下把表名填进去、切到表结构模式。
 * 列名由方言 SQL 决定（TABLE_NAME / table_name / NAME 都见过），所以这里按一组候选做
 * 大小写不敏感的匹配，<b>找不到就不加那一列</b>，而不是猜一个键然后发一次 table=undefined 的请求。
 * 猜错的代价是一条「table is required」的 400，而用户明明点了按钮。
 */
const TABLE_NAME_CANDIDATES = ['table_name', 'tablename', 'name', 'table', 'view_name', 'object_name'];

function findTableNameKey(row) {
  if (!row) {
    return null;
  }
  const keys = Object.keys(row);
  for (const candidate of TABLE_NAME_CANDIDATES) {
    const hit = keys.find((k) => k.toLowerCase() === candidate);
    if (hit) {
      return hit;
    }
  }
  return null;
}

function loadFor(mode, params) {
  switch (mode) {
    case 'schemas':
      return fetchSchemaSchemas(params);
    case 'tables':
      return fetchSchemaTables(params);
    case 'views':
      return fetchSchemaViews(params);
    case 'sequences':
      return fetchSchemaSequences(params);
    case 'indexes':
      return fetchSchemaIndexes(params);
    case 'search':
      return fetchSchemaSearch(params);
    case 'table':
      return fetchSchemaTable(params);
    default:
      // 走不到：mode 只能来自 MODES。抛出来比静默返回空 Promise 好——后者会显示成一张空表。
      throw new Error(`未知的模式：${mode}`);
  }
}

/**
 * describeTable 未命中时的说明。
 *
 * 这是整组端点里唯一一个「200 但其实没查到」的形状：{error, table, schema, schemaSource, hint}。
 * 它<b>不是</b> HTTP 错误，所以 ErrorNotice 看不见它；不专门处理的话页面会显示一张
 * 「0 列」的表，和「这张表真的没有列」长得一样。
 *
 * hint 必须原文显示：它说的是「表可能在别的 schema 下，要显式传 schema」，而这正是
 * 最常见的原因（只读账号的登录 Schema 往往不是业务 Schema）。schemaSource 一起显示，
 * 它回答「这次实际搜的是我给的 schema 还是方言默认的那个」——少了这一条，
 * 「表不存在」和「该传 schema」在界面上没有区别。
 */
function DescribeMiss({ result }) {
  return (
    <Banner
      status="warning"
      title={`没有找到表 ${displayValue(result.table)}`}
      description={
        `后端返回 200 而不是错误：这不是一次失败的请求，是「在搜过的 schema 里没有这张表」。\n`
        + `error：${displayValue(result.error)}\n`
        + `本次实际搜索的 schema：${displayValue(result.schema)}`
        + `（schemaSource=${displayValue(result.schemaSource)}，`
        + `caller 表示用了你传的值，dialect-default 表示用了方言解析出的登录 Schema）\n`
        + `后端给的提示：${displayValue(result.hint)}`
      }
      container="card"
    />
  );
}

export default function SchemaPanel({ refreshToken }) {
  const [connection, setConnection] = useState('');
  const [schema, setSchema] = useState('');
  const [table, setTable] = useState('');
  const [keyword, setKeyword] = useState('');
  const [mode, setMode] = useState('tables');

  /*
   * 已提交的查询。null = 还没点过「查询」，也就是 usePanelData 的 enabled=false。
   *
   * 为什么不直接把输入框的值当 deps：那样每敲一个字符都会发一次请求，而这一组请求每次都落到
   * 业务库上。把「用户输入」和「已提交的查询」分成两份状态，是这一页唯一能同时做到
   * 「表单可编辑」和「不误触发查询」的办法。和 DbaPanel 完全一致。
   */
  const [submitted, setSubmitted] = useState(null);
  /** 库信息是独立的一次提交，理由见文件头。 */
  const [infoConnection, setInfoConnection] = useState(null);

  const result = usePanelData(
    () => loadFor(submitted.mode, submitted.params),
    [JSON.stringify(submitted), refreshToken],
    { enabled: submitted !== null },
  );

  const info = usePanelData(
    () => fetchDatabaseInfo({ connection: infoConnection }),
    [infoConnection, refreshToken],
    { enabled: infoConnection !== null },
  );

  const currentMode = MODES.find((m) => m.value === mode) ?? MODES[0];
  const needsTable = currentMode.needs.includes('table');
  const needsKeyword = currentMode.needs.includes('keyword');
  const missingTable = needsTable && table.trim() === '';
  const missingKeyword = needsKeyword && keyword.trim() === '';
  const canSubmit = !missingTable && !missingKeyword;

  const submit = useCallback((nextMode, overrides = {}) => {
    setSubmitted({
      mode: nextMode,
      params: {
        connection: connection.trim(),
        schema: schema.trim(),
        table: table.trim(),
        keyword: keyword.trim(),
        ...overrides,
      },
    });
  }, [connection, schema, table, keyword]);

  /** 「查看结构」：把这一行的表名填进输入框，切到表结构模式，并且立刻提交。 */
  const describe = useCallback((tableName) => {
    setTable(tableName);
    setMode('table');
    submit('table', { table: tableName });
  }, [submit]);

  const submittedMode = submitted?.mode ?? null;
  const data = result.data;

  /* 表结构模式的两种形状：命中 {columns, columnCount}，未命中 {error, hint, …}。 */
  const isDescribe = submittedMode === 'table';
  const describeMissed = isDescribe && data != null && data.error !== undefined;
  const describeColumns = isDescribe && !describeMissed && Array.isArray(data?.columns)
    ? data.columns
    : [];

  /* 表结构那张表的列定义。键名同样由方言决定，理由见文件末尾那段说明。 */
  const describeKeySignature = describeColumns.length > 0
    ? Object.keys(describeColumns[0]).join('\u0000')
    : '';
  const describeTableColumns = useMemo(() => {
    const keys = describeKeySignature === '' ? [] : describeKeySignature.split('\u0000');
    return keys.map((key) => textColumn(key, key, { flex: 1 }));
  }, [describeKeySignature]);

  /*
   * 列表模式的行。schemas 给的是裸字符串数组，别的给对象数组——统一成对象数组，
   * 否则下面推导列名的那段会对字符串做 Object.keys（得到 '0','1','2' 这种下标键）。
   */
  const rows = useMemo(() => {
    if (submittedMode === null || isDescribe || data == null) {
      return [];
    }
    const raw = data[RESULT_KEY[submittedMode]];
    if (!Array.isArray(raw)) {
      return [];
    }
    return submittedMode === 'schemas'
      ? raw.map((name) => ({ schema: name }))
      : raw;
  }, [data, submittedMode, isDescribe]);

  /*
   * ── 动态列 + 动态搜索字段，以及为什么这里可以违反「必须是模块级常量」那条规矩 ──
   * InteractiveTable 的文档要求 columns / searchFields 由调用方以模块级常量传入，因为它们
   * 进了内部一串 memo 的依赖。这里做不到（列名由方言决定），但把它们各自包一层 useMemo、
   * 依赖只有「键名列表拼成的字符串」之后，效果是等价的：同一份数据形状下引用不变，
   * 只在真的换了方言 / 换了模式时重算一次。
   *
   * 依赖刻意用 keySignature 这个字符串而不是 rows：rows 每次刷新都是新数组（哪怕内容一样），
   * 用它当依赖等于每次刷新都重建全部过滤与排序。
   */
  const firstRow = rows.length > 0 ? rows[0] : null;
  const keySignature = firstRow ? Object.keys(firstRow).join('\u0000') : '';
  const tableNameKey = useMemo(() => findTableNameKey(firstRow), [keySignature]); // eslint-disable-line react-hooks/exhaustive-deps

  /** 表清单 / 搜表结果才给「查看结构」列：索引和序列没有可跳的目标。 */
  const canDescribeFromRow = (submittedMode === 'tables' || submittedMode === 'search')
    && tableNameKey !== null;

  const listColumns = useMemo(() => {
    const keys = keySignature === '' ? [] : keySignature.split('\u0000');
    const cols = keys.map((key) => textColumn(key, key, { flex: 1, filter: key }));
    if (canDescribeFromRow) {
      cols.push({
        key: '_describe',
        header: '结构',
        align: 'center',
        sortable: false,
        // csv:false —— 导出的 CSV 里一个按钮没有意义，见 toCsv 的列过滤。
        csv: false,
        width: pixel(108),
        renderCell: (item) => (
          <Button
            label="查看结构"
            variant="ghost"
            onClick={() => describe(String(item[tableNameKey]))}
          />
        ),
      });
    }
    return cols;
  }, [keySignature, canDescribeFromRow, tableNameKey, describe]);

  const searchFields = useMemo(() => {
    const keys = keySignature === '' ? [] : keySignature.split('\u0000');
    return [SEARCH_ALL_FIELD, ...keys.map((key) => ({ key, type: 'string', label: key }))];
  }, [keySignature]);

  /*
   * 行标识用「整行内容」而不是某一个列：表清单里没有保证唯一的键（同名表可能出现在多个
   * schema 下），而下标做不了 key——排一次序详情面板就会指向另一行（见 contentRowKey 的说明）。
   */
  const rowKey = useMemo(() => {
    const keys = keySignature === '' ? [] : keySignature.split('\u0000');
    return keys.length > 0 ? contentRowKey(keys) : undefined;
  }, [keySignature]);

  /** 结果区回显「这份结果是哪次查询产生的」：切了模式但没重新点查询时，别让人以为看的是新模式的数据。 */
  const submittedText = submitted
    ? [
      `mode=${submitted.mode}`,
      submitted.params.connection ? `connection=${submitted.params.connection}` : 'connection=（默认）',
      submitted.params.schema ? `schema=${submitted.params.schema}` : 'schema=（方言默认）',
      submitted.mode === 'search' ? `keyword=${submitted.params.keyword}` : null,
      MODES.find((m) => m.value === submitted.mode)?.needs.includes('table')
        ? `table=${submitted.params.table}`
        : null,
    ].filter(Boolean).join(' · ')
    : null;

  return (
    <VStack gap={6}>
      <Banner
        status="warning"
        title="这一页的每次查询都会真的读业务库的数据字典"
        description={
          '连接名不存在、连不上、账号没有元数据权限、方言不提供对应查询，都会返回 HTTP 500 + 消息，'
          + '这是预期行为而不是故障。所以这一页默认不查任何东西，填好参数再点「查询」。'
          + 'schema 留空时由方言解析当前 Schema（Oracle 登录用户 / MySQL 当前 database / '
          + 'PostgreSQL current_schema()）——只读账号的登录 Schema 常常不是业务 Schema，'
          + '查不到东西时先怀疑这一条。'
        }
        container="card"
      />

      <Section
        title="连接对端是什么"
        source={
          'GET /api/ui/schema/database-info · 报的是对端自述的产品与版本；'
          + '和「连接与连接池」页显示的注册时声明的方言不一致，就是方言配错了'
        }
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="connection"
              value={connection}
              onChange={setConnection}
              placeholder="留空用默认连接"
              description="连接名见「连接与连接池」页"
              width={240}
            />
            <Button
              label="读取库信息"
              variant="secondary"
              isLoading={info.isLoading}
              onClick={() => setInfoConnection(connection.trim())}
            />
          </HStack>
          <ErrorNotice error={info.error} />
          {info.data
            ? (
              <KpiGrid
                items={[
                  { label: '产品', value: info.data.databaseProductName },
                  { label: '产品版本', value: info.data.databaseProductVersion },
                  { label: '驱动', value: info.data.driverName },
                  { label: '驱动版本', value: info.data.driverVersion },
                  { label: 'URL', value: info.data.url },
                  { label: '登录用户', value: info.data.userName },
                ]}
              />
            )
            : (
              <Text type="supporting" color="secondary">
                还没读过库信息，这一格目前没有向任何库发过请求。
              </Text>
            )}
        </VStack>
      </Section>

      <Section
        title="查询条件"
        source={`${currentMode.endpoint} · 模式决定要填哪些参数，不需要的输入框不显示`}
      >
        <VStack gap={4}>
          <SegmentedControl value={mode} onChange={setMode} label="浏览什么">
            {MODES.map((m) => (
              <SegmentedControlItem key={m.value} value={m.value} label={m.label} />
            ))}
          </SegmentedControl>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="connection"
              value={connection}
              onChange={setConnection}
              placeholder="留空用默认连接"
              width={220}
            />
            {/*
              schema 在 schemas 模式下不显示：那个端点列的就是「有哪些 schema」，
              传一个 schema 进去没有意义（后端也不读这个参数）。常驻置灰会让人以为
              「这个模式也能按 schema 过滤，只是不让我填」。
            */}
            {mode !== 'schemas' && (
              <TextInput
                label="schema"
                value={schema}
                onChange={setSchema}
                placeholder="留空由方言解析当前 Schema"
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
                  ? { type: 'error', message: '这个模式必须带 table，缺了后端直接 400' }
                  : undefined}
                width={220}
              />
            )}
            {needsKeyword && (
              <TextInput
                label="keyword"
                value={keyword}
                onChange={setKeyword}
                isRequired
                placeholder="表名片段，两侧自动加通配符"
                description="后端不接受空关键词——空关键词等于一次误触就拉全库表名"
                status={missingKeyword
                  ? { type: 'error', message: 'keyword 必填，缺了后端直接 400' }
                  : undefined}
                width={260}
              />
            )}
            <Button
              label="查询"
              variant="primary"
              isDisabled={!canSubmit}
              isLoading={result.isLoading}
              onClick={() => submit(mode)}
            />
          </HStack>
          {submitted === null && (
            <Text type="supporting" color="secondary">
              还没点过「查询」，这一页目前没有向任何库发过请求。
            </Text>
          )}
        </VStack>
      </Section>

      {/* 查询本身的错误单独一条：状态码 + 响应体原文全留在页面上，不美化不截断。 */}
      <ErrorNotice error={result.error} />

      {isDescribe && describeMissed && <DescribeMiss result={data} />}

      {isDescribe && !describeMissed && (
        <Section
          title={`表结构 ${displayValue(data?.table)}`}
          source={`GET /api/ui/schema/table · ${submittedText} · 后端报的 schema=${displayValue(data?.schema)}`}
          count={data ? describeColumns.length : undefined}
        >
          <DataTable
            columns={describeTableColumns}
            rows={describeColumns}
            emptyTitle={result.error ? '这次查询失败了' : '这张表返回了 0 列'}
            emptyDescription={
              result.error
                ? '错误原文见上方。'
                : '请求成功、也命中了表，但 columns 是空的——这种情况值得去看一眼后端。'
            }
          />
        </Section>
      )}

      {submittedMode !== null && !isDescribe && (
        rows.length === 0
          ? (
            <Section
              title="查询结果"
              source={`${MODES.find((m) => m.value === submittedMode)?.endpoint} · ${submittedText}`}
              count={data ? 0 : undefined}
            >
              <DataTable
                columns={[]}
                rows={[]}
                emptyTitle={result.error ? '这次查询失败了' : '返回了 0 行'}
                emptyDescription={
                  result.error
                    ? '错误原文见上方，方言不支持与权限不足都是这个形状。'
                    : '请求成功但没有数据。schema 留空时搜的是方言解析出的当前 Schema，'
                      + '只读账号的登录 Schema 常常不是业务 Schema——先试试显式填一个 schema。'
                }
              />
            </Section>
          )
          : (
            <InteractiveTable
              title="查询结果"
              source={
                `${MODES.find((m) => m.value === submittedMode)?.endpoint} · ${submittedText}`
                + ' · 列名由方言的那条元数据 SQL 决定，因此是动态推导的'
              }
              rows={rows}
              columns={listColumns}
              searchFields={searchFields}
              searchName={`schema-${submittedMode}`}
              getRowKey={rowKey}
              detailTitle="这一行的全部字段"
              csvBaseName={`schema-${submittedMode}`}
              emptyTitle="返回了 0 行"
              emptyDescription="请求成功但没有数据。"
            />
          )
      )}
    </VStack>
  );
}

/*
 * ── 表结构的列名也是动态的，不要写死 ──
 * 一开始这里写了一份固定的 {name, type, nullable, comment}，以为 describeTable 返回的是
 * 归一化过的结构。读了 DatabaseReadRepository.describeTable 之后删掉了：它的 columns 同样是
 * `jdbcTemplate.queryForList(dialect.columnsQuery(...))` 的原始结果，键名由方言那条 SQL 决定
 * （Oracle 给 COLUMN_NAME / DATA_TYPE / NULLABLE，别的方言给别的）。
 * 写死的那一版在 Oracle 上会显示四列全是「—」——请求成功、数据也在，但页面上什么都看不见。
 * 所以和列表模式共用同一套「从第一行的键名推导」的做法。
 */
