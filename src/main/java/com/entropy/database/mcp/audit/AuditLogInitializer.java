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
package com.entropy.database.mcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Initializes audit log table on startup.
 *
 * <p>Gated on the same key as {@link AuditLogRepository}: without {@code spring.datasource.url}
 * this runner used to create {@code audit_log} in an anonymous embedded database that nothing else
 * ever read.
 *
 * <p>建表委托给 {@link AuditLogRepository#ensureTableExists()}，这里不再内联 DDL：原先这里写死了一份
 * H2/PostgreSQL 语法的建表语句，于是（一）在 MySQL/Oracle/SQL Server 上启动即失败，只留一条 warn；
 * （二）它和仓储各持一份 DDL，一旦列定义变化，启动时先按旧定义把表建出来，仓储随后只会看到「表已存在」
 * 而按新列名读写，得到的是一张字段对不上的表。
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.url")
public class AuditLogInitializer {

    private static final Logger log = LoggerFactory.getLogger(AuditLogInitializer.class);

    @Bean
    public CommandLineRunner initAuditLogTable(AuditLogRepository auditLogRepository) {
        return args -> {
            try {
                auditLogRepository.ensureTableExists();
            } catch (Exception e) {
                // 启动阶段不阻断：审计表建不出来时 QueryAuditLoggerImpl 会退化到文件审计
                log.warn("Audit table creation failed (insufficient permissions?): {}", e.getMessage(), e);
            }
        };
    }
}
