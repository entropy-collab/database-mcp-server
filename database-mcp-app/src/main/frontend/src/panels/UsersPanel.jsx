import {
  AlertDialog,
  Badge,
  Banner,
  Button,
  HStack,
  Selector,
  Switch,
  Text,
  TextInput,
  VStack,
  pixel,
  proportional,
} from '@astryxdesign/core';
import { useCallback, useMemo, useState } from 'react';
import { createUser, disableUser, fetchUsers } from '../api.js';
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
 * 调用者身份：列出 / 新增 / 停用。来源 GET /api/users（UserAdminController）。
 *
 * ── 这一页和「工具清单」是本面板仅有的两个会改服务端状态的地方 ──
 * 而且它是<b>更危险的那一个</b>：工具开关最坏让 tools/list 变空（点回来即可恢复），
 * 这一页最坏是多发一个 ROLE_ADMIN 出去，而那件事<b>不可撤销</b>——被造出来的管理员和
 * 造它的人权限完全相同，停用原来那个身份也拿不回来。因此下面三条常驻文案不是装饰：
 * 1. 这组接口能造出与自己同权限的身份（后端类注释明写是有意的：否则"不重启加一个管理员"
 *    做不到），事后唯一能查到"谁被给了管理员"的地方是 JdbcUserStore.create 打的那条日志；
 * 2. 口令以<b>原文</b>提交、服务端哈希。所以这一页必须跑在 HTTPS 上，且请求体不能进访问日志；
 * 3. 管理员<b>不在</b>这份清单里（口令来自环境变量、不进库），列表为空不等于没有调用者。
 *
 * ── 没配状态库时整页是 503，而那是受支持的部署形态 ──
 * UserAdminService 挂在 spring.datasource.url 上。不配库时身份回落到凭据文件或只有管理员，
 * 三个端点都回 503 + 一条带可操作建议的消息。所以 503 走「功能未启用」的 info 横幅，
 * 不走 ErrorNotice——把一个部署选择显示成故障，会让人去查一个不存在的问题。
 *
 * ── 刻意没做的两件事 ──
 * - <b>改口令 / 改权限</b>：后端没有这两个端点（只有 list / create / disable）。这里不拿
 *   "先停用再新建同名"去凑一个假的编辑功能——用户名是主键，停用的行还在，同名 create 会 400；
 * - <b>启用回来</b>：DELETE 的反面不存在（没有 PUT /api/users/{name}）。停用之后只能改库或
 *   换一个用户名，页面必须在确认框里说清这一点，否则"先停了再点回来"会发现点不回来。
 */

/** 后端 Credentials.PRINCIPAL_TYPES 的镜像。多写一个值的症状是"能选、但点了 400"。 */
const PRINCIPAL_TYPES = ['user', 'agent', 'service'];

const DEFAULT_TYPE = 'user';

/**
 * 后端 JdbcUserStore.GRANTABLE_AUTHORITIES 的镜像，<b>必须写全 ROLE_ 前缀</b>。
 *
 * 只有两个值，所以用两个 Switch 而不是一个多选下拉：多选控件在两个选项上是纯负担，
 * 而 Switch 能把"默认一个都不给"这件事直接显示成两个关着的开关。
 * 后端不接受省略前缀的写法（admin），这里也就不做那层贴心转换——两种拼法并存等于
 * 让同一个权限有两个名字，而其中一个是不生效的那个。
 */
const ROLE_ADMIN = 'ROLE_ADMIN';
const ROLE_DBA = 'ROLE_DBA';

const STATUS_ENABLED = '启用';
const STATUS_DISABLED = '已停用';

/** 与 ToolsPanel 同一个理由：派生成字符串字段，enum 过滤/排序/CSV 才有意义。 */
const STATUS_ENUM_VALUES = [
  { value: STATUS_ENABLED, label: STATUS_ENABLED },
  { value: STATUS_DISABLED, label: STATUS_DISABLED },
];

const NAME_COLUMN = {
  key: 'username',
  header: '用户名',
  sortable: true,
  filter: 'username',
  width: proportional(2),
  renderCell: (item) => (
    <Text type="supporting" weight="semibold" color={item.enabled ? undefined : 'disabled'}>
      {item.username}
    </Text>
  ),
};

