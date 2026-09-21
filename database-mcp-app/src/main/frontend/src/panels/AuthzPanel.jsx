import {
  Badge,
  Banner,
  HStack,
  Text,
  VStack,
  pixel,
  proportional,
} from '@astryxdesign/core';
import { useMemo } from 'react';
import { fetchAuthz, fetchConnections } from '../api.js';
import { usePanelData } from '../usePanelData.js';
import {
  ErrorNotice,
  InteractiveTable,
  KpiGrid,
  SEARCH_ALL_FIELD,
  Section,
  textColumn,
} from '../components.jsx';

/**
 * 按调用者的连接级/表级授权。来源 GET /api/authz（AuthzViewController），只读。
 *
 * ── 这一页回答的问题 ──
 * "某个身份能读写哪些连接、哪些表"。它的数据是 entropy.mcp.authz.grants 这份配置按角色展开后的
 * 结果，<b>不是</b>问判定引擎得到的——facet 的 schema 没声明 listable，答不了反向枚举。
 * 好在规则里只有直接授权与角色包含、没有继承或递归，所以配置本身就是完整答案。
 *
 * ── 这一页最容易被读错的一件事 ──
 * <b>总开关关着（enabled=false）时，这张表和实际权限没有关系</b>：判定第一行就返回，等于全放行。
 * 一张空的 grants 表在开关关着时意味着"所有人都能读写一切"，而不是"谁都没有权限"——两个结论
 * 正好相反，所以那条横幅是 error 而不是 info，并且排在最上面。
 *
 * ── 另外两条这一页答不了的事（都有常驻文案，别删）──
 * 1. 连接自身的 readonly 标记是另一道闸，而且在授权<b>之前</b>执行
 *    （RoutingDatabaseFacade.rejectIfReadonly）。所以这里的"读写"只是授权允许，不代表连接允许；
 * 2. 只读元数据（listTables / describeTable / listIndexes）根本不过授权判定。表结构对任何能调
 *    /mcp 的身份都可见，和这张表无关。
 *
 * ── 为什么顺带拉一次 /api/ui/connections ──
 * grant 里的 connection 必须是<b>规范名且大小写敏感</b>（别名不生效），写错的症状是一次静默的拒绝：
 * 配置合法、启动正常、那个人访问时被拒，而没有任何报错指向那一行。把 grant 的连接名与已注册的
 * 连接对一遍，能在页面上直接标出"这个名字当前没有对应的连接"。<b>但它不是错误判定</b>：
 * BYOK 连接是运行期用 createNamedConnection 注册的，一个还没建起来的连接名完全可能是对的。
 */

const ABILITY_READ_WRITE = '读写';
const ABILITY_READ_ONLY = '只读';
/** 角色的读写都是 false 时。当前三个角色都能读，所以这一档只会在后端加了新角色后出现。 */
const ABILITY_NONE = '无';

const ABILITY_ENUM_VALUES = [
  { value: ABILITY_READ_WRITE, label: ABILITY_READ_WRITE },
  { value: ABILITY_READ_ONLY, label: ABILITY_READ_ONLY },
  { value: ABILITY_NONE, label: ABILITY_NONE },
];

const SCOPE_CONNECTION = '整条连接';
const SCOPE_TABLE = '单张表';

const SCOPE_ENUM_VALUES = [
  { value: SCOPE_CONNECTION, label: SCOPE_CONNECTION },
  { value: SCOPE_TABLE, label: SCOPE_TABLE },
];

/** 连接级 grant 在「授权对象」列与 CSV 里显示的字面量。 */
const OBJECT_WHOLE_CONNECTION = '（该连接上的全部对象）';

const CONNECTION_REGISTERED = '已注册';
const CONNECTION_MISSING = '未注册';
/** 连接清单没拉到时用这一档。不能默认填"已注册"——那是在说一件我们并不知道的事。 */
const CONNECTION_UNKNOWN = '未知';

const CONNECTION_STATE_ENUM_VALUES = [
  { value: CONNECTION_REGISTERED, label: CONNECTION_REGISTERED },
  { value: CONNECTION_MISSING, label: CONNECTION_MISSING },
  { value: CONNECTION_UNKNOWN, label: CONNECTION_UNKNOWN },
];

