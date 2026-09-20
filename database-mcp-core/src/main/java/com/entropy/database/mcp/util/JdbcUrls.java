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
package com.entropy.database.mcp.util;

import java.util.Locale;

/**
 * 关于 {@code spring.datasource.url} 的判据，与展示无关（脱敏见 {@link JdbcUrlMasker}）。
 *
 * <p><b>为什么要有这个类</b>：「这个库重启之后还在不在」是服务端状态的每一张表都要回答的问题，
 * 而每个持久化组件都要靠它决定启动时是否打那条「重启即丢」的 WARN。这份判据原先有两份完全相同的
 * 拷贝（{@code AuditLogInitializer.warnIfInMemory} 与 {@code JdbcUserStore.isInMemory}），
 * 第三个持久化组件（{@code ToolToggleRepository}）出现时它们又不在同一个包里，于是抽到这里。
 *
 * <p>两处原有的静态方法保留为委托：它们各自的测试断言的是「这个组件会不会告警」，
 * 那条断言应当挂在组件上，而判据只有一份。
 */
public final class JdbcUrls {

    private JdbcUrls() {
    }

    /**
     * 按 JDBC URL 判断库是否只存在于进程内存里。
     *
     * <p>判据放在 URL 上而不是 {@link java.sql.DatabaseMetaData}：产品名只能告诉你这是 H2，
     * 区分不出 {@code jdbc:h2:mem:}（进程一停必丢）与 {@code jdbc:h2:file:}（落盘留存），
     * 而这两者的结论是相反的。HSQLDB、Derby、SQLite 的内存形态一并覆盖——它们同样可能被部署方换上来。
     *
     * @param url 原始 JDBC URL，{@code null} 与空白都当作「不是内存库」（没有库可判）
     */
    public static boolean isInMemory(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String normalized = url.toLowerCase(Locale.ROOT);
        return normalized.startsWith("jdbc:h2:mem")
                || normalized.startsWith("jdbc:hsqldb:mem")
                || normalized.contains(":memory:")
                || normalized.startsWith("jdbc:derby:memory");
    }
}
