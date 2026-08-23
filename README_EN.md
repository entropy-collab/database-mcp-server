# Database MCP Server

A Java-based Model Context Protocol (MCP) server for secure database query and management, built on Spring Boot and Spring AI 2.0.

## Features

- **Multi-Database Support**: Oracle, MySQL, PostgreSQL, SQL Server, SQLite, DB2, H2, and Generic JDBC
- **BYOK (Bring Your Own Key)**: Secure connection management with lease-based pooling
- **Cross-Database Federation**: Query across multiple databases in a single operation
- **SQL Audit**: Complete execution logging with compliance reporting
- **Data Quality**: Built-in quality checks with custom rule support
- **Change Data Capture**: Real-time data synchronization with CDC support
- **Lineage Tracking**: Data lineage analysis and impact assessment
- **Query Optimization**: AI-powered SQL optimization recommendations
- **Streaming**: SSE (Server-Sent Events) streaming for large result sets
- **Security**: SQL injection prevention, data masking, and RBAC

## Quick Start

### Docker (Recommended)

```bash
docker run -d \
  --name database-mcp \
  -p 8686:8686 \
  -e DB_HOST=localhost:1521 \
  -e DB_NAME=ORCLPDB \
  -e DB_USERNAME=example_user \
  -e DB_PASSWORD=your_password_here \
  entropy/database-mcp:latest
```

### Environment Variables

```bash
# Core Configuration
SERVER_PORT=8686
SERVER_CONTEXT_PATH=/mcp
APP_VERSION=1.0.0

# Database Connection
DB_HOST=localhost:1521
DB_NAME=ORCLPDB
DB_USERNAME=example_user
DB_PASSWORD=your_password_here

# JDBC URL Override
DB_JDBC_URL=jdbc:oracle:thin:@//localhost:1521/ORCLPDB

# Security
SECURITY_SQL_VALIDATION_ENABLED=true
SECURITY_MAX_RESULTS=10000
SECURITY_OPERATIONS_ALLOW=DQL
SECURITY_OPERATIONS_DENY=DDL,DML
```

## MCP Tools

### Query Tools
- `execute_query` - Execute parameterized SQL queries
- `batch_insert` - Batch insert records efficiently
- `execute_ddl` - Execute DDL statements (CREATE, ALTER, DROP)

### Administration Tools
- `get_table_structure` - Get table schema and constraints
- `list_tables` - List all tables with pagination
- `get_connection_pool_stats` - Monitor connection pool metrics

### Cross-Database Tools
- `federated_query` - Query across multiple databases
- `cross_database_join` - Join tables from different databases
- `data_migration` - Migrate data between databases

### ETL Tools
- `run_etl_job` - Execute ETL workflow
- `get_etl_status` - Check job execution status
- `list_etl_jobs` - List all ETL jobs

### Backup Tools
- `create_backup` - Create database backup
- `restore_backup` - Restore from backup
- `list_backups` - List available backups

### Quality Tools
- `run_quality_check` - Execute data quality checks
- `get_quality_report` - Get quality assessment report
- `configure_quality_rule` - Add custom quality rules

### Lineage Tools
- `analyze_lineage` - Trace data lineage
- `get_impact_analysis` - Assess impact of schema changes
- `export_lineage` - Export lineage to Mermaid/DOT format

### Optimization Tools
- `analyze_query_performance` - Analyze query performance
- `get_index_recommendations` - Get index optimization suggestions
- `rewrite_sql` - Get SQL rewrite suggestions

## Architecture

```
src/main/java/com/entropy/database/mcp/
├── DatabaseMcpApplication.java    # Main entry point
├── aop/                           # Aspect-Oriented Programming
│   ├── ConnectionArgExtractor.java
│   ├── McpToolExceptionAspect.java
│   ├── PerformanceTimingAspect.java
│   └── SqlValidationAspect.java
├── byok/                          # Bring Your Own Key module
│   ├── DynamicDataSourceManager.java
│   ├── LeasedDataSource.java
│   └── ByokDataSourceFactory.java
├── backup/                        # Database backup management
├── catalog/                       # Data catalog and metadata
├── cdc/                           # Change data capture
├── config/                        # Spring configuration
├── dialect/                       # Database dialect support
├── exception/                     # Exception hierarchy
├── extension/                     # Custom tool extension
├── gateway/                       # Federated query gateway
├── lineage/                       # Data lineage tracking
├── monitor/                       # Connection pool monitoring
├── optimizer/                     # SQL optimization engine
├── properties/                    # Configuration properties
├── quality/                       # Data quality checks
├── repository/                    # Data access layer
├── security/                      # Security features
├── service/                       # Business services
├── session/                       # Multi-session management
├── stream/                        # SSE streaming
├── tools/                         # MCP tool implementations
└── util/                          # Utility classes
```

## Security

### SQL Validation
All queries are validated using JSqlParser to prevent SQL injection. The system enforces:
- Operation whitelisting (DQL/DDL/DML)
- Table name validation
- Parameterized query enforcement
- Max results limiting

### Data Masking
Sensitive data can be automatically masked based on configurable rules.

### Audit Logging
Complete execution audit trail with:
- Query timestamp
- Executing user
- Source IP
- Execution time
- Result count
- Row-level changes

## Development

### Build

```bash
mvn clean package -DskipTests
```

### Run Tests

```bash
mvn test
```

### Docker Build

```bash
docker build -t database-mcp .
docker compose up -d
```

## Configuration

See [application.yml](src/main/resources/application.yml) for full configuration options.

### Multi-Environment Profiles

- `dev` - Development environment
- `test` - Testing environment
- `prod` - Production environment

### Database Properties

```yaml
spring:
  datasource:
    primary:
      jdbc-url: ${DB_JDBC_URL:jdbc:oracle:thin:@//${DB_HOST}/${DB_NAME}}
      username: ${DB_USERNAME}
      password: ${DB_PASSWORD}
      driver-class-name: ${DB_DRIVER:oracle.jdbc.OracleDriver}
```

## API Reference

### Health Check

```http
GET /actuator/health
```

### MCP Endpoint

```http
POST /mcp/message
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "execute_query",
    "arguments": {
      "query": "SELECT * FROM users WHERE id = ?",
      "params": [1]
    }
  },
  "id": 1
}
```

## License

Apache License 2.0

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## Support

- GitHub Issues: Report bugs and feature requests
- Documentation: See [docs/](docs/) for detailed guides
