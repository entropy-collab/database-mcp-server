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

import com.entropy.database.mcp.tools.ToolCatalog;
import com.entropy.database.mcp.tools.ToolToggleRegistry;
import com.entropy.database.mcp.tools.ToolToggleRegistry.ToggleResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 运行期启用/停用 MCP 工具的运维接口。<b>停用 = 工具从 {@code tools/list} 消失</b>，
 * 真正执行与全部语义都在 {@link ToolToggleRegistry}，本类只做 HTTP 侧的翻译。
 *
 * <h2>鉴权</h2>
 * <p>挂在 {@code /api/**} 之下，因此由 {@code SecurityConfig.securityFilterChain} 里的
 * {@code auth.requestMatchers("/api/**").hasRole("ADMIN")} 那一行强制 {@code ROLE_ADMIN}。
 * 这里刻意<b>不</b>再加方法级注解：两处各写一份的话，改动其中一处就会出现"以为收紧了其实没有"。
 * 注意 {@code entropy.mcp.security.enabled=false} 时那一行整个不生效，本接口对任何能连上端口的人
 * 开放——那个部署形态下它是一个<b>写</b>接口，比只读面板的暴露面严重，评估裸跑风险时要单独看。
 *
 * <h2>为什么不动 {@code WebUiController}</h2>
 * <p>那个类的约定是只读面板端点（{@code /api/ui/**}），它的每一个方法都只读进程内状态。
 * 本接口是写操作，混进去就再也没有"这个类不改任何状态"这条能一眼看出来的约束了。
 *
 * <h2>为什么不做成 MCP 工具</h2>
 * <p>与 {@link UserAdminController} 同一个理由：做成工具意味着一个 AI agent 可以把工具停掉，
 * 包括把管理工具自己停掉。那是一条自我剥夺路径，而工具级授权还没就位。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolAdminController {

    private final ToolToggleRegistry toggles;
    private final ToolCatalog catalog;

    public ToolAdminController(ToolToggleRegistry toggles, ToolCatalog catalog) {
        this.toggles = toggles;
        this.catalog = catalog;
    }

    /**
     * 开关请求体。
     *
     * <p>{@code Boolean} 而不是 {@code boolean}：缺字段与 {@code false} 必须能区分。基本类型会把
     * {@code {}} 静默读成"停用=false"，也就是把一个写错的请求执行成一次真实的启用操作。
     */
    public record ToggleRequest(Boolean disabled) {
    }

    /**
     * 工具清单与当前开关状态。
     *
     * <p>GET /api/tools →
     * {@code {total, exposed, disabledCount, persisted,
     *          tools: [{name, group, summary, tags, exposed, disabled}]}}
     *
     * <p>{@code exposed=false} 的工具是被部署期 {@code entropy.mcp.tools} 裁掉的：它们列出来是为了
     * 让运维看到"这台服务器上还有这些工具，但本接口动不了它们"，对它们调 PUT 会得到 400。
     *
     * <p>{@code persisted=false} 表示没配 {@code spring.datasource.url}，改动活不过重启。
     * 这个字段必须在：与 {@link UserAdminController} 不同，这里<b>不能</b>回 503——内存模式是
     * 受支持的部署形态（默认形态就是它），不是故障，接口照常可用，只是后果不同。
     *
     * <p>全部用 {@link LinkedHashMap} 组装而不是 {@code Map.of}：字段顺序要稳定，
     * 而且 {@code Map.of} 不接受 null 值——{@code group} 对非目录来源的工具就是 null，
     * 这个仓库刚为同一个原因修过四处 NPE。
     */
    @GetMapping
    public Map<String, Object> list() {
        Set<String> exposedNames = toggles.exposedToolNames();
        Set<String> disabledNames = toggles.disabledToolNames();

        List<Map<String, Object>> tools = new ArrayList<>();
        catalog.descriptors().stream()
                .sorted(Comparator.comparing(ToolCatalog.ToolDescriptor::name))
                .forEach(descriptor -> tools.add(entry(descriptor.name(), descriptor.group(),
                        descriptor.summary(), descriptor.tags(), exposedNames, disabledNames)));
        // 快照里有、目录里没有的工具：目录只覆盖 McpToolBase 子类，其它来源（扩展注册的工具）
        // 同样能被停用，漏掉它们会让 exposed 这个计数和下面的列表对不上
        exposedNames.stream()
                .filter(name -> !catalog.contains(name))
                .forEach(name -> tools.add(
                        entry(name, null, "", List.of(), exposedNames, disabledNames)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", tools.size());
        body.put("exposed", exposedNames.size());
        body.put("disabledCount", disabledNames.size());
        body.put("persisted", toggles.persistent());
        body.put("tools", tools);
        return body;
    }

    /**
     * 停用或启用单个工具。
     *
     * <p>PUT /api/tools/{name}，体 {@code {"disabled": true|false}} →
     * {@code {name, disabled, changed, remainingExposed, persisted}}
     *
     * <p>{@code changed=false} 表示它本来就是这个状态（幂等，不是错误）。
     *
     * <p>{@code @PathVariable} 显式写出 name，理由见 {@link AuditLogController#getLogs}：
     * 不写时参数名依赖编译期的 {@code -parameters}，下游用自己的构建配置重打包后会退化成
     * {@code arg0}，表现为每次调用都失败，而不是编译期报错。
     */
    @PutMapping("/{name}")
    public Map<String, Object> toggleTool(@PathVariable(name = "name") String name,
                                          @RequestBody ToggleRequest request) {
        boolean disabled = requireDisabledFlag(request);
        ToggleResult result = apply(() -> toggles.setDisabled(name, disabled));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("disabled", disabled);
        body.put("changed", result.changed() > 0);
        body.put("remainingExposed", result.remainingExposed());
        body.put("persisted", toggles.persistent());
        return body;
    }

    /**
     * 按分组批量停用或启用。
     *
     * <p>PUT /api/tools/groups/{group}，体 {@code {"disabled": true|false}} →
     * {@code {group, disabled, affected: [...], changed, remainingExposed, persisted}}
     *
     * <p>{@code affected} 是这条命令<b>作用到</b>的工具（该分组下全部已暴露的工具），
     * {@code changed} 是其中状态真的变了的个数。两者不同是正常的：重复执行一次同样的命令，
     * {@code affected} 不变而 {@code changed} 归零。
     */
    @PutMapping("/groups/{group}")
    public Map<String, Object> toggleGroup(@PathVariable(name = "group") String group,
                                           @RequestBody ToggleRequest request) {
        boolean disabled = requireDisabledFlag(request);
        ToggleResult result = apply(() -> toggles.setGroupDisabled(group, disabled));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", group);
        body.put("disabled", disabled);
        body.put("affected", result.affected());
        body.put("changed", result.changed());
        body.put("remainingExposed", result.remainingExposed());
        body.put("persisted", toggles.persistent());
        return body;
    }

    private static Map<String, Object> entry(String name, String group, String summary,
                                             List<String> tags,
                                             Set<String> exposedNames, Set<String> disabledNames) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("group", group);
        entry.put("summary", summary);
        entry.put("tags", tags);
        entry.put("exposed", exposedNames.contains(name));
        entry.put("disabled", disabledNames.contains(name));
        return entry;
    }

    /**
     * 缺 {@code disabled} 字段回 400 而不是当 false。
     *
     * <p>把缺字段读成"启用"意味着一个打错的请求会静默执行一次状态变更，而调用方以为什么都没发生。
     */
    private static boolean requireDisabledFlag(ToggleRequest request) {
        if (request == null || request.disabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请求体必须带 disabled 字段：{\"disabled\": true} 停用，{\"disabled\": false} 启用");
        }
        return request.disabled();
    }

    /**
     * 把注册表的入参校验失败翻成 400。
     *
     * <p>500 会让调用方以为是服务端故障而去重试，而重试一万次结果相同——与
     * {@link UserAdminController#create} 的处理一致。消息原样透出：注册表在里面列了可选值。
     */
    private static ToggleResult apply(Supplier<ToggleResult> operation) {
        try {
            return operation.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
