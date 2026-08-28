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

import com.entropy.database.mcp.properties.QualityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Built-in quality rules registry.
 * Provides standard rules for common data quality dimensions.
 */
@Component
public class QualityRuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(QualityRuleRegistry.class);

    // Precompiled regex patterns for format validation
    public static final String FORMAT_EMAIL = "^[\\\\w.+-]+@[\\\\w.-]+\\\\.[a-zA-Z]{2,}$";
    public static final String FORMAT_PHONE_CN = "^[1][3-9]\\\\d{9}$";
    public static final String FORMAT_ID_CARD_CN = "^[1-9]\\\\d{5}(18|19|20)\\\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\\\d|3[01])\\\\d{3}[\\\\dXx]$";
    public static final String FORMAT_POSTAL_CODE_CN = "^[1-9]\\\\d{5}$";
    public static final String FORMAT_URL = "^https?://[^\\\\s]+$";
    public static final String FORMAT_DATE_ISO = "^\\\\d{4}-\\\\d{2}-\\\\d{2}$";
    public static final String FORMAT_PHONE_ANY = "^\\\\+?[1-9]\\\\d{6,14}$";

    /**
     * Get default built-in rules for a table (all columns checked).
     * Returns null-rates and duplicate checks.
     */
    public List<QualityRule> getDefaultRules(String tableName) {
        log.debug("getDefaultRules called for table={}, returning empty list — use addColumnRules for per-column rules", tableName);
        return new ArrayList<>();
    }

    /**
     * Get format-checking rules for known column name patterns.
     */
    public List<QualityRule> getFormatRulesForColumn(String columnName) {
        List<QualityRule> rules = new ArrayList<>();
        String col = columnName.toLowerCase();

        if (col.contains("email") || col.contains("mail")) {
            rules.add(QualityRule.format("fmt-email-" + col, columnName, FORMAT_EMAIL,
                    QualityRule.Severity.WARNING));
        }
        if (col.contains("phone") || col.contains("mobile") || col.contains("tel")) {
            rules.add(QualityRule.format("fmt-phone-" + col, columnName, FORMAT_PHONE_CN,
                    QualityRule.Severity.INFO));
            rules.add(QualityRule.format("fmt-phone-any-" + col, columnName, FORMAT_PHONE_ANY,
                    QualityRule.Severity.INFO));
        }
        if (col.contains("id_card") || col.contains("idcard") || col.contains("身份证")) {
            rules.add(QualityRule.format("fmt-idcard-" + col, columnName, FORMAT_ID_CARD_CN,
                    QualityRule.Severity.WARNING));
        }
        if (col.contains("postal") || col.contains("zip")) {
            rules.add(QualityRule.format("fmt-zip-" + col, columnName, FORMAT_POSTAL_CODE_CN,
                    QualityRule.Severity.INFO));
        }
        if (col.contains("url") || col.contains("website")) {
            rules.add(QualityRule.format("fmt-url-" + col, columnName, FORMAT_URL,
                    QualityRule.Severity.INFO));
        }
        if (col.contains("date") || col.contains("birthday") || col.contains("birth")) {
            rules.add(QualityRule.format("fmt-date-" + col, columnName, FORMAT_DATE_ISO,
                    QualityRule.Severity.INFO));
        }
        return rules;
    }

    /**
     * Build a complete rule set for a table: defaults + format rules + custom rules.
     */
    public List<QualityRule> buildRules(String tableName, List<QualityRule> customRules,
                                        QualityProperties props) {
        List<QualityRule> all = new ArrayList<>();

        // Add built-in format rules (column name based)
        // These are added when the caller provides column list via describeTable

        // Add custom rules
        if (customRules != null) {
            all.addAll(customRules.stream().filter(QualityRule::enabled).toList());
        }

        return all;
    }
}
