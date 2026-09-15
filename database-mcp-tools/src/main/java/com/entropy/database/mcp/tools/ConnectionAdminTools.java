/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.ConnectionMetadata;
import com.entropy.database.mcp.byok.ConnectionProperties;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.credential.CredentialCipher;
import com.entropy.database.mcp.credential.SealedCredential;
import com.entropy.database.mcp.properties.CredentialCipherProperties;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

/**
 * Connection administration tools.
 *
 * <p>{@code createNamedConnection} lives here rather than in {@code EtlTools} on purpose:
 * it is the only way to register a target database, so gating it behind
 * {@code entropy.mcp.gateway.enabled} (as the ETL tools are) left the whole server unable
 * to reach any database when that switch was off. This class is registered
 * unconditionally.
 *
 * <h2>两条注册路径</h2>
 * {@code createNamedConnection} 收明文口令，{@code createSealedConnection} 收密文凭证。两者只在
 * 「凭证从哪来」上不同，注册之后的连接完全一样。密文那条路刻意<b>放在同一个类里</b>而不是拆成
 * 一个可条件装配的新 bean：新 bean 会需要一条新的 ArchUnit R2 例外（tools 不得直接依赖
 * BYOK 注册表），而注册连接本来就是本类既有的职责，没必要为「能不能条件装配」这一点便利去动
 * 架构基线。代价是私钥没配时这个工具依然出现在 tools/list 里，只是调用即报错并指回明文那条路——
 * 描述里的「前置条件」把这件事写清楚了。
 */
@Component
public class ConnectionAdminTools extends McpToolBase {

    private final DynamicDataSourceManager dataSourceManager;
    private final ObjectProvider<CredentialCipher> credentialCipher;
    private final CredentialCipherProperties cipherProperties;

    public ConnectionAdminTools(DynamicDataSourceManager dataSourceManager,
                                ObjectProvider<CredentialCipher> credentialCipher,
                                CredentialCipherProperties cipherProperties) {
        this.dataSourceManager = dataSourceManager;
        this.credentialCipher = credentialCipher;
        this.cipherProperties = cipherProperties;
    }


