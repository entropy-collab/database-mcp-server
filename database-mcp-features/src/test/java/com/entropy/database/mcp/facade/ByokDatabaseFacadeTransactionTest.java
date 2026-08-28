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

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.exception.McpToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Guards the one invariant {@code inTransaction} exists for: <b>a caller that is told the
 * transaction failed must not have had it committed</b>.
 *
 * <p>The failure mode being pinned here is subtle and silent. JDBC specifies that switching
 * {@code autoCommit} back on while a transaction is open <em>commits</em> it, so the connection
 * cleanup in {@code finally} was itself a commit path for every route that reached it without
 * rolling back: a {@code rollback()} that threw, and an {@link Error} thrown by the work, which a
 * {@code catch (Exception)} never saw. Both ended as "the tool reported an error and the writes
 * are in the database".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ByokDatabaseFacadeTransactionTest {

    @Mock
    private ByokDataSourceContext context;

    @Mock
    private Connection connection;

    private ByokDatabaseFacade facade() throws SQLException {
        when(context.getKey()).thenReturn("test-connection");
        when(context.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ByokDatabaseFacade(context);
    }

    @Test
    void commitsAndRestoresAutoCommitOnSuccess() throws SQLException {
        ByokDatabaseFacade facade = facade();

        String result = facade.inTransaction("test-connection", tx -> "done");

        assertThat(result).isEqualTo("done");
        InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).setAutoCommit(false);
        inOrder.verify(connection).commit();
        inOrder.verify(connection).setAutoCommit(true);
        inOrder.verify(connection).close();
        verify(connection, never()).rollback();
    }

    @Test
    void neverRestoresAutoCommitWhenRollbackFailed() throws SQLException {
        ByokDatabaseFacade facade = facade();
        doThrow(new SQLException("rollback refused")).when(connection).rollback();

        assertThatThrownBy(() -> facade.inTransaction("test-connection", tx -> {
            throw new IllegalStateException("work failed");
        })).isInstanceOf(McpToolException.class);

        // The transaction is still open, so restoring autoCommit would commit the partial writes
        // the caller was just told did not happen.
        verify(connection, never()).setAutoCommit(true);
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void rollbackFailureIsAttachedToTheOriginalError() throws SQLException {
        ByokDatabaseFacade facade = facade();
        SQLException rollbackFailure = new SQLException("rollback refused");
        doThrow(rollbackFailure).when(connection).rollback();

        assertThatThrownBy(() -> facade.inTransaction("test-connection", tx -> {
            throw new IllegalStateException("work failed");
        }))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("work failed")
                .rootCause()
                .satisfies(root -> assertThat(root.getSuppressed()).contains(rollbackFailure));
    }

    @Test
    void rollsBackAndRethrowsAnErrorFromTheWork() throws SQLException {
        ByokDatabaseFacade facade = facade();
        OutOfMemoryError oom = new OutOfMemoryError("heap gone");

        assertThatThrownBy(() -> facade.inTransaction("test-connection", tx -> {
            throw oom;
        })).isSameAs(oom);

        // Rolled back exactly once, before autoCommit is restored, and never committed.
        InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).rollback();
        inOrder.verify(connection).setAutoCommit(true);
        inOrder.verify(connection).close();
        verify(connection, times(1)).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void rollsBackWhenCommitItselfFails() throws SQLException {
        ByokDatabaseFacade facade = facade();
        doThrow(new SQLException("commit refused")).when(connection).commit();

        assertThatThrownBy(() -> facade.inTransaction("test-connection", tx -> "done"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("commit refused");

        InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).commit();
        inOrder.verify(connection).rollback();
        inOrder.verify(connection).setAutoCommit(true);
        inOrder.verify(connection).close();
    }

    @Test
    void closesTheConnectionEvenWhenNothingElseWorks() throws SQLException {
        ByokDatabaseFacade facade = facade();
        doThrow(new SQLException("rollback refused")).when(connection).rollback();
        doThrow(new SQLException("autoCommit refused")).when(connection).setAutoCommit(anyBoolean());

        assertThatThrownBy(() -> facade.inTransaction("test-connection", tx -> "done"))
                .isInstanceOf(McpToolException.class);

        verify(connection).close();
    }
}
