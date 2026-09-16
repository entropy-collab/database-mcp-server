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
package com.entropy.database.mcp.repository;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The per-connection templates the ETL pipeline needs, as seen from this package.
 *
 * <p>{@code ByokDataSourceContext} implements it, so a step handler passes the context it already
 * holds and never touches a {@link JdbcTemplate} itself (ArchUnit R6).
 *
 * <p><b>为什么是一个接口，而不是直接收 {@code ByokDataSourceContext}。</b>{@code byok} 已经依赖
 * 本包（{@code ByokDataSourceContext} 持有 {@code DatabaseReadRepository} /
 * {@code ExecutionPlanRepository}）。如果这里反向 import {@code ByokDataSourceContext}，
 * {@code byok} 与 {@code repository} 之间就出现包环，撞上 ArchUnit R5（基线 0）。把「搬数需要哪些
 * 模板」声明成本包的接口、由 {@code byok} 来实现，依赖方向仍然只有 byok → repository 一条。
 */
public interface EtlTemplates {

    /** Template for bulk transfers, bounded by the ETL statement ceiling. */
    JdbcTemplate getEtlJdbcTemplate();

    /** Template for DDL, which can block on metadata locks far longer than a write. */
    JdbcTemplate getDdlJdbcTemplate();
}
