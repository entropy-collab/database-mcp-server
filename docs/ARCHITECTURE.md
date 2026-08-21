# Database MCP Server Architecture

## Overview

Database MCP Server 是一个基于 Spring Boot 4.1.0 + Spring AI MCP Server (WebMVC) 构建的数据库访问中间件。它通过 Model Context Protocol (MCP) 向 AI 应用提供标准化、安全、可观测的数据库操作能力。

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 4.1.0 |
| MCP Server | Spring AI MCP Server (WebMVC/Stateless) |
| Database Access | JdbcTemplate + NamedParameterJdbcTemplate |
| Connection Pool | HikariCP (BYOK dynamic datasource management) |
| Caching | Caffeine (with TTL lease mechanism) |
| Metrics | Micrometer (Timer, Counter, Gauge) |
| Configuration | Spring @ConfigurationProperties |
| Async Processing | @Async + ConcurrentLinkedQueue |
| Testing | JUnit 5 + Mockito + AssertJ + Testcontainers |

## Core Architecture Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                    MCP Tools Layer (tools/)                     │
│  QueryTools | EtlTools | DdlExecutionTools | ConnectionAdminTools │
│  CacheAdminTools | DatabaseHealthTools | DialectQueryUtils       │
└───────────────────────────────┬─────────────────────────────────┘
                                │ uses
┌───────────────────────────────▼─────────────────────────────────┐
│                    Facade Layer (facade/)                        │
│  DatabaseFacade | RoutingDatabaseFacade | ByokDatabaseFacade    │
└───────────────────────────────┬─────────────────────────────────┘
                                │ delegates to
┌───────────────────────────────▼─────────────────────────────────┐
│                 Repository Layer (repository/)                   │
│  DatabaseReadRepository | DatabaseWriteRepository               │
│  ExecutionPlanRepository | AuditLogRepository                   │
└───────────────────────────────┬─────────────────────────────────┘
                                │ uses
┌───────────────────────────────▼─────────────────────────────────┐
│                   Security & Audit Layer                         │
│  SqlValidator | DataMaskingService | QueryAuditLogger           │
└───────────────────────────────┬─────────────────────────────────┘
                                │ uses
┌───────────────────────────────▼─────────────────────────────────┐
│                    Cache Layer (cache/)                          │
│  DatabaseCache (Caffeine + TTL lease)                           │
└───────────────────────────────┬─────────────────────────────────┘
                                │ uses
┌───────────────────────────────▼─────────────────────────────────┐
│              Dialect Strategy Layer (dialect/)                   │
│  DatabaseDialect interface + AbstractDatabaseDialect base class │
│  MySQL | PostgreSQL | SQL Server | Oracle | DB2 | SQLite | Generic│
└───────────────────────────────┬─────────────────────────────────┘
                                │ configures
┌───────────────────────────────▼─────────────────────────────────┐
│              BYOK & Connection Pool Layer (byok/)                │
│  DynamicDataSourceManager | ConnectionPoolFactory               │
│  LeasedDataSource (TTL-based lifecycle)                         │
└───────────────────────────────┬─────────────────────────────────┘
                                │ manages
