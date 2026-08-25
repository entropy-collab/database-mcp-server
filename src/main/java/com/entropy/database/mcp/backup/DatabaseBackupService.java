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

import java.util.Map;

/**
 * Interface for database backup and schema comparison services.
 *
 * All methods require an explicit connection name; there is no default connection.
 */
public interface DatabaseBackupService {

    /**
     * Backup table schema definition as DDL statements.
     */
    Map<String, Object> backupSchema(String tableName, String connection);

    /**
     * Backup table data as INSERT statements.
     */
    Map<String, Object> backupData(String tableName, int maxRows, String connection);

    /**
     * Incremental backup: backup only rows changed since last completed backup.
     */
    Map<String, Object> backupDataIncremental(String tableName, int maxRows, String connection);

    /**
     * Restore a table from a backup by replaying the saved SQL script in a transaction.
     */
    Map<String, Object> restoreBackup(String backupId, String connection);

    /**
     * Quick restore: truncate target table then replay backup INSERT statements.
     */
    Map<String, Object> quickRestore(String backupId, String connection);

    /**
     * Compare schema differences between two tables.
     */
    Map<String, Object> diffSchema(String sourceTable, String targetTable, String connection);
}
