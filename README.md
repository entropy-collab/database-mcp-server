# Database MCP Server

基于 Spring AI 2.0 的数据库 MCP (Model Context Protocol) 服务器，提供安全的数据库查询和管理功能。

## 环境要求

- Java 17+
- Maven 3.8+
- Oracle / MySQL / PostgreSQL 数据库

## 快速开始

```bash
# 构建
mvn clean package -DskipTests

# 运行
java -jar target/database-mcp-server-0.1.0-SNAPSHOT.jar

# 验证
curl http://localhost:8686/actuator/health
```

## 配置

### 数据库连接

默认连接 Oracle，通过环境变量覆盖凭据：

```yaml
spring:
  datasource:
    primary:
      jdbc-url: jdbc:oracle:thin:@//${DB_HOST}/${DB_NAME}
      username: ${DB_PRIMARY_USER}
      password: ${DB_PRIMARY_PASSWORD}
      maximum-pool-size: 10
```

### MCP 服务器

```yaml
spring:
  ai:
    mcp:
      server:
        name: database-mcp-server
        version: 0.1.0
        protocol: STATELESS
```

### 安全与缓存

```yaml
entropy:
  mcp:
    database:
      dialect: oracle          # oracle | mysql | postgres | generic
      query:
        max-rows: 1000         # 单次查询最大返回行数
        max-result-rows: 10000 # 熔断阈值
      security:
        enabled: true
        allowed-tables: []     # 白名单表，空则不限
      ddl:
        allowed: false
      cache:
        max-size: 1000
        expire-after-access: 5m
```

## Docker 部署

### 构建镜像

```bash
docker build -t database-mcp-server:latest .
```

### 运行容器

```bash
docker run -d \
  --name database-mcp \
  -p 8686:8686 \
  -e DB_PRIMARY_USER=your_username_here \
  -e DB_PRIMARY_PASSWORD=your_password_here \
  database-mcp-server:latest
```

Dockerfile 采用多阶段构建（eclipse-temurin:17-jdk 编译 → eclipse-temurin:17-jre 运行），内置健康检查，非 root 用户运行。

## MCP 客户端集成

### Claude Desktop

```json
{
  "mcpServers": {
    "database": {
      "command": "java",
      "args": ["-jar", "/path/to/database-mcp-server-0.1.0-SNAPSHOT.jar"],
      "env": {
        "DB_PRIMARY_USER": "your_username_here",
        "DB_PRIMARY_PASSWORD": "your_password_here"
      }
    }
  }
}
```

### Python

```python
from mcp import ClientSession, StdioServerParameters
import asyncio

async def main():
    server_params = StdioServerParameters(
        command="java",
        args=["-jar", "database-mcp-server-0.1.0-SNAPSHOT.jar"],
        env={"DB_PRIMARY_USER": "your_username_here", "DB_PRIMARY_PASSWORD": "your_password_here"}
    )
    async with ClientSession(server_params) as session:
        tools = await session.list_tools()
        print(f"Available tools: {[t.name for t in tools]}")
        result = await session.call_tool("listTables", {"schema": "EXAMPLE_SCHEMA"})
        print(result)

asyncio.run(main())
```

## 可用工具

### 元数据查询

| 工具 | 描述 | 参数 |
|------|------|------|
| `listTables` | 列出所有表 | schema |
| `listSchemas` | 列出所有 Schema | 无 |
| `describeTable` | 描述表结构 | table, schema |
| `listIndexes` | 列出索引 | table, schema |
| `listViews` | 列出视图 | schema |
| `listSequences` | 列出序列 | schema |
| `getDatabaseInfo` | 获取数据库信息 | 无 |

### 数据查询与导出

| 工具 | 描述 | 参数 |
|------|------|------|
| `executeQuery` | 执行 SELECT 查询 | sql, maxRows, continuationToken |
| `exportCsv` | 导出 CSV | sql, maxRows |
| `exportJson` | 导出 JSON | sql, maxRows |
| `explainPlan` | 分析执行计划 | sql |

### 写操作

| 工具 | 描述 | 参数 |
|------|------|------|
| `executeDdl` | 执行 DDL | sql |
| `executeInsert` | 执行 INSERT | sql |
| `executeUpdate` | 执行 UPDATE | sql |
| `executeDelete` | 执行 DELETE | sql |

### 管理

| 工具 | 描述 | 参数 |
|------|------|------|
| `clearCache` | 清除缓存 | 无 |
| `getStatistics` | 获取统计信息 | 无 |
| `backupSchema` | 备份表结构 | tableName |
| `backupData` | 备份数据 | tableName, maxRows |

## 故障排查

```bash
# 健康检查
curl http://localhost:8686/actuator/health

# 查看已注册 Bean
curl http://localhost:8686/actuator/beans

# 查看环境变量
curl http://localhost:8686/actuator/env

# 查看已注册端点
curl http://localhost:8686/actuator/mappings
```

## 项目结构

```
src/main/java/com/entropy/database/mcp/
├── config/          — 配置类
├── controller/      — REST 控制器
├── cache/           — Caffeine 缓存
├── dialect/         — 方言适配 (Oracle/MySQL/PostgreSQL/Generic)
├── domain/          — 领域模型
├── exception/       — 异常体系
├── facade/          — 统一门面
├── format/          — 结果格式化
├── gateway/         — 多源网关
├── init/            — 初始化
├── monitor/         — 监控 (指标、健康检查、连接池)
├── properties/      — 配置属性
├── repository/      — 数据访问层
├── security/        — 安全 (SQL校验、脱敏、审计)
├── service/         — 业务服务
├── stream/          — SSE 流式推送
├── tools/           — MCP 工具 (admin/read/write)
└── util/            — 公共工具
```

## 许可证

Copyright 2024-2026 Entropy. Licensed under the Apache License, Version 2.0.
