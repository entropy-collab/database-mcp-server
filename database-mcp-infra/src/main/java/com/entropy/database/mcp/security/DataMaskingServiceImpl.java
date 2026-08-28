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
package com.entropy.database.mcp.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sensitive data auto-masking service.
 * Protects PII (Personally Identifiable Information) in query results.
 */
@Component
@ConfigurationProperties(prefix = "entropy.mcp.database.masking")
public class DataMaskingServiceImpl implements DataMaskingService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // Common PII patterns
    private static final List<MaskingRule> DEFAULT_RULES = List.of(
        new MaskingRule("EMAIL",
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}"),
            (java.util.function.Function<String, String>) DataMaskingServiceImpl::maskEmailStatic),

        new MaskingRule("PHONE",
            Pattern.compile("(?:\\+?1[-.]?)?(?:\\(\\d{3}\\)|\\d{3})[-.]?\\d{3}[-.]?\\d{4}"),
            (java.util.function.Function<String, String>) DataMaskingServiceImpl::maskPhoneStatic),
        
        new MaskingRule("SSN", 
            Pattern.compile("\\d{3}[-.]?\\d{2}[-.]?\\d{4}"),
            m -> "***-**-" + ((String) m).substring(7)),
        
        new MaskingRule("CREDIT_CARD", 
            Pattern.compile("\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}"),
            m -> "****-****-****-" + ((String) m).substring(12)),
        
        new MaskingRule("IP_ADDRESS", 
            Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"),
            m -> "192.168.**.**"),
        
        new MaskingRule("URL", 
            Pattern.compile("https?://[\\w\\-]+(\\.[\\w\\-]+)+[/\\w\\-.?=&%#]*"),
            m -> "[REDACTED_URL]")
    );

    private List<MaskingRule> customRules = List.of();
    private List<String> columnPatterns = List.of(
        "email", "phone", "mobile", "tel", "cell",
        "ssn", "social_security", "id_card",
        "credit_card", "card_number", "cc_number",
        "password", "secret", "token", "api_key",
        "salary", "income", "bank_account"
    );

    @Override
    public List<Map<String, Object>> maskResults(
            List<Map<String, Object>> rows,
            List<String> explicitMaskColumns) {
        
        if (rows == null || rows.isEmpty()) {
            return rows;
        }

        // Determine which columns to mask
        Set<String> maskColumns = determineMaskColumns(rows, explicitMaskColumns);

        return rows.stream()
            .map(row -> maskRow(row, maskColumns))
            .toList();
    }

    @Override
    public List<String> getMaskColumnsForSchema(List<String> columnNames) {
        return columnNames.stream()
            .filter(name -> shouldMaskColumn(name))
            .toList();
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private Set<String> determineMaskColumns(
            List<Map<String, Object>> rows,
            List<String> explicitMaskColumns) {
        
        Set<String> columns = new HashSet<>();
        
        // Add explicitly requested columns
        if (explicitMaskColumns != null) {
            columns.addAll(explicitMaskColumns);
        }
        
        // Add columns matching patterns
        if (!rows.isEmpty()) {
            Set<String> allColumns = rows.get(0).keySet();
            for (String col : allColumns) {
                if (shouldMaskColumn(col)) {
                    columns.add(col);
                }
            }
        }
        
        return columns;
    }

    private boolean shouldMaskColumn(String columnName) {
        String lower = columnName.toLowerCase();
        return columnPatterns.stream()
            .anyMatch(pattern -> lower.contains(pattern.toLowerCase()));
    }

    private Map<String, Object> maskRow(Map<String, Object> row, Set<String> maskColumns) {
        Map<String, Object> maskedRow = new LinkedHashMap<>(row);
        
        for (String column : maskColumns) {
            Object value = maskedRow.get(column);
            if (value instanceof String) {
                maskedRow.put(column, applyMasking((String) value, column));
            }
        }
        
        return maskedRow;
    }

    private Object applyMasking(String value, String columnName) {
        // Try custom rules first
        for (MaskingRule rule : customRules) {
            if (rule.pattern.matcher(value).matches()) {
                log.debug("Applying custom mask rule: {} for column: {}", rule.name(), columnName);
                return rule.maskFunction().apply(value);
            }
        }
        
        // Try default rules
        for (MaskingRule rule : DEFAULT_RULES) {
            if (rule.pattern.matcher(value).matches()) {
                log.debug("Applying default mask rule: {} for column: {}", rule.name(), columnName);
                return rule.maskFunction().apply(value);
            }
        }
        
        // Partial mask if no rule matches but column should be masked
        if (shouldMaskColumn(columnName) && value.length() > 6) {
            return value.substring(0, 3) + "****" + value.substring(value.length() - 3);
        } else if (shouldMaskColumn(columnName)) {
            // Too short for partial mask - fully redact
            return "****";
        }
        
        return value;
    }

    private static String maskEmailStatic(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            String prefix = email.substring(0, Math.min(atIndex, 2));
            return prefix + "****@" + email.substring(atIndex);
        }
        return "[REDACTED_EMAIL]";
    }

    private static String maskPhoneStatic(String phone) {
        // Keep last 4 digits
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() >= 4) {
            return "***-***-" + digits.substring(digits.length() - 4);
        }
        return "[REDACTED_PHONE]";
    }

    public record MaskingRule(
        String name,
        Pattern pattern,
        java.util.function.Function<String, String> maskFunction
    ) {}
}