const CONNECTION_STATE_BADGE_VARIANT = {
  [CONNECTION_REGISTERED]: 'success',
  [CONNECTION_MISSING]: 'warning',
  [CONNECTION_UNKNOWN]: 'neutral',
};

const SUBJECT_COLUMN = {
  key: 'subject',
  header: '主体',
  sortable: true,
  filter: 'subject',
  width: proportional(2),
  renderCell: (item) => (
    <Text type="supporting" weight="semibold">{item.subject}</Text>
  ),
};

/**
 * 授权对象列：连接级 grant 显示成一句话，表级显示表名。
 *
 * 值取派生字段 object 而不是原始的 table，为的是让表格、排序与 CSV 三处口径一致：
 * toCsv 是按列的 key 取 row[key] 的，如果这一列只在 renderCell 里说"整条连接"，
 * 导出的 CSV 在那些行上就是空单元格——"覆盖整条连接"和"表名没填"在文件里无法区分，
 * 而前者恰恰是<b>更宽</b>的那一种。
 */
const OBJECT_COLUMN = {
  key: 'object',
  header: '授权对象',
  sortable: true,
  filter: 'object',
  width: proportional(2),
  renderCell: (item) => (
    item.table
      ? <Text type="supporting">{item.object}</Text>
      : <Text type="supporting" color="secondary">{item.object}</Text>
  ),
};

/** 能力列。neutral / success 两档，刻意不用 booleanColumn——"没有写权限"不是 error。 */
const ABILITY_COLUMN = {
  key: 'ability',
  header: '能力',
  align: 'center',
  sortable: true,
  filter: 'ability',
  width: pixel(96),
  renderCell: (item) => (
    <Badge
      variant={item.ability === ABILITY_READ_WRITE ? 'warning' : 'neutral'}
      label={item.ability}
    />
  ),
};

/**
 * 连接名是否对得上已注册的连接。
 *
 * 三档，而不是两档：连接清单没拉到时是「未知」。默认填"已注册"等于在说一件我们并不知道的事，
 * 而「未注册」也不是错误判定——BYOK 连接运行期才注册，对不上不等于配错了（详见文件头注释），
 * 所以那一档是 warning 而不是 error。
 */
const CONNECTION_STATE_COLUMN = {
  key: 'connectionState',
  header: '连接',
  align: 'center',
  sortable: true,
  filter: 'connectionState',
  width: pixel(96),
  renderCell: (item) => (
    <Badge
      variant={CONNECTION_STATE_BADGE_VARIANT[item.connectionState] ?? 'neutral'}
      label={item.connectionState}
    />
  ),
};

/*
 * 搜索字段与列都是模块级常量。
 *
 * 角色那一项刻意是 string（contains）而不是 enum：enum 需要在模块顶层写死取值，而角色清单
 * 来自后端的 roles 图例、会随新角色增加。这与 ToolsPanel 里「分组用 string、状态用 enum」
 * 是同一条判据——取值由后端决定的就别做成 enum，否则新角色在这里筛不到。
 * 反过来，能力 / 粒度 / 连接这三项的取值就是本文件顶上那几个常量，写死是准确的。
 */
const SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'subject', type: 'string', label: '主体' },
  { key: 'subjectType', type: 'string', label: '主体类型' },
  { key: 'connection', type: 'string', label: '连接' },
  { key: 'object', type: 'string', label: '授权对象' },
  { key: 'role', type: 'string', label: '角色' },
  { key: 'ability', type: 'enum', label: '能力', enumValues: ABILITY_ENUM_VALUES },
  { key: 'scopeLabel', type: 'enum', label: '粒度', enumValues: SCOPE_ENUM_VALUES },
  {
    key: 'connectionState',
    type: 'enum',
    label: '连接',
    enumValues: CONNECTION_STATE_ENUM_VALUES,
  },
];

const COLUMNS = [
  SUBJECT_COLUMN,
  textColumn('subjectType', '主体类型', { flex: 1, filter: 'subjectType' }),
  textColumn('connection', '连接', { flex: 1, filter: 'connection' }),
  textColumn('scopeLabel', '粒度', { flex: 1, filter: 'scopeLabel' }),
  OBJECT_COLUMN,
  textColumn('role', '角色', { flex: 1, filter: 'role' }),
  ABILITY_COLUMN,
  CONNECTION_STATE_COLUMN,
];

