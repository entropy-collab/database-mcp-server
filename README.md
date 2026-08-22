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

# 启动
java -jar target/database-mcp-server-0.1.0-SNAPSHOT.jar

# 验证
curl http://localhost:8686/actuator/health
```

### Docker 部署

```bash
docker build -t database-mcp-server:latest .

docker run -d \
  --name database-mcp \
  -p 8686:8686 \
  -e DB_HOST=db.example.com:1521 \
  -e DB_NAME=ORCL \
  -e DB_PRIMARY_USER=user \
  -e DB_PRIMARY_PASSWORD=password \
  database-mcp-server:latest
```

---

## 🧰 MCP 工具一览

### 数据查询

| 工具 | 功能 |
|------|------|
| `executeQuery` | 分页执行 SELECT 查询 |
| `exportCsv` | 导出查询结果为 CSV |
| `exportJson` | 导出查询结果为 JSON |
| `explainPlan` | 获取 SQL 执行计划分析 |

### 数据库探索

| 工具 | 功能 |
|------|------|
| `listTables` | 列出指定 Schema 下的所有表 |
| `describeTable` | 获取表结构详情 |
| `listIndexes` | 列出表索引信息 |
| `listViews` | 列出所有视图 |
| `listSchemas` | 列出所有 Schema |
| `getDatabaseInfo` | 获取数据库版本与配置信息 |

### 写入操作

| 工具 | 功能 |
|------|------|
| `executeDdl` | 执行 DDL 语句 |
| `executeInsert` | 执行 INSERT |
| `executeUpdate` | 执行 UPDATE |
| `executeDelete` | 执行 DELETE |

### ETL 与数据迁移

| 工具 | 功能 |
|------|------|
| `submitEtlJob` | 提交 ETL 批处理任务 |
| `getJobStatus` | 查询任务执行状态 |
| `transformAndInsert` | 转换并插入数据 |
| `upsertData` | UPSERT 操作 |

### 动态连接管理 (BYOK)

| 工具 | 功能 |
|------|------|
| `registerConnection` | 注册动态数据源连接 |
| `listConnections` | 列出所有活跃连接 |
| `testDbLink` | 测试连接可用性 |
| `dropDbLink` | 移除数据源连接 |

### SQL 分析与优化

| 工具 | 功能 |
|------|------|
| `optimizeQuery` | SQL 优化建议 |
| `getQueryAnalysis` | 查询深度分析 |

### 数据血缘与联邦查询

| 工具 | 功能 |
|------|------|
| `analyzeLineage` | 分析表数据血缘 |
| `executeFederatedQuery` | 跨库联邦查询 |
| `getCrossDatabaseTemplates` | 获取跨库查询模板 |

### 数据质量

| 工具 | 功能 |
|------|------|
| `checkQuality` | 数据质量检查 |
| `getQualityReport` | 获取质量报告 |

### CDC 变更捕获

| 工具 | 功能 |
|------|------|
| `subscribeCdc` | 订阅数据变更事件 |
| `getCdcStatus` | 查询 CDC 订阅状态 |

### 数据目录

| 工具 | 功能 |
|------|------|
| `searchTables` | 搜索表元数据 |
| `getCatalogInfo` | 获取数据目录信息 |

### 备份与运维

| 工具 | 功能 |
|------|------|
| `backupSchema` | 备份表结构定义 |
| `backupData` | 备份表数据快照 |
| `clearCache` | 清除缓存 |
| `getStatistics` | 获取系统统计信息 |
| `getHealthStatus` | 获取服务健康状态 |
| `listJobs` | 列出 ETL 任务历史 |
| `showPoolStats` | 查看连接池统计 |

---

## 🔗 客户端集成

### Claude Desktop

```json
{
  "mcpServers": {
    "database": {
      "command": "java",
      "args": ["-jar", "/path/to/database-mcp-server-0.1.0-SNAPSHOT.jar"],
      "env": {
        "DB_PRIMARY_USER": "${DB_PRIMARY_USER}",
        "DB_PRIMARY_PASSWORD": "${DB_PRIMARY_PASSWORD}",
        "DB_HOST": "${DB_HOST}",
        "DB_NAME": "${DB_NAME}"
      }
    }
  }
}
```

### Python 客户端

```python
from mcp import ClientSession, StdioServerParameters
import asyncio

async def main():
    server_params = StdioServerParameters(
        command="java",
        args=["-jar", "database-mcp-server-0.1.0-SNAPSHOT.jar"],
        env={
            "DB_PRIMARY_USER": "your_user",
            "DB_PRIMARY_PASSWORD": "your_password",
            "DB_HOST": "localhost:1521",
            "DB_NAME": "ORCL"
        }
    )
    async with ClientSession(server_params) as session:
        await session.initialize()
        tools = await session.list_tools()
        for tool in tools:
            print(f"🔧 {tool.name}: {tool.description}")

asyncio.run(main())
```

---

## 🔒 安全承诺

- **最小权限原则**：默认只读账号连接数据库，写入操作需显式授权
- **注入防护**：所有 SQL 经过 AST 分析，拒绝危险模式
- **数据脱敏**：敏感字段自动掩码，不出现在查询结果中
- **全量审计**：每次操作均有迹可查，支持合规审查
- **连接隔离**：BYOK 连接池互相隔离，防止越权访问

---

## 📁 项目结构

```
src/main/java/com/entropy/database/mcp/
├── tools/           # MCP 工具实现
├── facade/          # 统一门面层
├── security/        # SQL 校验 / 数据脱敏 / 审计
├── dialect/         # 多方言适配
├── byok/            # 动态连接池管理
├── cache/           # 缓存层
├── etl/             # ETL 引擎
├── cdc/             # CDC 变更捕获
├── lineage/         # 数据血缘分析
├── optimizer/       # SQL 优化建议
├── quality/         # 数据质量检查
├── catalog/         # 数据资产目录
├── backup/          # 备份与恢复
├── monitor/         # 监控与指标
└── audit/           # 审计日志持久化
```

---

## 📖 参考文档

- [架构设计文档](docs/ARCHITECTURE.md)
- [CentOS Docker 部署指南](docs/deploy/centos8-docker-compose-deployment.md)
- [MCP 协议规范](https://modelcontextprotocol.io)

---

## 📄 许可证

Copyright 2024-2026 Entropy. Licensed under the [Apache License, Version 2.0](LICENSE).
