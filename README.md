# Database MCP Server

<p align="center">
  <strong>为 AI 应用提供安全、可控、可观测的数据库访问能力</strong>
</p>
<p align="center">
  <em>基于 Spring AI MCP 协议 · 支持 Oracle / MySQL / PostgreSQL / SQL Server 等多方言数据库</em>
</p>

<p align="center">
  <a href="#"><img src="https://img.shields.io/badge/Java-21-brightgreen.svg" alt="Java 21"></a>
  <a href="#"><img src="https://img.shields.io/badge/Spring%20Boot-4.1.0-blue.svg" alt="Spring Boot 4.1"></a>
  <a href="#"><img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg" alt="License"></a>
</p>

---

## 🚀 简介

**Database MCP Server** 让 AI Agent 能够安全地访问和操作数据库——无需编写 JDBC 代码，无需暴露数据库凭据，一切通过 MCP 协议标准化交互。

无论是 Claude、Cursor 还是自定义 Python 客户端，都能直接获得**完整的数据库查询、分析、管理、ETL 能力**。

---

## ✨ 核心功能

### 🔍 智能数据查询

- **分页查询**：大结果集自动分页，通过 continuationToken 翻页，避免内存溢出
- **多格式导出**：一键导出 CSV / JSON，方便 AI 解析和下游处理
- **执行计划分析**：获取 SQL 执行计划，帮助 AI 理解查询路径，给出优化建议
- **智能补全**：支持多数据库方言，自动适配不同数据库的语法差异

### 🗄️ 数据库探索

- **Schema 发现**：列出所有表、视图、索引、序列，快速了解数据库结构
- **表结构透视**：查看表的字段定义、类型、注释，辅助 AI 理解数据含义
- **数据库信息**：获取数据库版本、字符集、连接状态等基础信息

### 🛡️ 企业级安全

- **SQL 安全校验**：AST 分析拒绝危险 SQL（DROP、TRUNCATE 等），可配置 Join 数和子查询深度上限
- **PII 数据脱敏**：自动识别并掩码邮箱、手机号、身份证号、银行卡号等敏感字段
- **操作审计追踪**：所有查询和写入操作异步记录审计日志，支持文件 + 数据库双持久化
- **连接权限隔离**：每个 BYOK 连接独立连接池，互不干扰，TTL 到期自动回收

### 🔌 动态连接管理（BYOK）

- **按需连接**：运行时注册任意数据库连接，无需重启服务
- **独立隔离**：每个连接拥有独立 HikariCP 连接池，互相隔离
- **自动续租**：TTL 机制自动续期活跃连接，释放闲置资源
- **连接健康检测**：实时测试连接可用性，失效连接自动剔除

### 🔄 ETL 数据处理

- **多线程批处理**：提交 ETL 任务后异步执行，支持 SQL 读取 / 写入 / 转换 / 导出等步骤
- **UPSERT 操作**：支持 upsert 语义，实现增量数据同步
- **任务状态追踪**：查询任务执行进度和每一步的状态

### 📊 SQL 分析与优化

- **执行计划解读**：标准化输出执行计划，标记潜在性能风险
- **索引推荐**：根据查询模式推荐合适的索引
- **SQL 改写建议**：识别低效查询模式，提供优化建议

### 🧬 数据血缘分析

- **表级血缘追踪**：分析表的上下游依赖关系
- **联邦查询**：跨多个数据库执行联合查询，统一入口访问异构数据源

### 🔄 CDC 变更捕获

- **数据变更订阅**：订阅特定表的增删改事件
- **事件驱动同步**：基于 CDC 事件触发下游数据同步流程

### 📋 数据质量检查

- **规则化校验**：定义数据质量规则（非空、唯一性、范围等），批量检查
- **质量问题报告**：生成质量问题清单，标注严重程度和分布统计

### 💾 备份与恢复

- **表结构备份**：备份表定义，便于版本管理和环境迁移
- **数据快照**：导出表数据快照，支持历史数据回溯

---

## 📦 快速开始

