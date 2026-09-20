import {
  AlertDialog,
  Badge,
  Banner,
  Button,
  HStack,
  Selector,
  Text,
  VStack,
  pixel,
  proportional,
} from '@astryxdesign/core';
import { useCallback, useMemo, useState } from 'react';
import { fetchToolAdmin, setGroupDisabled, setToolDisabled } from '../api.js';
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
 * 工具清单 + 运行期启用/停用。来源 GET /api/tools（ToolAdminController）。
 *
 * ── 这一页是整个面板里唯一会改服务端状态的地方 ──
 * 其余 15 个视图都只发 GET，这一页多了两个 PUT。所以它比别的页多欠三件事，缺一件都会
 * 让运维得出错误结论，下面每一件在页面上都有对应的常驻文案（不是装饰，别删）：
 * 1. 停用 = 工具从 tools/list <b>移除</b>，而 stateless 传输没有 notifyToolsListChanged
 *    （那是 stateful 的 McpSyncServer 才有的方法）。已经缓存了旧清单的客户端调一个刚被
 *    停用的工具，拿到的是"工具不存在"，而不是"被管理员停用了"。不写出来的话，第一个
 *    撞上它的人会去查 bug；
 * 2. persisted=false（没配 spring.datasource.url）时开关只活在进程内存里，重启回到
 *    entropy.mcp.tools 声明的状态，且没有任何报错——症状是"明明停掉的工具又回来了"；
 * 3. remainingExposed 归零是<b>合法</b>的运维动作（运行期 kill switch），后端刻意允许，
 *    但客户端看到的是一台没有任何工具的服务器，通常表现为"服务没接上"。
 *
 * ── 数据源为什么从 /api/ui/tools 换成 /api/tools ──
 * 两者背后是<b>同一份 ToolCatalog 索引</b>，但字段不同：
 * - /api/ui/tools 给 {total, exposed, groups, tools:[{name, group, summary, tags}]}；
 * - /api/tools 多了逐工具的 exposed / disabled，以及顶层的 disabledCount / persisted。
 * 没有逐工具的两个布尔就画不出"状态"列——只能把每一行都显示成"启用"，那是在说谎。
 * 有出入的地方一律以 /api/tools 为准，两处不一致时它才是能配合 PUT 的那一份：
 * - <b>exposed 的语义不同</b>。/api/ui/tools 的 exposed 是"裁剪后交给客户端的个数"；
 *   /api/tools 的 exposed 是<b>部署期暴露集的大小，含当前被停用的</b>。现在真正出现在
 *   tools/list 里的个数要自己算 exposed - disabledCount（也就是 PUT 返回的 remainingExposed）；
 * - <b>少了 groups 那一块</b>。所以「整组一个工具都没暴露」这件事不再靠对比 groups 与 tools
 *   得到，而是直接从行里看出来：/api/tools 的 tools 数组<b>包含</b>被部署期裁掉的工具
 *   （exposed=false），于是整组被裁掉的分组在表里是"有行、但一行都不可操作"，
 *   比上一版那个"两块对不上"的推断更直接，也少一次前后端口径漂移的机会。
 */

/**
 * 这份清单没有入参 schema，而且这不是个「以后补上」的 TODO。
 *
 * 后端的数据源是 ToolCatalog，它是从 @McpTool 方法反射出来的元数据索引，参数的 JSON Schema
 * 由下游的 SyncToolSpecification 持有，索引里根本没有。想补得注入
 * ObjectProvider<List<SyncToolSpecification>>，而容器里该类型有多个 bean、ToolExposureFilter
 * 又是 BeanPostProcessor——注入时机决定拿到的是裁剪前还是裁剪后的列表。所以这一页是目录，
 * 不是 API 文档：别让人以为能在这里查参数名。
 */
const NO_SCHEMA_TITLE = '这份清单没有工具的入参 schema';
const NO_SCHEMA_DESCRIPTION = '后端的数据源是 ToolCatalog（@McpTool 方法的反射元数据索引），'
  + '它不带 inputSchema。这里只能看到「有哪些工具、各属哪组、一句摘要、当前是否被停用」，'
  + '查不到参数名与参数类型——那些要问 MCP 客户端的 tools/list。';