    @McpTool(description = """
            【注册数据库连接】创建一个命名的 BYOK 连接：建连接池、跑一次连通性测试查询，成功后即可被其他工具按名引用。
            前置条件：先调用 listConnections 确认目标库不在已注册列表里。运维可以用服务端配置 entropy.mcp.database.connections 预先声明连接（密码走环境变量），那类连接开机即在、不会过期，应优先使用——本工具只在没有现成连接时才需要。
            使用场景：目标库未被预先声明、需要在会话中临时接入时。
            注意：password 会作为工具入参进入本次对话，因而留在对话历史里，请据实告知用户并在后续回复中不再重复；如果运维能提供密文凭证，请改用 createSealedConnection，那条路不需要任何人知道口令。不要把凭证拼进 jdbcUrl（如 ?password=、;PWD=、user/pw@），那会让它同时出现在 listConnections、describeConnection、getPoolStats 的返回值里。注册成功后建议先调用 describeConnection 确认连接就绪，再执行查询。
            返回字段：connectionName、dialect（实际生效的方言，未显式传入时由 jdbcUrl 推断）、message、recommendation。不回显任何凭证。
            不要用于：创建 Oracle 跨库链路（用 createDbLink）；把库注册进联邦网关（联邦网关的 databaseId 由服务端注册，见 listDatabases）；服务端开启 require-sealed-credentials 时的注册（本工具会直接拒绝，用 createSealedConnection）。
            标签：[write, connection, byok, setup]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public Map<String, Object> createNamedConnection(
            @McpToolParam(description = "连接名，后续所有工具用它引用这个数据库；同名重复注册会复用已有连接池") String name,
            @McpToolParam(description = "JDBC 连接串，必填（如 jdbc:oracle:thin:@host:1521/svc、jdbc:mysql://host:3306/db）；不要在里面夹带用户名密码") String jdbcUrl,
            @McpToolParam(description = "数据库登录用户名，必填") String username,
            @McpToolParam(description = "数据库登录密码，必填且不能为空白") String password,
            @McpToolParam(description = "数据库方言，取值：oracle、mysql、postgres、sqlserver、sqlite、db2、h2、generic；留空时按 jdbcUrl 自动推断",
                    required = false) String dialect) {
        return safeExecute(() -> {
            // 部署方明确要求「运行时注册只走密文」时，这条明文路径必须闭死。放在方法体里而不是靠
            // 条件装配摘掉整个工具：工具消失只会让模型换个别的工具瞎试，而一句带指引的错误能让它
            // 直接转向 createSealedConnection。
            if (cipherProperties.requireSealedCredentials()) {
                throw new McpToolException(ErrorCode.SECURITY_VIOLATION,
                        "本服务端已配置 entropy.mcp.security.credential-cipher.require-sealed-credentials=true，"
                                + "不接受明文口令。请改用 createSealedConnection，并向运维索取该数据库的密文凭证。",
                        name);
            }
            ConnectionProperties properties = ConnectionProperties.builder()
                    .jdbcUrl(jdbcUrl)
                    .username(username)
                    .password(password)
                    .dialect(dialect)
                    .build();
            properties.validate();
            ByokDataSourceContext context = dataSourceManager.acquire(name, properties);
            context.getJdbcTemplate().queryForList(context.getDialect().connectionTestQuery());
            // Connection registration is synchronous, but the MCP tool result is serialized to the client.
            // Advise the LLM to verify the connection before use, as rapid subsequent calls may race with
            // the response delivery.
            return success(Map.of(
                    "connectionName", name,
                    "dialect", properties.dialect(),
                    "message", "Connection created and tested successfully. Call describeConnection to confirm before querying.",
                    "recommendation", "Call describeConnection(\"connection\": \"" + name + "\") before using this connection for queries."
            ));
        });
    }

    @McpTool(description = """
            【用密文凭证注册数据库连接】把运维给出的密文凭证交给服务端解开，建连接池并跑一次连通性测试查询，成功后即可被其他工具按名引用。整个过程不需要任何人知道数据库口令。
            前置条件：服务端必须配置 entropy.mcp.security.credential-cipher.private-key，未配置时本工具直接报错，此时请改用 createNamedConnection。密文凭证由运维用服务端公钥生成，里面已经封好账号、口令、绑定的连接名与 jdbcUrl、以及有效期——所以本工具不接受用户名和口令入参；这四样缺任何一样，本工具都会拒绝。
            使用场景：不希望数据库口令进入对话历史时的运行时注册；密文过期后重新注册。
            注意：必须使用运维给出的那个连接名和那串 jdbcUrl（含参数、大小写与空白都要一致），任何一处不符都会被拒绝——密文是绑定这两者的，这道绑定拦的是「把密文指向一台假数据库来套取口令」。密文的有效期上限由服务端配置，超限或已过期都会被拒绝，过期后需要向运维重新索取，不要尝试改写密文内容。密文本身进入对话历史是可接受的：没有服务端私钥它无法还原口令，但仍应视为短期敏感信息。
            返回字段：connectionName、username（密文里携带的数据库账号）、dialect（实际生效的方言，未显式传入时由 jdbcUrl 推断）、credentialExpiresAt（本次使用的密文的过期时刻）、message、recommendation。不回显口令。
            不要用于：明文口令注册（用 createNamedConnection）；解密或查看密文内容（服务端不提供这种工具）。
            标签：[write, connection, byok, setup, sealed]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public Map<String, Object> createSealedConnection(
            @McpToolParam(description = "连接名，必须与运维封装密文时约定的一致（不区分大小写）；后续所有工具用它引用这个数据库") String name,
            @McpToolParam(description = "JDBC 连接串，必须与运维封装密文时使用的完全一致（逐字符比对，含参数顺序、大小写与空白）") String jdbcUrl,
            @McpToolParam(description = "运维给出的密文凭证，base64 字符串；不要改写、截断或重新编码，可含换行") String sealedCredential,
            @McpToolParam(description = "数据库方言，取值：oracle、mysql、postgres、sqlserver、sqlite、db2、h2、generic；留空时按 jdbcUrl 自动推断",
                    required = false) String dialect) {
        return safeExecute(() -> {
            CredentialCipher cipher = credentialCipher.getIfAvailable();
            if (cipher == null) {
                throw new McpToolException(ErrorCode.SECURITY_VIOLATION,
                        "服务端未配置密文凭证私钥（entropy.mcp.security.credential-cipher.private-key），"
                                + "无法解开密文。请改用 createNamedConnection，或让运维配置私钥后重试。",
                        name);
            }
            // 走运行时入口：这条路的密文经过模型和对话历史，所以连接名绑定、jdbcUrl 绑定与有效期
            // 三样都是必需项。用带语义的入口而不是给 open 传参数，是为了「哪条路允许无绑定」在
            // 方法名上就能看出来——曾经这里传的只是 maxTtl，无绑定密文因此被静默放行。
            SealedCredential credential =
                    cipher.openForRuntime(sealedCredential, name, jdbcUrl, cipherProperties.maxTtl());
            ConnectionProperties properties = ConnectionProperties.builder()
                    .jdbcUrl(jdbcUrl)
                    .username(credential.username())
                    .password(credential.password())
                    .dialect(dialect)
                    .build();
            properties.validate();
            ByokDataSourceContext context = dataSourceManager.acquire(name, properties);
            context.getJdbcTemplate().queryForList(context.getDialect().connectionTestQuery());
            return success(Map.of(
                    "connectionName", name,
                    "username", credential.username(),
                    "dialect", properties.dialect(),
                    "credentialExpiresAt", String.valueOf(credential.expiresAt()),
                    "message", "Connection created from a sealed credential and tested successfully.",
                    "recommendation", "Call describeConnection(\"connection\": \"" + name + "\") before using this connection for queries."
            ));
        });
    }

    @McpTool(description = """
            【列出所有连接】列出已注册的全部数据源连接及其概要信息。无入参。
            前置条件：无；未注册任何连接时 connections 为空数组。
            使用场景：不知道有哪些可用连接名时先列一遍，再把连接名传给其他工具的 connection 参数。
            返回字段：totalConnections（已注册连接数）、activeConnections（活跃连接数）、connections（按 key 升序排列的数组，每项含 key、dialect、jdbcUrlMasked、owner、status、createdAt、leaseExpiry、maxLifetimeExpiry、poolSize；元数据缺失的连接只含 key 与 status=UNKNOWN）。status 取值 ACTIVE、EXPIRED_LEASE、EXPIRED_MAX_LIFETIME。
            不要用于：查看单个连接的完整明细，如租约 TTL 与当前活跃连接数（用 describeConnection）；只要数量（用 getConnectionCount）；查看连接池运行指标（用 getPoolStats）。
            标签：[read, connection, list]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listConnections() {
        return safeExecute(() -> {
            Collection<String> keys = dataSourceManager.listConnectionKeys();
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("totalConnections", keys.size());
            result.put("activeConnections", dataSourceManager.getActiveConnectionCount());
            result.put("connections", keys.stream()
                    .map(key -> {
                        ConnectionMetadata meta = dataSourceManager.getConnectionMetadata(key);
                        if (meta == null) {
                            return Map.<String, Object>of("key", key, "status", "UNKNOWN");
                        }
                        return Map.<String, Object>of(
                                "key", meta.key(), "dialect", meta.dialect(),
                                "jdbcUrlMasked", meta.jdbcUrlMasked(), "owner", meta.owner(),
                                "status", meta.getStatus(),
                                "createdAt", meta.createdAt().toString(),
                                "leaseExpiry", meta.getLeaseExpiry().toString(),
                                "maxLifetimeExpiry", meta.getMaxLifetimeExpiry().toString(),
                                "poolSize", meta.poolSize());
                    })
                    .sorted(Comparator.comparing(m -> (String) m.get("key")))
                    .toList());
            return success(result);
        });
    }

    @McpTool(description = """
            【查看连接详情】查看单个连接的完整元数据，包括方言、脱敏后的 JDBC URL、租约与生命周期到期时间、池大小与活跃连接数。
            前置条件：连接必须已注册，否则报连接不存在。连接注册是异步的——调用 createNamedConnection 之后请用本工具确认 status 为 ACTIVE、连接已就绪，再执行查询。
            使用场景：确认连接是否可用、排查查询失败是否因租约过期、确认服务端识别到的数据库方言。
            返回字段：connection（含 key、dialect、jdbcUrlMasked、owner、status、createdAt、leaseTtl、leaseExpiry、maxLifetime、maxLifetimeExpiry、poolSize、activeConnections）。status 取值 ACTIVE、EXPIRED_LEASE、EXPIRED_MAX_LIFETIME。
            不要用于：不知道连接名时逐个试探（先用 listConnections）；查看池的实时使用率与健康告警（用 getPoolStatsForConnection）。
            标签：[read, connection, metadata]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> describeConnection(
            @McpToolParam(description = "要查询的 BYOK 连接名，必填且不可省略；须与 listConnections 返回的 key 完全一致，未注册时报连接不存在") String connection) {
        return safeExecute(() -> {
            ConnectionMetadata meta = dataSourceManager.getConnectionMetadata(connection);
            if (meta == null) {
                throw new McpToolException(ErrorCode.CONNECTION_NOT_FOUND, "Connection not found: " + connection, connection);
            }
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("key", meta.key());
            detail.put("dialect", meta.dialect());
            detail.put("jdbcUrlMasked", meta.jdbcUrlMasked());
            detail.put("owner", meta.owner());
            detail.put("status", meta.getStatus());
            detail.put("createdAt", meta.createdAt().toString());
            detail.put("leaseTtl", meta.leaseTtl().toString());
            detail.put("leaseExpiry", meta.getLeaseExpiry().toString());
            detail.put("maxLifetime", meta.maxLifetime().toString());
            detail.put("maxLifetimeExpiry", meta.getMaxLifetimeExpiry().toString());
            detail.put("poolSize", meta.poolSize());
            detail.put("activeConnections", meta.activeConnections());
            return success(Map.of("connection", detail));
        });
    }

    @McpTool(description = """
            【统计连接数量】只返回连接数量的汇总数字，不返回任何连接名或明细。无入参。
            前置条件：无。
            使用场景：只需判断是否已有连接、或监控活跃连接规模，不关心具体是哪些连接。
            返回字段：activeConnections（活跃连接数）、totalRegistered（已注册连接总数）。
            不要用于：需要知道具体连接名（用 listConnections）；需要单个连接的状态与到期时间（用 describeConnection）。
            标签：[read, connection, count]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getConnectionCount() {
        return safeExecute(() -> success(Map.of(
                "activeConnections", dataSourceManager.getActiveConnectionCount(),
                "totalRegistered", dataSourceManager.getConnectionCount())));
    }
}