```bash
# 构建
mvn clean package -DskipTests

# 启动（0.4.0 起 /mcp 默认要求 HTTP Basic 鉴权，必须给一个非空密码，否则启动失败）
MCP_SECURITY_ADMIN_PASSWORD='换成你自己的密码' \
  java -jar database-mcp-app/target/database-mcp-server-0.5.2.jar

# 只在本机跑、确实不想要鉴权时（/mcp 将对任何能连上端口的人开放）
java -jar database-mcp-app/target/database-mcp-server-0.5.2.jar \
  --entropy.mcp.security.enabled=false

# 验证（health 与 info 始终匿名可达，供探针使用）
curl http://localhost:8686/actuator/health

# 调 /mcp（鉴权开启时，用户名默认 admin，可用 entropy.mcp.security.admin-username 改）
curl -u admin:'换成你自己的密码' http://localhost:8686/mcp
```

**破坏性变更（0.4.0 引入，沿用至今）**：`entropy.mcp.security.enabled` 默认值由 `false` 改为 `true`。
这个开关的两个方向不对称——开着而没配密码会启动失败（当场就能发现），关着则是 `/mcp`
对任何能连上端口的人开放、且每个已注册的 BYOK 连接（含有 DDL 权限的）都能被打穿（只能靠
读启动日志才发现）。密码只认环境变量 `MCP_SECURITY_ADMIN_PASSWORD` 或
JVM `-Dmcp.security.admin-password`，刻意不支持写进 `application*.yml`，避免被提交进仓库；
空字符串等同于没配，同样会拒绝启动。

服务**不需要**任何数据库环境变量即可启动。目标库有两条接入路径：

**预声明（推荐）**——在 `application.yml` 里声明 `entropy.mcp.database.connections.<名字>`，
密码走 `${ENV_VAR}`。启动时建成 pinned 连接池，进程存活期间一直可用、不会过期：

```yaml
entropy:
  mcp:
    database:
      connections:
        oracle-prod:
          jdbc-url: jdbc:oracle:thin:@//db-host:1521/ORCLPDB
          username: mcp_reader
          password: ${ORACLE_PASSWORD}
          readonly: true      # 拒绝经此连接的写入
          required: true      # 连不上则启动失败
```

两个字段的边界要说清：`readonly: true` 是**本服务这一侧**的拦截（写类工具直接返回 `CON005`），
不代表这个数据库账号真的只有读权限——别把它当成最小权限的替代品。`required: true` 只影响启动，
注册动作发生在 `ApplicationRunner`，所以失败日志出现在 `Started ...Application` **之后**。

⚠️ `listConnections` / `describeConnection` 对 pinned 连接**照样回显** `leaseExpiry` 与
`maxLifetimeExpiry`（创建时刻 +1h / +2h）。这两个字段对它不生效（`LeasedDataSource.pinned`
免除了过期与 max-lifetime），纯属显示遗留——不要据此以为它会到期而去重新注册。

**BYOK**——运行时调 `createNamedConnection` 传 jdbcUrl / username / password，拿到一个会到期的租约池。

**BYOK + 密文凭证**——运行时调 `createSealedConnection`，只传连接名、jdbcUrl 和一串**密文凭证**；
账号与口令封在密文里，服务端用私钥解开。谁都不需要知道口令，模型和对话历史里也只留下一段
没有私钥就还原不了的 base64。用法见下面「密文凭证」。

⚠️ 三条路径的差别不只是方便程度：`createNamedConnection` 的密码是**工具入参**，因此会进入模型上下文、
并留在客户端保存的对话历史里。固定的那几个库请一律用预声明——对话里从头到尾只出现连接名，凭证一步都
不经过模型。需要运行时接入又不想让口令过对话历史，用 `createSealedConnection`。另外不要把凭证拼进
jdbcUrl（`?password=`、`;PWD=`、`user/pw@`）：那样它会同时出现在 `listConnections` /
`describeConnection` / `getPoolStats` 的返回值里。

三条路径下游完全一致——都走同一个 `ByokDataSourceFactory`，因此同样受 SQL 校验、脱敏、审计与
语句超时约束，工具也都按连接名引用。

### 密文凭证

