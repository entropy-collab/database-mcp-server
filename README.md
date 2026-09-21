# Database MCP Server

A Java-based Model Context Protocol (MCP) server for secure database query and management, built on Spring Boot 4.1 and Spring AI 2.0.

<p align="center">
  <a href="#"><img src="https://img.shields.io/badge/Java-25-brightgreen.svg" alt="Java 25"></a>
  <a href="#"><img src="https://img.shields.io/badge/Spring%20Boot-4.1.0-blue.svg" alt="Spring Boot 4.1"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg" alt="License"></a>
</p>

**English** · [简体中文](README_ZH.md)

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

### Docker

```bash
docker build -t database-mcp-server:latest .

docker run -d \
  --name database-mcp \
  -p 8686:8686 \
  -e MCP_SECURITY_ADMIN_PASSWORD='replace-with-your-own-password' \
  database-mcp-server:latest
```

The image runs the `production` profile with authentication on, so the container restart-loops
if no password is supplied. See `.env.example` for the `docker compose` variables.

No database environment variables are needed to start. Target databases are registered
at runtime through the `createNamedConnection` tool (BYOK), so credentials never live in
a config file. Register a connection, confirm it with `describeConnection`, then query.

### Environment Variables

```bash
# Server port
SERVER_PORT=8686

# Spring profile — application-production.yml / application-test.yml
SPRING_PROFILES_ACTIVE=production

# JVM options
JAVA_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC

# Required: HTTP Basic password for /mcp (username defaults to admin).
# entropy.mcp.security.enabled defaults to true since 0.4.0, so without a non-blank
# value here the context fails to start. Deliberate: better not to boot than to serve
# /mcp to anyone who can reach the port. An empty value counts as unset.
# Only a real environment variable or -Dmcp.security.admin-password is read — this
# cannot be put in application*.yml, so it never lands in version control.
MCP_SECURITY_ADMIN_PASSWORD=your_admin_password

# Optional: persist the audit log to a database table.
# Without this, audit records are written to file only.
MCP_AUDIT_DB_PASSWORD=your_audit_db_password
```

Tool exposure and security are configured through `entropy.mcp.*` properties in
`application.yml`, not environment variables — see
`entropy.mcp.tools.plane`, `entropy.mcp.security.*` and
`entropy.mcp.database.ddl.allowed`. The one exception is the admin password above:
it is read from the environment only, on purpose.

To run without authentication (local development only — `/mcp` can execute queries,
DDL and ETL writes against every registered BYOK connection):

```bash
java -jar app.jar --entropy.mcp.security.enabled=false
```

### Debugging