┌───────────────────────────────▼─────────────────────────────────┐
│                  Infrastructure Layer                            │
│  JdbcTemplate | DataSource | MetricsCollector | HealthMonitor   │
└─────────────────────────────────────────────────────────────────┘
```

## Package Structure

### `config/` - Spring Configuration
- `DatabaseConfig`: Main bean configuration for MCP server
- `AsyncConfig`: Async processing configuration
- `MultiDataSourceConfig`: Multi-datasource routing
- `DatabaseConstants`: Default value constants (backward compatible)
- **`DatabaseProperties`**: Central configuration record with nested properties:
  - `QueryProperties`: max-rows, timeout, fetch-size, max-export-rows
  - `AuditProperties`: enabled, retention-days, sql-truncate-length
  - `SecurityProperties`: max-joins, max-subquery-depth
  - `EtlProperties`: thread-pool-size, batch-size
  - `CacheProperties`: max-size, query-ttl, metadata-ttl, max-warm-size
  - `ConnectionPoolProperties`: connection-timeout, idle-timeout, max-lifetime
  - `PreparedStatementProperties`: cache-size, cache-sql-limit
  - `MetricsProperties`: slow-query-threshold-ms

### `dialect/` - Database Dialect Strategy
- `DatabaseDialect`: Interface defining dialect-specific behavior
- `AbstractDatabaseDialect`: Base class with shared implementations
- `DialectResolver`: Resolves dialect by name
- `DialectUtils`: Utility methods for dialect operations
- Concrete implementations: `MySqlDialect`, `PostgresDialect`, `SqlServerDialect`, `OracleDialect`, `Db2Dialect`, `SqliteDialect`, `GenericDialect`

### `byok/` - Bring Your Own Key (Dynamic Datasource)
- `DynamicDataSourceManager`: Interface for datasource lifecycle management
- `DynamicDataSourceManagerImpl`: Implementation with Caffeine cache + TTL lease
- `ConnectionPoolFactory`: Creates HikariCP pools per connection
- `ByokInfrastructureFactory`: Creates per-connection infrastructure (JdbcTemplate, audit)
- `LeasedDataSource`: Wraps DataSource with TTL-based lease renewal
- `ByokCleanupTask`: Periodic cleanup of expired connections

### `facade/` - Application Facades
- `DatabaseFacade`: Primary facade orchestrating read/write/backup operations
- `RoutingDatabaseFacade`: Routes operations to primary or BYOK datasources
- `ByokDatabaseFacade`: Specialized facade for BYOK connections
- `FacadeDependencies`: Record encapsulating all facade dependencies

### `repository/` - Data Access Layer
- `DatabaseReadRepository`: Read operations with caching, masking, limits
- `DatabaseWriteRepository`: Write operations with SQL validation
- `ExecutionPlanRepository`: Query execution plan analysis
- `AuditLogRepository`: Async audit log persistence via JdbcTemplate

### `security/` - Security & Audit
- `SqlValidator`: SQL validation with configurable limits (max-rows, max-joins, max-subquery-depth)
- `SqlValidatorImpl`: Implementation using regex and AST analysis
- `DataMaskingService`: PII data masking (email, phone, credit card, etc.)
- `QueryAuditLogger`: Async audit logging with file + database persistence
- `SqlValidationAspect`: AOP aspect for automatic SQL validation

### `monitor/` - Observability
- `McpMetricsCollector`: Micrometer metrics collection interface
- `McpMetricsCollectorImpl`: Implementation with Timer, Counter, Gauge
- `DatabaseHealthMonitor`: Health check aggregation
- `ConnectionPoolMonitor`: HikariCP pool metrics
- Metrics tracked: query duration, slow queries, cache hit/miss, BYOK connections, ETL jobs

### `etl/` - ETL Processing
- `JobExecutionEngine`: Multi-threaded ETL job execution
- `JobExecution`: Job execution record
- `MigrationJob`: ETL job definition
- `Step`: Individual ETL step
- Async execution with configurable thread pool

### `cache/` - Caching Layer
- `DatabaseCache`: Cache interface for query results and metadata
- `DatabaseCacheImpl`: Caffeine-based implementation with TTL
- Two-level cache: query results (small TTL) and metadata (larger TTL)

### `tools/` - MCP Tool Implementations
- `QueryTools`: executeQuery, listTables, describeTable, etc.
- `EtlTools`: submitEtlJob, getJobStatus
- `DdlExecutionTools`: executeDdl, validateDdl
- `ConnectionAdminTools`: registerConnection, listConnections
- `CacheAdminTools`: clearCache, warmCache
- `DatabaseHealthTools`: getHealthStatus, getMetrics
- `DialectQueryUtils`: Dialect-specific query utilities

### `aop/` - Cross-Cutting Concerns
- `PerformanceTimingAspect`: Automatic method execution timing
- `SqlValidationAspect`: Automatic SQL validation on tool methods
- `McpToolExceptionAspect`: Exception handling and logging

### `audit/` - Audit Log Persistence
- `AuditLogEntity`: Audit log record
- `AuditLogRepository`: JdbcTemplate-based persistence
- `AuditLogInitializer`: Auto-creates audit_log table on startup

### `exception/` - Exception Hierarchy
- `ErrorCode`: Enumeration of error codes
- `DatabaseMcpException`: Unified runtime exception
- `SqlValidationException`: SQL validation failures

### `domain/` - Domain Objects
- `PaginatedQueryResult`: Paginated query results with continuation token
- `PlanAnalysis`: Query execution plan analysis
- `PlanProperty`: Individual plan property
- `StandardizedPlan`: Normalized execution plan

## Key Design Patterns

### 1. Strategy Pattern (Dialect)
Each database dialect implements `DatabaseDialect` interface, allowing:
- Dialect-specific SQL generation
- Dialect-specific connection pool configuration
- Runtime dialect resolution via `DialectResolver`

### 2. Facade Pattern
`DatabaseFacade` provides simplified API for tool implementations, encapsulating:
- Read/write operation coordination
- Caching integration
- Metrics collection
- Audit logging

### 3. Dynamic Datasource Management (BYOK)
- `DynamicDataSourceManager` manages multiple datasources dynamically
- Each BYOK connection gets its own HikariCP pool
- TTL-based lease renewal via `LeasedDataSource`
- Caffeine cache with removal listener for lifecycle management
- Metrics collection for connection creation/removal

### 4. Configuration Externalization
- `DatabaseProperties` record with nested records for type-safe configuration
- Default value clamping in compact constructors
- `DatabaseConstants` retained for backward compatibility
- `application.yml` exposes all configurable parameters

### 5. Async Audit Logging
- `@Async` annotation for non-blocking audit log persistence
- `ConcurrentLinkedQueue` for queueing audit events
- Fallback to file logging if database persistence fails
- Configurable retention and truncation

### 6. AOP Cross-Cutting Concerns
- `PerformanceTimingAspect`: Automatic performance metrics
- `SqlValidationAspect`: Automatic SQL validation before execution
- `McpToolExceptionAspect`: Centralized exception handling

## Data Flow

### Query Execution Flow
```
MCP Client → MCP Server → QueryTools.executeQuery()
    ↓
