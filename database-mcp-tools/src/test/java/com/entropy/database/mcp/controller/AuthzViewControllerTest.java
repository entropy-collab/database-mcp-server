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
package com.entropy.database.mcp.controller;

import com.entropy.database.mcp.authz.ToolAuthzProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/authz} 的 HTTP 契约。
 *
 * <p>钉的是「页面赖以说真话的那几件事」：
 * <ul>
 *   <li>角色图例与 {@code AuthzRole} 一致（reader 不能写、writer 能读）。前端照这份图例显示，
 *       它错了页面就会把一个只读身份画成能写；</li>
 *   <li>{@code table} 的有无翻成 {@code scope}，且表名按大写归一（与从 SQL 里取出的对象名一致）；</li>
 *   <li>{@code enabled} 原样回显。这个字段决定前端出 error 横幅还是 info 横幅——
 *       关闭时判定全放行，此时那张表只是"开启之后会生效"的预览；</li>
 *   <li>排序稳定，且连接级 grant 排在同连接的表级之前。</li>
 * </ul>
 *
 * <p>standaloneSetup 的理由同 {@code ToolAdminControllerTest}：本模块没有
 * {@code @SpringBootConfiguration}，而这里只需要控制器自己的返回体。它在真实过滤器链下需要
 * {@code ROLE_ADMIN} 那一条，由 {@code WebUiTest.AuthEnabled} 覆盖。
 */
class AuthzViewControllerTest {

    private static MockMvc mockMvc(boolean enabled, ToolAuthzProperties.Grant... grants) {
        var properties = new ToolAuthzProperties();
        properties.setEnabled(enabled);
        properties.setGrants(List.of(grants));
        return MockMvcBuilders.standaloneSetup(new AuthzViewController(properties)).build();
    }

    @Test
    void roleLegendMatchesTheRoleDefinition() throws Exception {
        mockMvc(false).perform(get("/api/authz"))
                .andExpect(status().isOk())
                // 顺序跟着 AuthzRole 的声明顺序，所以可以按下标断言
                .andExpect(jsonPath("$.roles[0].role").value("reader"))
                .andExpect(jsonPath("$.roles[0].canRead").value(true))
                .andExpect(jsonPath("$.roles[0].canWrite").value(false))
                .andExpect(jsonPath("$.roles[1].role").value("writer"))
                .andExpect(jsonPath("$.roles[1].canRead").value(true))
                .andExpect(jsonPath("$.roles[1].canWrite").value(true))
                .andExpect(jsonPath("$.roles[2].role").value("admin"))
                .andExpect(jsonPath("$.roles[2].canWrite").value(true));
    }

    /** 开关关闭 + 空配置：前端要靠这两个字段说出"现在是全放行"，所以两者都必须如实出现。 */
    @Test
    void disabledSwitchAndEmptyGrantsAreReportedAsIs() throws Exception {
        mockMvc(false).perform(get("/api/authz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.grantCount").value(0))
                .andExpect(jsonPath("$.subjectCount").value(0))
                .andExpect(jsonPath("$.connectionCount").value(0))
                .andExpect(jsonPath("$.grants").isEmpty());
    }

    @Test
    void connectionGrantExpandsToReadWithoutWrite() throws Exception {
        mockMvc(true, new ToolAuthzProperties.Grant("user:zhangsan", "prod", "reader", null))
                .perform(get("/api/authz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.grantCount").value(1))
                .andExpect(jsonPath("$.grants[0].subject").value("user:zhangsan"))
                .andExpect(jsonPath("$.grants[0].subjectType").value("user"))
                .andExpect(jsonPath("$.grants[0].subjectId").value("zhangsan"))
                .andExpect(jsonPath("$.grants[0].connection").value("prod"))
                // 连接级 grant 的 table 必须是 null 而不是空串：空串在前端会被当成"表名就是空的"
                .andExpect(jsonPath("$.grants[0].table").value((Object) null))
                .andExpect(jsonPath("$.grants[0].scope").value("connection"))
                .andExpect(jsonPath("$.grants[0].canRead").value(true))
                .andExpect(jsonPath("$.grants[0].canWrite").value(false));
    }

    /** 表级 grant：表名折成大写（与 SqlTables 取出的对象名一致），scope 变成 table。 */
    @Test
    void tableGrantIsUppercasedAndScopedToThatTable() throws Exception {
        mockMvc(true, new ToolAuthzProperties.Grant("agent:claude-x", "prod", "writer", "audit_log"))
                .perform(get("/api/authz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants[0].subjectType").value("agent"))
                .andExpect(jsonPath("$.grants[0].table").value("AUDIT_LOG"))
                .andExpect(jsonPath("$.grants[0].scope").value("table"))
                .andExpect(jsonPath("$.grants[0].canRead").value(true))
                .andExpect(jsonPath("$.grants[0].canWrite").value(true));
    }

    /**
     * 排序：主体 → 连接 → 表，且<b>连接级排在同连接的表级之前</b>。
     *
     * <p>后者靠 nullsFirst 实现。反过来的话，读这张表的人会先看到一条"只能写 AUDIT_LOG"的例外，
     * 再看到那条覆盖整条连接的 grant，而真实的判定顺序正好相反（连接级先判、过了就短路）。
     */
    @Test
    void grantsAreSortedWithConnectionScopeFirst() throws Exception {
        mockMvc(true,
                new ToolAuthzProperties.Grant("user:b", "prod", "reader", "orders"),
                new ToolAuthzProperties.Grant("user:a", "prod", "reader", "orders"),
                new ToolAuthzProperties.Grant("user:a", "prod", "admin", null))
                .perform(get("/api/authz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectCount").value(2))
                .andExpect(jsonPath("$.connectionCount").value(1))
                .andExpect(jsonPath("$.grants[0].subject").value("user:a"))
                .andExpect(jsonPath("$.grants[0].scope").value("connection"))
                .andExpect(jsonPath("$.grants[1].subject").value("user:a"))
                .andExpect(jsonPath("$.grants[1].table").value("ORDERS"))
                .andExpect(jsonPath("$.grants[2].subject").value("user:b"));
    }
}
