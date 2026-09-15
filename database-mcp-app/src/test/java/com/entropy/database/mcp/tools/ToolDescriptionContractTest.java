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

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具描述模板的强制校验。
 *
 * <p>为什么需要：{@code 标签：[...]} 这一行已经不只是文档，它是 {@link ToolPlane} 做数据面/控制面
 * 切分的唯一判据。而 {@link ToolCatalog#parseTags} 解析失败时是<b>静默</b>退化为空标签、
 * {@link ToolCatalog#summarize} 解析失败时静默退化为首行截断——漏写或写错不会有任何提示。
 *
 * <p>踩过的两次：
 * <ul>
 *   <li>0.4.0 前 {@code listConnections}/{@code describeConnection}/{@code getConnectionCount}
 *       误带 {@code admin} 标签，整个 connection-admin 组被判成控制面，纯数据面副本连"看一眼有
 *       哪些连接"都做不到；</li>
 *   <li>同一批工具的 {@code readOnlyHint = true} 与标签里的 {@code admin} 互相矛盾，两套元数据
 *       各写一遍却没有任何东西保证它们一致。</li>
 * </ul>
 *
 * <p>标签缺失本身是 fail-closed 的（落进控制面，不会误开放），所以这里的重点不是防灾，
 * 而是让"标签写错"在 CI 阶段就暴露，而不是等某个部署形态下工具凭空消失。
 */
@SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.security.enabled=false"
})
class ToolDescriptionContractTest {

    /** 描述模板里的中文名，形如 {@code 【清空查询缓存】}。 */
    private static final Pattern TITLE = Pattern.compile("【([^】]+)】");

    private static final Pattern TAGS = Pattern.compile("标签[：:]\\s*\\[([^\\]]*)\\]");

    /** 与 {@link ToolPlane} 的判据保持一致：出现任意一个就说明工具会改动状态。 */
    private static final Set<String> MUTATING_TAGS =
            Set.of("write", "ddl", "destructive", "admin");

    /** 参数描述里出现这些说法，就等于向模型承诺"这个参数可以不传"。 */
    private static final List<String> OPTIONAL_HINTS = List.of(
            "可省略", "留空", "省略时", "省略、", "可传 null", "传 null 时", "传 null 表示");

    @Autowired
    private ObjectProvider<McpToolBase> toolBeans;

    private record Tool(String name, String description, boolean readOnlyHint) {

        List<String> tags() {
            Matcher matcher = TAGS.matcher(description);
            if (!matcher.find()) {
                return List.of();
            }
            return java.util.Arrays.stream(matcher.group(1).split(","))
                    .map(raw -> raw.strip().toLowerCase())
                    .filter(tag -> !tag.isEmpty())
                    .toList();
        }
    }

