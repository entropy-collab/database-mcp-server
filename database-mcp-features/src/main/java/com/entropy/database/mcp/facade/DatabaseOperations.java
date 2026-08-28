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

/**
 * The full database contract, for callers that genuinely need every capability.
 *
 * <p>This type exists for convenience only. Prefer depending on the narrowest capability
 * interface a class actually uses — {@link DatabaseMetadataOperations} for introspection tools,
 * {@link DatabaseReadOperations} for query tools, and so on. Depending on the narrow interface is
 * what makes it visible in a constructor signature that, say, a health-check tool has no business
 * writing to the database.
 *
 * <p>All operations take an explicit connection name; there is no default connection.
 */
public interface DatabaseOperations extends
        DatabaseMetadataOperations,
        DatabaseReadOperations,
        DatabaseWriteOperations,
        DatabaseAdminOperations,
        DatabaseBackupOperations,
        CrossConnectionOperations {
}