服务端持**私钥**，运维持**公钥**。方向与 Druid `ConfigTools` 相反是有意的：Druid 把解密材料
（公钥）写进配置，密文只防肉眼；而这里的密文要经过模型和对话历史，必须做到「拿到密文也还原不出口令」。

```bash
# 1. 生成密钥对：私钥写进 --out 指定的文件（权限 600，内容就是一行 MCP_CREDENTIAL_PRIVATE_KEY=...），
#    stdout 只打公钥。私钥刻意不走 stdout——它会留在 CI 日志与终端回滚缓冲里，那是这套机制的全部安全性。
#    不给 --out 时默认写当前目录的 mcp-credential-private.key；注入服务端后请删掉该文件。
java -cp database-mcp-server-0.5.2.jar \
     -Dloader.main=com.entropy.database.mcp.cli.CredentialSealCli \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     keygen --out mcp-credential-private.key

# 2. 封装一份凭证；口令从标准输入读，不走命令行参数（ps 可见、会进 shell 历史）
java -cp database-mcp-server-0.5.2.jar \
     -Dloader.main=com.entropy.database.mcp.cli.CredentialSealCli \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     seal --public-key public.key --name orders-prod \
          --url 'jdbc:postgresql://db-host:5432/orders' --username app_ro --ttl 15m
```

服务端配置（`application.yml` 里已经接好占位符，实际部署只需要给环境变量
`MCP_CREDENTIAL_PRIVATE_KEY`；不给就是空串，机制关闭、服务照常起来）：

```yaml
entropy:
  mcp:
    security:
      credential-cipher:
        private-key: ${MCP_CREDENTIAL_PRIVATE_KEY:}  # 只走环境变量，不写进文件
        max-ttl: 15m                                 # 运行时注册允许的最长剩余有效期
        require-sealed-credentials: false            # 改成 true 则 createNamedConnection 直接拒绝明文
```

密文同时绑定**连接名**、**jdbcUrl**（逐字符比对）和**有效期**，三者缺一不可：

- 不绑连接名，任何拿到密文的人都能把它注册成自己的连接名再读数据；
- 不绑 jdbcUrl，攻击者可以把密文指向自己控制的假库，让服务端把解出来的明文口令送过去；
- 不绑有效期，这段 base64 就是一枚永久口令躺在对话历史里。

已知取舍：有效期内同一份密文**可以重复使用**（没有一次性消费——那需要服务端记住用过的密文，
重启即全部作废，而 BYOK 租约到期后调用方本来就需要用同一份凭证重新注册）。默认 15 分钟就是这个
重放窗口。私钥没配时整套机制关闭，`createSealedConnection` 会直接报错并指回 `createNamedConnection`。

服务端配置里的口令也可以写成密文，对应 Druid 的原始场景（配置文件、镜像层、配置中心快照里不留明文）：

```yaml
        oracle-prod:
          jdbc-url: jdbc:oracle:thin:@//db-host:1521/ORCLPDB
          username: mcp_reader
          password: sealed:BASE64...    # 封装时不加 --ttl：配置项不该在某个凌晨自己失效
```

这条路不要求密文带有效期（进程重启必须还能用），但绑定照样强制，且解不开一律启动失败——
即便 `required: false`。

可选：把审计日志持久化到库表时，配置 `spring.datasource.url` / `username` /
`password`（示例见 `application.yml` 顶部注释，密码走 `MCP_AUDIT_DB_PASSWORD`）。
不配置时审计只写文件。

### Docker 部署

```bash
docker build -t database-mcp-server:latest .

docker run -d \
  --name database-mcp \
  -p 8686:8686 \
  -e MCP_SECURITY_ADMIN_PASSWORD='换成你自己的密码' \
  database-mcp-server:latest
```

镜像默认走 `production` profile，鉴权是开着的，不给密码容器会反复重启。
`docker compose` 用法见 `.env.example`。

### 调试与排障

