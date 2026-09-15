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
package com.entropy.database.mcp.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 质量报告的 JSON 导出。
 *
 * <p>0.5.0 线上实测：{@code checkTableQuality} 的 {@code formattedReport} 恒为
 * {@code {"error":"Quality report generation failed"}}——{@link QualityReportService} 用的是自建
 * {@code ObjectMapper}，没注册 {@code JavaTimeModule}，序列化 {@link QualityReport#checkedAt()}
 * （{@code Instant}）时抛 {@code InvalidDefinitionException}。异常被 catch 成一行错误串，
 * 结构化字段又是好的，所以从返回体上很难看出来，只有翻容器日志才发现。这条用例就是那个哨兵。
 */
class QualityReportServiceTest {

    private final QualityReportService service = new QualityReportService();

    @Test
    @DisplayName("导出 JSON 时能序列化 Instant 字段，不再降级成错误串")
    void exportsInstantField() {
        String json = service.exportJson(report());

        assertThat(json).doesNotContain("Quality report generation failed");
        assertThat(json).contains("\"checkedAt\"").contains("\"tableName\" : \"EMP_CARD_INFO\"");
    }

    /**
     * 0.5.1 线上实测：只注册 JavaTimeModule 时 checkedAt 渲染成 {@code 1788186121.969010604}，
     * 跟同一个响应里 report.checkedAt 的 ISO 文本对不上。
     */
    @Test
    @DisplayName("checkedAt 渲染成 ISO-8601 文本而不是 epoch 浮点数")
    void writesInstantAsIsoText() {
        String json = service.exportJson(report());

        assertThat(json).contains("\"checkedAt\" : \"2026-08-31T13:17:12Z\"");
    }

    private QualityReport report() {
        return new QualityReport(
                "EMP_CARD_INFO", "FCS", "acchisdb",
                Instant.parse("2026-08-31T13:17:12Z"),
                38275L, 0, 0, 100.0,
                List.of(), List.of());
    }
}
