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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.extension.CustomToolRegistrar;
import com.entropy.database.mcp.session.MultiSessionContext;
import com.entropy.database.mcp.session.McpToolContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session management and custom tool dispatch tools.
 *
 * <p>Provides three key capabilities:
 * <ol>
 *   <li><b>Session context management</b> — view and manipulate the current MCP session state</li>
 *   <li><b>Custom tool invocation</b> — dispatch calls to dynamically registered tools</li>
 *   <li><b>Cross-tool data sharing</b> — store and retrieve data via {@link MultiSessionContext}</li>
 * </ol>
 *
 * <p>These tools enable the AI model to:
 * <ul>
 *   <li>Save query results to session storage for later use</li>
 *   <li>Invoke custom plugins or extended tools</li>
 *   <li>Check session state and available tools</li>
 * </ul>
 */
@Component
public class McpSessionDispatchTool extends McpToolBase {

    private final CustomToolRegistrar customToolRegistrar;
    private final MultiSessionContext multiSessionContext;

    public McpSessionDispatchTool(CustomToolRegistrar customToolRegistrar,
                                  MultiSessionContext multiSessionContext) {
        this.customToolRegistrar = customToolRegistrar;
        this.multiSessionContext = multiSessionContext;
    }

    @McpTool(description = """
            【写入会话键值】把中间结果写入 MCP 会话级键值存储，供后续工具调用取用。
            前置条件：无需数据库连接。写入的数据只在内存中，条目 60 分钟后过期，服务重启即全部丢失。
            重要限制：本服务以无状态（STATELESS）模式运行，默认作用域是「单次工具调用」——本次写入的值在下一次 sessionGet 中读不到。跨调用共享需要调用方显式传入同一作用域标识，当前工具入参未暴露该标识，因此本工具实际只适合在同一次调用链内传值。
            使用场景：在同一次调用链中暂存查询结果、ETL 中间状态或用户偏好，避免重复查询。
            返回字段：namespace、key、stored（固定 true）。
            不要用于：需要跨会话或跨进程持久保存的数据（请写入数据库表，用 insertData 或 exportQueryToTable）；读取已写入的值（用 sessionGet）。
            标签：[write, session, cache, kv]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> sessionStore(
            @McpToolParam(description = "命名空间，用于隔离不同用途的键。推荐取值：queries（查询结果）、etl（ETL 中间状态）、preferences（偏好设置）、scratch（临时草稿）；也接受任意自定义字符串，不做校验") String namespace,
            @McpToolParam(description = "命名空间内的键名，同名写入会覆盖旧值") String key,
            @McpToolParam(description = "要存储的值，任意可序列化对象（字符串、数字、列表、对象均可）") Object value) {
        return safeExecute(() -> {
            multiSessionContext.set(namespace, key, value);
            log.debug("Stored '{}' in session namespace '{}' (session={})", key, namespace, multiSessionContext.currentSessionId());
            return success(context("namespace", namespace, "key", key, "stored", true));
        });
    }

    @McpTool(description = """
            【读取会话键值】按命名空间与键名读取 MCP 会话级键值存储中的值。
            前置条件：该键必须在同一作用域内、且未过期（写入后 60 分钟内）；无状态模式下上一次 sessionStore 写入的值通常读不到，取不到时返回 found=false 而不是报错。
            使用场景：取回同一调用链中先前暂存的中间结果。
            返回字段：namespace、key、found（是否命中）、value（未命中时为 null）。
            不要用于：不知道键名时逐个试探（先用 sessionKeys 列出该命名空间的所有键）。
            标签：[read, session, cache, kv]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> sessionGet(
            @McpToolParam(description = "命名空间，需与写入时一致（如 queries、etl、preferences、scratch）") String namespace,
            @McpToolParam(description = "要读取的键名") String key) {
        return safeExecute(() -> {
            Object value = multiSessionContext.get(namespace, key);
            if (value == null) {
                return success(Map.of("namespace", namespace, "key", key, "found", false, "value", null));
            }
            return success(Map.of("namespace", namespace, "key", key, "found", true, "value", value));
        });
    }

    @McpTool(description = """
            【列出会话键名】列出当前作用域下指定命名空间中的所有键名。
            前置条件：无；命名空间不存在或已全部过期时返回空列表与 count=0，不报错。
            使用场景：不确定先前存了哪些键时先列一遍，再用 sessionGet 取值。
            返回字段：namespace、keys（键名集合）、count（键数量）。
            不要用于：读取键对应的值（用 sessionGet）；查看会话本身的元信息如作用域标识（用 getSessionInfo）。
            标签：[read, session, cache, kv]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> sessionKeys(
            @McpToolParam(description = "要列出键名的命名空间（如 queries、etl、preferences、scratch）") String namespace) {
        return safeExecute(() -> {
            Set<String> keys = multiSessionContext.keys(namespace);
            return success(context("namespace", namespace, "keys", keys, "count", keys.size()));
        });
    }

    @McpTool(description = """
            【删除会话键值】从会话键值存储中删除指定命名空间下的一个键。
            破坏性影响：删除后该键的值立即不可读且无法恢复；键不存在时静默返回，不报错。仅影响内存中的会话数据，不触碰数据库中的任何表。
            前置条件：无。
            使用场景：中间结果已消费完毕，主动释放占用；或需要让后续调用重新计算而不是复用旧值。
            返回字段：namespace、key、removed（固定 true，无论键此前是否存在）。
            不要用于：清理所有已过期条目（用 sessionPurge）；清空数据库缓存（用 clearCache）。
            标签：[write, session, cache, delete]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> sessionRemove(
            @McpToolParam(description = "命名空间（如 queries、etl、preferences、scratch）") String namespace,
            @McpToolParam(description = "要删除的键名") String key) {
        return safeExecute(() -> {
            multiSessionContext.remove(namespace, key);
            return success(context("namespace", namespace, "key", key, "removed", true));
        });
    }

    @McpTool(description = """
            【清理过期会话数据】立即扫描所有作用域，删除已超过 60 分钟 TTL 的会话条目，并清理变空的命名空间与作用域。无入参。
            破坏性影响：被清理的条目立即消失且不可恢复。仅删除已过期条目，不会动仍在有效期内的数据，也不会触碰数据库中的任何表。后台已有定时任务周期性执行同样的清理，手动调用只是提前触发。
            前置条件：无。
            使用场景：写入过大对象后想立刻回收内存，或确认过期数据已被清走。
            返回字段：purged（固定 true）、activeSessions（清理后仍存在条目的作用域数量）。
            不要用于：删除某个仍有效的键（用 sessionRemove）；清空数据库查询缓存（用 clearCache）。
            标签：[write, session, cache, cleanup]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> sessionPurge() {
        return safeExecute(() -> {
            multiSessionContext.purgeExpired();
            return success(context("purged", true, "activeSessions", multiSessionContext.sessionCount()));
        });
    }

    @McpTool(description = """
            【调用自定义工具】执行通过扩展机制注册到本服务的自定义工具，行为完全由该工具自身决定。
            前置条件：目标工具必须已注册；先用 listCustomTools 确认工具名，未注册的包可先用 scanCustomTools 扫描。
            使用场景：调用插件或运行期动态注册的扩展能力，这些能力不在内置工具清单中。
            返回字段：直接透传目标工具的返回结果，字段结构未知；工具名不存在时返回 success=false、error、cause=NoSuchToolException、availableTools（可用工具名列表）；工具执行抛异常时返回 success=false、error、cause。
            风险提示：自定义工具可能执行写操作或访问外部系统，本服务不对其副作用做限制，调用前请确认其语义。
            不要用于：查看有哪些自定义工具（用 listCustomTools）；注册或发现新工具（用 scanCustomTools）。
            标签：[dispatch, custom, extension, invoke]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public Map<String, Object> invokeCustomTool(
            @McpToolParam(description = "要调用的自定义工具名，需与 listCustomTools 返回的 name 完全一致") String toolName,
            @McpToolParam(description = "工具入参，键值对形式；参数名需与目标工具的形参名一致。可省略，省略时按空参数调用", required = false) Map<String, Object> args) {
        return safeExecute(() -> {
            Map<String, Object> result = customToolRegistrar.invoke(toolName, args != null ? args : Map.of());
            McpToolContext.current().ifPresent(ctx -> ctx.putMeta("lastCustomTool", toolName));
            return result;
        });
    }

    @McpTool(description = """
            【列出自定义工具】列出当前已注册到扩展注册表的所有自定义工具及其描述。无入参。
            前置条件：无；未注册任何自定义工具时返回空列表与 count=0。
            使用场景：调用 invokeCustomTool 前确认工具名与用途，或确认 scanCustomTools 扫描是否生效。
            返回字段：tools（数组，每项含 name，注册时提供了描述则含 description）、count（工具数量）。
            不要用于：扫描包以发现并注册新工具（用 scanCustomTools）；执行工具（用 invokeCustomTool）；查看内置工具清单（内置工具由 MCP 协议的 tools/list 返回，不在此列）。
            标签：[read, custom, extension, list]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> listCustomTools() {
        return safeExecute(() -> {
            Set<String> tools = customToolRegistrar.listTools();
            List<Map<String, Object>> details = tools.stream()
                    .map(name -> customToolRegistrar.getTool(name)
                            .map(def -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("name", def.name());
                                m.put("description", def.description());
                                return m;
                            })
                            .orElse(Map.of("name", name)))
                    .toList();
            return success(context("tools", details, "count", tools.size()));
        });
    }

    @McpTool(description = """
            【扫描注册自定义工具】扫描指定 Java 包，把其中带 @McpTool 注解的方法注册为可调用的自定义工具。
            前置条件：目标包必须在本服务运行时的类路径上，且其所在类已是 Spring 容器中的 Bean；否则该类会被跳过，只记日志不报错。
            使用场景：插件类已随类路径加载但尚未注册，需要在运行期让它们变为可调用。
            返回字段：package（本次扫描的包名）、registered（本次新增的工具数，按注册表规模差值计算；重名覆盖时可能为 0）、totalTools（注册表中的工具总数）。
            注意：重复扫描同一个包会以同名覆盖已有注册项，不会报错。
            不要用于：查看已注册工具（用 listCustomTools）；执行工具（用 invokeCustomTool）。
            标签：[write, custom, extension, scan]
            """,
             annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public Map<String, Object> scanCustomTools(
            @McpToolParam(description = "要扫描的 Java 包名，完整包路径（如 com.example.mytools）；不支持通配符") String basePackage) {
        return safeExecute(() -> {
            int before = customToolRegistrar.listTools().size();
            customToolRegistrar.scanPackage(basePackage);
            int after = customToolRegistrar.listTools().size();
            return success(context("package", basePackage, "registered", after - before, "totalTools", after));
        });
    }

    @McpTool(description = """
            【查看会话信息】返回当前调用的会话作用域标识与调用上下文元数据。无入参。
            前置条件：无。
            使用场景：排查会话键值读写为何取不到数据、确认本次调用使用的连接名与耗时、确认关联标识以便对齐日志。
            返回字段：有调用上下文时含 sessionId、correlationId、toolName、connection、elapsedMillis、activeTools（已注册自定义工具数）、hasMeta；无调用上下文时只含 sessionId、hasSession（false）、activeTools。
            不要用于：查看会话里存了哪些键（用 sessionKeys）；查看连接池或服务指标（用 getPoolStats 或 getMetrics）。
            标签：[read, session, metadata]
            """,
             annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getSessionInfo() {
        return safeExecute(() -> {
            McpToolContext ctx = McpToolContext.current().orElse(null);
            if (ctx == null) {
                return success(Map.of("sessionId", multiSessionContext.currentSessionId(),
                        "hasSession", false, "activeTools", customToolRegistrar.listTools().size()));
            }
            return success(context(
                    "sessionId", multiSessionContext.currentSessionId(),
                    "correlationId", ctx.correlationId(),
                    "toolName", ctx.toolName(),
                    "connection", ctx.connection(),
                    "elapsedMillis", ctx.elapsedMillis(),
                    "activeTools", customToolRegistrar.listTools().size(),
                    "hasMeta", ctx.hasMeta("lastCustomTool")));
        });
    }
}
