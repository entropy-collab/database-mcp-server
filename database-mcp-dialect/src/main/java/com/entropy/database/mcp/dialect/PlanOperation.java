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
package com.entropy.database.mcp.dialect;

/**
 * 执行计划里一步操作「在做什么」，与数据库品种无关。
 *
 * <p>刻意不描述某个数据库怎么称呼它：Oracle 的 {@code TABLE ACCESS FULL}、PostgreSQL 的
 * {@code Seq Scan}、MySQL 的 {@code type=ALL}、SQL Server 的 {@code Table Scan} 说的是同一件事
 * （{@link #FULL_TABLE_SCAN}），把品种词汇留在 {@link DatabaseDialect#classifyPlanLine(String)} 里，
 * 服务层只面对这几个语义值。
 *
 * <p>这里没有「哈希排序」之类的复合概念：一行计划只做一件事，跨行的组合（例如同时出现排序与哈希连接）
 * 由调用方在收集到的集合上判断，不塞进枚举。
 */
public enum PlanOperation {

    /** 全表扫描：不经索引读整张表。最值得告警的一步。 */
    FULL_TABLE_SCAN,

    /** 索引范围/区间扫描，含只读索引与位图形态。 */
    INDEX_RANGE_SCAN,

    /** 唯一索引/主键的单行定位。 */
    INDEX_UNIQUE_SCAN,

    /** 嵌套循环连接。 */
    NESTED_LOOP_JOIN,

    /** 哈希连接。 */
    HASH_JOIN,

    /** 排序（含 MySQL 的 {@code Using filesort}）。 */
    SORT,

    /** 无法归入上面任何一类，或该行不是操作行（表头、成本行、空行）。 */
    OTHER
}