SqlValidationAspect (validate SQL)
    ↓
DatabaseFacade.executeQuery()
    ↓
DatabaseCache.get() (check cache)
    ↓
DatabaseReadRepository.executeQuery()
    ↓
SqlValidator.validate() (enforce limits)
    ↓
JdbcTemplate.query() (execute SQL)
    ↓
DataMaskingService.mask() (mask PII)
    ↓
DatabaseCache.put() (cache result)
    ↓
QueryAuditLogger.log() (async audit)
    ↓
McpMetricsCollector.recordQuery() (metrics)
    ↓
Return PaginatedQueryResult to client
```

### BYOK Connection Flow
```
Client provides ConnectionProperties
    ↓
DynamicDataSourceManager.acquire(key, connection)
    ↓
Check Caffeine cache for existing connection
    ↓
If not found:
    ↓
    DialectResolver.resolve(dialect)
    ↓
    ConnectionPoolFactory.createDataSource(connection, dialect)
    ↓
    ByokInfrastructureFactory.create(key, jdbcTemplate, dialect)
    ↓
    Create LeasedDataSource with TTL
    ↓
    Cache in Caffeine with removal listener
    ↓
    MetricsCollector.recordByokConnectionCreated()
    ↓
Return ByokDataSourceContext
```

### ETL Job Execution Flow
```
EtlTools.submitEtlJob()
    ↓
JobExecutionEngine.submit(job)
    ↓