    private List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        toolBeans.orderedStream().forEach(bean -> {
            for (Method method : AopUtils.getTargetClass(bean).getDeclaredMethods()) {
                McpTool annotation = method.getAnnotation(McpTool.class);
                if (annotation == null) {
                    continue;
                }
                String name = annotation.name().isBlank() ? method.getName() : annotation.name();
                tools.add(new Tool(name, annotation.description(),
                        annotation.annotations().readOnlyHint()));
            }
        });
        return tools;
    }

    /** 空导入会让下面每条断言都空过，先证明真的扫到了工具。 */
    @Test
    void findsTheRealToolSet() {
        assertThat(tools()).hasSizeGreaterThan(100);
    }

    @Test
    void everyToolDeclaresAChineseTitle() {
        assertThat(tools()).allSatisfy(tool ->
                assertThat(TITLE.matcher(tool.description()).find())
                        .as("%s 的描述缺少【中文名】开头，ToolCatalog.summarize 会退化为首行截断",
                                tool.name())
                        .isTrue());
    }

    @Test
    void everyToolDeclaresNonEmptyTags() {
        assertThat(tools()).allSatisfy(tool ->
                assertThat(tool.tags())
                        .as("%s 的描述缺少 标签：[...] 行，plane 切分会把它当作控制面工具", tool.name())
                        .isNotEmpty());
    }

    /** 标签是给 plane 用的，{@code readOnlyHint} 是给客户端看的，两者不能互相打脸。 */
    @Test
    void readOnlyHintAgreesWithTags() {        assertThat(tools()).allSatisfy(tool -> {
            List<String> tags = tool.tags();
            if (tool.readOnlyHint()) {
                assertThat(tags)
                        .as("%s 声明了 readOnlyHint=true，标签里却有会改动状态的关键字，"
                                + "它会被 plane=data 错误地摘掉", tool.name())
                        .doesNotContainAnyElementsOf(MUTATING_TAGS);
                assertThat(tags)
                        .as("%s 声明了 readOnlyHint=true，却没有 read 标签，进不了数据面", tool.name())
                        .contains("read");
            } else {
                assertThat(tags)
                        .as("%s 既没声明 readOnlyHint=true，标签里也没有任何会改动状态的关键字，"
                                + "两处元数据对不上：只读工具请补 readOnlyHint=true，"
                                + "会改状态的工具请补 write/ddl/destructive/admin 之一", tool.name())
                        .containsAnyElementsOf(MUTATING_TAGS);
            }
        });
    }

    /**
     * 参数描述说"可省略 / 留空 / 传 null"时，schema 里必须真的把它标成可选。
     *
     * <p>为什么必须有：{@code @McpToolParam} 不写 {@code required = false} 就进 schema 的
     * {@code required} 列表，而 SDK 的入参校验对必填项<b>既不接受省略也不接受 null</b>。
     * 模型照描述省略后拿到的是 {@code 未找到所需属性"xxx"}——描述教它做一件一定会被拒的事，
     * 它没有任何线索能自己纠正。
     *
     * <p>0.4.0 实测：{@code ToolParams.CONNECTION_DESCRIPTION} 的 Javadoc 写着"Marked
     * required = false"，15 个调用点里一个都没真的标上；同时另有 14 个调用点已经标了，
     * 同一句描述在两种 schema 下并存。{@code createSealedConnection} 的 dialect 也是这样：
     * 描述说"留空时按 jdbcUrl 自动推断"，实际必须显式传一个字符串。
     */
    @Test
    void paramsThatSoundOptionalAreDeclaredOptional() {
        List<String> offenders = new ArrayList<>();
        toolBeans.orderedStream().forEach(bean -> {
            for (Method method : AopUtils.getTargetClass(bean).getDeclaredMethods()) {
                McpTool tool = method.getAnnotation(McpTool.class);
                if (tool == null) {
                    continue;
                }
                String toolName = tool.name().isBlank() ? method.getName() : tool.name();
                for (Parameter parameter : method.getParameters()) {
                    McpToolParam param = parameter.getAnnotation(McpToolParam.class);
                    if (param == null || !param.required()) {
                        continue;
                    }
                    if (soundsOptional(param.description())) {
                        offenders.add(toolName + "." + parameter.getName());
                    }
                }
            }
        });
        assertThat(offenders)
                .as("这些参数的描述承诺可以省略，schema 里却是必填：要么补 required = false，"
                        + "要么把描述改成「必填」——两者不一致时模型一定会踩")
                .isEmpty();
    }

    /**
     * 连接名参数一律叫 {@code connection}，不许再出现 {@code connectionName}。
     *
     * <p>为什么必须有：Spring AI 直接拿 Java 形参名当 JSON schema 的属性名，所以形参改名就是改契约。
     * 0.4.0 实测同一个语义分裂成两个名字——61 处 {@code connection}、22 处 {@code connectionName}
     * （备份 5 个、血缘 7 个、ETL 5 个、DDL 远程/批量 2 个，加 describeConnection、
     * getPoolStatsForConnection、checkTableQuality）。传错的表现是「未找到所需属性」或
     * {@code Connection is required but not provided}，很容易被当成服务故障去查。
     *
     * <p>更隐蔽的一层：{@link com.entropy.database.mcp.aop.ConnectionArgExtractor} 反射找的是名为
     * {@code connection} 的形参，找不到就退化成「第一个短的非 SQL 字符串参数」。对
     * {@code getUpstream(tableName, connectionName, format)} 这种签名，退化路径会把 <b>tableName</b>
     * 当成连接名写进错误前缀与性能日志——名字不统一时，报错本身就是错的。
     *
     * <p>返回字段里的 {@code connectionName} 是另一回事，属于输出契约，本测试不管。
     */
    @Test
    void connectionParamIsAlwaysNamedConnection() {
        List<String> offenders = new ArrayList<>();
        toolBeans.orderedStream().forEach(bean -> {
            for (Method method : AopUtils.getTargetClass(bean).getDeclaredMethods()) {
                McpTool tool = method.getAnnotation(McpTool.class);
                if (tool == null) {
                    continue;
                }
                String toolName = tool.name().isBlank() ? method.getName() : tool.name();
                for (Parameter parameter : method.getParameters()) {
                    McpToolParam param = parameter.getAnnotation(McpToolParam.class);
                    if (param == null) {
                        continue;
                    }
                    boolean wrongName = "connectionName".equalsIgnoreCase(parameter.getName());
                    boolean sharedDescription = ToolParams.CONNECTION_DESCRIPTION.equals(param.description())
                            || ToolParams.CONNECTION_REQUIRED_DESCRIPTION.equals(param.description());
                    if (wrongName || (sharedDescription && !"connection".equals(parameter.getName()))) {
                        offenders.add(toolName + "." + parameter.getName());
                    }
                }
            }
        });
        assertThat(offenders)
                .as("连接名参数必须叫 connection：形参名就是 JSON schema 的属性名，"
                        + "两个名字并存会让调用方按错的那个传，且会让 ConnectionArgExtractor "
                        + "退化到「第一个短字符串」从而把表名当成连接名")
                .isEmpty();
    }

    /** {@code 不可省略} 里含有 {@code 可省略}，先摘掉否定表述再判。 */
    private static boolean soundsOptional(String description) {
        String text = description.replace("不可省略", "").replace("不能省略", "");
        return OPTIONAL_HINTS.stream().anyMatch(text::contains);
    }
}