const DEFAULT_SORT = [
  { sortKey: 'subject', direction: 'ascending' },
  { sortKey: 'connection', direction: 'ascending' },
];

/** 一条 grant 的唯一键：同一个主体在同一条连接的同一张表上可以有多条不同角色的 grant。 */
const ROW_KEY = (row) => `${row.subject}|${row.connection}|${row.table ?? ''}|${row.role}`;

const TABLE_NAME = 'authz-grants';

export default function AuthzPanel({ refreshToken }) {
  /* 两个端点一起拉：都不连业务库，所以可以挂载即拉。
     连接清单拉失败不该让这一页空掉——它只用来标注连接名对不对得上，所以 catch 成 null。 */
  const { data, error } = usePanelData(
    () => Promise.all([fetchAuthz(), fetchConnections().catch(() => null)])
      .then(([authz, connections]) => ({ authz, connections })),
    [refreshToken],
  );

  const authz = data?.authz;
  const isEnabled = authz?.enabled === true;

  /** 已注册的连接名。拉不到时是 null，此时不做"未注册"的标注（宁可不说，也不说错）。 */
  const registeredConnections = useMemo(() => {
    const list = data?.connections?.registered?.connections;
    if (!Array.isArray(list)) {
      return null;
    }
    return new Set(list.map((row) => row.key).filter((key) => typeof key === 'string'));
  }, [data]);

  const rows = useMemo(() => {
    const grants = Array.isArray(authz?.grants) ? authz.grants : [];
    return grants.map((grant) => ({
      ...grant,
      /* 派生成字符串：过滤引擎的 enum 只认字符串，排序拿 true/false 排毫无意义，
         CSV 里"读写"也比 "true,true" 可读（同 ToolsPanel 的 status）。 */
      ability: grant.canWrite ? ABILITY_READ_WRITE : grant.canRead ? ABILITY_READ_ONLY : ABILITY_NONE,
      /* 后端的 scope 是 connection/table，这里另起一个 scopeLabel 而不是把它覆盖掉：
         同一个键有两套取值，下一个人读 API 文档和读这段代码会得到不同的答案。 */
      scopeLabel: grant.table ? SCOPE_TABLE : SCOPE_CONNECTION,
      object: grant.table ?? OBJECT_WHOLE_CONNECTION,
      connectionState: registeredConnections === null
        ? CONNECTION_UNKNOWN
        : registeredConnections.has(grant.connection) ? CONNECTION_REGISTERED : CONNECTION_MISSING,
    }));
  }, [authz, registeredConnections]);

  /** 连接名对不上的那些。用来出一条汇总横幅——逐行看 Badge 不如一次把名字列出来。 */
  const unmatchedConnections = useMemo(() => [...new Set(rows
    .filter((row) => row.connectionState === CONNECTION_MISSING)
    .map((row) => row.connection))].sort((a, b) => a.localeCompare(b)), [rows]);

  return (
    <VStack gap={6}>
      <ErrorNotice error={error} />

      {/*
        开关状态必须是这一页最上面的一句话，而且两个方向都要说。
        关着的时候是 error：此时"全放行"是真实行为，而页面上那张表看起来像是一份限制清单。
      */}
      {authz && !isEnabled && (
        <Banner
          status="error"
          title="按调用者的授权没有开启：下面那张表不反映实际权限"
          description={
            'entropy.mcp.authz.enabled=false，ConnectionAuthorizer 的判定第一行就返回——'
            + '任何能通过认证的调用者对任何连接、任何表都能读写。'
            + '此时下面的 grants 只是一份"开启之后会生效"的配置预览：'
            + '它为空意味着"开启后谁都访问不了"（fail-closed），而不是"现在谁都访问不了"。'
            + '打开这个开关是破坏性动作，开之前请先确认这张表已经覆盖了所有在用的调用者。'
          }
          container="card"
        />
      )}

      {authz && isEnabled && (
        <Banner
          status="info"
          title="按调用者的授权已开启，fail-closed"
          description={
            '没有任何 grant 命中的组合一律拒绝。下面这张表就是全部授权数据——'
            + '它由 entropy.mcp.authz.grants 展开而来，改它只能改配置并重启（本页没有写操作，'
            + '这是刻意的：策略进版本控制才能走 code review、事后查得到是哪个提交放开的）。'
          }
          container="card"
        />
      )}

      {unmatchedConnections.length > 0 && (
        <Banner
          status="warning"
          title={`有 ${unmatchedConnections.length} 个 grant 里的连接名当前没有对应的已注册连接`}
          description={
            `${unmatchedConnections.join('、')}。`
            + 'grant 的 connection 必须是规范名、大小写敏感，别名不生效——写错的症状是一次'
            + '静默的拒绝（配置合法、启动正常、那个人访问时被拒），所以这里替你对了一遍。'
            + '但这不一定是错的：BYOK 连接是运行期用 createNamedConnection 注册的，'
            + '一个还没建起来的连接名完全可能是对的。对照「连接与连接池」页确认。'
          }
          container="card"
        />
      )}

      {authz && registeredConnections === null && (
        <Banner
          status="info"
          title="连接清单没拉到，所以「连接」那一列是「未知」"
          description={
            '这一页顺带打一次 /api/ui/connections，用来对 grant 里的连接名是否真的存在。'
            + '那个请求失败时不做判定——把"不知道"显示成"已注册"，会让一个写错的连接名看起来是对的。'
            + '授权数据本身不受影响，它来自 /api/authz。'
          }
          container="card"
        />
      )}

      <Banner
        status="info"
        title="这张表答不了的两件事"
        description={
          '一、连接自身的 readonly 标记是另一道闸，而且在授权判定之前执行——这里的"读写"'
          + '只意味着授权允许，不意味着那条连接允许写；'
          + '二、只读元数据（listTables / describeTable / listIndexes）根本不过授权判定，'
          + '表结构对任何能调 /mcp 的身份都可见，与这张表无关。'
        }
        container="card"
      />

      <KpiGrid
        items={[
          {
            label: '授权条数',
            value: authz?.grantCount,
            hint: 'entropy.mcp.authz.grants 的条数；不含默认值，这份配置默认是空的',
          },
          {
            label: '涉及身份',
            value: authz?.subjectCount,
            hint: '按 类型:id 去重；与 /api/users 的身份是同一个值域，但两边不要求一致',
          },
          {
            label: '涉及连接',
            value: authz?.connectionCount,
            hint: '按连接名去重（大小写敏感）',
          },
          {
            label: '开关',
            value: authz ? (isEnabled ? '已开启' : '未开启') : undefined,
            status: authz ? (isEnabled ? 'success' : 'error') : undefined,
            statusLabel: isEnabled ? '判定生效' : '判定全放行',
            hint: isEnabled ? '没有 grant 命中即拒绝' : '关闭时任何调用者都能读写一切',
          },
        ]}
      />

      <Section
        title="角色能力"
        source="GET /api/authz 的 roles · 由后端 AuthzRole 生成，前端不写第二份"
      >
        <VStack gap={3}>
          <HStack gap={4} wrap="wrap">
            {(Array.isArray(authz?.roles) ? authz.roles : []).map((role) => (
              <HStack key={role.role} gap={2} align="center">
                <Badge variant="neutral" label={role.role} />
                <Text type="supporting" color="secondary">
                  {role.canWrite ? '可读可写' : role.canRead ? '只能读' : '读写都不可'}
                </Text>
              </HStack>
            ))}
          </HStack>
          <Text type="supporting" color="secondary">
            {'writer 自动能读、admin 两者都能，所以配置里不需要给同一个人写两条。'
             + '不带表名的 grant 覆盖该连接上的全部对象；带表名的 grant 只覆盖那一张表，'
             + '不附带任何连接级权限——"只能写 audit_log"写一条就够。'}
          </Text>
        </VStack>
      </Section>

      <InteractiveTable
        title="授权明细"
        source="GET /api/authz · 配置回显 + 角色展开，不连库、不问判定引擎"
        rows={rows}
        columns={COLUMNS}
        searchFields={SEARCH_FIELDS}
        searchName={TABLE_NAME}
        defaultSort={DEFAULT_SORT}
        getRowKey={ROW_KEY}
        detailTitle="授权详情"
        csvBaseName="mcp-authz-grants"
        emptyTitle="没有任何授权配置"
        emptyDescription={
          'entropy.mcp.authz.grants 是空的。开关关着时这没有影响（全放行）；'
          + '开关开着时意味着所有按调用者判定的读写路径都会被拒绝，只剩不判授权的元数据能用。'
        }
      />
    </VStack>
  );
}