/** group 为空的工具归到这一段。后端对非目录来源的工具会给出 null group，所以这个分支真的会走到。 */
const UNGROUPED = '（未分组）';

/*
 * 三态状态值。
 *
 * 派生成<b>字符串</b>字段而不是直接用 exposed/disabled 两个布尔渲染，理由与 components.jsx
 * 里 resultColumn 的那段完全一样：PowerSearch 的 enum 过滤只认字符串、排序的默认比较器
 * 拿 true/false 排出来毫无意义、CSV 里 "已停用" 比 "true,false" 可读。
 * 派生放在 rows 的 map 里，不在列定义里偷偷加字段。
 */
const STATUS_ENABLED = '启用';
const STATUS_DISABLED = '已停用';
/** exposed=false：被部署期 entropy.mcp.tools 裁掉，运行期<b>动不了</b>它（对它发 PUT 是 400）。 */
const STATUS_PRUNED = '配置裁剪';

const STATUS_ENUM_VALUES = [
  { value: STATUS_ENABLED, label: STATUS_ENABLED },
  { value: STATUS_DISABLED, label: STATUS_DISABLED },
  { value: STATUS_PRUNED, label: STATUS_PRUNED },
];

const STATUS_BADGE_VARIANT = {
  [STATUS_ENABLED]: 'success',
  [STATUS_DISABLED]: 'warning',
  /* neutral 而不是 error：被配置裁掉不是故障，是部署方的决定。
     （也不能用 accent —— 0.6.2 的 BadgeVariantMap 里没有这个成员。） */
  [STATUS_PRUNED]: 'neutral',
};

/** 按钮禁用的原因要能在按钮上直接读到，否则只能靠点一下换回 400 才知道。 */
const PRUNED_TOOLTIP = '这个工具被部署期配置 entropy.mcp.tools（plane/groups/include/exclude）裁掉了，'
  + '不在运行期可操作的暴露集里。运行期刻意不允许把它加回来——那等于用一次点击绕过部署方的'
  + '暴露策略。要启用它只能改配置并重启。';

/**
 * 工具名列。
 *
 * 不用 textColumn 而是自己写 renderCell，只为一件事：exposed=false 的行要<b>灰显</b>。
 * 「行要一眼看出不可操作」不能只靠状态列那个 Badge——扫一列 Badge 仍然要逐行读。
 * 刻意不走 InteractiveTable 的 getRowTone（它能给整行上底色）：rowStatus 插件的标记
 * 文案在 components.jsx 里是写死的「失败」/「超过阈值」，给一个"被配置裁掉的工具"
 * 挂上"失败"标记是在造一个不存在的故障。
 */
const NAME_COLUMN = {
  key: 'name',
  header: '工具名',
  sortable: true,
  filter: 'name',
  width: proportional(2),
  renderCell: (item) => (
    <Text type="supporting" weight="semibold" color={item.exposed ? undefined : 'disabled'}>
      {item.name}
    </Text>
  ),
};

const STATUS_COLUMN = {
  key: 'status',
  header: '状态',
  align: 'center',
  sortable: true,
  filter: 'status',
  width: pixel(104),
  renderCell: (item) => (
    item.status
      ? <Badge variant={STATUS_BADGE_VARIANT[item.status] ?? 'neutral'} label={item.status} />
      : <Text type="supporting">—</Text>
  ),
};

/** 操作列之外的那些列。操作列带回调与在途状态，只能在组件里 useMemo 拼出来。 */
const BASE_COLUMNS = [
  NAME_COLUMN,
  STATUS_COLUMN,
  textColumn('group', '分组', { flex: 1, filter: 'group' }),
  textColumn('summary', '摘要', { flex: 4, filter: 'summary' }),
  textColumn('tags', '标签', { flex: 2, filter: 'tags' }),
];

