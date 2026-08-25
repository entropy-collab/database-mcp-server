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
package com.entropy.database.mcp.backup;

/**
 * Backup type classification.
 */
public enum BackupType {
    /** Full backup — captures entire table state */
    FULL,
    /** Incremental — captures only rows changed since last backup */
    INCREMENTAL,
    /** Snapshot — a point-in-time consistent copy (uses flashback/flashback query where available) */
    SNAPSHOT,
    /**
     * 只含建表 DDL、不含任何数据行的结构备份。
     *
     * <p>结构备份曾经也记成 {@link #FULL}，于是它和「整表数据备份」在元数据上无从分辨：用结构备份的 id
     * 去调 quickRestore，会先 DELETE 全表、再回放 0 条 INSERT，把表清空并提交。数据恢复路径因此需要一个
     * 能在元数据上就判定「这份备份没有数据」的类型。
     */
    SCHEMA
}
