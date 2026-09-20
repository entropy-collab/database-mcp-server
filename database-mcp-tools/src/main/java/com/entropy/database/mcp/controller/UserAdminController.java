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

import com.entropy.database.mcp.security.UserAdminService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 调用者身份的运维接口。
 *
 * <p>挂在 {@code /api/**} 之下，因此由 {@code SecurityConfig} 强制 {@code ROLE_ADMIN}——
 * 环境变量里的管理员天生有，库里的身份要在新建时显式给 {@code roles}。
 *
 * <p><b>所以这个接口能造出与自己同权限的身份。</b>这是有意的（否则"不重启加一个管理员"做不到），
 * 但它意味着一个 {@code ROLE_ADMIN} 泄漏就能自我复制成任意多个，停用原来那个也拿不回来。
 * 授予权限时的日志由 {@code JdbcUserStore.create} 打出，那是事后唯一能查到"谁被给了管理员"的地方。
 *
 * <p><b>刻意不做成 MCP 工具。</b>做成工具意味着一个 AI agent 可以创建调用者身份，而工具级授权
 * 还没就位（{@code /mcp} 目前只要求 {@code authenticated}）。那会是一条提权路径：拿到任意一个
 * 身份的人可以造出更多身份。等策略引擎接上、能表达"只有 X 能管身份"之后再说。
 *
 * <p>口令以原文提交、服务端哈希，理由与代价见 {@link UserAdminService} 类注释——
 * 简言之这个接口必须跑在 HTTPS 上，且请求体不要进访问日志。
 */
@RestController
@RequestMapping("/api/users")
public class UserAdminController {

    /**
     * 没配 {@code spring.datasource.url} 时为 null：{@link UserAdminService} 挂在那个键上，
     * 而不配库是受支持的部署形态（身份回落到凭据文件或只有管理员）。控制器因此不能强依赖它，
     * 否则整个应用起不来——这和 {@link AuditLogController} 对仓储的处理是同一个理由。
     */
    private final UserAdminService users;

    public UserAdminController(@Nullable UserAdminService users) {
        this.users = users;
    }

    /**
     * 新增身份的请求体。{@code password} 是原文，服务端哈希后落库，响应里不回显。
     *
     * <p>{@code roles} 可缺省：JSON 里不给这个字段就是不授予任何权限，与升级前的行为一致。
     * 可选值只有 {@code ROLE_ADMIN} 与 {@code ROLE_DBA}，其它值会被 400 拒掉而不是静默忽略——
     * 静默忽略的症状是"配了但不生效"。
     */
    public record CreateRequest(String username, String type, String password, List<String> roles) {}

    @GetMapping
    public List<UserAdminService.View> list() {
        return required().list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserAdminService.View create(@RequestBody CreateRequest request) {
        try {
            return required().create(request.username(), request.type(), request.password(),
                    request.roles());
        } catch (IllegalArgumentException e) {
            // 入参问题回 400：500 会让调用方以为是服务端故障而去重试，而重试一万次结果相同
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /**
     * 停用一个身份。
     *
     * <p>{@code @PathVariable} 显式写出 name：不写时 Spring 只能靠 class 文件里的
     * {@code MethodParameters} 反推参数名，而那段信息只有编译带 {@code -parameters} 才会写入。
     * 下游用自己的构建配置重新打包时参数名会退化成 {@code arg0}，表现为启动后每次调用都失败，
     * 而不是编译期报错。同 {@link AuditLogController#getLogs}。
     */
    @DeleteMapping("/{username}")
    public Map<String, Object> disable(@PathVariable(name = "username") String username) {
        boolean changed;
        try {
            changed = required().disable(username);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知调用者: " + username);
        }
        // 返回 disabled 而不是空体：调用方需要能区分"停用成功"与"本来就是停用状态"
        return Map.of("username", username, "disabled", true);
    }

    private UserAdminService required() {
        if (users == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "身份表未启用：没有配置 spring.datasource.url。"
                    + "改配置文件的 entropy.mcp.security.users-file，或配上状态库。");
        }
        return users;
    }
}