传输是 Streamable HTTP，因此官方 [MCP Inspector](https://github.com/modelcontextprotocol/inspector)
可以直接连过来看工具清单、按 JSON Schema 填参数、逐个试调——不需要给本项目做任何前端：

```bash
# 服务在远端且端口只绑本机时，先开隧道
ssh -L 8686:localhost:8686 <server>

npx @modelcontextprotocol/inspector
# UI 里传输选 Streamable HTTP，地址填 http://localhost:8686/mcp
# 鉴权开着时，把 admin:密码 的 Basic 凭证填进 Authorization 头
```

Inspector 是两个进程：React UI（默认 6274）与 Node 代理 MCPP（默认 6277），浏览器打的是 MCPP、
由它转发到 `/mcp`。**不要把它跑在被测服务器上**：MCPP 有权在本机 spawn 进程（stdio 传输就靠这个），
一旦为了远程访问设了 `HOST=0.0.0.0`，等于在那台机器上开了个远程执行入口。它默认只绑
`localhost`、且启动时生成一次性 session token，就是这个原因。

不带 UI 的最小验证仍然是一条 curl（`Accept` 两个类型都要给，STATELESS 模式下响应可能是 SSE 帧）：

```bash
curl -s -X POST http://localhost:8686/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

#### 工具调用报错的三个常见成因

| 现象 | 判据 | 处置 |
|------|------|------|
| **连接未注册** — `参数 'connection' 未提供` 或 `CONNECTION_NOT_FOUND` | `listConnections` 返回空或不含目标名 | 先 `createNamedConnection`（或 `createSealedConnection`）注册，再 `describeConnection` 确认就绪 |
| **SQL 语法/权限** — `DataAccessException`、`BadSqlGrammarException` | 报错消息带具体 SQL 和数据库方言提示 | 按提示改写 SQL；确认账号权限 |
| **连接到寿** — `Datasource exceeded max lifetime`（`CON003 LEASE_EXPIRED`） | `describeConnection` 返回 status 为 `EXPIRED_LEASE` 或 `EXPIRED_MAX_LIFETIME` | 连接注册过、库也没问题，纯粹是 lease（默认 1h）或 maxLifetime（默认 2h）到期。**不要去查网络**——直接用相同参数重新 `createNamedConnection` 即可恢复。服务端配置的连接（`entropy.mcp.database.connections`）是 pinned 的，不会到寿 |

---

## 🧰 MCP 工具一览

约 130 个工具（默认配置下全部注册）。这里刻意不钉精确数字——工具增删后它就是谎报；准确数量看
启动日志打印的 `MCP tool exposure: X of Y tools registered`，或 `suggestTools` 返回的 `totalTools`。
可通过 `entropy.mcp.tools.plane`
（`all` / `data` / `control`）与 `groups` / `include` / `exclude` 收敛实际暴露的清单，
详见 `application.yml`。不确定该调哪个时，先调 `suggestTools` 用自然语言检索。

所有工具的连接名**入参统一叫 `connection`**。这条是被 `ToolDescriptionContractTest` 钉住的不变量：
形参名就是 JSON schema 的属性名，所以改形参名等于改契约。注意**返回字段**里仍有 `connectionName`
（如 `getPoolStats` 的 `pools[].connectionName`）——那是输出契约，与入参名无关。

一次性把全部工具的描述发给模型会占掉大量上下文，因此提供了三套开箱预设，
按用途只暴露需要的分组（可与 `production` 叠加）：

```bash
--spring.profiles.active=production,tools-explore   # 查询与探索      23 个
--spring.profiles.active=production,tools-dba       # DBA 诊断        31 个
--spring.profiles.active=production,tools-govern    # 目录/血缘/质量  36 个
```

启动日志会打印实际保留的数量与每组明细。`plane: data` 是另一种切法（只留只读工具，约占四分之三），
数据面能查看和校验连接，但 `createNamedConnection` 带 `write` 标签、仍属控制面，纯数据面副本
需要靠 `include: [createNamedConnection]` 穿透，或由控制面副本统一注册。

注意：「ETL 任务」一组共 10 个工具由 `entropy.mcp.gateway.enabled` 控制（默认 `true`），
关掉后这批工具不注册。连接管理的 5 个工具（含 `createNamedConnection`、`createSealedConnection`）无条件注册，
不受该开关影响。

### 查询与导出

| 工具 | 功能 |
|------|------|
| `executeQuery` | 分页执行 SELECT，通过 continuationToken 翻页 |
| `executeQueryWithFilter` | 带过滤条件的查询 |
| `executeSqlTemplate` | 按预置模板执行 SQL |
| `batchQuery` | 批量执行多条查询 |
| `getDatabaseInfo` | 获取数据库版本与配置信息 |
| `exportCsv` | 导出查询结果为 CSV |
| `exportJson` | 导出查询结果为 JSON |
| `explainPlan` | 获取 SQL 执行计划 |
| `assessQueryRisk` | 评估查询风险等级 |

### 数据库探索

| 工具 | 功能 |
|------|------|
| `describe` | 通用对象描述入口 |
| `listSchemas` | 列出所有 Schema |
| `listTables` | 列出指定 Schema 下的所有表 |
| `searchTables` | 按关键字搜索表 |
| `describeTable` | 获取表结构详情 |
| `listIndexes` | 列出表索引信息 |
| `listViews` | 列出所有视图 |
| `listSequences` | 列出所有序列 |
| `suggestTools` | 按自然语言诉求推荐工具 |

⚠️ `listTables` 没有结果上限：真实 Oracle 库上实测一次返回约 1000 张表（含大量 `_BAK`
与日期后缀的历史表），足以撑爆客户端上下文。表多的 Schema 请改用 `searchTables` 按关键词定位。

### DDL 与写入

| 工具 | 功能 |
|------|------|
| `validateDdl` | 校验 DDL 语句而不执行 |
| `executeDdl` | 执行 DDL 语句 |
| `executeDdlBatch` | 批量执行 DDL |
| `executeDdlRemote` | 在远端库执行 DDL |
| `diffSchema` | 比对两个 Schema 的差异 |
| `backupData` | 备份表数据快照 |
| `insertData` | 插入数据 |
| `insertQueryResult` | 把查询结果写入目标表 |
| `upsertData` | UPSERT 操作 |
| `transformAndInsert` | 转换并插入数据 |
| `exportQueryToTable` | 导出查询结果到新表 |

### ETL 任务

| 工具 | 功能 |
|------|------|
| `submitEtlJob` | 提交 ETL 批处理任务 |
| `getJobStatus` | 查询任务执行状态 |
| `listJobs` | 列出任务历史 |
| `stopJob` | 终止运行中的任务 |
| `validateDataQuality` | ETL 链路上的数据质量校验 |

### 连接管理 (BYOK)

| 工具 | 功能 |
|------|------|
| `createNamedConnection` | 注册命名数据源连接 |
| `createSealedConnection` | 用密文凭证注册连接（账号口令不进对话历史） |
| `listConnections` | 列出所有活跃连接 |
| `describeConnection` | 查看单个连接的状态 |
| `getConnectionCount` | 获取连接数统计 |

### 跨库与联邦查询

| 工具 | 功能 |
|------|------|
| `listDatabases` | 列出可访问的数据库 |
| `listRemoteTables` | 列出远端库的表 |
| `describeRemoteTable` | 获取远端表结构 |
| `executeFederatedQuery` | 跨库联邦查询 |
| `executeSelectiveQuery` | 定向路由查询 |
| `queryCrossDatabaseJoin` | 跨库 JOIN |
| `queryComplexCrossDatabaseAnalytics` | 跨库复杂分析查询 |
| `getCrossDatabaseTemplates` | 获取跨库查询模板 |
| `getFederatedDatabaseInfo` | 获取联邦库信息 |
| `getGatewayStatistics` | 网关统计 |
| `createDbLink` / `testDbLink` / `dropDbLink` | DB Link 的建/测/删 |

### SQL 分析与优化

| 工具 | 功能 |
|------|------|
| `analyzeQuery` | 查询深度分析 |
| `analyzeTable` | 表级分析 |
| `recommendIndexes` | 索引推荐 |
| `suggestRewrites` | SQL 改写建议 |
| `interpretPlan` | 解读执行计划 |
| `getOptimizerConfig` | 查看优化器配置 |

### 数据血缘

| 工具 | 功能 |
|------|------|
| `analyzeLineage` | 分析表数据血缘 |
| `getUpstream` / `getDownstream` | 上游 / 下游依赖 |
| `getImpactAnalysis` | 变更影响分析 |
| `listAllEdges` | 列出全部血缘边 |
| `exportMermaid` / `exportDot` | 导出为 Mermaid / DOT |
| `getLineageConfig` | 查看血缘配置 |

### 数据质量

| 工具 | 功能 |
|------|------|
| `checkTableQuality` | 表级数据质量检查 |
| `listQualityRuleTemplates` | 列出质量规则模板 |
| `getQualityAlertSummary` | 质量告警汇总 |

### 数据目录

| 工具 | 功能 |
|------|------|
| `generateCatalog` | 生成数据资产目录 |
| `scanSchema` | 扫描 Schema 元数据 |
| `searchAssets` | 检索数据资产 |
| `classifyColumn` | 字段敏感度分类 |
| `listSensitiveColumns` | 列出敏感字段 |
| `getCatalogConfig` | 查看目录配置 |

### CDC 变更捕获

| 工具 | 功能 |
|------|------|
| `checkCdcSupport` | 检查数据库是否支持 CDC |
| `registerSubscription` | 注册变更订阅 |
| `listSubscriptions` | 列出订阅 |
| `unregisterSubscription` | 注销订阅 |
| `readChanges` | 读取变更事件 |
| `getCurrentLsn` | 获取当前日志位点 |
| `createMirrorTable` | 创建镜像表 |
| `getCdcStatus` / `getCdcConfig` | CDC 状态 / 配置 |

### 备份与恢复

| 工具 | 功能 |
|------|------|
| `backupTable` | 备份单表数据（导出为可回放的 INSERT） |
| `backupSchema` | 备份单表结构（导出建表 DDL，目前仅 Oracle） |
| `restoreBackup` | 回放备份脚本恢复（追加，不清空目标表） |
| `quickRestore` | 整表还原（先清空再回放，只接受完整数据备份） |
| `listBackups` / `getBackup` | 列出 / 查看备份 |
| `deleteBackup` / `cleanupBackups` | 删除 / 清理备份 |
| `getBackupConfig` | 查看备份配置与内存存储上限 |

⚠️ **备份记录只存在进程内存里**，重启即全部丢失，本服务不落盘也不建表。上限由
`entropy.mcp.database.backup.max-records`（默认 200 条）与 `retention-days`（默认 7 天）
控制，超出即静默淘汰，之后 `restoreBackup` / `quickRestore` 会报 `NOT_FOUND`。
需要长期留存请在 `backupTable` 返回时取走 `sqlStatements` 自行落盘。

### DBA 诊断与运维

| 工具 | 功能 |
|------|------|
| `checkHealth` | 数据库健康检查 |
| `listActiveSessions` | 列出活跃会话 |
| `showLocks` / `showBlockingTree` | 锁 / 阻塞树 |
| `killSession` | 终止会话 |
| `showUndoUsage` | Undo 使用量 |
| `listTablespaces` / `listDataFiles` | 表空间 / 数据文件 |
| `estimateTableSize` | 估算表大小 |
| `gatherTableStats` | 收集表统计信息 |
| `showIndexStatus` | 索引状态 |
| `listInvalidObjects` | 列出失效对象 |
| `flashbackQuery` | 闪回查询 |
| `listCurrentPrivileges` / `listGrants` | 当前权限 / 授权明细 |

### 审计与监控

| 工具 | 功能 |
|------|------|
| `getAuditLogs` | 查询审计日志 |
| `getAuditMetrics` | 审计指标 |
| `getSlowQueries` | 慢查询清单 |
| `getSqlPatternStats` | SQL 模式统计 |
| `getDataAccessReport` | 数据访问报告 |
| `getProtectionReport` | 脱敏保护报告 |
| `getPoolStats` / `getPoolStatsForConnection` | 连接池统计 |
| `getMetrics` | 运行指标 |
| `getStatistics` | 系统统计信息 |
| `clearCache` | 清除缓存 |

### 会话与自定义工具

| 工具 | 功能 |
|------|------|
| `sessionStore` / `sessionGet` | 写入 / 读取会话键值 |
| `sessionKeys` / `sessionRemove` / `sessionPurge` | 列出 / 删除 / 清空 |
| `getSessionInfo` | 查看会话作用域信息 |
| `listCustomTools` / `scanCustomTools` / `invokeCustomTool` | 自定义工具的列出 / 扫描 / 调用 |

---

## 🔗 客户端集成

本服务是 **HTTP MCP server**（`spring-ai-starter-mcp-server-webmvc` + `protocol: STATELESS`），
只在 `/mcp` 上提供一个无状态端点，**没有 stdio 传输**。所以客户端要配 URL，不能配
`command: java -jar` 把它当子进程拉起来。

### Claude Desktop

Claude Desktop 只会 stdio，需要用 `mcp-remote` 之类的桥接器把 HTTP 端点转成 stdio：

```json
{
  "mcpServers": {
    "database": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote", "http://localhost:8686/mcp",
        "--header", "Authorization: Basic YWRtaW46eW91cl9hZG1pbl9wYXNzd29yZA=="
      ]
    }
  }
}
```

服务需要另行启动（见「快速开始」）。`Authorization` 是 `admin:密码` 的 Base64；
若把 `entropy.mcp.security.enabled` 关掉则可省略该 header。
无论哪种方式都不需要在客户端配置里放数据库凭据——目标库连接由 AI 在会话中调用
`createNamedConnection` 注册。

### Python 客户端

```python
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client
import asyncio, base64