const STATUS_COLUMN = {
  key: 'status',
  header: '状态',
  align: 'center',
  sortable: true,
  filter: 'status',
  width: pixel(96),
  renderCell: (item) => (
    <Badge
      variant={item.status === STATUS_ENABLED ? 'success' : 'warning'}
      label={item.status}
    />
  ),
};

/**
 * 权限列。空集要显示成一句话而不是空白：「这个身份能过认证但什么都不能干」和
 * 「这一列没渲染出来」在一个空单元格上分不出来，而前者是最常见的正常状态。
 */
const ROLES_COLUMN = {
  key: 'roles',
  header: '权限',
  sortable: true,
  filter: 'roles',
  width: proportional(2),
  renderCell: (item) => (
    item.roles
      ? (
        <HStack gap={2} wrap="wrap">
          {item.roles.split(', ').map((role) => (
            <Badge key={role} variant={role === ROLE_ADMIN ? 'error' : 'neutral'} label={role} />
          ))}
        </HStack>
      )
      : <Text type="supporting" color="secondary">（无权限：只能过认证）</Text>
  ),
};

const BASE_COLUMNS = [
  NAME_COLUMN,
  STATUS_COLUMN,
  textColumn('type', '主体类型', { flex: 1, filter: 'type' }),
  ROLES_COLUMN,
];

const SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'username', type: 'string', label: '用户名' },
  { key: 'status', type: 'enum', label: '状态', enumValues: STATUS_ENUM_VALUES },
  { key: 'type', type: 'string', label: '主体类型' },
  { key: 'roles', type: 'string', label: '权限' },
];

const DEFAULT_SORT = [{ sortKey: 'username', direction: 'ascending' }];

/** 用户名是 mcp_user 的主键，直接用。 */
const ROW_KEY = (row) => String(row.username);

const TABLE_NAME = 'user-admin';

const ALREADY_DISABLED_TOOLTIP = '这个身份已经是停用状态。停用语句是无条件的 UPDATE，'
  + '再发一次 DELETE 照样返回 200 而什么都不会变——按钮禁掉是为了不让那个 200 被读成'
  + '"又停了一次"。要让它重新可用，后端没有对应的端点（DELETE 没有反面），只能改库。';

