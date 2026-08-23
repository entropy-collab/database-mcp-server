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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Quality report generator — supports JSON and CSV export formats.
 */
@Component
public class QualityReportService {

    private static final Logger log = LoggerFactory.getLogger(QualityReportService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Export report as JSON string.
     */
    public String exportJson(QualityReport report) {
        try {
            StringWriter writer = new StringWriter();
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(writer, report);
            return writer.toString();
        } catch (IOException e) {
            log.error("Failed to serialize quality report to JSON", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Export report issues as CSV string.
     */
    public String exportCsv(QualityReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("rule_id,rule_name,rule_type,column,severity,actual_value,threshold,total_rows,issue_count,detail\n");
        for (QualityIssue issue : report.issues()) {
            sb.append(escapeCsv(issue.ruleId()))
                    .append(",").append(escapeCsv(issue.ruleName()))
                    .append(",").append(issue.ruleType().name())
                    .append(",").append(escapeCsv(issue.column() != null ? issue.column() : ""))
                    .append(",").append(issue.severity())
                    .append(",").append(issue.actualValue())
                    .append(",").append(issue.threshold())
                    .append(",").append(issue.totalRows())
                    .append(",").append(issue.issueCount())
                    .append(",").append(escapeCsv(issue.detail()))
                    .append("\n");
        }
        // Add summary row if no issues
        if (report.issues().isEmpty()) {
            sb.append("SUMMARY,,,").append(report.tableName())
                    .append(",HEALTHY,100.0,0,")
                    .append(report.totalRows()).append(",0,")
                    .append("No issues found\n");
        }
        return sb.toString();
    }

    /**
     * Build a human-readable text summary.
     */
    public String exportText(QualityReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Data Quality Report ===\n");
        sb.append("Table: ").append(report.tableName()).append("\n");
        sb.append("Schema: ").append(report.schema() != null ? report.schema() : "default").append("\n");
        sb.append("Connection: ").append(report.connectionKey() != null ? report.connectionKey() : "unknown").append("\n");
        sb.append("Checked At: ").append(DATE_FMT.format(report.checkedAt())).append("\n");
        sb.append("Total Rows: ").append(report.totalRows()).append("\n");
        sb.append("Rules Checked: ").append(report.rulesChecked()).append("\n");
        sb.append("Issues Found: ").append(report.issuesFound()).append("\n");
        sb.append("Overall Score: ").append(String.format("%.1f%%", report.overallScore())).append("\n");
        sb.append("Status: ").append(report.statusLabel()).append("\n\n");

        if (!report.issues().isEmpty()) {
            sb.append("--- Issues ---\n");
            for (QualityIssue issue : report.issues()) {
                sb.append(String.format("[%s] %s: %s (actual=%.2f, threshold=%.2f)\n",
                        issue.severity(), issue.ruleName(), issue.detail(),
                        issue.actualValue(), issue.threshold()));
            }
        } else {
            sb.append("No issues found. Data quality is healthy.\n");
        }
        return sb.toString();
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