The transport is Streamable HTTP, so the official
[MCP Inspector](https://github.com/modelcontextprotocol/inspector) connects directly — no
custom UI needed in this project:

```bash
# When the port is bound to the server's loopback only, tunnel first
ssh -L 8686:localhost:8686 <server>

npx @modelcontextprotocol/inspector
# In the UI pick "Streamable HTTP" and enter http://localhost:8686/mcp
# With authentication on, put the admin Basic credentials in the Authorization header
```

Inspector is two processes: the React UI (port 6274) and the Node proxy MCPP (port 6277);
the browser talks to MCPP, which forwards to `/mcp`. **Do not run it on the server under
test** — MCPP is allowed to spawn local processes (that is how stdio transport works), so
setting `HOST=0.0.0.0` to reach it remotely opens a remote-execution path on that host.
That is why it binds to `localhost` and mints a one-time session token by default.

The minimal UI-free check is a single curl (send both `Accept` types — in STATELESS mode
the response may be an SSE frame):

```bash
curl -s -X POST http://localhost:8686/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

#### Three common causes of tool-call errors

| Symptom | Diagnosis | Resolution |
|---------|-----------|------------|
| **Connection not registered** — `参数 'connection' 未提供` or `CONNECTION_NOT_FOUND` | `listConnections` returns empty or missing the target name | `createNamedConnection` (or `createSealedConnection`) first, then `describeConnection` to confirm it is ready |
| **SQL syntax / permissions** — `DataAccessException`, `BadSqlGrammarException` | Error message includes the offending SQL and a dialect hint | Rewrite the SQL for the target dialect; check account privileges |
| **Connection expired** — `Datasource exceeded max lifetime` (`CON003 LEASE_EXPIRED`) | `describeConnection` returns status `EXPIRED_LEASE` or `EXPIRED_MAX_LIFETIME` | The connection was registered and the database is fine — the lease (default 1 h) or maxLifetime (default 2 h) simply elapsed. **Do not troubleshoot the network** — re-run `createNamedConnection` with the same parameters to restore it. Server-configured connections (`entropy.mcp.database.connections`) are pinned and never expire |

## MCP Tools

Roughly 120 tools, all registered under the default configuration. The exact count is
deliberately not pinned here — it goes stale the moment a tool is added or removed. Read it off
the startup log (`MCP tool exposure: X of Y tools registered`) or from the `totalTools` field
returned by `suggestTools`. Use
`entropy.mcp.tools.plane` (`all` / `data` / `control`) plus `groups` / `include` /
`exclude` to narrow the exposed set — see `application.yml`. Call `suggestTools` with a
natural-language intent when unsure which tool to use.

Every tool names its connection **input** `connection`. This is pinned by
`ToolDescriptionContractTest`: the Java parameter name *is* the JSON schema property name, so
renaming a parameter is a contract change. Note that **output** fields still use
`connectionName` (e.g. `getPoolStats` → `pools[].connectionName`) — that is the response
contract and unrelated to the input name.

Sending every tool description to the model consumes a large amount of context, so
three ready-made presets expose only the groups a given role needs (composable with the
`production` profile):

```bash
--spring.profiles.active=production,tools-explore   # query and exploration   23 tools
--spring.profiles.active=production,tools-dba       # DBA diagnostics         31 tools
--spring.profiles.active=production,tools-govern    # catalog/lineage/quality 36 tools
```

The startup log prints the retained count and a per-group breakdown. `plane: data` is an
alternative cut (read-only tools only, about three quarters of the catalogue): the data plane can
list and verify connections, but
`createNamedConnection` carries the `write` tag and stays on the control plane, so a pure
data-plane replica needs `include: [createNamedConnection]` to punch through — or lets a
control-plane replica do the registering.

Note: the 10 tools under "ETL Jobs" are gated by `entropy.mcp.gateway.enabled`
(default `true`) and are not registered when it is off. The five connection-management
tools, including `createNamedConnection` and `createSealedConnection`, are registered unconditionally.

### Query & Export
- `executeQuery` - Paginated SELECT with continuationToken
- `executeQueryWithFilter` - Query with filter conditions
- `executeSqlTemplate` - Run a predefined SQL template
- `batchQuery` - Execute multiple queries in one call
- `getDatabaseInfo` - Database version and configuration
- `exportCsv` / `exportJson` - Export result sets
- `explainPlan` - Fetch the execution plan
- `assessQueryRisk` - Score a query's risk level

### Schema Discovery
- `describe` - Generic object description entry point
- `listSchemas` / `listTables` / `listViews` / `listSequences` - Enumerate objects
- `searchTables` - Search tables by keyword
- `describeTable` - Column definitions, types, comments
- `listIndexes` - Index details for a table
- `suggestTools` - Recommend tools from a natural-language intent

⚠️ `listTables` has no result cap: on a real Oracle schema it returned roughly 1000 tables in
one call (including many `_BAK` and date-suffixed history tables) — enough to blow up a client's
context. On table-heavy schemas use `searchTables` with a keyword instead.

### DDL & Writes
- `validateDdl` - Validate DDL without executing
- `executeDdl` / `executeDdlBatch` / `executeDdlRemote` - Execute DDL
- `diffSchema` - Diff two schemas
- `backupData` - Snapshot table data
- `insertData` / `insertQueryResult` / `upsertData` - Write rows
- `transformAndInsert` - Transform then insert
- `exportQueryToTable` - Materialise a query into a table

### ETL Jobs
- `submitEtlJob` - Submit an ETL batch job
- `getJobStatus` / `listJobs` / `stopJob` - Job lifecycle
- `validateDataQuality` - Quality gate inside an ETL pipeline

### Connection Management (BYOK)
- `createNamedConnection` - Register a named data source
- `createSealedConnection` - Register from a sealed credential (no password in the conversation)
- `listConnections` / `describeConnection` / `getConnectionCount` - Inspect connections

### Cross-Database & Federation
- `listDatabases` / `listRemoteTables` / `describeRemoteTable` - Explore remote sources
- `executeFederatedQuery` - Federated query across databases
- `executeSelectiveQuery` - Route a query to a selected source
- `queryCrossDatabaseJoin` - Join tables across databases
- `queryComplexCrossDatabaseAnalytics` - Complex cross-database analytics
- `getCrossDatabaseTemplates` / `getFederatedDatabaseInfo` / `getGatewayStatistics` - Gateway metadata
- `createDbLink` / `testDbLink` / `dropDbLink` - DB link lifecycle

### Optimization
- `analyzeQuery` / `analyzeTable` - Query and table analysis
- `recommendIndexes` - Index recommendations
- `suggestRewrites` - SQL rewrite suggestions
- `interpretPlan` - Explain an execution plan
- `getOptimizerConfig` - Current optimizer settings

### Lineage
- `analyzeLineage` - Trace table lineage
- `getUpstream` / `getDownstream` - Upstream and downstream dependencies
- `getImpactAnalysis` - Impact of a schema change
- `listAllEdges` - All lineage edges
- `exportMermaid` / `exportDot` - Export lineage graphs
- `getLineageConfig` - Current lineage settings

### Data Quality
- `checkTableQuality` - Run quality checks on a table
- `listQualityRuleTemplates` - Available rule templates
- `getQualityAlertSummary` - Aggregated quality alerts

### Data Catalog
- `generateCatalog` / `scanSchema` - Build and refresh the catalog
- `searchAssets` - Search data assets
- `classifyColumn` / `listSensitiveColumns` - Sensitivity classification
- `getCatalogConfig` - Current catalog settings

### Change Data Capture
- `checkCdcSupport` - Check whether the database supports CDC
- `registerSubscription` / `listSubscriptions` / `unregisterSubscription` - Subscriptions
- `readChanges` / `getCurrentLsn` - Read change events and log position
- `createMirrorTable` - Create a mirror table
- `getCdcStatus` / `getCdcConfig` - CDC status and settings

### Backup & Restore
- `backupTable` / `backupSchema` - Back up table data (as replayable INSERTs) or a table's DDL
- `restoreBackup` / `quickRestore` - Replay a backup (append) or restore a whole table (delete first)
- `listBackups` / `getBackup` - Inspect backups
- `deleteBackup` / `cleanupBackups` - Remove backups
- `getBackupConfig` - Current backup settings and in-memory storage limits

⚠️ Backup records live **in process memory only** — nothing is written to disk or to a
database table, and everything is lost on restart. They are bounded by
`entropy.mcp.database.backup.max-records` (200) and `retention-days` (7); records beyond
either bound are evicted silently, after which `restoreBackup` / `quickRestore` return
`NOT_FOUND`. For durable backups, save the `sqlStatements` returned by `backupTable`
yourself.

### DBA Diagnostics
- `checkHealth` - Database health check
- `listActiveSessions` / `killSession` - Session inspection and termination
- `showLocks` / `showBlockingTree` - Lock and blocking analysis
- `showUndoUsage` - Undo consumption
- `listTablespaces` / `listDataFiles` - Storage layout
- `estimateTableSize` / `gatherTableStats` - Sizing and statistics
- `showIndexStatus` / `listInvalidObjects` - Object state
- `flashbackQuery` - Flashback query
- `listCurrentPrivileges` / `listGrants` - Privilege inspection

### Audit & Monitoring
- `getAuditLogs` / `getAuditMetrics` - Audit trail and metrics
- `getSlowQueries` / `getSqlPatternStats` - Slow query and pattern statistics
- `getDataAccessReport` / `getProtectionReport` - Compliance reporting
- `getPoolStats` / `getPoolStatsForConnection` - Connection pool statistics
- `getMetrics` / `getStatistics` - Runtime metrics
- `clearCache` - Clear caches

### Session & Custom Tools
- `sessionStore` / `sessionGet` / `sessionKeys` / `sessionRemove` / `sessionPurge` - Scoped key-value store
- `getSessionInfo` - Inspect the current scope
- `listCustomTools` / `scanCustomTools` / `invokeCustomTool` - User-defined SQL tools

## Architecture

Maven multi-module layout with a one-way dependency graph enforced at build time by ArchUnit:

```
database-mcp-contract/   # Zero-dependency leaf: dialect contracts
database-mcp-core/       # Configuration properties, session context, exception hierarchy
database-mcp-dialect/    # Dialect adapters (Oracle / MySQL / PostgreSQL / SQL Server)
database-mcp-infra/      # Data sources, pooling, caching, audit persistence
database-mcp-features/   # ETL / CDC / lineage / quality / backup / optimizer / catalog
database-mcp-tools/      # MCP tool implementations and exposure filtering
database-mcp-app/        # Spring Boot entry point and configuration
```

Dependency order: `contract <- core <- infra <- features <- tools <- app`

## Security

### HTTP Authentication
`entropy.mcp.security.enabled` defaults to `true` (breaking change introduced in 0.4.0, still in
effect). With it on:
- `/mcp` requires an authenticated principal
- `/api/**` requires `ROLE_ADMIN`
- every actuator endpoint other than `health` and `info` requires authentication
- anything else is denied

HTTP Basic is always wired; Bearer/JWT is added as well when any
`spring.security.oauth2.resourceserver.jwt.*` property is set. `health` and `info` stay
anonymous so that liveness and readiness probes keep working.

### Credential Handling
Target-database connections come in three flavours, and the difference is a security boundary, not
just convenience:

- **Declared (preferred)** — `entropy.mcp.database.connections.<name>` in `application.yml`, with the
  password supplied through a `${ENV_VAR}` placeholder. Pinned pools are built at startup and never
  expire. Only the connection *name* ever appears in a conversation; no credential passes through the
  model.
- **BYOK** — `createNamedConnection` at runtime. The password is a *tool argument*, so it enters the
  model's context and stays in whatever conversation history the client keeps. Use this for ad-hoc
  access only.
- **BYOK + sealed credentials** — `createSealedConnection` at runtime, passing only a connection name,
  a jdbcUrl and one **sealed credential** blob. Username and password are encrypted inside the blob and
  opened server-side with a private key: nobody calling the tool needs to know the password, and the
  model and conversation history only ever see base64 that cannot be reversed without that key. See
  [Sealed Credentials](#sealed-credentials).

Never embed credentials in the JDBC URL (`?password=`, `;PWD=`, `user/pw@`): the URL is echoed back
by `listConnections`, `describeConnection`, `getPoolStats` and `getDatabaseInfo`.

Those four do run every URL through `JdbcUrlMasker` first — userinfo becomes `****` and parameters
are filtered by allowlist, keeping only keys that cannot carry a secret (`databaseName`,
`currentSchema`, `sslMode`, …) — but relying on the masker is strictly worse than never sending the
secret at all.

All three flavours share everything downstream: the same `ByokDataSourceFactory`, hence the same SQL
validation, masking, auditing and statement timeouts.

### Sealed Credentials

The **server holds the private key**; the operator holds the **public key**. The direction is
deliberately the opposite of Druid's `ConfigTools`: Druid puts the decryption material (the public
key) next to the ciphertext in the config, so the ciphertext only defeats a casual reader. Here the
ciphertext travels through the model and the conversation history, so holding it must not be enough
to recover the password.

```bash
# 1. Generate a key pair. The private key is written to the file given by --out (mode 600; the file
#    is a single `MCP_CREDENTIAL_PRIVATE_KEY=...` line), and stdout carries the public key only.
#    The private key deliberately never goes to stdout: stdout survives in CI logs and terminal
#    scrollback, and that key is the entire security of this mechanism. Without --out it defaults to
#    ./mcp-credential-private.key. Delete the file once it has been injected into the server.
java -cp database-mcp-server-0.6.0.jar \
     -Dloader.main=com.entropy.database.mcp.cli.CredentialSealCli \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     keygen --out mcp-credential-private.key

# 2. Seal one credential. The password is read from stdin, never from a CLI flag
#    (flags are visible in `ps` and land in shell history).
java -cp database-mcp-server-0.6.0.jar \
     -Dloader.main=com.entropy.database.mcp.cli.CredentialSealCli \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     seal --public-key public.key --name orders-prod \
          --url 'jdbc:postgresql://db-host:5432/orders' --username app_ro --ttl 15m
```

Server configuration — `application.yml` already wires the placeholder, so a real deployment only
needs the `MCP_CREDENTIAL_PRIVATE_KEY` environment variable. Unset means an empty value, which means
the feature is off and the server starts normally:

```yaml
entropy:
  mcp:
    security:
      credential-cipher:
        private-key: ${MCP_CREDENTIAL_PRIVATE_KEY:}  # environment only, never in a file
        max-ttl: 15m                                 # longest remaining lifetime accepted at runtime
        require-sealed-credentials: false            # true ⇒ createNamedConnection rejects plaintext
```

A sealed blob is bound to the **connection name**, the **jdbcUrl** (compared byte for byte) and an
**expiry**. All three are load-bearing:

- without the name binding, anyone holding the blob could register it under a connection name of their
  own choosing and read through it;
- without the URL binding, an attacker could point the blob at a database they control and have the
  server hand the decrypted password over to it;
- without an expiry, that base64 string is a permanent password sitting in conversation history.

Known tradeoff: within its lifetime the same blob **can be replayed** — there is no single-use
consumption, which would require the server to remember spent blobs (lost on every restart) and would
break the normal flow where a caller re-registers with the same credential after a BYOK lease expires.
The default 15 minutes *is* that replay window. With no private key configured the whole mechanism is
off and `createSealedConnection` fails immediately, pointing back at `createNamedConnection`.

Sealed blobs also work for pinned connections, which keeps the password out of the environment too:

```yaml
entropy:
  mcp:
    database:
      connections:
        orders-prod:
          jdbc-url: jdbc:postgresql://db-host:5432/orders
          username: app_ro
          password: sealed:BASE64...   # opened at startup; username must match the sealed one
```

Two boundaries worth stating: `readonly: true` is enforced **by this server** (write tools
return `CON005`); it says nothing about the database account's actual privileges, so do not
treat it as a substitute for least privilege. And `listConnections` / `describeConnection`
still echo `leaseExpiry` and `maxLifetimeExpiry` for pinned connections even though neither
applies (`LeasedDataSource.pinned` is exempt from both) — a display leftover; do not
re-register on account of it.

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

See [application.yml](database-mcp-app/src/main/resources/application.yml) for full
configuration options.

### Multi-Environment Profiles

- default - `application.yml`
- `test` - `application-test.yml`
- `production` - `application-production.yml`

### Tool Exposure

```yaml
entropy:
  mcp:
    tools:
      plane: all          # all / data / control; any other value fails startup
      groups: []
      include: []
      exclude: []
```

`plane` is derived from the tags in each tool description: `data` keeps read-only tools
(`read` present, none of `write` / `ddl` / `destructive` / `admin`), `control` is the
complement. Unknown names in `include` / `exclude` / `groups` fail startup rather than
being silently ignored.

### Audit Persistence (optional)

Auditing lands in three places: the audit log file, an in-memory ring buffer (last 100 entries, for
SSE/polling), and the `audit_log` table. Only the table is queryable as history by `getAuditLogs` /
`getDataAccessReport` / `getProtectionReport`.

The first two are always on. The table is **opt-in**: with no `spring.datasource.url` there is no
`audit_log` at all and auditing stays file-plus-buffer. Two ways to turn it on:

**Option 1 — external database (durable; use this for a compliance-grade trail)**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://audit-db:5432/mcp_audit
    username: mcp
    password: ${MCP_AUDIT_DB_PASSWORD}
```

**Option 2 — in-process H2 (no external dependency; just enough to exercise the audit query tools
locally, in demos, or in smoke tests)**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:mcp_audit;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    username: sa
    password: ""
```

With option 2 the history **dies with the process and is not shared across replicas**. Startup logs
a `Audit log is persisted to an IN-MEMORY database (...)` warning saying exactly that.

Either can be set without touching the file, via `--spring.datasource.url=...` or the
`SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` environment
variables.

Notes:

- **No manual DDL needed.** `AuditLogRepository` detects the product family (a separate `CREATE
  TABLE` for H2 / PostgreSQL / MySQL / Oracle / SQL Server) and creates `audit_log` on demand. The
  account needs DDL privileges; if the table cannot be created, auditing degrades to file-only.
- **Do not point the audit database at a database being audited.** It muddles ownership, and such
  accounts usually lack DDL privileges anyway.
- `DB_CLOSE_DELAY=-1` is required, not a tuning knob: without it H2 drops the whole database when
  Hikari reclaims the last idle connection, which shows up as audit rows silently going missing.
- Upgrading a deployment that already has an `audit_log` table: columns were renamed
  (`sql`→`sql_text`, `rows`→`row_count`, `timestamp`→`event_time`). See the `ALTER TABLE` examples
  in the `AuditLogRepository` class javadoc.

Target database connections are not configured here — they are registered at runtime via
`createNamedConnection`.

### Read-Only Web UI

The server serves a small read-only operations page at the root path: open
`http://<host>:8686/` in a browser. Four tabs:

| Tab | Backing endpoint | Notes |
| --- | --- | --- |
| Audit trail (in-memory) | `GET /api/audit/logs?limit=&operation=` | In-process ring buffer, last 100 entries, cleared on restart |
| Audit history (persisted) | `GET /api/audit/history?limit=&tool=&connectionKey=&startTime=&endTime=` | **Requires the audit persistence from the previous section.** Without `spring.datasource.url` the endpoint answers 503 and this tab is permanently empty |
| Connections & pools | `GET /api/ui/connections` | Registered connections (name, dialect, masked JDBC URL, lease expiry) plus live HikariCP metrics and health per pool |
| Performance | `GET /api/ui/performance?limit=` | Slow queries, SQL pattern aggregation, micrometer metrics snapshot |

`GET /api/ui/config` returns `{authEnabled, auditPersistence, maxLimit}`; the page drives both the
banner and the "audit history unavailable" state from it rather than hardcoding either.

**⚠️ This page widens the unauthenticated surface.** With `entropy.mcp.security.enabled=false` the
page and every endpoint it calls are open to anyone who can reach the port — including the raw SQL in
the audit trail, the raw SQL of slow queries, and the full list of registered connections. These are
not new permissions (`/api/audit/**` was always at this level), but it turns "readable if you know
the `/api/audit/logs` path" into "visible on opening the root URL", and in practice that difference
*is* the exposure. So:

- Running unauthenticated, the page shows a **non-dismissible** red banner saying exactly that. Its
  content is derived from `authEnabled` in `/api/ui/config`, not hardcoded. The startup
  `MCP HTTP authentication is DISABLED` banner now names the UI too.
- The only switch that closes this is `entropy.mcp.security.enabled=true` (which also requires
  `MCP_SECURITY_ADMIN_PASSWORD`). With it on, `SecurityConfig` puts `/api/**` and the page's static
  resources (`/`, `/index.html`, `/assets/**`) in the same `ROLE_ADMIN` bucket, and the
  browser prompts for Basic credentials. The third entry is a wildcard rather than a file list
  because Vite emits content-hashed assets (`assets/index-<hash>.js`): an exact list silently
  403s the whole page on every authenticated deployment after each frontend rebuild.
- They are always the same bucket: there is no "page loads but every table is 401" middle state.
  Conversely, with authentication on and no credentials, `/` is a 401 and the frontend renders that
  401 verbatim instead of an empty table that reads like "no data".

Deliberate implementation choices — read these before changing this area:

- **The frontend is built.** Vite + React 19 + the [Meta Astryx](https://github.com/facebook/astryx)
  design system. Sources live in `database-mcp-app/src/main/frontend/`; `frontend-maven-plugin` runs
  `npm ci && npm run build` during `generate-resources` and the output lands in
  `target/classes/static/`, shipped inside the fat jar. The plugin installs its own node/npm (never
  the machine's) under `src/main/frontend/node/` rather than `target/`, so a `clean` does not force
  another download.
  - Every dependency is pinned to an exact version (no `^`, no `~`) and `package-lock.json` is
    committed: Astryx is Beta.
  - `mvn -o` (offline) inherently conflicts with a frontend build, hence the `-Dfrontend.skip=true`
    escape hatch. It deliberately does **not** also skip tests: when the output is missing,
    `WebUiStaticAssetsPresenceTest` fails and explains why, so a jar with an empty `static/` cannot
    ship silently. For an offline Java-only build, omit `clean`:
    `mise exec -- mvn -o install -Dfrontend.skip=true`.
  - Two Astryx 0.6.2 traps are recorded in code comments: the published docs' import
    `@astryxdesign/theme-neutral/css` does not exist in 0.6.2 (the real entry is `/theme.css`, see
    `src/main.jsx`), and the theme CSS is wrapped in `@scope ([data-astryx-theme="neutral"])`, so
    `index.html` must carry that attribute on `<html>` or the page renders completely unstyled
    without raising an error.
- **No CDN links.** The target runs in an air-gapped-ish Kubernetes namespace where anything fetched
  from the internet simply fails. Use inline SVG if a chart is ever needed.
- **Auto-refresh is off by default.** Every refresh of the connections tab goes through read methods
  on the connection registry, and `PerformanceTimingAspect` feeds those into
  `recordToolExecution` — a fixed poll would have the page polluting the very metrics on its own
  performance tab.
- **JDBC URLs are always the masked copy.** Every URL on the page comes from
  `ConnectionMetadata.jdbcUrlMasked` / `HikariPoolStats.jdbcUrlMasked`, both produced through
  `JdbcUrlMasker` when the pool is built. `DatabaseHealthMonitor.getHealthStatus()` is deliberately
  **not** exposed: it puts an unmasked `DatabaseMetaData.getURL()` into its result, and it describes
  the default datasource (the audit database) rather than the BYOK connections this page is about.
  Connection health is expressed through `isPoolHealthy` / `healthWarnings` / `degradedPools` from
  the pool statistics instead.
- **`/` does not shadow `/mcp` or `/actuator/**`.** The static resource handler has the lowest
  precedence; the MCP protocol endpoint is still `/mcp`.

Verifying that the page is also present **in the packaged fat jar** (the classic failure mode for
this kind of change is "fine in the IDE, 404 in the jar"):

```bash
java -jar database-mcp-app/target/database-mcp-server-0.6.0.jar \
  --spring.profiles.active=test --server.port=18700 --entropy.mcp.security.enabled=false

curl -si http://localhost:18700/ | head -1              # 200, text/html
curl -s  http://localhost:18700/api/ui/config           # {"authEnabled":false,"auditPersistence":false,...}
curl -s  http://localhost:18700/api/ui/connections
curl -s  http://localhost:18700/api/ui/performance?limit=5
curl -si http://localhost:18700/api/audit/history | head -1   # 503, audit not persisted
```

## API Reference

### Health Check

```http
GET /actuator/health
```

Anonymous, so probes keep working with authentication enabled.

### MCP Endpoint

The path is `/mcp`, not `/mcp/message`: `spring.ai.mcp.server.protocol` is `STATELESS`,
which serves a single endpoint. With authentication on, any other path is denied outright,
so a request to `/mcp/message` returns 403 rather than 404.

```http
POST /mcp
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Basic YWRtaW46eW91cl9hZG1pbl9wYXNzd29yZA==

{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "executeQuery",
    "arguments": {
      "connection": "primary",
      "sql": "SELECT id, name FROM users ORDER BY id",
      "maxRows": 100
    }
  },
  "id": 1
}
```

## License

Copyright 2024-2026 Entropy. Licensed under the [Apache License, Version 2.0](LICENSE).

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## Support

- GitHub Issues: Report bugs and feature requests
