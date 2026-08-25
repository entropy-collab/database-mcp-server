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

/**
 * Moving rows between two different registered connections.
 *
 * <p>Kept apart from {@link DatabaseWriteOperations} because every method there takes a single
 * trailing {@code connection}; a two-connection operation does not fit that shape and would be
 * easy to misread.
 *
 * <p>There is no distributed transaction: the read side is streamed in pages and the write side
 * commits per batch, so a failure part-way leaves the target partially populated. The returned
 * count is what actually landed.
 */
public interface CrossConnectionOperations {

    /**
     * Stream the result of {@code sourceSql} from {@code sourceConnection} into
     * {@code targetTable} on {@code targetConnection}.
     *
     * @param targetColumns target column order; when null the source column labels are used
     * @return rows written to the target
     */
    long copyRows(String sourceSql, String sourceConnection,
                  String targetTable, List<String> targetColumns,
                  int batchSize, String targetConnection);
}
