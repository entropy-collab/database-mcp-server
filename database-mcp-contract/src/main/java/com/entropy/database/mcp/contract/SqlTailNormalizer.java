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
package com.entropy.database.mcp.contract;

import java.util.Optional;

/**
 * 把一条厂商方言 DDL 末尾「解析器不认、但对校验无关紧要」的尾巴摘掉，好让它能过 AST 校验。
 *
 * <h2>为什么需要这个缝</h2>
 * 写入路径的闸门先把语句解析成 AST，再按语句类型做白名单。而解析器是 RDBMS-agnostic 的
 * 单一联合语法，没有厂商模式，所以像 Oracle 分区表上的 {@code CREATE INDEX ... LOCAL}
 * 这类尾巴一直解析不出来——语句因此被拒，而它其实是完全正常的建索引语句。等联合语法长出
 * 每一家厂商的 DDL 尾巴是没有 ETA 的事。
 *
 * <h2>能力被刻意做得极窄：只能截后缀</h2>
 * 实现**只允许**返回原文的一个前缀，也就是「把末尾某段拿掉」。这不是靠实现自觉：调用方
 * 会强制校验返回值确实是原文前缀、且被拿掉的那段形状足够无害，不满足就当作实现违约处理。
 * 这样一来实现能造成的最坏后果是「校验时看到的语句比实际执行的少一截尾巴」，而**不可能**
 * 是「把 DROP TABLE 改写成 SELECT 1 骗过白名单」。
 *
 * <p>截断后的文本走的是完整的既有校验管线：语句类型白名单仍由解析出的 AST 类型决定，
 * 表白名单仍从 AST 取对象名。也就是说这个缝只影响「能不能解析成功」，不影响「解析成功之后
 * 准不准」。执行时用的始终是原文。
 *
 * <h2>只在 DDL 路径上生效</h2>
 * SELECT 路径不走这个缝：查询没有厂商 DDL 尾巴这类问题，而放松只读路径的解析要求没有收益。
 *
 * <h2>目前不做 ServiceLoader 发现</h2>
 * 这个接口放在零依赖的 contract 模块，形状上已经可以当 SPI 用，但**当前实现只加载内置的那一个**，
 * 不扫描 classpath。理由是信任模型不同：{@code DialectProvider} 可以自动发现，因为选错方言
 * 最多是 SQL 报错；而这里选错实现是在放松写入路径的闸门。等第一个内置实现在生产里跑稳、
 * 尾巴形状的约束被真实用例检验过之后，再决定要不要开放外部注册，以及是否要求按名字显式启用。
 */
public interface SqlTailNormalizer {

    /** 用于日志与将来的显式启用配置，同名实现视为配置错误。 */
    String name();

    /** 多个实现时的询问顺序，小的先问。同 priority 的相对顺序不做保证。 */
    default int priority() {
        return 0;
    }

    /**
     * @param sql 已经 trim 过的单条语句原文，解析器对它报了错
     * @return 去掉尾巴后的语句；{@link Optional#empty()} 表示「这条我不认识」，交给下一个实现。
     *         返回值必须是 {@code sql} 的严格前缀，否则调用方会拒绝该结果并记录违约。
     */
    Optional<String> stripUnparsableTail(String sql);
}
