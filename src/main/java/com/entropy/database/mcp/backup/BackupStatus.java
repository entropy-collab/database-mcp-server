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
 * Backup operation lifecycle status.
 */
public enum BackupStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    /**
     * 备份成功执行，但因为命中行数上限而只捕获了表的一部分。
     *
     * <p>被截断的备份记成 COMPLETED 时，{@code listBackups}/{@code getBackup} 看不出缺行，拿它做「先清空
     * 再灌回」的整表还原就会静默丢掉未被捕获的那部分数据。单独一个状态让不完整这件事留在元数据里，恢复
     * 路径才有机会拒绝或告警。
     */
    PARTIAL,
    FAILED,
    ROLLED_BACK
}
