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
package com.entropy.database.mcp.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration properties for the database backup module.
 *
 * <p><b>破坏性变更（0.4.0 引入，沿用至今）</b>：
 * <ul>
 *   <li>删除 {@code auto-cleanup} / {@code default-backup-schema} / {@code recover-mode-cascade}
 *       三个键。它们从未被任何逻辑读过，只被 {@code getBackupConfig} 原样回显，读到的人会以为
 *       存在自动清理任务和级联恢复能力——两者都不存在。</li>
 *   <li>新增 {@code max-records}，并让 {@code retention-days} 真正生效。此前备份记录存在
 *       {@code BackupMetadataRepository} 的 Caffeine map 里，上限 200 条 / 7 天是<b>编译期常量</b>，
 *       而 {@code retention-days} 默认 30 只作用于手工调用的 {@code cleanupBackups}。于是
 *       {@code getBackupConfig} 回的 {@code retentionDays: 30} 是一个纯谎报：记录到第 7 天必被清掉。
 *       默认值因此改为 7，与真实行为对齐；想留更久就把它调大，现在会真的生效。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "entropy.mcp.database.backup")
public record BackupProperties(
    boolean enabled,
    boolean incrementalEnabled,
    int maxBackupRows,
    /** 内存中最多保留的备份记录条数，超出后按 LRU 静默淘汰。 */
    int maxRecords,
    /** 备份记录的最长存活天数，既是内存存储的硬过期时间，也是 cleanupBackups 的默认保留期。 */
    int retentionDays
) {
    /**
     * {@code @ConstructorBinding} 不能省：本 record 同时存在紧凑规范构造器与下面那个无参便捷构造器，
     * 两个都是 public。Boot 的 {@code DefaultBindConstructorProvider} 在「多个非私有构造器且没有
     * 注解指明」时会<b>放弃值对象绑定</b>，退化成 JavaBean 绑定——而 record 没有 setter，于是所有
     * {@code entropy.mcp.database.backup.*} 配置<b>被静默忽略</b>，永远只拿到无参构造器里的默认值。
     * 这个坑在 0.4.0 被实测撞到：{@code --entropy.mcp.database.backup.retention-days=3} 启动后
     * {@code getBackupConfig} 仍然回 7。{@code ConfigurationPropertiesBindingTest} 把这条钉住了。
     */
    @ConstructorBinding
    public BackupProperties {
        enabled = Boolean.TRUE.equals(enabled);
        incrementalEnabled = Boolean.TRUE.equals(incrementalEnabled);
        maxBackupRows = maxBackupRows > 0 ? maxBackupRows : 500000;
        maxRecords = maxRecords > 0 ? maxRecords : 200;
        retentionDays = retentionDays > 0 ? retentionDays : 7;
    }

    public BackupProperties() {
        this(true, true, 500000, 200, 7);
    }
}
