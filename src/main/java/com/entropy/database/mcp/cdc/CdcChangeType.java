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

/**
 * CDC change type: INSERT / UPDATE / DELETE / DDL / TRUNCATE
 */
public enum CdcChangeType {
    INSERT("I", "插入"),
    UPDATE("U", "更新"),
    DELETE("D", "删除"),
    DDL("DDL", "DDL操作"),
    TRUNCATE("T", "截断");

    private final String code;
    private final String zh;

    CdcChangeType(String code, String zh) {
        this.code = code;
        this.zh = zh;
    }

    public String getCode() { return code; }
    public String getZh() { return zh; }

    public static CdcChangeType fromCode(String code) {
        for (CdcChangeType t : values()) {
            if (t.code.equalsIgnoreCase(code)) return t;
        }
        return null;
    }
}
