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

import java.util.List;
import java.util.Map;

/**
 * Schema and object introspection.
 *
 * <p>Split out of the former single {@code DatabaseOperations} god-interface so an implementation
 * can declare exactly the capabilities it provides. Every method takes an explicit connection
 * name; there is no default connection.
 */
public interface DatabaseMetadataOperations {

    List<Map<String, Object>> listTables(String schema, String connection);

    List<Map<String, Object>> searchTables(String keyword, String connection);

    List<String> listSchemas(String connection);

    Map<String, Object> describeTable(String table, String schema, String connection);

    List<Map<String, Object>> listIndexes(String table, String schema, String connection);

    List<Map<String, Object>> listViews(String schema, String connection);

    List<Map<String, Object>> listSequences(String schema, String connection);

    Map<String, Object> getDatabaseInfo(String connection);
}
