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
package com.entropy.database.mcp.catalog;

/**
 * Data category for business domain classification.
 */
public enum DataCategory {
    PERSONAL_INFO("个人信息", "Personal Info"),
    FINANCIAL("财务数据", "Financial"),
    BUSINESS("业务数据", "Business"),
    SYSTEM("系统数据", "System"),
    ANALYTICS("分析数据", "Analytics"),
    CONFIG("配置数据", "Config"),
    OTHER("其他", "Other");

    private final String zh;
    private final String en;

    DataCategory(String zh, String en) {
        this.zh = zh;
        this.en = en;
    }

    public String getZh() { return zh; }
    public String getEn() { return en; }
}
