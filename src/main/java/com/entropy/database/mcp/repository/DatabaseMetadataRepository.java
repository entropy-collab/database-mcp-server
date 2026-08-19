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
package com.entropy.database.mcp.repository;

import com.entropy.database.mcp.service.DatabaseBackupService;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Database metadata repository.
 * Handles schema exploration and backup operations.
 */
@Repository
public class DatabaseMetadataRepository {

    private final DatabaseBackupService backupService;

    public DatabaseMetadataRepository(DatabaseBackupService backupService) {
        this.backupService = backupService;
    }

    public Map<String, Object> backupSchema(String tableName) {
        return backupService.backupSchema(tableName);
    }

    public Map<String, Object> backupData(String tableName, int maxRows) {
        return backupService.backupData(tableName, maxRows);
    }

    public Map<String, Object> diffSchema(String sourceTable, String targetTable) {
        return backupService.diffSchema(sourceTable, targetTable);
    }
}
