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
 * The statements a caller may issue inside {@link DatabaseWriteOperations#inTransaction}.
 *
 * <p>Everything issued through this handle runs on the transaction's own connection. That is the
 * whole point of the type: it makes it impossible to accidentally mix transactional work with a
 * {@code JdbcTemplate} call, which would silently borrow a second connection and auto-commit.
 */
public interface TransactionContext {

    /** Execute a statement, returning the affected row count. */
    int update(String sql, Object... args);

    /** Execute a statement whose row count is not meaningful (typically DDL). */
    void execute(String sql);

    /** Read rows within the transaction, so uncommitted writes are visible. */
    List<Map<String, Object>> queryRows(String sql, Object... args);

    /**
     * Whether DDL issued here participates in the transaction.
     *
     * <p>{@code false} on Oracle and MySQL, where DDL commits implicitly and a rollback cannot
     * undo it. Callers that report success or failure to a user must consult this before claiming
     * a failed batch was rolled back.
     */
    boolean ddlIsTransactional();
}