/*
 * group 给成 string（contains）而不是 enum：enum 需要在模块顶层就写死取值，
 * 而分组名来自后端、会随新功能增加。string 的 contains 在这里够用，而且顺带支持
 * 「所有 backup 相关的组」这种前缀式的粗筛。
 *
 * status 反过来给成 enum：它的取值就是上面那三个常量，写死是准确的，
 * 而且「只看已停用的」是这一页最常用的一次筛选，做成下拉比让人手打"已停用"好。
 *
 * tags 是数组，也按 string 处理：applyFilters 对 string 类型要求 typeof === 'string'，
 * 数组会直接不匹配。所以下面把 tags 拍成了逗号分隔的字符串（见 rows 的 map）。
 */
const SEARCH_FIELDS = [
  SEARCH_ALL_FIELD,
  { key: 'name', type: 'string', label: '工具名' },
  { key: 'status', type: 'enum', label: '状态', enumValues: STATUS_ENUM_VALUES },
  { key: 'group', type: 'string', label: '分组' },
  { key: 'summary', type: 'string', label: '摘要' },
  { key: 'tags', type: 'string', label: '标签' },
];

/** 默认按分组、再按工具名升序：和「按分组整组操作」这件事的阅读顺序一致。 */
const DEFAULT_SORT = [
  { sortKey: 'group', direction: 'ascending' },
  { sortKey: 'name', direction: 'ascending' },
];

/** 工具名在 MCP 协议里就是唯一键，直接用。 */
const ROW_KEY = (row) => String(row.name);

/**
 * 表标识。<b>从 'tools' 改成了 'tool-admin'，这一次是故意的。</b>
 *
 * 它同时是列显隐 / 列宽 / 保存的视图三处 localStorage 的 key（见 InteractiveTable 的说明），
 * 改掉等于让这张表的用户设置回到默认。通常不该这么做，但这一版给表加了「状态」和「操作」
 * 两列：沿用旧 key 的话，之前调过列显隐的人存下来的那份键列表里没有这两列，
 * validateColumnKeys 会把它原样保留（它只剔除不存在的键，不会补新增的键），
 * 结果是<b>操作按钮那一列静默消失</b>，而这一版的主要功能就在那一列上。
 * 一次性重置用户的列设置，比让一部分人完全看不到新功能好。
 */
const TABLE_NAME = 'tool-admin';

/** 落库提示接在每条摘要后面：没有它，一条"已停用"读起来像是永久生效。 */
function persistenceSuffix(body) {
  return body?.persisted
    ? ''
    : '；本次改动只在进程内存里，重启后会回到配置声明的状态';
}