MetricsCollector.recordEtlJobSubmitted()
    ↓
ExecutorService.submit(task)
    ↓
For each step:
    ↓
    DynamicDataSourceManager.acquire(connectionKey)
    ↓
    Execute step (SQL/COPY/SCRIPT)
    ↓
    Record step execution state
    ↓
MetricsCollector.recordEtlJobCompleted(success)
    ↓
Return JobExecution to client
```

## Configuration Management

### Externalized Configuration
All configuration is externalized via `DatabaseProperties`:

```yaml
entropy:
  mcp:
    database:
      dialect: mysql
      query:
        max-rows: 100
        timeout-seconds: 30
        max-result-rows: 10000
        max-export-rows: 500
        fetch-size: 100
      audit:
        enabled: true
        retention-days: 7
        sql-truncate-length: 200
        entry-sql-truncate-length: 500
      security:
        max-joins: 10
        max-subquery-depth: 5
      etl:
        thread-pool-size: 4
        batch-size: 1000
      cache:
        max-size: 1000
        query-ttl: 30s
        metadata-ttl: 5m
        max-warm-size: 10
      connection-pool:
        connection-timeout: 30s
        idle-timeout: 10m
        max-lifetime: 30m
      prepared-statement:
        cache-size: 250
        cache-sql-limit: 2048
      metrics:
        slow-query-threshold-ms: 5000
```

### Configuration Validation
- `DatabaseProperties` uses Java record compact constructors for default value clamping
- Invalid configurations are corrected automatically (e.g., negative values → defaults)
- `@ConfigurationProperties` enables Spring Boot configuration binding

## Observability

### Metrics (Micrometer)
- **Query Metrics**: execution count, duration (Timer), slow query count (Counter)
- **Cache Metrics**: hit count, miss count, eviction count (Counter)
- **BYOK Metrics**: connection created/removed count (Counter)
- **ETL Metrics**: job submitted, success, failure count (Counter), success rate (Gauge)
- **Connection Pool**: active, idle, total connections (Gauge)

### Health Monitoring
- `DatabaseHealthMonitor`: Aggregates datasource health status
- `ConnectionPoolMonitor`: HikariCP pool metrics
- Exposed via Spring Boot Actuator `/actuator/health`

### Audit Logging
- Async audit logging via `@Async` annotation
- Dual persistence: file-based + database (`audit_log` table)
- Configurable retention and truncation
- Tracks: tool name, SQL, rows affected, duration, success/failure, connection key

## Testing Strategy

- **Unit Tests**: 154+ tests with Mockito mocking
- **Integration Tests**: Spring Boot test context with H2
- **End-to-End Tests**: Full MCP protocol flow testing
- **Test Coverage**: BYOK lifecycle, ETL execution, dialect operations, security validation

## Security Considerations

1. **SQL Injection Prevention**: `SqlValidator` enforces SQL validation
2. **Query Limits**: Configurable max-rows, max-result-rows, max-joins, max-subquery-depth
3. **Data Masking**: PII masking for email, phone, credit card, SSN, etc.
4. **Connection Isolation**: Each BYOK connection gets isolated HikariCP pool
5. **Audit Trail**: All operations logged for compliance

## Performance Optimizations

1. **Caffeine Cache**: High-performance caching with TTL
2. **Connection Pooling**: HikariCP for optimal connection management
3. **Prepared Statement Caching**: Configurable cache size and SQL limit
4. **Fetch Size Optimization**: Configurable fetch size for large result sets
5. **Async Processing**: Non-blocking audit logging and ETL execution
6. **AOP Timing**: Minimal overhead performance monitoring

## Extension Points

1. **New Database Dialect**: Implement `DatabaseDialect` interface
2. **Custom Data Masking**: Extend `DataMaskingService`
3. **Additional Metrics**: Extend `McpMetricsCollector`
4. **Custom ETL Steps**: Extend `Step` hierarchy
5. **New MCP Tools**: Add to `tools/` package
