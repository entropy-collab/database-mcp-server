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
package com.entropy.database.mcp.facade;

import java.util.Map;

/**
 * Backup and schema comparison.
 *
 * <p>Split away from {@link DatabaseAdminOperations} because these need a collaborating backup
 * service and a metadata store, not just a connection. Keeping them separate is what lets the
 * per-connection facade implement its interfaces without any
 * {@code UnsupportedOperationException} stubs — previously {@code backupSchema}, {@code backupData}
 * and {@code diffSchema} were declared there and always threw, so the routing facade advertised
 * three capabilities that no connection could actually perform.
 */
public interface DatabaseBackupOperations {

    Map<String, Object> backupSchema(String tableName, String connection);

    Map<String, Object> backupData(String tableName, int maxRows, String connection);

    Map<String, Object> diffSchema(String sourceTable, String targetTable, String connection);
}
