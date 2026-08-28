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
 * Data and schema mutation.
 *
 * <p>Separate from {@link DatabaseReadOperations} so a connection registered read-only can be
 * represented by an implementation that simply does not offer these methods, instead of offering
 * them and throwing.
 */
public interface DatabaseWriteOperations {

    Map<String, Object> executeDdl(String sql, String connection);

    /**
     * Execute a single mutating or control statement and return the affected row count.
     *
     * <p>Covers the cases tools previously ran through a raw {@code JdbcTemplate}: session
     * control ({@code ALTER SYSTEM KILL SESSION}), DB link creation, and one-off dialect DML.
     */
    int executeUpdate(String sql, String connection, Object... args);

    /**
     * Insert rows in batches into {@code table}.
     *
     * @param columns   column names, in the same order as each row's values
     * @param rows      row values; each inner list must match {@code columns} in length
     * @param batchSize rows per JDBC batch
     * @return total rows written
     */
    long batchInsert(String table, List<String> columns, List<List<Object>> rows,
                     int batchSize, String connection);

    /**
     * Insert-or-update rows keyed on {@code keyColumns}, using the dialect's native upsert form.
     *
     * @return total rows written
     */
    long batchUpsert(String table, List<String> keyColumns, List<String> columns,
                     List<List<Object>> rows, int batchSize, String connection);

    /**
     * Run {@code work} against a single connection inside one transaction.
     *
     * <p>Commits when {@code work} returns and rolls back when it throws. Callers that need
     * several statements to succeed or fail together must use this rather than issuing the
     * statements separately — a {@code JdbcTemplate} borrows a fresh connection per call, so
     * statements issued through it are not in the caller's transaction.
     *
     * <p>Note that DDL is implicitly committed on Oracle and MySQL, so a rollback cannot undo it.
     * {@link TransactionContext#ddlIsTransactional()} reports whether the target honours it.
     */
    <T> T inTransaction(String connection, TransactionalWork<T> work);
}
