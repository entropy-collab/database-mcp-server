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
package com.entropy.database.mcp.byok;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * One remote federated database: the positional and named templates over a single
 * {@link DataSource}, created once at registration.
 *
 * <p>Replaces the previous pair of parallel maps in the gateway (a {@code JdbcTemplate} map plus a
 * write-only {@code DataSource} map that nothing ever read), which had to be kept in sync under a
 * lock to avoid a half-registered client. Both templates are derived from the same datasource in
 * {@link #over}, so a client either exists with all three parts or does not exist at all.
 *
 * <h2>为什么住在 infra 而不是 features</h2>
 * <p>这是连接管理，不是能力：给一个远端联邦目标建模板、把语句超时钉在模板上，都是 JDBC 层的事。它
 * 留在 {@code features} 的 gateway 包时，能力包必须直接 import {@code org.springframework.jdbc..}
 * （ArchUnit R6 禁止的形状，11 处违规全部来自它）。而这里套 {@code facade} 也不成立：facade 是按
 * <em>连接名</em> 路由到 BYOK 注册表的读接口，联邦目标压根不是 BYOK 连接——{@code DataSource} 由调用方
 * 直接传进来、生命周期不在本进程的注册表里，没有名字可供路由。所以正解是把这段连接管理代码下沉，
 * 与 {@code EtlRowStream} / {@code BatchInsertHelper} 当年从上层搬进 infra 是同一个理由。
 *
 * <p>放 {@code byok} 而不是 {@code repository}：本包的职责就是「按 {@link DataSource} 组织模板与
 * 池」（见 {@link StatementTemplates}），而且这里唯一的新增依赖是 {@code byok → javax.sql}，不会像
 * {@code repository} 反向 import {@code byok} 那样造出包环（ArchUnit R5，基线 0）。
 *
 * <h2>对外为什么只有 queryForList</h2>
 * <p>{@link #queryForList(String)} / {@link #queryForList(String, Map)} 之外不暴露任何模板访问器：
 * 给出一个返回 {@link JdbcTemplate} 的 getter 等于把违规原样搬个地方，调用方（gateway）仍然会在自己
 * 的字节码里出现 spring-jdbc 类型。{@link #dataSource()} 是例外且无需绕路——R6 只禁
 * {@code org.springframework.jdbc..}，{@code javax.sql.DataSource} 不在其内，而 gateway 确实需要它
 * 来探测方言、做连接可用性检查。
 */
public final class RemoteJdbcClient {

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final int queryTimeoutSeconds;

    private RemoteJdbcClient(DataSource dataSource, JdbcTemplate jdbc,
                             NamedParameterJdbcTemplate named, int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.named = named;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /**
     * Build the client for {@code dataSource}, bounding every statement it will ever issue.
     *
     * <p>The named template wraps the positional one rather than the datasource, so both paths share
     * the single configured ceiling; configuring them separately is how one of the two silently ends
     * up unbounded.
     *
     * @param queryTimeoutSeconds statement ceiling for this client. A federated fan-out waits on
     *                            every database it targets, so an unbounded remote statement
     *                            holds a gateway thread — and the request thread joining on it —
     *                            until the driver gives up. A non-positive value means "not
     *                            configured" and leaves the driver default in place, matching
     *                            {@code JdbcTemplate}'s own convention.
     */
    public static RemoteJdbcClient over(DataSource dataSource, int queryTimeoutSeconds) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        if (queryTimeoutSeconds > 0) {
            jdbc.setQueryTimeout(queryTimeoutSeconds);
        }
        return new RemoteJdbcClient(dataSource, jdbc, new NamedParameterJdbcTemplate(jdbc),
                queryTimeoutSeconds);
    }

    /** Run {@code sql} with no parameters. */
    public List<Map<String, Object>> queryForList(String sql) {
        return jdbc.queryForList(sql);
    }

    /**
     * Run {@code sql} binding {@code params} <em>by name</em>: the SQL must use {@code :name}
     * placeholders.
     *
     * <p>This deliberately goes through {@link NamedParameterJdbcTemplate} instead of flattening the
     * map with {@code values().toArray()} and binding positionally — map iteration order has no
     * relation to placeholder order (for a {@code HashMap} it is a hash artefact), so the flattened
     * form silently read the wrong rows. A {@code ?} style statement now fails loudly on the unset
     * placeholder.
     */
    public List<Map<String, Object>> queryForList(String sql, Map<String, Object> params) {
        return named.queryForList(sql, params);
    }

    /**
     * The datasource this client was registered with, for metadata probes and validity checks.
     *
     * <p>Its lifecycle belongs to whoever registered it: this class never closes it.
     */
    public DataSource dataSource() {
        return dataSource;
    }

    /** The statement ceiling in effect, or a non-positive value when none was configured. */
    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }
}