export default function ToolsPanel({ refreshToken }) {
  /*
   * reloadToken 与顶栏的 refreshToken 并列进 deps：每次开关操作之后这一页必须自己重新拉
   * 一遍，不能等运维去点顶栏的刷新。原因不只是"显示要跟上"——一次分组操作可能只成功了
   * 一部分（后端逐个 add/removeTool），只有重新拉才知道真实状态。
   */
  const [reloadToken, setReloadToken] = useState(0);
  const { data, error } = usePanelData(() => fetchToolAdmin(), [refreshToken, reloadToken]);

  /** 在途操作的标识（'tool:<名字>' / 'group:<分组>'）。同时只允许一个：并发两次开关会让摘要对不上。 */
  const [pending, setPending] = useState(null);
  /** 上一次操作的错误。和加载错误分开显示：两者的下一步动作不同（重试 vs 改请求）。 */
  const [actionError, setActionError] = useState(null);
  /** 上一次操作的结果摘要（改了几个、还剩几个）。 */
  const [resultText, setResultText] = useState(null);
  /** 危险操作的二次确认。null = 没有待确认的操作。 */
  const [confirm, setConfirm] = useState(null);
  const [group, setGroup] = useState('');

  const rows = useMemo(() => {
    const tools = Array.isArray(data?.tools) ? data.tools : [];
    return tools.map((tool) => ({
      ...tool,
      group: tool.group || UNGROUPED,
      /* 三态派生。注意顺序：exposed=false 优先——那种工具的 disabled 恒为 false
         （后端的 disabled 集合永远是暴露集的子集），按 disabled 先判会把它显示成"启用"，
         而它根本不在 tools/list 里。 */
      status: !tool.exposed
        ? STATUS_PRUNED
        : tool.disabled ? STATUS_DISABLED : STATUS_ENABLED,
      /* 数组拍成字符串：过滤引擎的 string 分支要求 typeof === 'string'，
         数组会静默不匹配（返回 false，不报错）——那种"搜不到但也不说为什么"最难查。
         CSV 导出也顺带变成可读的 "a, b" 而不是 ["a","b"]。 */
      tags: Array.isArray(tool.tags) ? tool.tags.join(', ') : tool.tags,
    }));
  }, [data]);

  /*
   * 现在仍然出现在 tools/list 里的工具数。
   *
   * 后端没有在 GET 里直接给这个数（只有两个 PUT 的返回体里有 remainingExposed），所以这里
   * 按它的定义自己算：暴露集大小 - 被停用数。<b>不能</b>直接用 data.exposed —— 那是含
   * 被停用工具的暴露集大小，拿它当"客户端能看到几个"会在停用了一半工具时显示成没变。
   */
  const exposedCount = Number.isInteger(data?.exposed) ? data.exposed : null;
  const disabledCount = Number.isInteger(data?.disabledCount) ? data.disabledCount : null;
  const remainingExposed = exposedCount === null || disabledCount === null
    ? null
    : exposedCount - disabledCount;
  const isPersisted = data?.persisted === true;

  /*
   * 分组清单从 rows 里归集，<b>不</b>另写一份常量清单：写两份之后后端新加的分组这里点不到，
   * 这里多出的分组点了必然 400（口径与 api.js 里 fetchDbaViews 上方那段说明一致）。
   *
   * 分成两类是因为后端的分组接口只接受"至少有一个已暴露工具"的分组（ToolToggleRegistry
   * .requireGroup 对空分组抛 IllegalArgumentException → 400）。把整组被裁掉的分组也列进
   * 下拉框，等于摆一个点了一定报错的选项。
   */
  const { operableGroups, prunedGroups } = useMemo(() => {
    const exposedByGroup = new Map();
    for (const row of rows) {
      exposedByGroup.set(row.group, (exposedByGroup.get(row.group) ?? false) || row.exposed === true);
    }
    const all = [...exposedByGroup.keys()].sort((a, b) => a.localeCompare(b));
    return {
      operableGroups: all.filter((g) => exposedByGroup.get(g)),
      prunedGroups: all.filter((g) => !exposedByGroup.get(g)),
    };
  }, [rows]);

  /** 选中分组下可被操作的工具数 / 其中已停用的个数。确认文案要把范围说清楚，不能只说"整组"。 */
  const groupStats = useMemo(() => {
    const inGroup = rows.filter((row) => row.group === group && row.exposed === true);
    return {
      exposed: inGroup.length,
      disabled: inGroup.filter((row) => row.disabled === true).length,
    };
  }, [rows, group]);

  /**
   * 跑一次开关操作。
   *
   * 无论成败都在 finally 里重新拉一次清单：失败时页面上的状态和服务端的真实状态最容易
   * 分叉（分组操作可能改了一半），此时保留旧数据比拉一次新数据更容易误导。
   */
  const runToggle = useCallback(async (key, request, describe) => {
    setPending(key);
    setActionError(null);
    setResultText(null);
    try {
      const body = await request();
      setResultText(describe(body));
    } catch (e) {
      setActionError(e);
    } finally {
      setPending(null);
      setReloadToken((t) => t + 1);
    }
  }, []);

  /** 单个工具的停用/启用。点的是哪一侧由行的当前状态决定，页面上不做"开关"这种双态控件。 */
  const toggleTool = useCallback((row) => {
    const disable = !row.disabled;
    const run = () => runToggle(
      `tool:${row.name}`,
      () => setToolDisabled(row.name, disable),
      (body) => `工具 ${body.name} 已${body.disabled ? '停用' : '启用'}`
        /* changed=false 是幂等成功而不是失败，但必须说出来：否则点了没反应的人
           会以为请求丢了，而实际上是它本来就是这个状态。 */
        + (body.changed ? '' : '（它本来就是这个状态，未变更）')
        + `；仍在 tools/list 的工具还有 ${body.remainingExposed} 个`
        + persistenceSuffix(body),
    );
    /* 单个停用<b>只在会把清单清空时</b>才确认。每次停用都弹一下确认框的话，
       "先全停再一个个放回来"这种正常操作会变成点二十次确认，人会开始无脑点确认，
       于是真正危险的那一次也被无脑点过去了。 */
    if (disable && remainingExposed === 1) {
      setConfirm({
        title: '这是最后一个还在 tools/list 里的工具',
        description: `停用 ${row.name} 之后 tools/list 会变成空数组。这是运行期 kill switch 的`
          + '预期行为（与启动期「配置把工具全裁光即启动失败」不同），但 MCP 客户端那边看到的是'
          + '一台没有任何工具的服务器，通常表现为"服务没接上"。确认要继续吗？',
        actionLabel: '仍然停用',
        run,
      });
      return;
    }
    run();
  }, [runToggle, remainingExposed]);

  const toggleGroup = useCallback((disable) => {
    const run = () => runToggle(
      /* 键里带上方向：两个按钮共用一个键的话，整组「启用」在途时「停用」按钮也会转圈，
         那个 spinner 说的是一件没在发生的事。 */
      `group:${group}:${disable ? 'disable' : 'enable'}`,
      () => setGroupDisabled(group, disable),
      (body) => `分组 ${body.group}：命中 ${Array.isArray(body.affected) ? body.affected.length : 0} 个已暴露的工具，`
        /* affected 与 changed 不同是正常的（重复执行同一条命令时 changed 归零），
           两个数都显示出来，否则"命中 8 个、改了 0 个"会被读成"一个都没生效"。 */
        + `其中 ${body.changed} 个状态发生变化（${disable ? '停用' : '启用'}）`
        + `；仍在 tools/list 的工具还有 ${body.remainingExposed} 个`
        + persistenceSuffix(body),
    );
    if (!disable) {
      /* 整组启用不确认：它只会让工具重新出现，最坏结果是多暴露了一组工具，
         而且立刻可以再停掉。确认框留给不可一键撤销的那一侧。 */
      run();
      return;
    }
    const willBeZero = remainingExposed !== null
      && groupStats.exposed - groupStats.disabled >= remainingExposed;
    setConfirm({
      title: `整组停用：${group}`,
      description: `这会把该分组下 ${groupStats.exposed} 个已暴露的工具从 tools/list 移除`
        + `（其中 ${groupStats.disabled} 个已经是停用状态，不会变化）。`
        + (willBeZero
          ? '执行后 tools/list 会变成空数组，MCP 客户端会看到一台没有任何工具的服务器。'
          : '')
        + '已经缓存了旧清单的客户端调到这些工具时，拿到的是"工具不存在"而不是"被停用"。',
      actionLabel: '整组停用',
      run,
    });
  }, [runToggle, group, groupStats, remainingExposed]);

  /*
   * 操作列。带回调和在途状态，所以只能在这里拼——它是本文件唯一不是模块级常量的列。
   * 依赖里有 pending，于是一次点击会让 columns 换一次引用、下游的过滤/排序 memo 重算一遍；
   * 这是明码标价的代价（只在一次请求的往返期间发生），换的是按钮上的 loading 与互斥。
   */
  const columns = useMemo(() => [
    ...BASE_COLUMNS,
    {
      key: '_action',
      header: '操作',
      align: 'center',
      sortable: false,
      // csv:false —— 导出的 CSV 里一个按钮没有意义，见 components.jsx 的 toCsv 列过滤。
      csv: false,
      width: pixel(96),
      renderCell: (item) => {
        if (item.exposed !== true) {
          /* 禁用 + tooltip，而不是把按钮藏掉：藏起来会让人以为"这一行没有操作"，
             而真相是"这一行的操作只能通过改配置重启完成"。Button 在 tooltip 存在时
             用 aria-disabled 而不是原生 disabled（见 core 的实现），所以键盘也读得到原因。 */
          return <Button label="停用" variant="ghost" isDisabled tooltip={PRUNED_TOOLTIP} />;
        }
        const isBusy = pending === `tool:${item.name}`;
        return (
          <Button
            label={item.disabled ? '启用' : '停用'}
            variant={item.disabled ? 'secondary' : 'destructive'}
            isLoading={isBusy}
            /* 有别的操作在途时全部禁掉：两次并发开关会让两条摘要里的 remainingExposed
               互相覆盖，页面上显示的"还剩几个"就成了一个过期的数。 */
            isDisabled={pending !== null && !isBusy}
            onClick={(e) => {
              /* 行本身挂着"打开详情面板"的 onClick（useRowInteractionPlugin），
                 不拦住冒泡的话点一次停用会顺带弹出右侧详情。 */
              e.stopPropagation();
              toggleTool(item);
            }}
          />
        );
      },
    },
  ], [pending, toggleTool]);

  const canOperateGroup = group !== '' && operableGroups.includes(group) && pending === null;

  return (
    <VStack gap={6}>
      <ErrorNotice error={error} />

      {/* 操作失败单独一条，状态码与响应体原文全留在页面上：后端把「工具名拼错」和
          「被部署期裁掉」分成两句话写在 400 的消息里，压成"操作失败"就把这两条线索扔了。 */}
      {actionError && (
        <Banner
          status="error"
          title="这次开关操作失败了"
          description={actionError.message}
          container="card"
        />
      )}

      {resultText && (
        <Banner
          status="success"
          title="开关操作已执行"
          description={resultText}
          container="card"
          isDismissable
          onDismiss={() => setResultText(null)}
        />
      )}

      {remainingExposed === 0 && (
        <Banner
          status="error"
          title="当前 tools/list 是空的：MCP 客户端看不到任何工具"
          description={
            '暴露集里的工具已经全部被停用。这是运行期 kill switch 的预期行为'
            + '（与启动期「配置把工具全裁光即启动失败」不同），不是故障，'
            + '但客户端那边通常表现为"服务没接上"。用下面每一行的「启用」把工具逐个放回来。'
          }
          container="card"
        />
      )}

      {data && !isPersisted && (
        <Banner
          status="warning"
          title="开关只存在于进程内存里，重启即失效"
          description={
            '这台服务器没有配置状态库（spring.datasource.url），ToolToggleRepository 不装配，'
            + '所以在这里停用的工具在重启后会全部恢复暴露，回到 entropy.mcp.tools 配置声明的'
            + '状态，而且没有任何报错——症状就是"明明停掉了的工具又回到 tools/list 里"。'
            + '要让停用活过重启，请配上服务端状态库（与审计流水、调用者身份同一个键）。'
          }
          container="card"
        />
      )}

      {/* 这一条与后端 ToolToggleRegistry 的类注释是同一句话，两处必须一致。 */}
      <Banner
        status="warning"
        title="停用之后，客户端拿到的是「工具不存在」而不是「被停用」"
        description={
          '停用的实现是把工具从 tools/list 里移除（真的 removeTool，不是调用时拦截）。'
          + 'stateless 传输没有 server → client 的推送通道，McpStatelessSyncServer 因此没有 '
          + 'notifyToolsListChanged（那是 stateful 的 McpSyncServer 才有的方法），'
          + '客户端只有在下一次主动 tools/list 时才知道清单变了。'
          + '已经缓存了旧清单的客户端调用一个刚被停用的工具，得到的是"工具不存在"这一类错误。'
          + '这是协议层的已知代价，不是 bug——停用一个高频工具之前要知道会看到一批这样的报错。'
        }
        container="card"
      />

      <Banner
        status="info"
        title={NO_SCHEMA_TITLE}
        description={NO_SCHEMA_DESCRIPTION}
        container="card"
      />

      <KpiGrid
        items={[
          {
            label: '工具总数',
            value: data?.total,
            hint: '目录里反射出来的全部工具，含被部署期裁掉的',
          },
          {
            label: '部署期暴露集',
            value: data?.exposed,
            hint: 'entropy.mcp.tools 裁剪后可在运行期操作的工具；含当前被停用的那些',
          },
          {
            label: '仍在 tools/list',
            value: remainingExposed === null ? undefined : remainingExposed,
            status: remainingExposed === null
              ? undefined
              : remainingExposed === 0 ? 'error' : 'success',
            statusLabel: remainingExposed === 0 ? '客户端看不到任何工具' : '客户端可见',
            hint: '暴露集 − 已停用，也就是 PUT 返回体里的 remainingExposed',
          },
          {
            label: '已停用',
            value: data?.disabledCount,
            hint: isPersisted ? '已落库，重启后会被回放' : '仅进程内存，重启即恢复暴露',
          },
        ]}
      />

      <Section
        title="按分组批量操作"
        source="分组清单从 GET /api/tools 的 tools[].group 归集 · 前端不写第二份清单"
      >
        <VStack gap={4}>
          <HStack gap={3} wrap="wrap" vAlign="end">
            <Selector
              label="分组"
              options={operableGroups}
              value={group}
              onChange={setGroup}
              placeholder={operableGroups.length === 0 ? '没有可操作的分组' : '选择一个分组…'}
              isDisabled={operableGroups.length === 0}
              width={260}
            />
            <Button
              label="整组停用"
              variant="destructive"
              isDisabled={!canOperateGroup}
              isLoading={pending === `group:${group}:disable`}
              onClick={() => toggleGroup(true)}
            />
            <Button
              label="整组启用"
              variant="secondary"
              isDisabled={!canOperateGroup}
              isLoading={pending === `group:${group}:enable`}
              onClick={() => toggleGroup(false)}
            />
          </HStack>
          {group !== '' && operableGroups.includes(group) && (
            <Text type="supporting" color="secondary">
              {`分组 ${group} 下有 ${groupStats.exposed} 个可操作的工具，其中 ${groupStats.disabled} 个当前已停用。`}
              {'批量操作只作用于已暴露的工具，被配置裁掉的那些不受影响。'}
            </Text>
          )}
          {prunedGroups.length > 0 && (
            <VStack gap={2}>
              {/* 下拉框里刻意没有这些分组：后端对"一个已暴露工具都没有"的分组回 400，
                  把它们列进去等于摆一个点了一定报错的选项。但它们必须在页面上出现——
                  「这一组被整组裁掉了」本身是有用的信息。 */}
              <Text type="supporting" color="secondary">
                下面这些分组当前一个工具都没暴露（被 entropy.mcp.tools 整组裁掉），
                因此不出现在上面的下拉框里，运行期也无法启用：
              </Text>
              <HStack gap={2} wrap="wrap">
                {prunedGroups.map((g) => <Badge key={g} variant="neutral" label={g} />)}
              </HStack>
            </VStack>
          )}
        </VStack>
      </Section>

      <InteractiveTable
        title="工具清单与开关"
        source={
          'GET /api/tools · 含被部署期裁掉的工具（状态列为「配置裁剪」，不可操作）'
          + ' · 停用 = 从 tools/list 移除'
        }
        rows={rows}
        columns={columns}
        searchFields={SEARCH_FIELDS}
        searchName={TABLE_NAME}
        defaultSort={DEFAULT_SORT}
        getRowKey={ROW_KEY}
        detailTitle="工具详情"
        csvBaseName="mcp-tools"
        emptyTitle="没有任何工具"
        emptyDescription="GET /api/tools 返回了空清单——正常部署下不会出现，先看上面有没有报错。"
      />

      {/*
        危险操作的确认。用 core 的 AlertDialog 而不是自己写两步按钮：它按 WAI-ARIA 的
        alertdialog 模式实现（role="alertdialog"、点外面关不掉、Esc 等于取消、初始焦点
        落在取消按钮上）。不新增依赖，也不用自己维护焦点陷阱。

        它<b>不会</b>自动关闭（文档里明确写了），所以 onAction 里要自己 setConfirm(null)。
      */}
      {confirm && (
        <AlertDialog
          isOpen
          onOpenChange={(open) => { if (!open) { setConfirm(null); } }}
          title={confirm.title}
          description={confirm.description}
          cancelLabel="取消"
          actionLabel={confirm.actionLabel}
          onAction={() => {
            const { run } = confirm;
            setConfirm(null);
            run();
          }}
          width={520}
        />
      )}
    </VStack>
  );
}
