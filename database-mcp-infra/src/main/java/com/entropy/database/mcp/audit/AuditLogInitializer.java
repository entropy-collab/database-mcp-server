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

import com.entropy.database.mcp.util.JdbcUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

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
 *
 * <p><b>内存库告警</b>：审计库是 opt-in 的（没配 {@code spring.datasource.url} 时这个类整个不装配）。
 * 部署方可以把它指向一个进程内 H2 来让审计查询工具在没有外部库时也能跑，那是显式选择，但代价是
 * 进程一停历史全丢。这个代价必须在启动日志里可见 —— 本项目历史上的缺陷正是「审计静默落进**匿名**
 * 内存库，而 {@code audit.enabled=true} 报告一切正常」（见 {@link AuditLogRepository} 类注释）。
 * 所以由 {@link #warnIfInMemory(String)} 在启动时明确说出来。**NEVER 把这条 WARN 降级成 info 或删掉。**
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.url")
public class AuditLogInitializer {

    private static final Logger log = LoggerFactory.getLogger(AuditLogInitializer.class);

    private final String datasourceUrl;

    public AuditLogInitializer(@Value("${spring.datasource.url}") String datasourceUrl) {
        this.datasourceUrl = datasourceUrl;
    }

    @Bean
    public CommandLineRunner initAuditLogTable(AuditLogRepository auditLogRepository) {
        return args -> {
            warnIfInMemory(datasourceUrl);
            try {
                auditLogRepository.ensureTableExists();
            } catch (Exception e) {
                // 启动阶段不阻断：审计表建不出来时 QueryAuditLoggerImpl 会退化到文件审计
                log.warn("Audit table creation failed (insufficient permissions?): {}", e.getMessage(), e);
            }
        };
    }

    /**
     * 按 JDBC URL 判定审计库是否只存在于进程内存里，是就告警。
     *
     * <p>判据本身住在 {@link com.entropy.database.mcp.util.JdbcUrls#isInMemory}，这里只负责
     * 「审计历史会丢」这句话：同一个库上还有调用者身份表与 MCP 工具开关表，各自要说的丢失后果不同，
     * 所以告警分开、判据共用。
     *
     * <p>包级可见以便直接断言。
     */
    static boolean warnIfInMemory(String url) {
        boolean inMemory = JdbcUrls.isInMemory(url);
        if (inMemory) {
            log.warn("Audit log is persisted to an IN-MEMORY database ({}): audit history is lost "
                    + "when this process stops, and it is not shared across replicas. This is the "
                    + "zero-configuration default, not a compliance-grade audit trail. Point "
                    + "spring.datasource.url at an external database to keep the trail.",
                    url.toLowerCase(Locale.ROOT));
        }
        return inMemory;
    }
}