export default function UsersPanel({ refreshToken }) {
  const [reloadToken, setReloadToken] = useState(0);
  const { data, error } = usePanelData(() => fetchUsers(), [refreshToken, reloadToken]);

  /** 在途操作的标识（'create' / 'disable:<用户名>'）。同时只允许一个。 */
  const [pending, setPending] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [resultText, setResultText] = useState(null);
  const [confirm, setConfirm] = useState(null);

  const [username, setUsername] = useState('');
  const [type, setType] = useState(DEFAULT_TYPE);
  const [password, setPassword] = useState('');
  const [isAdmin, setAdmin] = useState(false);
  const [isDba, setDba] = useState(false);

  /* 503 是"没配状态库"，不是故障：单独拎出来，下面用 info 横幅渲染并把表单禁掉。 */
  const isUnavailable = error?.status === 503;

  const rows = useMemo(() => {
    const users = Array.isArray(data) ? data : [];
    return users.map((user) => ({
      ...user,
      status: user.enabled ? STATUS_ENABLED : STATUS_DISABLED,
      /* 在拍平之前先判一次管理员：拍平之后只能对字符串做 includes，而"某个角色名恰好包含
         另一个角色名"这件事今天不成立、明天加一个角色就未必（ROLE_ADMIN_READONLY 之类）。 */
      hasAdminRole: Array.isArray(user.roles) && user.roles.includes(ROLE_ADMIN),
      /* 数组拍成字符串：过滤引擎的 string 分支要求 typeof === 'string'，数组会静默不匹配；
         CSV 里也变成可读的 "ROLE_ADMIN, ROLE_DBA"。渲染时再按 ', ' 拆回来。 */
      roles: Array.isArray(user.roles) ? user.roles.join(', ') : user.roles,
    }));
  }, [data]);

  const counts = useMemo(() => ({
    total: rows.length,
    enabled: rows.filter((row) => row.enabled === true).length,
    disabled: rows.filter((row) => row.enabled !== true).length,
    admins: rows.filter((row) => row.enabled === true && row.hasAdminRole).length,
  }), [rows]);

  const run = useCallback(async (key, request, describe) => {
    setPending(key);
    setActionError(null);
    setResultText(null);
    try {
      const body = await request();
      setResultText(describe(body));
      return true;
    } catch (e) {
      setActionError(e);
      return false;
    } finally {
      setPending(null);
      /* 无论成败都重新拉一遍：失败时页面上的清单最容易和服务端分叉
         （比如 400 是"用户名已存在"——那个用户名确实在库里，只是不在这份旧清单里）。 */
      setReloadToken((t) => t + 1);
    }
  }, []);

  const submit = useCallback(() => {
    const roles = [
      ...(isAdmin ? [ROLE_ADMIN] : []),
      ...(isDba ? [ROLE_DBA] : []),
    ];
    const doCreate = async () => {
      const ok = await run(
        'create',
        () => createUser({ username, type, password, roles }),
        (body) => `已新增身份 ${body.username}（${body.type}），`
          + (body.roles?.length
            ? `授予 ${body.roles.join(' 与 ')}`
            : '未授予任何权限——它能通过认证，但 /api/** 与面板都是 403')
          + '。口令不会回显，忘了只能换一个用户名重建。',
      );
      if (!ok) {
        /* 失败时<b>保留</b>表单：400 的常见原因是用户名重名或权限写错，运维要改一个字再提交，
           清掉等于让他把口令重新敲一遍。 */
        return;
      }
      /*
       * 成功后清空整张表单，包括那两个权限开关。
       *
       * 开关不回到关闭是个真实的脚坑：连着加几个只读 agent 时，第一次勾过 ROLE_ADMIN 之后
       * 后面每一个都会默默带上管理员——而"授予了管理员"这件事在列表里要滚到那一行才看得到。
       * 类型回到默认也是同一个道理，只是后果轻得多。
       */
      setUsername('');
      setPassword('');
      setAdmin(false);
      setDba(false);
      setType(DEFAULT_TYPE);
    };
    if (!isAdmin) {
      doCreate();
      return;
    }
    /* 只在授予 ROLE_ADMIN 时确认。给每次新建都弹一个确认框的话，批量加几个只读 agent
       会变成连点确认，于是真正危险的那一次也被无脑点过去了（同 ToolsPanel 的判据）。 */
    setConfirm({
      title: `要给 ${username} 授予 ROLE_ADMIN 吗`,
      description: '这个身份将拥有与你完全相同的权限：能打开本面板、调用 /api/** 下的全部接口，'
        + '并且能继续创建更多管理员。这件事不可撤销——后端没有改权限的端点，'
        + '停用你自己这个身份也收不回它已经拿到的权限。'
        + '事后唯一能查到"谁被给了管理员"的地方是 JdbcUserStore.create 打出的那条日志。'
        + '只有确实需要一个能管身份与工具开关的运维账号时才继续。',
      actionLabel: '仍然授予管理员',
      run: doCreate,
    });
  }, [run, username, type, password, isAdmin, isDba]);

  const disable = useCallback((row) => {
    setConfirm({
      title: `停用 ${row.username}`,
      description: '停用之后这个身份立刻无法认证（它持有的凭据全部失效），行仍然留在库里。'
        + '这一步在页面上没有反面：后端只有 DELETE，没有"启用回来"的端点，'
        + '而同名重建会撞上主键——要恢复只能直接改库的 enabled 列。'
        + '如果这个身份正在被某个 MCP 客户端或 agent 使用，那一侧会立刻开始拿 401。',
      actionLabel: '停用',
      run: () => run(
        `disable:${row.username}`,
        () => disableUser(row.username),
        (body) => `已停用 ${body.username}。它持有的凭据即刻失效，`
          + '库里的行还在（enabled 置 0），但页面上没有把它启用回来的入口。',
      ),
    });
  }, [run]);

  const columns = useMemo(() => [
    ...BASE_COLUMNS,
    {
      key: '_action',
      header: '操作',
      align: 'center',
      sortable: false,
      csv: false,
      width: pixel(96),
      renderCell: (item) => {
        if (item.enabled !== true) {
          return <Button label="停用" variant="ghost" isDisabled tooltip={ALREADY_DISABLED_TOOLTIP} />;
        }
        const isBusy = pending === `disable:${item.username}`;
        return (
          <Button
            label="停用"
            variant="destructive"
            isLoading={isBusy}
            isDisabled={pending !== null && !isBusy}
            onClick={(e) => {
              /* 行本身挂着"打开详情面板"的 onClick，不拦冒泡会顺带弹出右侧详情。 */
              e.stopPropagation();
              disable(item);
            }}
          />
        );
      },
    },
  ], [pending, disable]);

  const canSubmit = username.trim() !== '' && password !== '' && pending === null && !isUnavailable;

  return (
    <VStack gap={6}>
      {/* 503 不走 ErrorNotice（见头注释）；其余错误照旧原样显示状态码与响应体。 */}
      {!isUnavailable && <ErrorNotice error={error} />}

      {isUnavailable && (
        <Banner
          status="info"
          title="身份表未启用：这台服务器没有配置状态库"
          description={
            'UserAdminService 挂在 spring.datasource.url 上，没配库时它不装配，三个端点都是 503。'
            + '这是受支持的部署形态——此时调用者身份来自 entropy.mcp.security.users-file 声明的'
            + '凭据文件（口令必须是 bcrypt 哈希），或者只有环境变量里的那一个管理员。'
            + '要在这里增删身份，先配上状态库（与审计流水、工具开关同一个键）。'
            + `服务端原话：${error.message}`
          }
          container="card"
        />
      )}

      {actionError && (
        <Banner
          status="error"
          title="这次操作失败了"
          description={actionError.message}
          container="card"
        />
      )}

      {resultText && (
        <Banner
          status="success"
          title="操作已执行"
          description={resultText}
          container="card"
          isDismissable
          onDismiss={() => setResultText(null)}
        />
      )}

      {/* 这两条与 UserAdminController / UserAdminService 的类注释是同一件事，三处口径必须一致。 */}
      <Banner
        status="warning"
        title="这个页面能造出与你权限相同的管理员，而那一步不可撤销"
        description={
          '给新身份勾上 ROLE_ADMIN，它就能打开本面板、调用 /api/** 的全部接口，并继续创建更多'
          + '管理员。后端刻意允许（否则"不重启加一个管理员"做不到），代价是一个 ROLE_ADMIN 泄漏'
          + '就能自我复制成任意多个，停用原来那个也拿不回来。后端没有改权限的端点，'
          + '事后唯一能查到"谁被给了管理员"的地方是 JdbcUserStore.create 打出的日志。'
        }
        container="card"
      />

      <Banner
        status="warning"
        title="口令以原文提交，所以这一页必须跑在 HTTPS 上"
        description={
          '新增身份时口令走请求体的明文，由服务端哈希后落库（响应里不回显）。凭据文件只接受'
          + 'bcrypt 是因为它长期躺在磁盘上，而这里的原文只存在于一次请求里——代价是它会经过网络，'
          + '并且不能让请求体进访问日志。在纯 HTTP 的部署上不要用这个表单加身份。'
        }
        container="card"
      />

      <Banner
        status="info"
        title="管理员不在这份清单里"
        description={
          '环境变量里的那个管理员（MCP_SECURITY_ADMIN_PASSWORD，用户名由 '
          + 'entropy.mcp.security.admin-username 决定）不进库，因此既不出现在表里，也不能被这组'
          + '接口创建或停用——同名创建与停用管理员在服务端都是 400。清单为空不代表没有调用者；'
          + '凭据文件里声明的身份同样不在这张表里。'
        }
        container="card"
      />

      <KpiGrid
        items={[
          { label: '库里的身份', value: counts.total, hint: '不含管理员与凭据文件里声明的身份' },
          { label: '可认证', value: counts.enabled, hint: 'enabled=1，凭据当前有效' },
          {
            label: '已停用',
            value: counts.disabled,
            hint: '行还在（停用是 UPDATE 而不是 DELETE），页面上没有启用回来的入口',
          },
          {
            label: '其中带 ROLE_ADMIN',
            value: counts.admins,
            status: counts.admins > 0 ? 'warning' : undefined,
            statusLabel: counts.admins > 0 ? '与你同权限' : undefined,
            hint: '这些身份能打开本面板、并继续创建管理员；环境变量里那个管理员不计在内',
          },
        ]}
      />

      <Section
        title="新增调用者身份"
        source="POST /api/users · 口令原文提交、服务端哈希 · 用户名是主键，建了不能改名也不能改权限"
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <TextInput
              label="用户名"
              value={username}
              onChange={setUsername}
              isRequired
              isDisabled={isUnavailable}
              placeholder="也是授权判定里的主体 id"
              width={220}
            />
            <Selector
              label="主体类型"
              options={PRINCIPAL_TYPES}
              value={type}
              onChange={setType}
              isDisabled={isUnavailable}
              width={160}
            />
            <TextInput
              label="口令"
              type="password"
              value={password}
              onChange={setPassword}
              isRequired
              isDisabled={isUnavailable}
              /* 不让密码管理器把别处存的口令填进来：这是给"另一个身份"设置新口令的字段，
                 不是登录框。autoComplete 只影响浏览器建议，值仍然由 React 控制。 */
              autoComplete="new-password"
              placeholder="不会回显，忘了只能换名重建"
              width={220}
            />
          </HStack>
          <HStack gap={5} wrap="wrap" align="center">
            <Text type="label" color="secondary">授予权限（默认一个都不给）</Text>
            <Switch label={ROLE_ADMIN} value={isAdmin} onChange={setAdmin} isDisabled={isUnavailable} />
            <Switch label={ROLE_DBA} value={isDba} onChange={setDba} isDisabled={isUnavailable} />
            <Button
              label="新增"
              variant={isAdmin ? 'destructive' : 'primary'}
              isDisabled={!canSubmit}
              isLoading={pending === 'create'}
              onClick={submit}
            />
          </HStack>
          <Text type="supporting" color="secondary">
            {'可选权限只有这两个（JdbcUserStore.GRANTABLE_AUTHORITIES），必须写全 ROLE_ 前缀，'
             + '其它值后端直接 400 而不是静默忽略。一个都不给是合法的：那个身份能通过认证，'
             + '但 /api/** 与本页面对它都是 403——它只能调 /mcp。'}
          </Text>
          {isAdmin && (
            <Text type="supporting" color="secondary">
              {'已勾选 ROLE_ADMIN：点「新增」会先弹一次确认。这个权限给出去之后收不回来。'}
            </Text>
          )}
        </VStack>
      </Section>

      <InteractiveTable
        title="调用者身份"
        source="GET /api/users · 不含管理员与凭据文件里的身份 · 停用是 UPDATE，行不会消失"
        rows={rows}
        columns={columns}
        searchFields={SEARCH_FIELDS}
        searchName={TABLE_NAME}
        defaultSort={DEFAULT_SORT}
        getRowKey={ROW_KEY}
        detailTitle="身份详情"
        csvBaseName="mcp-users"
        emptyTitle="库里没有任何调用者身份"
        emptyDescription={
          '这不一定是问题：管理员来自环境变量、凭据文件里的身份也不在这张表里，'
          + '两者都不会出现在这份清单上。用上面的表单加一个，或者继续用现有的那些。'
        }
      />

      {confirm && (
        <AlertDialog
          isOpen
          onOpenChange={(open) => { if (!open) { setConfirm(null); } }}
          title={confirm.title}
          description={confirm.description}
          cancelLabel="取消"
          actionLabel={confirm.actionLabel}
          onAction={() => {
            const { run: proceed } = confirm;
            setConfirm(null);
            proceed();
          }}
          width={520}
        />
      )}
    </VStack>
  );
}