TOKEN = base64.b64encode(b"admin:your_admin_password").decode()

async def main():
    async with streamablehttp_client(
        "http://localhost:8686/mcp",
        headers={"Authorization": f"Basic {TOKEN}"},
    ) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()
            result = await session.list_tools()
            for tool in result.tools:
                print(f"🔧 {tool.name}: {tool.description}")

asyncio.run(main())
```

---

## 🔒 安全承诺

- **默认要求鉴权**：`/mcp`、`/api/**` 与 health/info 之外的 actuator 端点默认需要
  HTTP Basic（或配了 JWT 时的 Bearer）；没有密码则拒绝启动，不会静默降级成免鉴权
- **最小权限原则**：默认只读账号连接数据库，写入操作需显式授权
- **注入防护**：所有 SQL 经过 AST 分析，拒绝危险模式
- **数据脱敏**：敏感字段自动掩码，不出现在查询结果中
- **全量审计**：每次操作均有迹可查，支持合规审查
- **连接隔离**：BYOK 连接池互相隔离，防止越权访问
- **凭证不回显**：`listConnections` / `describeConnection` / `getPoolStats` / `getDatabaseInfo` 返回的
  JDBC URL 统一经 `JdbcUrlMasker` 处理——userinfo 替换为 `****`，参数走白名单（只保留
  `databaseName`、`currentSchema` 这类不承载凭证的键），因此 `?password=` 与 `user/pw@` 都不会外泄

---

## 📁 项目结构

Maven 多模块，依赖方向单向收敛，由 ArchUnit 在编译期强制：

```
database-mcp-contract/   # 零依赖叶子模块：方言契约
database-mcp-core/       # 配置属性、会话上下文、异常体系
database-mcp-dialect/    # 多方言适配（Oracle / MySQL / PostgreSQL / SQL Server）
database-mcp-infra/      # 数据源、连接池、缓存、审计持久化
database-mcp-features/   # ETL / CDC / 血缘 / 质量 / 备份 / 优化器 / 目录
database-mcp-tools/      # MCP 工具实现与暴露面裁剪
database-mcp-app/        # Spring Boot 启动模块与配置
```

依赖顺序：`contract ← core ← infra ← features ← tools ← app`

---

## 📖 参考文档

- [MCP 协议规范](https://modelcontextprotocol.io)

---

## 📄 许可证

Copyright 2024-2026 Entropy. Licensed under the [Apache License, Version 2.0](LICENSE).
