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

import org.springframework.ai.mcp.annotation.McpTool;

import java.util.Map;

/**
 * 供 {@link ToolCatalog}、{@link IntentRouter}、{@link ToolExposureFilter} 单测使用的假工具。
 *
 * <p>刻意不复用真实工具类：真实工具需要数据源门面等依赖，且它们的描述会随业务演进变化，
 * 断言挂在上面会让测试变脆。这里的描述严格遵循项目的中文描述模板，用来验证解析逻辑。
 */
final class SampleTools {

    private SampleTools() {
    }

    /** 分组名应推导为 {@code query-like}。 */
    static class QueryLikeTools extends McpToolBase {

        @McpTool(description = """
                【执行示例查询】在示例连接上执行一条只读查询并返回结果行。
                前置条件：无。
                返回字段：rows、rowCount。
                标签：[read, query, select]
                """)
        public Map<String, Object> executeSample() {
            return success("rows", Map.of());
        }
    }

    /** 分组名应推导为 {@code health-like}。 */
    static class HealthLikeTools extends McpToolBase {

        /** 显式 name 应当覆盖方法名。 */
        @McpTool(name = "checkSampleHealth", description = """
                【检查示例健康】返回示例连接的连通性与版本信息。
                标签：[read, health, monitor]
                """)
        public Map<String, Object> healthMethodName() {
            return success("status", "UP");
        }

        @McpTool(description = """
                【示例闪回查询】按时间点读取历史版本数据，仅 Oracle 支持。
                标签：[read, oracle, flashback]
                """)
        public Map<String, Object> flashbackSample() {
            return success("rows", Map.of());
        }

        /** 描述不合模板、也没有标签行，摘要与标签应优雅退化而不是抛错。 */
        @McpTool(description = "plain description without the Chinese template")
        public Map<String, Object> untemplatedSample() {
            return success("ok", true);
        }
    }

    /** 类名以 {@code Tool} 结尾，分组名应推导为 {@code sample-dispatch}。 */
    static class SampleDispatchTool extends McpToolBase {

        @McpTool(description = """
                【示例分派】把请求转给自定义工具。
                标签：[write, session]
                """)
        public Map<String, Object> dispatchSample() {
            return success("ok", true);
        }
    }
}
