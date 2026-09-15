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
package com.entropy.database.mcp.etl;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.util.ValidationUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 异步 ETL 链路上唯一的标识符 / WHERE 片段过闸点。
 *
 * <p><b>为什么存在这个类。</b>同步版工具 {@code EtlTools.transformAndInsert} 一直有三道校验：
 * 列映射两侧必须是纯标识符（否则 {@code "(SELECT PASSWORD FROM X):Y"} 就能把子查询塞进
 * SELECT 列表）、目标表名必须是合法标识符、whereClause 必须过
 * {@link ValidationUtils#validateWhereClause}。异步版（{@code submitEtlJob} → step handler）
 * 把这三道全丢了：handler 走 {@code ctx.getEtlJdbcTemplate()} 直连，既不经过
 * {@code ByokDatabaseFacade.validateIdentifiers}，也不经过 {@code SqlValidationAspect}
 * （切点绑死 routing 包），而 {@code Step} 只校验 id/type、{@code MigrationJob.validate()}
 * 只查重复与环。<b>删掉这里的任何一条校验，等于把异步 ETL 的 SQL 注入缺口重新打开。</b>
 *
 * <p><b>为什么不放在 {@code Step} 构造器或 {@code MigrationJob.validate()}。</b>标识符是否合法
 * 由方言决定（Oracle 与 H2 的规则不同），而提交阶段还没解析连接，拿不到 {@link DatabaseDialect}。
 * 所以过闸放在 handler 派发入口 {@code JobExecutionEngine.executeStep}：那是所有 handler 的
 * 唯一入口，连接已解析、方言已就位，且只需一处调用。
 *
 * <p><b>为什么参数要通过 {@link JobExecutionEngine} 的 reader 读。</b>{@code getListParam} 允许
 * 逗号分隔的字符串形式，若这里自己解析 {@code step.params()}，字符串形态的 columnMapping 就会
 * 绕过校验而在 handler 里被展开成多列——两处解析必须是同一份代码。
 */
final class EtlStepGuard {

    private EtlStepGuard() {
    }

    /**
     * 解析好的列映射：三个列表下标一一对应。
     *
     * @param sourceColumns 已校验为纯标识符的源列
     * @param targetColumns 已校验为纯标识符的目标列
     * @param transforms    转换名，只会命中固定的 switch 分支，不会被拼进 SQL
     */
    record ColumnMapping(List<String> sourceColumns, List<String> targetColumns, List<String> transforms) {
    }

    /**
     * 派发前对整个 step 过闸。
     *
     * <p>按类型只校验该类型真正会拼进 SQL 的东西；DDL 的每条语句由
     * {@link DdlStepHandler} 调 {@code engine.validateDdl} 逐条过
     * {@code SqlValidator.validateDdl}——语句白名单归 SqlValidator 管，这里不重复判断。
     */
    static void validateStep(Step step, DatabaseDialect sourceDialect, DatabaseDialect targetDialect,
                             JobExecutionEngine engine) {
        switch (step.type()) {
            case QUERY_TO_TABLE -> requireTargetTable(step, targetDialect);
            case TRANSFORM -> {
                // TRANSFORM 读写同一个 context（见 TransformStepHandler 的类注释），所以两侧都用源方言。
                requireTargetTable(step, sourceDialect);
                parseColumnMapping(engine.getListParam(step, "columnMapping", List.of()), sourceDialect);
                requireSafeWhereClause(engine.getStringParam(step, "whereClause", null));
            }
            case UPSERT -> {
                requireTargetTable(step, targetDialect);
                requireIdentifiers(engine.getListParam(step, "keyColumns", List.of()),
                        targetDialect, "keyColumns");
            }
            case DDL, QUERY_TO_JSON, READ, EXPORT -> {
                // 这些类型不把标识符拼进 SQL：DDL 走 SqlValidator，其余只读 sourceSql
                // （由 engine.validateSourceSql 负责）。
            }
        }
    }

    /**
     * 校验并归一化写入类 step 的目标表名。
     *
     * <p>调用点原先只做 {@code dialect.normalizeTableName()}——那只是大小写归一，不是校验，
     * 于是带空格、分号、引号、{@code --} 的表名会原样进 {@code INSERT INTO ...}。
     */
    static String requireTargetTable(Step step, DatabaseDialect dialect) {
        String validated = requireIdentifier(step.targetTable(), dialect, "targetTable");
        return dialect == null ? validated : dialect.normalizeTableName(validated);
    }

    /** 解析 {@code 源列:目标列[:转换]}，两侧都必须是纯标识符。 */
    static ColumnMapping parseColumnMapping(List<String> mappings, DatabaseDialect dialect) {
        List<String> sourceColumns = new ArrayList<>();
        List<String> targetColumns = new ArrayList<>();
        List<String> transforms = new ArrayList<>();
        for (String mapping : mappings) {
            String[] parts = mapping == null ? new String[0] : mapping.split(":");
            if (parts.length < 2) {
                // 同步版在这里抛错；异步版原先是 continue，等于静默丢列，
                // 于是「映射写错了」表现为「目标表少了几列」而不是一条报错。
                throw reject("columnMapping", "格式非法，应为 源列:目标列[:转换]，实际为 " + mapping);
            }
            sourceColumns.add(requireIdentifier(parts[0], dialect, "columnMapping source column"));
            targetColumns.add(requireIdentifier(parts[1], dialect, "columnMapping target column"));
            transforms.add(parts.length >= 3 ? parts[2] : "none");
        }
        return new ColumnMapping(List.copyOf(sourceColumns), List.copyOf(targetColumns),
                List.copyOf(transforms));
    }

    /** 逐个校验一组标识符（列名、键列名）。 */
    static void requireIdentifiers(List<String> values, DatabaseDialect dialect, String paramName) {
        for (String value : values) {
            requireIdentifier(value, dialect, paramName);
        }
    }

    /**
     * 只接受纯标识符，返回去空白后的值。
     *
     * <p>方言为 null 时退回 {@link ValidationUtils#validateIdentifier}：它比方言的规则更严
     * （不允许下划线开头），宁可误拒也不放行——这条路径只在没有方言可用时走到。
     */
    static String requireIdentifier(String value, DatabaseDialect dialect, String paramName) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isBlank()) {
            throw reject(paramName, "不能为空");
        }
        if (dialect == null) {
            ValidationUtils.validateIdentifier(trimmed, paramName);
            return trimmed;
        }
        if (!dialect.isValidIdentifier(trimmed)) {
            throw reject(paramName, "不是合法标识符: " + value);
        }
        return trimmed;
    }

    /**
     * whereClause 会被字面拼到 SELECT 之后，所以必须过 WHERE 片段白名单。
     *
     * <p>同步版 {@code EtlTools.buildTransformSelect} 一直调这个校验，异步版曾整段遗漏。
     */
    static void requireSafeWhereClause(String whereClause) {
        ValidationUtils.validateWhereClause(whereClause, "whereClause");
    }

    private static McpValidationException reject(String paramName, String detail) {
        return new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                paramName + " " + detail);
    }
}
