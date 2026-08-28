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
package com.entropy.database.mcp.cdc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDC change type.
 *
 * <p>The first five codes are real DML/DDL operations. {@link #TRIGGER_AUDIT} and
 * {@link #FLASHBACK} exist because some capture mechanisms cannot report the operation:
 * the MySQL/PostgreSQL trigger audit tables only record <em>that</em> a key changed, and an
 * Oracle Flashback <em>Query</em> (as opposed to a Version Query) only yields row versions.
 * Those dialects emit their code as a literal, and it must resolve to an enum constant — the
 * previous mismatch made {@code fromCode} return null for every row, and CDC reads silently
 * returned an empty list no matter how many changes existed.
 */
public enum CdcChangeType {
    INSERT("I", "插入"),
    UPDATE("U", "更新"),
    DELETE("D", "删除"),
    DDL("DDL", "DDL操作"),
    TRUNCATE("T", "截断"),
    TRIGGER_AUDIT("TRIGGER_AUDIT", "触发器审计变更（操作类型未知）"),
    FLASHBACK("FLASHBACK", "闪回行版本（操作类型未知）"),
    UNKNOWN("UNKNOWN", "未识别的变更类型");

    private static final Logger log = LoggerFactory.getLogger(CdcChangeType.class);

    /** Codes already warned about, so an unknown code floods the log at most once per value. */
    private static final Set<String> WARNED_CODES = ConcurrentHashMap.newKeySet();

    /** Upper bound on remembered codes, so a pathological source cannot grow the set without limit. */
    private static final int MAX_WARNED_CODES = 64;

    private final String code;
    private final String zh;

    CdcChangeType(String code, String zh) {
        this.code = code;
        this.zh = zh;
    }

    public String getCode() { return code; }
    public String getZh() { return zh; }

    /**
     * Resolves a dialect-emitted code (or an enum name such as {@code "INSERT"}, which is what MCP
     * clients pass when subscribing). Returns {@code null} when nothing matches, and warns once per
     * distinct unmatched value — an unrecognized change type is never dropped silently.
     */
    public static CdcChangeType fromCode(String code) {
        for (CdcChangeType t : values()) {
            if (t.code.equalsIgnoreCase(code) || t.name().equalsIgnoreCase(code)) return t;
        }
        warnUnrecognized(code);
        return null;
    }

    /**
     * Same as {@link #fromCode(String)} but maps an unmatched value to {@link #UNKNOWN} so the event
     * still reaches the caller instead of disappearing.
     */
    public static CdcChangeType fromCodeOrUnknown(String code) {
        CdcChangeType resolved = fromCode(code);
        return resolved != null ? resolved : UNKNOWN;
    }

    private static void warnUnrecognized(String code) {
        String key = code == null ? "<null>" : code;
        if (WARNED_CODES.size() < MAX_WARNED_CODES && WARNED_CODES.add(key)) {
            log.warn("Unrecognized CDC change_type '{}' — mapped to UNKNOWN. Known codes: {}",
                    key, Arrays.stream(values()).map(CdcChangeType::getCode).toList());
        }
    }
}
