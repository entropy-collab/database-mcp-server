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
package com.entropy.database.mcp.web;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「前端产物有没有进 classpath」这一件事的守门人。刻意不起 Spring 上下文——它要在
 * 最便宜的层面上给出最直白的失败信息。
 *
 * <h2>为什么需要一个专门的测试来做这件事</h2>
 * <p>前端构建可以用 {@code -Dfrontend.skip=true} 关掉（理由见 {@code database-mcp-app/pom.xml}：
 * 项目的构建命令是 {@code mise exec -- mvn -o ...}，而 {@code -o} 是离线，
 * 而 {@code install-node-and-npm} 与 {@code npm ci} 在冷缓存下都要联网）。
 *
 * <p>那个开关带来的风险很具体：<b>跳过前端 + clean 之后，构建会照样 BUILD SUCCESS，
 * 打出一个 {@code BOOT-INF/classes/static/} 是空的 jar</b>。这种 jar 装上去，
 * {@code /} 是 404，运维会先怀疑反向代理、再怀疑 Spring 配置，最后才想到是打包漏了东西。
 *
 * <p>所以这个开关刻意<b>不</b>顺带跳过测试：产物缺失时这里失败，构建就停在打包之前，
 * 并且失败信息直接把「你是不是加了 -Dfrontend.skip」这句话说出来。
 * 两条路径因此都成立——默认构建产出可用的 UI；离线构建（不带 clean）复用上一次的产物照样能过。
 */
class WebUiStaticAssetsPresenceTest {

    private static final String INDEX_HTML = "static/index.html";

    private static final String MISSING_HINT = """
            classpath 上找不到 %s —— 前端产物没有进 target/classes/static。

            最可能的原因：这次构建带了 -Dfrontend.skip=true，并且同时做了 clean，
            于是上一次的产物被删掉、这一次又没有重建。

            两种解法，按情况选一个：
              1. 让前端真的构建一次（需要网络）：
                 mise exec -- mvn -pl database-mcp-app generate-resources
              2. 纯 Java 改动、又必须离线：去掉 clean，让上一次的产物留在 target/ 里：
                 mise exec -- mvn -o install -Dfrontend.skip=true

            这个测试存在的唯一目的，就是不让"static/ 是空的"这件事静默通过打包。
            """.formatted(INDEX_HTML);

    @Test
    void frontendBuildOutputIsOnTheClasspath() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(INDEX_HTML)) {
            assertThat(in).as(MISSING_HINT).isNotNull();

            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            // 是 Vite 的产物，而不是谁手写的占位 index.html：产物一定引用 hash 化的 /assets/ 资源。
            assertThat(html)
                    .as("static/index.html 在，但没有引用任何 /assets/ 资源；"
                        + "它不像是 Vite 的产物，检查 vite.config.mjs 的 build.outDir")
                    .contains("/assets/");

            // 主题属性掉了页面不会报错，只会渲染成没有任何 design token 的裸元素，
            // 所以在这里也钉一次，见 index.html 里的说明。
            assertThat(html)
                    .as("index.html 缺少 data-astryx-theme：Astryx 的主题 CSS 全部包在 "
                        + "@scope ([data-astryx-theme=\"neutral\"]) 里，缺了它页面会完全没有样式")
                    .contains("data-astryx-theme");
        }
    }
}
